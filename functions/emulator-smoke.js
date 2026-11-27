const assert = require("node:assert/strict");
const { generateKeyPairSync, randomUUID, verify } = require("node:crypto");
const { execFileSync } = require("node:child_process");
const path = require("node:path");
const { mkdtempSync, writeFileSync, rmSync, rmdirSync } = require("node:fs");
const { tmpdir } = require("node:os");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { managerGrantId } = require("./manager-access");

const projectId = "demo-camasir-sarayi";
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST;
const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
const isLocal = host => /^127[.]0[.]0[.]1:[0-9]+$/.test(host || "");
if (!isLocal(authHost) || !isLocal(firestoreHost) || process.env.GCLOUD_PROJECT !== projectId) {
  throw new Error("Bu test yalnızca demo-camasir-sarayi yerel Firebase emülatöründe çalışır.");
}

const app = initializeApp({ projectId });
const auth = getAuth(app);
const db = getFirestore(app);
const suffix = randomUUID().slice(0, 8);

async function account(label, emailVerified = true, domain = "example.test") {
  const email = label + "-" + suffix + "@" + domain;
  const password = randomUUID();
  const user = await auth.createUser({ email, password, emailVerified });
  const response = await fetch("http://" + authHost + "/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  assert.ok(response.ok, "Yerel oturum açılamadı: " + response.status);
  return { uid: user.uid, email, token: (await response.json()).idToken };
}

async function call(account, action, dormId, args = {}) {
  const headers = { "Content-Type": "application/json" };
  if (account) headers.Authorization = "Bearer " + account.token;
  const response = await fetch("http://127.0.0.1:5001/" + projectId + "/us-central1/laundry", {
    method: "POST", headers,
    body: JSON.stringify({ data: { action, dormId, ...args } }),
  });
  return { status: response.status, ...(await response.json()) };
}

function expectError(response, status) {
  assert.equal(response.error?.status, status, JSON.stringify(response));
}

async function testWorker(admin, student, outsider) {
  const { createWorker } = await import("../worker/index.mjs");
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const env = {
    FIREBASE_PROJECT_ID: projectId,
    FIREBASE_API_KEY: "demo",
    ENROLLMENT_IP_SECRET: "emulator-only-enrollment-hmac",
    FIRESTORE_SERVICE_ACCOUNT: JSON.stringify({
      project_id: projectId, client_email: "test@example.test", private_key_id: "fixture",
      private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
    }),
  };
  const worker = createWorker((input, init) => {
    const url = new URL(input);
    if (url.hostname === "oauth2.googleapis.com") {
      const jwt = init.body.get("assertion").split(".");
      assert.ok(verify("RSA-SHA256", Buffer.from(jwt[0] + "." + jwt[1]), publicKey, Buffer.from(jwt[2], "base64url")));
      return Response.json({ access_token: "owner", expires_in: 3600 });
    }
    if (url.hostname === "identitytoolkit.googleapis.com") {
      const headers = new Headers(init.headers);
      headers.set("Connection", "close");
      return fetch("http://" + authHost + "/" + url.host + url.pathname + url.search, {
        ...init, headers, signal: AbortSignal.timeout(30000),
      });
    }
    if (url.hostname === "firestore.googleapis.com") return fetch("http://" + firestoreHost + url.pathname + url.search, init);
    throw new Error("Unexpected test request");
  });
  const dormId = "worker-" + suffix;
  const catalog = { name: "Worker Test Yurdu", city: "Test İl", district: "Test İlçe", active: true };
  await db.collection("dormCatalog").doc(dormId).create(catalog);
  execFileSync(process.execPath, [path.join(__dirname, "seed.js"), projectId, admin.uid, dormId]);
  const callWorker = async (account, action, args = {}, ip = "192.0.2.10") => {
    const request = new Request("https://test.workers.dev/laundry", {
      method: "POST", headers: { Authorization: "Bearer " + account.token, "CF-Connecting-IP": ip },
      body: JSON.stringify({ action, dormId, ...args }),
    });
    return worker.fetch(request, env).then(result => result.json());
  };
  const authorized = await callWorker(admin, "catalog");
  const publicCatalog = await worker.fetch(new Request("https://test.workers.dev/staff-dorms"), env).then(res => res.json());
  const publicDorm = publicCatalog.result?.dorms.find(dorm => dorm.id === dormId);
  assert.deepEqual(publicDorm, { id: dormId, name: catalog.name, city: catalog.city, district: catalog.district });
  assert.equal(authorized.result?.dorms.find(dorm => dorm.id === dormId)?.role, "ADMIN", JSON.stringify(authorized));
  assert.equal((await callWorker(admin, "managerAccess")).result?.dorms.find(dorm => dorm.id === dormId)?.role, "ADMIN");
  assert.equal((await callWorker(student, "staffApply", {
    name: "Personel Adayı", status: "APPROVED",
  })).result?.staffStatus, "PENDING");
  assert.equal((await callWorker(outsider, "staffStatus", { uid: student.uid })).result?.staffStatus, "");
  assert.deepEqual((await callWorker(student, "managerAccess")).result?.dorms, []);
  const staffApplication = db.doc("staffApplications/" + student.uid);
  const staffUrl = "http://" + firestoreHost + "/v1/projects/" + projectId + "/databases/(default)/documents/staffApplications/" + student.uid;
  assert.equal((await fetch(staffUrl, { headers: { Authorization: "Bearer " + student.token } })).status, 403);
  assert.equal((await staffApplication.get()).get("status"), "PENDING");
  const candidate = await account("candidate", false);
  assert.equal((await callWorker(candidate, "staffApply", { name: "Test Adayı" })).error?.code, "permission-denied");
  assert.equal((await callWorker(candidate, "enroll", { name: "Test Adayı", code: "123456" })).error?.code, "permission-denied");
  assert.equal((await callWorker(student, "staffReviews")).error?.code, "permission-denied");
  assert.equal((await callWorker(student, "staffReview", { applicantId: student.uid, decision: "APPROVED" })).error?.code, "permission-denied");
  assert.equal((await callWorker(admin, "staffReviews")).result?.applications[0]?.id, student.uid);
  assert.equal((await callWorker(student, "staffApply", { name: "Başka Ad", dormId: "inactive-" + suffix })).result?.staffStatus, "PENDING");
  assert.equal((await staffApplication.get()).get("dormId"), dormId);
  const code1 = await callWorker(admin, "rotateJoinCode");
  assert.match(code1.result?.joinCode || "", /^[0-9]{6}$/, JSON.stringify(code1));
  const code2 = await callWorker(admin, "rotateJoinCode");
  const code = code2.result?.joinCode;
  assert.match(code || "", /^[0-9]{6}$/);
  assert.notEqual(code1.result.joinCode, code);
  assert.equal((await callWorker({ token: "gecersiz" }, "catalog")).error?.code, "unauthenticated");
  for (let attempt = 0; attempt < 5; attempt++) {
    const rejected = await callWorker(outsider, "enroll", { code: "000000", name: "Test Öğrenci" });
    assert.equal(rejected.error?.code, "failed-precondition");
  }
  const limited = await callWorker(outsider, "enroll", { code, name: "Test Öğrenci" });
  assert.equal(limited.error?.code, "resource-exhausted");
  const limitDoc = await db.doc(`dorms/${dormId}/enrollmentLimits/${outsider.uid}`).get();
  assert.equal(limitDoc.get("attempts"), 5);
  assert.ok(limitDoc.get("expiresAt") > Date.now() && limitDoc.get("expiresAt") <= Date.now() + 15 * 60000);
  assert.equal((await callWorker(student, "rotateJoinCode")).error?.code, "permission-denied");
  assert.equal((await callWorker(student, "enroll", { code: code1.result.joinCode, name: "Test Öğrenci" })).error?.code, "failed-precondition");
  const otherAccounts = await Promise.all([account("ip-a"), account("ip-b"), account("ip-c")]);
  for (const [index, person] of otherAccounts.entries()) {
    for (let attempt = 0; attempt < (index === 2 ? 4 : 5); attempt++) {
      const rejected = await callWorker(person, "enroll", { code: "000000", name: "Test Öğrenci" });
      assert.equal(rejected.error?.code, "failed-precondition");
    }
  }
  const enrollment = { code, name: "Test Öğrenci" };
  assert.equal((await callWorker(student, "enroll", enrollment)).error?.code, "resource-exhausted");
  assert.equal((await callWorker(student, "enroll", enrollment, "")).error?.code, "unavailable");
  const ipLimits = await db.collection(`dorms/${dormId}/enrollmentIpLimits`).get();
  assert.equal(ipLimits.size, 1);
  assert.match(ipLimits.docs[0].id, /^[0-9a-f]{64}$/);
  assert.equal(ipLimits.docs[0].get("attempts"), 20);
  const applied = await callWorker(student, "enroll", enrollment, "192.0.2.11");
  assert.equal(applied.result?.member.status, "PENDING", JSON.stringify(applied));
  assert.ok(!Object.hasOwn(applied.result, "joinCode"));
  assert.equal((await callWorker(student, "rotateJoinCode")).error?.code, "failed-precondition");
  const approved = await callWorker(admin, "member", { uid: student.uid, status: "ACTIVE" });
  assert.equal(approved.result?.members.find(item => item.id === student.uid)?.status, "ACTIVE", JSON.stringify(approved));
  assert.ok(approved.result?.inbox.some(item => item.kind === "member-request" && item.uid === admin.uid));
  assert.equal((await callWorker(student, "rotateJoinCode")).error?.code, "failed-precondition");
  const created = await callWorker(admin, "machine", { name: "Makine", number: 1, status: "AVAILABLE", roomName: "Bodrum Kat" });
  const machineId = created.result?.machines[0]?.id;
  assert.ok(machineId, JSON.stringify(created));
  const report = await callWorker(student, "report", { id: machineId, description: "Test arıza bildirimi", type: "Diğer" });
  assert.ok(!report.result?.inbox.some(item => item.kind === "fault-report"));
  const managerInbox = (await callWorker(admin, "status")).result?.inbox;
  const faultNotification = managerInbox.find(item => item.kind === "fault-report");
  assert.ok(faultNotification);
  const markedRead = await callWorker(admin, "read", { id: faultNotification.id });
  assert.ok(markedRead.result?.inbox.find(item => item.id === faultNotification.id)?.read);
  const claim = await callWorker(student, "join", { id: machineId });
  assert.equal(claim.result?.machines[0].mine, true, JSON.stringify(claim));
  const started = await callWorker(student, "start", { id: machineId, minutes: 1 });
  assert.equal(started.result?.machines[0].status, "RUNNING", JSON.stringify(started));
  const ref = db.collection("dorms").doc(dormId);
  const state = (await ref.get()).data();
  state.machines[0].end = Date.now() - 1000;
  await ref.update({ machines: state.machines, nextChangeAt: state.machines[0].end });
  await worker.scheduled({}, env);
  assert.equal((await ref.get()).data().machines[0].status, "AWAITING_UNLOAD");
  const [manager, anotherManager] = await Promise.all([account("roster-manager"), account("roster-manager-2")]);
  const authorizedDorm = "roster-" + suffix;
  const secondDorm = "roster-2-" + suffix;
  await Promise.all([authorizedDorm, secondDorm].map(id => db.collection("dormCatalog").doc(id).create(catalog)));
  const directory = mkdtempSync(path.join(tmpdir(), "camasir-roster-"));
  const rosterFile = path.join(directory, "staff.json");
  function importRoster(rows) {
    writeFileSync(rosterFile, JSON.stringify(rows));
    execFileSync(process.execPath, [path.join(__dirname, "import-manager-access.js"), rosterFile, "--apply", projectId]);
  }
  try {
    importRoster([
      { email: manager.email.toUpperCase(), dormIds: [authorizedDorm, secondDorm] },
      { email: anotherManager.email, dormIds: [authorizedDorm] },
      { email: candidate.email, dormIds: [authorizedDorm] },
    ]);
    const lookup = await callWorker(manager, "managerAccess");
    assert.deepEqual(lookup.result?.dorms.map(dorm => dorm.id), [authorizedDorm, secondDorm], JSON.stringify(lookup));
    assert.deepEqual((await call(manager, "managerAccess", authorizedDorm)).result?.dorms, lookup.result?.dorms);
    assert.equal((await callWorker(candidate, "managerAccess")).result?.needsVerification, true);
    const selected = { dormId: authorizedDorm };
    assert.equal((await callWorker(candidate, "activateManager", selected)).error?.code, "permission-denied");
    assert.equal((await callWorker(outsider, "activateManager", selected)).error?.code, "permission-denied");
    assert.equal((await callWorker(manager, "activateManager")).error?.code, "permission-denied");
    const activated = await Promise.all([manager, anotherManager].map(person => callWorker(person, "activateManager", selected)));
    for (const response of activated) {
      assert.equal(response.result?.member.role, "ADMIN", JSON.stringify(response));
      assert.ok(!Object.hasOwn(response.result.member, "managerGrant"));
    }
    const stateRef = db.collection("dorms").doc(authorizedDorm);
    assert.equal(Object.keys((await stateRef.get()).data().members).length, 2);
    assert.equal((await callWorker(anotherManager, "status")).error?.code, "permission-denied");
    assert.equal((await callWorker(student, "status", selected)).error?.code, "permission-denied");
    assert.equal((await callWorker(manager, "staffReview", { ...selected, applicantId: student.uid, decision: "APPROVED" })).error?.code, "permission-denied");
    assert.equal((await call(manager, "managerAccess", authorizedDorm)).result?.dorms.find(dorm => dorm.id === authorizedDorm)?.role, "ADMIN");
    const newCode = (await callWorker(manager, "rotateJoinCode", selected)).result?.joinCode;
    assert.match(newCode || "", /^[0-9]{6}$/);
    const grantPath = "dormManagerGrants/" + managerGrantId(manager.email);
    const grantApi = "http://" + firestoreHost + "/v1/projects/" + projectId + "/databases/(default)/documents/" + grantPath;
    assert.equal((await fetch(grantApi, { headers: { Authorization: "Bearer " + student.token } })).status, 403);
    importRoster([{ email: manager.email, dormIds: [secondDorm] }]);
    assert.equal((await callWorker(manager, "status", selected)).error?.code, "permission-denied");
    assert.equal((await callWorker(manager, "rotateJoinCode", selected)).error?.code, "permission-denied");
    assert.equal((await callWorker(anotherManager, "status", selected)).result?.joinCode, newCode);
    assert.equal((await callWorker(manager, "activateManager", { dormId: secondDorm })).result?.member.role, "ADMIN");
    importRoster([{ email: manager.email, dormIds: [] }]);
    assert.deepEqual((await callWorker(manager, "managerAccess")).result?.dorms, []);
  } finally {
    rmSync(rosterFile, { force: true });
    rmdirSync(directory);
  }
  const verificationLink = new URL(await auth.generateEmailVerificationLink(candidate.email));
  const verified = await fetch("http://" + authHost + "/identitytoolkit.googleapis.com/v1/accounts:update?key=demo", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ oobCode: verificationLink.searchParams.get("oobCode") }),
  });
  assert.equal(verified.status, 200);
  assert.equal((await auth.getUser(candidate.uid)).emailVerified, true);
  assert.equal((await callWorker(candidate, "staffApply", { name: "Test Adayı", dormId: "inactive-" + suffix })).error?.code, "not-found");
  assert.equal((await callWorker(candidate, "staffApply", { name: "Test Adayı" })).result?.staffStatus, "PENDING");
  assert.equal((await callWorker(admin, "staffReview", { applicantId: candidate.uid, decision: "REJECTED" })).result?.staffStatus, "REJECTED");
  assert.equal((await callWorker(candidate, "staffStatus")).result?.staffStatus, "REJECTED");
  assert.equal((await callWorker(candidate, "staffApply", { name: "Yeni Başvuru" })).result?.staffStatus, "REJECTED");
  const currentDorm = (await ref.get()).data();
  currentDorm.machines = [];
  currentDorm.queue = [];
  await ref.set(currentDorm);
  assert.equal((await callWorker(admin, "staffReview", { applicantId: student.uid, decision: "APPROVED" })).result?.staffStatus, "APPROVED");
  assert.equal((await staffApplication.get()).get("status"), "APPROVED");
  assert.equal((await callWorker(student, "status")).result?.member.role, "ADMIN");
  assert.equal((await callWorker(student, "staffReviews")).error?.code, "permission-denied");
  await testKyk(worker, env, callWorker);
  console.log("Worker testi geçti: personel başvurusu, bildirimler, yurt yetkisi, kod sınırı, öğrenci onayı ve sıra.");
}

async function testKyk(worker, env, callWorker) {
  const { readFile } = require("node:fs/promises");
  const { parseKykCatalog, importKykCatalog } = require("./import-kyk-catalog");
  const rows = parseKykCatalog(await readFile(path.join(__dirname, "../data/kyk-dormitories.json"), "utf8"));
  assert.equal(new Set(rows.map(row => row.city)).size, 81);
  const existing = { active: true, name: "1071 KYK Erkek Yurdu", city: "Muş", district: "Merkez" };
  const legacy = db.doc("dormCatalog/legacy-1071");
  await legacy.create(existing);
  const before = await importKykCatalog(db, rows);
  assert.equal(before.changed, rows.length);
  assert.equal((await importKykCatalog(db, rows)).changed, 0);
  assert.deepEqual((await legacy.get()).data(), existing);
  assert.equal((await db.doc("kykRegistry/kyk-049153406").get()).get("legacyDormId"), "legacy-1071");
  assert.equal((await db.doc("kykPublicCatalog/current").get()).get("dorms")
    .find(row => row.id === "kyk-049153406").enrollmentId, "legacy-1071");
  const inactive = rows.find(row => !row.officialEmail && row.id !== "kyk-049153406");
  assert.equal((await db.doc("dormCatalog/" + inactive.id).get()).exists, false);
  const main = await account("kyk-owner", false, "gsb.gov.tr");
  const invited = await account("kyk-staff");
  const stranger = await account("kyk-stranger", true, "gsb.gov.tr");
  const manual = await account("kyk-manual", false, "gsb.gov.tr");
  await auth.updateUser(main.uid, { displayName: "KYK Test Yetkilisi" });
  const district = rows.find(row => row.officialEmail);
  const dormId = district.id;
  const publicRef = db.doc("kykPublicCatalog/current");
  const publicRows = (await publicRef.get()).get("dorms");
  publicRows.find(item => item.id === dormId).officialEmail = main.email;
  await Promise.all([
    db.doc("kykRegistry/" + dormId).update({ officialEmail: main.email }),
    publicRef.update({ dorms: publicRows }),
  ]);
  const publicResult = await worker.fetch(new Request("https://test.workers.dev/kyk-catalog"), env).then(result => result.json());
  assert.ok(publicResult.result?.dorms.some(item => item.id === dormId && item.officialEmail === main.email && !item.active));
  assert.equal((await callWorker(invited, "kykPrepare", { dormId: inactive.id, name: "Sahte Kurumsal" })).error?.code, "invalid-argument");
  assert.equal((await callWorker(stranger, "kykPrepare", { dormId, name: "Başka Kurumsal" })).error?.code, "permission-denied");
  assert.equal((await callWorker(manual, "kykPrepare", { dormId: inactive.id, name: "Manuel KYK Adayı" })).result?.staffStatus, "EMAIL_PENDING");
  assert.equal((await callWorker(manual, "kykComplete")).error?.code, "permission-denied");
  assert.equal((await callWorker(manual, "kykPrepare", { dormId, name: "Manuel KYK Adayı" })).error?.code, "failed-precondition");
  const manualLink = new URL(await auth.generateEmailVerificationLink(manual.email));
  assert.equal((await fetch("http://" + authHost + "/identitytoolkit.googleapis.com/v1/accounts:update?key=demo", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ oobCode: manualLink.searchParams.get("oobCode") }),
  })).status, 200);
  assert.equal((await callWorker(manual, "kykComplete", { role: "ADMIN" })).result?.staffStatus, "PENDING");
  assert.deepEqual((await callWorker(manual, "managerAccess")).result?.dorms, []);
  assert.equal((await callWorker(manual, "kykActivate", { dormId: inactive.id })).error?.code, "permission-denied");
  assert.equal((await db.doc("dormCatalog/" + inactive.id).get()).exists, false);
  assert.equal((await callWorker(main, "kykPrepare", { dormId, name: "KYK Test Yetkilisi" })).result?.staffStatus, "EMAIL_PENDING");
  assert.equal((await callWorker(main, "kykActivate", { dormId })).error?.code, "permission-denied");
  const link = new URL(await auth.generateEmailVerificationLink(main.email));
  const verification = await fetch("http://" + authHost + "/identitytoolkit.googleapis.com/v1/accounts:update?key=demo", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ oobCode: link.searchParams.get("oobCode") }),
  });
  assert.equal(verification.status, 200);
  assert.equal((await callWorker(main, "kykPending")).result?.dormId, dormId);
  assert.equal((await callWorker(main, "kykActivate", { dormId: inactive.id })).error?.code, "permission-denied");
  assert.ok(!(await callWorker(main, "catalog")).result?.dorms.some(item => item.id === dormId));
  const claims = await Promise.all([1, 2].map(() => callWorker(main, "kykComplete")));
  assert.ok(claims.some(claim => claim.result?.dormId === dormId), JSON.stringify(claims));
  assert.ok(claims.every(claim => claim.result?.dormId === dormId || claim.result?.staffStatus === "APPROVED"), JSON.stringify(claims));
  assert.equal((await db.doc("dorms/" + dormId).get()).get("audit").filter(item => item.action === "kykActivate").length, 1);
  assert.equal((await callWorker(stranger, "kykActivate", { dormId })).error?.code, "already-exists");
  assert.ok((await callWorker(invited, "catalog")).result?.dorms.some(item => item.id === dormId));
  assert.equal((await callWorker(invited, "status", { dormId })).error?.code, "permission-denied");
  execFileSync(process.execPath, [path.join(__dirname, "seed.js"), projectId, manual.uid, inactive.id]);
  assert.equal((await callWorker(manual, "status", { dormId: inactive.id })).result?.member.kykOwner, true);
  const invitation = await callWorker(main, "kykInviteCreate", { dormId, email: invited.email, name: "Test Personeli" });
  const token = invitation.result?.invitation.split(".")[1];
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  const pending = (await callWorker(main, "kykInvites", { dormId })).result?.invitations[0];
  assert.equal(pending.email, invited.email);
  assert.ok(!Object.hasOwn(pending, "hash"));
  assert.equal((await callWorker(stranger, "kykInviteAccept", { dormId, token })).error?.code, "permission-denied");
  assert.equal((await callWorker(stranger, "kykInviteRotate", { dormId, inviteId: pending.id })).error?.code, "permission-denied");
  const rotated = await callWorker(main, "kykInviteRotate", { dormId, inviteId: pending.id });
  const newToken = rotated.result?.invitation.split(".")[1];
  assert.match(newToken, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(newToken, token);
  assert.equal((await callWorker(invited, "kykInviteAccept", { dormId, token })).error?.code, "permission-denied");
  assert.equal((await callWorker(invited, "kykInviteAccept", { dormId, token: newToken })).result?.dormId, dormId);
  assert.equal((await callWorker(invited, "kykInviteAccept", { dormId, token: newToken })).error?.code, "permission-denied");
  assert.equal((await callWorker(main, "kykInviteRotate", { dormId, inviteId: pending.id })).error?.code, "failed-precondition");
  assert.equal((await callWorker(invited, "kykInviteAccept", { dormId, token })).error?.code, "permission-denied");
  const scoped = await callWorker(invited, "status", { dormId });
  assert.equal(scoped.result?.member.staffScope, "OPERATIONS");
  assert.ok(!Object.hasOwn(scoped.result, "joinCode"));
  assert.deepEqual(scoped.result.members, []);
  assert.equal((await callWorker(invited, "rotateJoinCode", { dormId })).error?.code, "permission-denied");
  assert.equal((await callWorker(invited, "status")).error?.code, "permission-denied");
  assert.equal((await callWorker(invited, "kykInvites", { dormId })).error?.code, "permission-denied");
  assert.equal((await callWorker(main, "kykInviteRevoke", { dormId, inviteId: pending.id })).error, undefined);
  assert.equal((await callWorker(invited, "status", { dormId })).error?.code, "permission-denied");
  console.log("KYK testi geçti: idempotent katalog, Muş 1071, e-posta doğrulaması, tek aktivasyon, davet ve erişim iptali.");
}

async function run() {
  const [admin, student, outsider, unverifiedAdmin] = await Promise.all([
    account("admin"), account("student"), account("other"), account("unverified-admin", false),
  ]);
  const dormId = "test-" + suffix;
  const dorm = {
    name: "Test Yurdu", city: "Test İl", district: "Test İlçe", active: true, joinCode: "AB12CD",
    rooms: [], machines: [], queue: [], history: [], faults: [], inbox: [], audit: [],
    members: {
      [admin.uid]: { name: "Test Yönetici", role: "ADMIN", status: "ACTIVE" },
      [unverifiedAdmin.uid]: { name: "Test Yönetici 2", role: "ADMIN", status: "ACTIVE" },
    },
  };
  await db.collection("dorms").doc(dormId).create(dorm);
  await db.collection("dormCatalog").doc(dormId).create({
    name: dorm.name, city: dorm.city, district: dorm.district, active: true,
  });
  await db.collection("dormCatalog").doc("inactive-" + suffix).create({
    name: "Pasif Yurt", city: dorm.city, district: dorm.district, active: false,
  });
  const firestoreUrl = "http://" + firestoreHost + "/v1/projects/" + projectId + "/databases/(default)/documents";
  const catalogQuery = {
    from: [{ collectionId: "dormCatalog" }],
    where: { fieldFilter: { field: { fieldPath: "active" }, op: "EQUAL", value: { booleanValue: true } } },
    limit: 100,
  };
  async function readCatalog(token, query) {
    const headers = { "Content-Type": "application/json" };
    if (token) headers.Authorization = "Bearer " + token;
    return fetch(firestoreUrl + ":runQuery", {
      method: "POST", headers, body: JSON.stringify({ structuredQuery: query }),
    });
  }
  const visible = await readCatalog(student.token, catalogQuery);
  assert.equal(visible.status, 200, await visible.clone().text());
  const names = (await visible.json()).flatMap(item => item.document?.fields?.name?.stringValue ?? []);
  assert.ok(names.includes(dorm.name));
  assert.ok(!names.includes("Pasif Yurt"));
  assert.equal((await readCatalog(null, catalogQuery)).status, 403);
  assert.equal((await readCatalog(student.token, { ...catalogQuery, where: undefined })).status, 403);
  const deniedWrite = await fetch(firestoreUrl + "/dormCatalog/" + dormId, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Authorization: "Bearer " + student.token },
    body: JSON.stringify({ fields: { name: { stringValue: "Başka ad" }, active: { booleanValue: true } } }),
  });
  assert.equal(deniedWrite.status, 403);
  expectError(await call(null, "catalog", dormId), "UNAUTHENTICATED");
  const catalog = await call(student, "catalog", dormId);
  assert.ok(catalog.result?.dorms.some(item => item.id === dormId), JSON.stringify(catalog));
  expectError(await call(unverifiedAdmin, "status", dormId), "PERMISSION_DENIED");
  expectError(await call(outsider, "status", dormId), "PERMISSION_DENIED");
  const blockedRead = await fetch("http://" + firestoreHost + "/v1/projects/" + projectId + "/databases/(default)/documents/dorms/" + dormId, {
    headers: { Authorization: "Bearer " + student.token },
  });
  assert.equal(blockedRead.status, 403);
  const blockedRoleWrite = await fetch(firestoreUrl + "/dorms/" + dormId, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Authorization: "Bearer " + student.token },
    body: JSON.stringify({ fields: { members: { mapValue: { fields: {} } } } }),
  });
  assert.equal(blockedRoleWrite.status, 403);
  for (let index = 0; index < 5; index++) {
    expectError(await call(outsider, "enroll", dormId, { code: "WRONG1", name: "Test Öğrenci" }), "FAILED_PRECONDITION");
  }
  expectError(await call(outsider, "enroll", dormId, { code: dorm.joinCode, name: "Test Öğrenci" }), "RESOURCE_EXHAUSTED");
  const applied = await call(student, "enroll", dormId, { code: dorm.joinCode, name: "Test Öğrenci" });
  assert.equal(applied.result?.member.status, "PENDING", JSON.stringify(applied));
  expectError(await call(student, "join", dormId, { room: "main" }), "FAILED_PRECONDITION");
  const approved = await call(admin, "member", dormId, { uid: student.uid, status: "ACTIVE" });
  assert.equal(approved.result?.members.find(item => item.id === student.uid)?.status, "ACTIVE");
  const created = await call(admin, "machine", dormId, { name: "Makine", number: 1, status: "AVAILABLE", roomName: "Bodrum Kat" });
  assert.equal(created.result?.rooms[0].name, "Bodrum Kat", JSON.stringify(created));
  const machineId = created.result.machines[0].id;
  const claimed = await call(student, "join", dormId, { id: machineId });
  assert.equal(claimed.result?.machines[0].mine, true);
  const started = await call(student, "start", dormId, { id: machineId, minutes: 100 });
  assert.equal(started.result?.machines[0].minutes, 100);
  expectError(await call(admin, "extend", dormId, { id: machineId, minutes: 120 }), "FAILED_PRECONDITION");
  console.log("Firebase emülatör testi geçti: kimlik, veri erişimi, katılım sınırı, üyelik, makine ve yıkama.");
  await testWorker(unverifiedAdmin, student, outsider);
}

run().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => deleteApp(app));
