const { readFile } = require("node:fs/promises");
const { isDormId, managerEmail } = require("./manager-access");

const MUS_1071 = "kyk-049153406";
const key = value => String(value ?? "").toLocaleLowerCase("tr").normalize("NFKD")
  .replace(/[\u0300-\u036f]/g, "").replace(/ı/g, "i")
  .replace(/\b(kyk|ogrenci|erkek|kiz|yurdu|yurt|mudurlugu)\b/g, " ").replace(/[^a-z0-9]+/g, " ").trim();
const date = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value));
const officialSource = value => {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password &&
      (url.hostname === "gsb.gov.tr" || url.hostname.endsWith(".gsb.gov.tr"));
  } catch { return false; }
};

function parseKykCatalog(text) {
  const file = JSON.parse(text.replace(/^\uFEFF/, ""));
  if (file.version !== 1 || !date(file.updatedAt) || !Array.isArray(file.dormitories) || !file.dormitories.length) {
    throw new Error("KYK kataloğu veya sürümü geçersiz.");
  }
  const ids = new Set();
  const emails = new Set();
  return file.dormitories.map((row, i) => {
    const email = managerEmail(row?.officialEmail);
    const textFields = ["name", "city", "district"];
    if (!row || !/^kyk-\d{9}$/.test(row.id) || ids.has(row.id) ||
        textFields.some(field => typeof row[field] !== "string" || !row[field].trim() || row[field].length > 180) ||
        (row.type !== null && (typeof row.type !== "string" || row.type.length > 60)) ||
        !officialSource(row.sourceUrl) || !date(row.sourceCheckedAt) || row.activationStatus !== "INACTIVE" ||
        (row.officialEmail === null ? row.emailSourceUrl !== null :
          !email.endsWith("@gsb.gov.tr") || emails.has(email) || !officialSource(row.emailSourceUrl))) {
      throw new Error(`Geçersiz veya mükerrer katalog kaydı: ${i + 1}.`);
    }
    ids.add(row.id);
    if (email) emails.add(email);
    const { id, name, city, district, type, sourceUrl, emailSourceUrl, sourceCheckedAt } = row;
    return { id, name, city, district, type, sourceUrl, emailSourceUrl, sourceCheckedAt, officialEmail: email || null };
  });
}

async function importKykCatalog(db, catalog) {
  if (Buffer.byteLength(JSON.stringify(catalog)) > 500000) throw new Error("Tek katalog için veri boyutu sınırı aşıldı.");
  const legacy = (await db.collection("dormCatalog").where("active", "==", true).select("name", "city", "district").get()).docs;
  let changed = 0;
  let protectedRows = 0;
  for (let offset = 0; offset < catalog.length; offset += 100) {
    const batch = catalog.slice(offset, offset + 100);
    const counts = await db.runTransaction(async tx => {
      const entries = await tx.getAll(...batch.map(row => db.doc("kykRegistry/" + row.id)));
      let updated = 0;
      let protectedCount = 0;
      for (let index = 0; index < batch.length; index++) {
        const { id, ...row } = batch[index];
        const entry = entries[index];
        const previous = entry.data() ?? {};
        const matches = legacy.filter(doc => doc.id !== id && key(doc.get("city")) === key(row.city) &&
          key(doc.get("district")) === key(row.district) &&
          (key(doc.get("name")) === key(row.name) || id === MUS_1071 && /\b1071\b/.test(key(doc.get("name")))));
        const legacyProtected = Boolean(previous.legacyProtected || id === MUS_1071 || matches.length);
        const data = {
          ...row, legacyProtected,
          legacyDormId: previous.legacyDormId || (matches.length === 1 ? matches[0].id : null),
        };
        if (data.legacyDormId && !isDormId(data.legacyDormId)) throw new Error("Geçersiz mevcut yurt kimliği.");
        if (legacyProtected) protectedCount++;
        if (Object.entries(data).every(([field, value]) => previous[field] === value)) continue;
        tx.set(entry.ref, data, { merge: true });
        updated++;
      }
      return { updated, protectedCount };
    });
    changed += counts.updated;
    protectedRows += counts.protectedCount;
  }
  const registry = await db.collection("kykRegistry")
    .select("name", "city", "district", "officialEmail", "legacyDormId", "legacyProtected").get();
  const publicRows = registry.docs.map(doc => ({
    id: doc.id, name: doc.get("name"), city: doc.get("city"), district: doc.get("district"),
    officialEmail: doc.get("legacyProtected") ? "" : doc.get("officialEmail") || "",
    legacyActive: Boolean(doc.get("legacyDormId")), enrollmentId: doc.get("legacyDormId") || doc.id,
  }));
  const publicRef = db.doc("kykPublicCatalog/current");
  const serialized = JSON.stringify(publicRows);
  if (Buffer.byteLength(serialized) > 500000) throw new Error("Birleştirilmiş katalog veri boyutu sınırı aşıldı.");
  if (JSON.stringify((await publicRef.get()).get("dorms")) !== serialized) await publicRef.set({ dorms: publicRows });
  return { changed, protectedRows };
}

async function main() {
  const [file, option, projectId, ...extra] = process.argv.slice(2);
  if (!file || extra.length || (option && (option !== "--apply" || !/^[a-z][a-z0-9-]{4,29}$/.test(projectId || "")))) {
    throw new Error("Kullanım: node functions/import-kyk-catalog.js DOSYA.json [--apply PROJE_KIMLIGI]");
  }
  const rows = parseKykCatalog(await readFile(file, "utf8"));
  console.log(`${rows.length} yurt, ${new Set(rows.map(row => row.city)).size} il, ${rows.filter(row => row.officialEmail).length} kurumsal posta.`);
  if (!option) return;
  const { initializeApp, applicationDefault, deleteApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId, credential: applicationDefault() });
  try { console.log(await importKykCatalog(getFirestore(app), rows)); }
  finally { await deleteApp(app); }
}

if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = { parseKykCatalog, importKykCatalog };
