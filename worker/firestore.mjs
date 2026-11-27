import { Buffer } from "node:buffer";

const tokenUrl = "https://oauth2.googleapis.com/token";
let cachedToken;
const encode = value => Buffer.from(value).toString("base64url");

async function accessToken(env, fetcher) {
  let credential;
  try { credential = JSON.parse(env.FIRESTORE_SERVICE_ACCOUNT); }
  catch { throw Object.assign(new Error("Invalid Firestore binding"), { diagnostic: "FS_BINDING" }); }
  const { client_email: email, private_key: pem, private_key_id: kid, project_id: project } = credential || {};
  if (project !== env.FIREBASE_PROJECT_ID || !email || !pem || !kid) {
    throw Object.assign(new Error("Invalid Firestore service account"), { diagnostic: "FS_ACCOUNT" });
  }
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken?.kid === kid && cachedToken.expires > now + 60) return cachedToken.value;
  const key = await crypto.subtle.importKey(
    "pkcs8", Buffer.from(pem.replace(/-----[^-]+-----|\s/g, ""), "base64"),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  ).catch(() => { throw Object.assign(new Error("Invalid Firestore signing key"), { diagnostic: "FS_SIGNING_KEY" }); });
  const header = JSON.stringify({ alg: "RS256", typ: "JWT", kid });
  const claims = JSON.stringify({
    iss: email, aud: tokenUrl, scope: "https://www.googleapis.com/auth/datastore",
    iat: now, exp: now + 600,
  });
  const unsigned = encode(header) + "." + encode(claims);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, Buffer.from(unsigned));
  const response = await fetcher(tokenUrl, {
    method: "POST", redirect: "manual", signal: AbortSignal.timeout(8000),
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: unsigned + "." + encode(signature),
    }),
  });
  const data = await response.json();
  if (!response.ok || typeof data.access_token !== "string") {
    throw Object.assign(new Error("Firestore OAuth request failed"), { diagnostic: "FS_OAUTH_" + response.status });
  }
  cachedToken = { kid, value: data.access_token, expires: now + Number(data.expires_in) };
  return cachedToken.value;
}

function encodeFields(data) {
  return Object.fromEntries(Object.entries(data).map(([key, value]) => [key, encodeValue(value)]));
}

function encodeValue(value) {
  if (value === null) return { nullValue: null };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (Number.isSafeInteger(value)) return { integerValue: String(value) };
  if (Number.isFinite(value)) return { doubleValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeValue) } };
  if (value && typeof value === "object") return { mapValue: { fields: encodeFields(value) } };
  throw new TypeError("Unsupported Firestore value");
}

function decodeFields(fields = {}) {
  return Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, decodeValue(value)]));
}

function decodeValue(value) {
  if ("nullValue" in value) return null;
  if ("stringValue" in value) return value.stringValue;
  if ("booleanValue" in value) return value.booleanValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return value.doubleValue;
  if ("timestampValue" in value) return Date.parse(value.timestampValue);
  if (value.arrayValue) return (value.arrayValue.values || []).map(decodeValue);
  if (value.mapValue) return decodeFields(value.mapValue.fields);
  throw new TypeError("Unsupported Firestore response");
}

export function firestoreStore(env, fetcher = fetch) {
  const root = "projects/" + env.FIREBASE_PROJECT_ID + "/databases/(default)/documents";
  async function request(path, data) {
    const token = await accessToken(env, fetcher);
    const response = await fetcher("https://firestore.googleapis.com/v1/" + root + path, {
      method: data === undefined ? "GET" : "POST",
      headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
      body: data === undefined ? undefined : JSON.stringify(data),
      redirect: "manual", signal: AbortSignal.timeout(8000),
    });
    const payload = await response.json();
    if (!response.ok) {
      const error = new Error("Firestore request failed");
      error.code = payload.error?.status;
      error.diagnostic = "FS_HTTP_" + response.status;
      throw error;
    }
    return payload;
  }
  const filter = (fieldPath, op, value) => ({ fieldFilter: { field: { fieldPath }, op, value: encodeValue(value) } });
  async function list(where, transaction, limit, fields, collectionId = "dorms") {
    const rows = await request(":runQuery", {
      structuredQuery: {
        from: [{ collectionId }], where, limit,
        select: fields && { fields: fields.map(fieldPath => ({ fieldPath })) },
      },
      transaction,
    });
    return rows.filter(row => row.document).map(({ document }) => ({
      id: document.name.split("/").pop(), data: decodeFields(document.fields),
    }));
  }
  const listDorms = (activeOnly, transaction) => list(activeOnly ? filter("active", "EQUAL", true) : undefined, transaction);
  async function getMany(paths, transaction) {
    if (!paths.length) return [];
    const documents = paths.map(path => root + "/" + path);
    const rows = await request(":batchGet", { documents, transaction });
    const found = new Map(rows.filter(row => row.found)
      .map(({ found: doc }) => [doc.name, decodeFields(doc.fields)]));
    return documents.map(name => found.get(name));
  }
  async function runTransaction(work) {
    let previous;
    for (let attempt = 0; ; attempt++) {
      let transaction;
      try {
        ({ transaction } = await request(":beginTransaction", {
          options: { readWrite: { retryTransaction: previous } },
        }));
        const writes = [];
        const value = await work({
          async get(path) {
            return (await getMany([path], transaction))[0];
          },
          listDorms: activeOnly => listDorms(activeOnly, transaction),
          set: (path, data) => writes.push({ update: { name: root + "/" + path, fields: encodeFields(data) } }),
          delete: path => writes.push({ delete: root + "/" + path }),
        });
        if (writes.length) await request(":commit", { transaction, writes });
        else await request(":rollback", { transaction });
        return value;
      } catch (error) {
        if (transaction) await request(":rollback", { transaction }).catch(() => {});
        if (error.code !== "ABORTED" || attempt >= 3) throw error;
        previous = transaction;
        const delay = (100 + Math.random() * 350) * (attempt + 1);
        await new Promise(resolve => setTimeout(resolve, delay));
      }
    }
  }

  return {
    async get(path) {
      try { return decodeFields((await request("/" + path)).fields); }
      catch (error) {
        if (error.code === "NOT_FOUND") return undefined;
        throw error;
      }
    },
    getMany,
    listDorms,
    findKykMailbox: email => list(filter("officialEmail", "EQUAL", email), undefined, 2,
      ["legacyProtected", "activated"], "kykRegistry"),
    listStaffDorms: () => list(filter("active", "EQUAL", true), undefined, undefined,
      ["name", "city", "district"], "dormCatalog"),
    listStaffApplications: dormId => list({ compositeFilter: { op: "AND", filters: [
      filter("dormId", "EQUAL", dormId), filter("status", "EQUAL", "PENDING"),
    ] } }, undefined, 100, ["name", "email", "submittedAt"], "staffApplications"),
    listManagerDorms(uid) {
      const member = "members.`" + uid + "`";
      return list(filter(member + ".role", "EQUAL", "ADMIN"), undefined, 40, ["name", "city", "district", "active", member]);
    },
    listDueDorms: now => list(filter("nextChangeAt", "LESS_THAN_OR_EQUAL", now), undefined, 16),
    runTransaction,
  };
}
