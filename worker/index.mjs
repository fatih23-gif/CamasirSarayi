import { Buffer } from "node:buffer";
import service from "../functions/service.js";
import { firestoreStore } from "./firestore.mjs";

const { ServiceError, laundry, advanceDorms, deleteAccount } = service;
const statusCodes = {
  "invalid-argument": 400, unauthenticated: 401, "permission-denied": 403,
  "not-found": 404, "failed-precondition": 409, "resource-exhausted": 429, unavailable: 503,
};

async function authCall(env, endpoint, idToken, fetcher) {
  const url = new URL("https://identitytoolkit.googleapis.com/v1/accounts:" + endpoint);
  url.searchParams.set("key", env.FIREBASE_API_KEY);
  const response = await fetcher(url, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken }), redirect: "manual", signal: AbortSignal.timeout(8000),
  });
  const result = await response.json();
  if (!response.ok) {
    const code = result.error?.message?.split(" : ")[0];
    if (["INVALID_ID_TOKEN", "TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND"].includes(code)) {
      throw new ServiceError("unauthenticated", "Oturumun geçersiz. Yeniden giriş yap.");
    }
    if (code === "CREDENTIAL_TOO_OLD_LOGIN_AGAIN") {
      throw new ServiceError("failed-precondition", "Çıkış yapıp tekrar giriş yap.");
    }
    throw Object.assign(new ServiceError("unavailable", "Hesap hizmetine ulaşılamadı."), {
      diagnostic: "AUTH_HTTP_" + response.status,
    });
  }
  return result;
}

async function verifyAuth(request, env, fetcher) {
  const idToken = request.headers.get("Authorization")?.match(/^Bearer ([\w.-]{1,8192})$/i)?.[1];
  if (!idToken) throw new ServiceError("unauthenticated", "Oturum açman gerekiyor.");
  const { users } = await authCall(env, "lookup", idToken, fetcher);
  const user = users?.[0];
  // Firebase doğrulamasından önce istemci tokenındaki hiçbir alan kullanılmaz.
  const token = JSON.parse(Buffer.from(idToken.split(".")[1], "base64url").toString("utf8"));
  if (!user || user.disabled || user.localId !== token.sub || token.aud !== env.FIREBASE_PROJECT_ID ||
      token.iss !== "https://securetoken.google.com/" + env.FIREBASE_PROJECT_ID ||
      !/^[a-zA-Z0-9_-]{1,128}$/.test(token.sub) || !Number.isFinite(token.auth_time) ||
      !Number.isFinite(token.exp) || token.exp <= Math.floor(Date.now() / 1000) ||
      token.auth_time < Number(user.validSince || 0)) {
    throw new ServiceError("unauthenticated", "Oturumun geçersiz. Yeniden giriş yap.");
  }
  return { idToken, uid: token.sub, token: {
    auth_time: token.auth_time, email_verified: user.emailVerified === true, email: user.email, name: user.displayName,
  } };
}

async function readBody(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new ServiceError("invalid-argument", "İstek içeriği eksik.");
  const decoder = new TextDecoder();
  let text = "";
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 16384) {
      await reader.cancel();
      throw new ServiceError("invalid-argument", "İstek içeriği çok büyük.");
    }
    text += decoder.decode(value, { stream: true });
  }
  try { return JSON.parse(text + decoder.decode()); }
  catch { throw new ServiceError("invalid-argument", "Geçersiz istek."); }
}

const response = (data, status = 200) => Response.json(data, {
  status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
});

async function enrollmentIpKey(request, env, dormId) {
  const ip = request.headers.get("CF-Connecting-IP");
  if (!ip || !/^[0-9a-f:.]{1,45}$/i.test(ip) || !env.ENROLLMENT_IP_SECRET) {
    throw new ServiceError("unavailable", "Katılım isteği doğrulanamadı. Biraz sonra tekrar dene.");
  }
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", encoder.encode(env.ENROLLMENT_IP_SECRET),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode(JSON.stringify([dormId, ip.toLowerCase()])));
  return Buffer.from(digest).toString("hex");
}

export function createWorker(fetcher = fetch) {
  return {
    async fetch(request, env) {
      const path = new URL(request.url).pathname;
      if (request.method === "GET" && path === "/health") {
        return response({ ok: Boolean(env.FIRESTORE_SERVICE_ACCOUNT && env.FIREBASE_API_KEY && env.ENROLLMENT_IP_SECRET) });
      }
      if (request.method === "GET" && path === "/staff-dorms") {
        try {
          const dorms = await firestoreStore(env, fetcher).listStaffDorms();
          return Response.json({ result: { dorms: dorms.map(({ id, data }) => ({
            id, name: data.name, city: data.city, district: data.district,
          })) } }, { headers: { "Cache-Control": "public, max-age=300", "X-Content-Type-Options": "nosniff" } });
        } catch {
          return response({ error: { code: "unavailable", message: "Yurt listesi yüklenemedi." } }, 503);
        }
      }
      if (request.method === "GET" && path === "/kyk-catalog") {
        try {
          const cache = globalThis.caches?.default;
          const key = new Request(new URL("/kyk-catalog", request.url));
          const cached = await cache?.match(key);
          if (cached) return cached;
          const [catalog, active] = await firestoreStore(env, fetcher)
            .getMany(["kykPublicCatalog/current", "kykPublicCatalog/active"]);
          const ids = new Set(active?.dormIds || []);
          const dorms = (catalog?.dorms || []).map(({ id, name, city, district, officialEmail, legacyActive, enrollmentId }) => {
            const active = Boolean(legacyActive || ids.has(id));
            return { id, name, city, district, active, enrollmentId: enrollmentId || id, officialEmail };
          });
          const result = Response.json({ result: { dorms } }, { headers: { "Cache-Control": "public, max-age=300" } });
          if (cache) await cache.put(key, result.clone()).catch(() => {});
          return result;
        } catch {
          return response({ error: { code: "unavailable", message: "KYK kataloğu yüklenemedi." } }, 503);
        }
      }
      if (request.method !== "POST" || !["/laundry", "/deleteAccount"].includes(path)) {
        return response({ error: { code: "not-found", message: "İşlem bulunamadı." } }, 404);
      }
      let phase = "BODY";
      try {
        const data = await readBody(request);
        phase = "AUTH";
        const auth = await verifyAuth(request, env, fetcher);
        phase = "SERVICE";
        const store = firestoreStore(env, fetcher);
        const ipKey = path === "/laundry" && data?.action === "enroll"
          ? await enrollmentIpKey(request, env, data.dormId) : undefined;
        const result = path === "/laundry"
          ? await laundry(store, { auth, data, ipKey })
          : await deleteAccount(store, { auth, data }, () => authCall(env, "delete", auth.idToken, fetcher));
        return response({ result });
      } catch (error) {
        const diagnostic = error.diagnostic || phase;
        if (error instanceof ServiceError) {
          const suffix = error.code === "unavailable" ? " (" + diagnostic + ")" : "";
          return response({ error: { code: error.code, message: error.message + suffix } }, statusCodes[error.code] || 400);
        }
        console.error("Worker işlemi tamamlanamadı.", diagnostic);
        return response({ error: { code: "unavailable", message: "Sunucu şu anda işlemi tamamlayamıyor. (" + diagnostic + ")" } }, 503);
      }
    },
    async scheduled(_event, env) {
      await advanceDorms(firestoreStore(env, fetcher));
    },
  };
}

export default createWorker();
