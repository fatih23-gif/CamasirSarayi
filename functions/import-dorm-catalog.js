const { readFile } = require("node:fs/promises");

function parseCatalog(text) {
  const input = JSON.parse(text.replace(/^\uFEFF/, ""));
  if (!Array.isArray(input) || input.length === 0) throw new Error("JSON dosyasında en az bir yurt olmalı.");

  const ids = new Set();
  const allowed = new Set(["id", "name", "city", "district", "active"]);
  return input.map((row, index) => {
    const fail = message => { throw new Error(`Kayıt ${index + 1}: ${message}`); };
    if (!row || typeof row !== "object" || Array.isArray(row)) fail("Yurt bilgileri bir nesne olmalı.");
    if (Object.keys(row).some(key => !allowed.has(key))) fail("Yalnızca id, name, city, district ve active alanları kabul edilir.");
    const field = key => {
      if (typeof row[key] !== "string" || !row[key].trim()) fail(`${key} alanı boş olamaz.`);
      return row[key].trim();
    };
    const id = field("id");
    if (!/^[a-zA-Z0-9_-]{1,80}$/.test(id) || /^__.*__$/.test(id)) fail("Geçersiz yurt kimliği.");
    if (ids.has(id)) fail(`${id} kimliği dosyada birden fazla kez geçiyor.`);
    ids.add(id);
    if (row.active !== undefined && typeof row.active !== "boolean") fail("active alanı true veya false olmalı.");
    return { id, name: field("name"), city: field("city"), district: field("district"), active: row.active ?? true };
  });
}

async function run() {
  const [file, option, projectId, ...extra] = process.argv.slice(2);
  if (!file || extra.length || (option && (option !== "--apply" || !/^[a-z][a-z0-9-]{4,29}$/.test(projectId || "")))) {
    throw new Error("Kullanım: node import-dorm-catalog.js YURTLAR.json [--apply PROJE_KIMLIGI]");
  }
  const catalog = parseCatalog(await readFile(file, "utf8"));
  console.log(`${catalog.length} yurt kaydı geçerli.`);
  if (!option) {
    console.log("Kontrol tamamlandı. Firestore'a yazılmadı.");
    return;
  }

  const { initializeApp, applicationDefault, deleteApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const app = initializeApp({ projectId, credential: applicationDefault() });
  try {
    const db = getFirestore(app);
    const writer = db.bulkWriter();
    const imports = catalog.map(({ id, ...data }) =>
      writer.create(db.collection("dormCatalog").doc(id), data)
        .then(() => "created")
        .catch(error => error.code === 6 ? "exists" : { id, code: error.code })
    );
    await writer.close();
    const results = await Promise.all(imports);
    const created = results.filter(item => item === "created").length;
    const existing = results.filter(item => item === "exists").length;
    console.log(`${projectId}: ${created} yeni kayıt, ${existing} mevcut ve korunan kayıt.`);
    for (const result of results.filter(item => typeof item === "object")) {
      console.error(`${result.id} eklenemedi. Firestore hata kodu: ${result.code}.`);
      process.exitCode = 1;
    }
  } finally {
    await deleteApp(app);
  }
}

run().catch(error => { console.error(error.message); process.exitCode = 1; });
