const { readFile } = require("node:fs/promises");
const { managerEmail, managerGrantId, managerGrantPath, isDormId, maxManagerDorms } = require("./manager-access");

function parseRoster(text) {
  const rows = JSON.parse(text.replace(/^\uFEFF/, ""));
  if (!Array.isArray(rows) || !rows.length) throw new Error("Dosyada en az bir yetki kaydı olmalı.");
  const seen = new Set();
  return rows.map((row, index) => {
    const fail = message => { throw new Error(`Kayıt ${index + 1}: ${message}`); };
    if (!row || typeof row !== "object" || Object.keys(row).some(key => key !== "email" && key !== "dormIds")) {
      fail("Yalnızca email ve dormIds alanları kabul edilir.");
    }
    const email = managerEmail(row.email);
    if (!email) fail("Geçerli bir e-posta adresi gir.");
    if (seen.has(email)) fail("Aynı e-postaya ait yurtları tek kayıtta birleştir.");
    seen.add(email);
    const ids = row.dormIds;
    if (!Array.isArray(ids) || ids.length > maxManagerDorms || !ids.every(isDormId) || new Set(ids).size !== ids.length) {
      fail(`dormIds eşsiz ve geçerli en fazla ${maxManagerDorms} yurt kimliği içermeli.`);
    }
    return { id: managerGrantId(email), dormIds: ids };
  });
}

async function importRoster(file, projectId) {
  const rows = parseRoster(await readFile(file, "utf8"));
  console.log(`${rows.length} e-posta kaydı, ${rows.reduce((n, row) => n + row.dormIds.length, 0)} yurt yetkisi.`);
  if (!projectId) return console.log("Dosya kontrol edildi. Firestore'a yazılmadı.");

  const { initializeApp, applicationDefault, deleteApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId, credential: applicationDefault() });
  try {
    const db = getFirestore(app);
    const ids = [...new Set(rows.flatMap(row => row.dormIds))];
    for (let offset = 0; offset < ids.length; offset += 200) {
      const docs = await db.getAll(...ids.slice(offset, offset + 200).map(id => db.doc("dormCatalog/" + id)), { fieldMask: ["active"] });
      const inactive = docs.filter(doc => doc.get("active") !== true).map(doc => doc.id);
      if (inactive.length) throw new Error("Katalogda bulunmayan veya kapalı yurtlar: " + inactive.join(", "));
    }
    for (let offset = 0; offset < rows.length; offset += 200) {
      const batch = db.batch();
      for (const { id, dormIds } of rows.slice(offset, offset + 200)) {
        const ref = db.doc(managerGrantPath(id));
        if (dormIds.length) batch.set(ref, { dormIds });
        else batch.delete(ref);
      }
      await batch.commit();
      console.log(`${Math.min(offset + 200, rows.length)}/${rows.length} kayıt uygulandı.`);
    }
  } finally {
    await deleteApp(app);
  }
}

if (require.main === module) {
  const [file, option, projectId, ...extra] = process.argv.slice(2);
  if (!file || extra.length || (option && (option !== "--apply" || !/^[a-z][a-z0-9-]{4,29}$/.test(projectId || "")))) {
    console.error("Kullanım: node import-manager-access.js YETKILILER.json [--apply PROJE_KIMLIGI]");
    process.exitCode = 1;
  } else {
    importRoster(file, projectId).catch(error => { console.error(error.message); process.exitCode = 1; });
  }
}

module.exports = { parseRoster };
