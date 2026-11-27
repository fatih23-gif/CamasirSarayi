const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldPath } = require("firebase-admin/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { ServiceError, laundry, advanceDorms, deleteAccount } = require("./service");

initializeApp();
const firestore = getFirestore();
const options = { enforceAppCheck: process.env.CAMASIR_ENFORCE_APP_CHECK === "true" };

function dormQuery(activeOnly) {
  const query = firestore.collection("dorms");
  return activeOnly ? query.where("active", "==", true) : query;
}

function readDocs(snapshot) {
  return snapshot.docs.map(doc => ({ id: doc.id, data: doc.data() }));
}

const store = {
  async get(path) {
    return (await firestore.doc(path).get()).data();
  },
  async getMany(paths) {
    if (!paths.length) return [];
    return (await firestore.getAll(...paths.map(path => firestore.doc(path)))).map(doc => doc.data());
  },
  async listManagerDorms(uid) {
    const query = firestore.collection("dorms").where(new FieldPath("members", uid, "role"), "==", "ADMIN");
    return readDocs(await query.select("name", "city", "district", "active", new FieldPath("members", uid)).limit(40).get());
  },
  async listDorms(activeOnly) {
    return readDocs(await dormQuery(activeOnly).get());
  },
  async findKykMailbox(email) {
    return readDocs(await firestore.collection("kykRegistry").where("officialEmail", "==", email)
      .select("legacyProtected", "activated").limit(2).get());
  },
  async listStaffApplications(dormId) {
    return readDocs(await firestore.collection("staffApplications")
      .where("dormId", "==", dormId).where("status", "==", "PENDING")
      .select("name", "email", "submittedAt").limit(100).get());
  },
  async listDueDorms(now) {
    return readDocs(await firestore.collection("dorms").where("nextChangeAt", "<=", now).limit(16).get());
  },
  runTransaction(work) {
    return firestore.runTransaction(tx => work({
      async get(path) {
        const data = (await tx.get(firestore.doc(path))).data();
        if (data?.expiresAt?.toMillis) data.expiresAt = data.expiresAt.toMillis();
        return data;
      },
      async listDorms(activeOnly) { return readDocs(await tx.get(dormQuery(activeOnly))); },
      set(path, data) { tx.set(firestore.doc(path), data); },
      delete(path) { tx.delete(firestore.doc(path)); },
    }));
  },
};

function callable(handler) {
  return onCall(options, async request => {
    try {
      return await handler(request);
    } catch (error) {
      if (error instanceof ServiceError) throw new HttpsError(error.code, error.message);
      throw error;
    }
  });
}

exports.laundry = callable(request => laundry(store, request));
exports.advanceLaundry = onSchedule("every 1 minutes", () => advanceDorms(store));
exports.deleteAccount = callable(request => deleteAccount(store, request, uid => getAuth().deleteUser(uid)));
