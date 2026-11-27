const { advance, change } = require("./laundry");
const { managerEmail, managerGrantId, managerGrantPath, isDormId, grantedDorms } = require("./manager-access");
const { kykAccess, staffActions } = require("./kyk-access");

class ServiceError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function saveDorm(tx, path, state, before) {
  let next = null;
  for (const item of state.machines) {
    const time = item.status === "RUNNING" ? item.end
      : item.status === "CLAIMED" ? item.expires - (item.reminded ? 0 : 60000) : 0;
    if (time > 0 && (next === null || time < next)) next = time;
  }
  if (next === null) delete state.nextChangeAt;
  else state.nextChangeAt = next;
  if (before !== JSON.stringify(state)) tx.set(path, state);
}

function saveCatalog(tx, dormId, {name, city, district, active}) {
  tx.set("dormCatalog/" + dormId, { name, city, district, active });
}

async function advanceDorms(db) {
  const due = await db.listDueDorms(Date.now());
  for (const { id } of due) {
    await db.runTransaction(async tx => {
      const path = "dorms/" + id;
      const state = await tx.get(path);
      if (!state?.active) return;
      const before = JSON.stringify(state);
      advance(state, Date.now());
      saveDorm(tx, path, state, before);
    });
  }
}
function snapshot(state, uid, now) {
  const { managerGrant, staffEmail, ...member } = ownMember(state, uid) || {};
  const active = member.status === "ACTIVE";
  const admin = active && member.role === "ADMIN";
  const fullAdmin = admin && !member.staffScope;
  const room = state.queue.find(item => item.uid === uid)?.room;
  return {
    now, dorm: state.name, member,
    ...(fullAdmin ? { joinCode: state.joinCode || "" } : {}),
    rooms: active ? state.rooms : [],
    machines: active ? state.machines.map(({ uid: owner, durationUpdates, ...machine }) => ({
      ...machine, mine: owner === uid, owner: fullAdmin ? owner : "",
    })) : [],
    queue: active && !member.staffScope ? state.queue.map(({ uid: student, ...entry }) => ({
      ...entry, mine: student === uid, ...fullAdmin && { uid: student, name: state.members[student]?.name || "Öğrenci" },
    })) : [],
    position: active && room ? state.queue.filter(item => item.room === room).findIndex(item => item.uid === uid) + 1 : 0,
    history: active ? state.history.filter(item => fullAdmin || item.uid === uid) : [],
    faults: admin ? state.faults.map(({ uid: reporterId, ...item }) => ({
      ...item, ...(fullAdmin ? { uid: reporterId, reporter: state.members[reporterId]?.name || "Öğrenci" } : {}),
    })) : [],
    members: fullAdmin ? Object.entries(state.members).map(([id, { managerGrant, staffEmail, ...value }]) => ({ id, ...value })) : [],
    inbox: active ? state.inbox.filter(item => item.uid === uid) : [],
    audit: fullAdmin ? state.audit : [],
  };
}

function ownMember(state, uid) {
  return Object.hasOwn(state.members, uid) ? state.members[uid] : undefined;
}

function managerIdentity(auth) {
  const email = managerEmail(auth.token?.email);
  return auth.token?.email_verified === true && email ? managerGrantId(email) : "";
}

function mayManage(member, auth, grantId, dormIds, dormId) {
  if (member?.status !== "ACTIVE" || member.role !== "ADMIN") return false;
  if (member.managerGrant) return member.managerGrant === grantId && dormIds.includes(dormId);
  if (member.staffEmail) return auth.token?.email_verified === true && managerEmail(auth.token?.email) === member.staffEmail;
  return auth.token?.email_verified === true || member.verifiedByOperator === true;
}

function dormSummary(id, { name, city, district }, member) {
  return { id, name, city, district, status: member?.status || "", role: member?.role || "",
    canReviewStaff: member?.role === "ADMIN" && member.canReviewStaff === true };
}

async function managerAccess(db, auth) {
  const grantId = managerIdentity(auth);
  const [managed, grant] = await Promise.all([
    db.listManagerDorms(auth.uid),
    grantId ? db.get(managerGrantPath(grantId)) : undefined,
  ]);
  const dormIds = grantedDorms(grant);
  const catalog = await db.getMany(dormIds.map(id => "dormCatalog/" + id));
  const allowed = new Map();
  catalog.forEach((data, index) => {
    if (data?.active === true) allowed.set(dormIds[index], dormSummary(dormIds[index], data));
  });
  for (const { id, data } of managed) {
    const member = ownMember(data, auth.uid);
    if (data.active === true && mayManage(member, auth, grantId, dormIds, id)) {
      allowed.set(id, dormSummary(id, data, member));
    }
  }
  return { dorms: [...allowed.values()], needsVerification: !grantId && !allowed.size,
    restricted: managed.length > 0 && !allowed.size };
}

async function activateManager(db, auth, dormId) {
  const grantId = managerIdentity(auth);
  if (!grantId) throw new ServiceError("permission-denied", "Yönetici hesabının e-posta adresini doğrula.");
  const uid = auth.uid;
  return db.runTransaction(async tx => {
    const grant = await tx.get(managerGrantPath(grantId));
    if (!grantedDorms(grant).includes(dormId)) {
      throw new ServiceError("permission-denied", "Bu yurt için yönetici yetkin yok.");
    }
    const path = "dorms/" + dormId;
    const catalog = await tx.get("dormCatalog/" + dormId);
    if (catalog?.active !== true) throw new ServiceError("not-found", "Bu yurt kullanıma açık değil.");
    const existing = await tx.get(path);
    const state = existing ?? {
      name: catalog.name, city: catalog.city, district: catalog.district, active: true,
      rooms: [], machines: [], members: {}, queue: [], history: [], faults: [], inbox: [], audit: [],
    };
    if (state.active !== true) throw new ServiceError("failed-precondition", "Yurt şu anda kullanıma kapalı.");
    const before = JSON.stringify(state);
    const now = Date.now();
    advance(state, now);
    const member = ownMember(state, uid);
    if (member?.role !== "ADMIN" && (state.machines.some(machine => machine.uid === uid) ||
        state.queue.some(entry => entry.uid === uid))) {
      throw new ServiceError("failed-precondition", "Yönetici panelini açmak için önce sıradan ayrıl veya aktif kullanımını tamamla.");
    }
    if (member?.managerGrant !== grantId || member?.status !== "ACTIVE") {
      const name = typeof auth.token.name === "string" ? auth.token.name.trim().slice(0, 80) : "";
      state.members[uid] = { name: member?.name || name || "Yurt Yöneticisi", role: "ADMIN", status: "ACTIVE", managerGrant: grantId };
      state.audit.unshift({ uid, action: "activateManager", target: uid, reason: "Kurum listesiyle yönetici yetkisi açıldı.", at: now });
    }
    saveDorm(tx, path, state, before);
    return snapshot(state, uid, now);
  });
}

async function laundry(db, request) {
  if (!request.auth) throw new ServiceError("unauthenticated", "Oturum açman gerekiyor.");
  const { uid } = request.auth;
  if (!request.data || typeof request.data !== "object" || Array.isArray(request.data)) {
    throw new ServiceError("invalid-argument", "Geçersiz istek.");
  }
  const { action, dormId, ...args } = request.data;
  const applicationPath = "staffApplications/" + uid;
  if (action === "kykPrepare" || action === "kykComplete") {
    const email = managerEmail(request.auth.token?.email);
    if (!email.endsWith("@gsb.gov.tr")) {
      throw new ServiceError("invalid-argument", "Geçerli bir @gsb.gov.tr adresi gir.");
    }
    if (action === "kykComplete" && request.auth.token.email_verified !== true) {
      throw new ServiceError("permission-denied", "Önce kurumsal e-postanı doğrula.");
    }
    const result = await db.runTransaction(async tx => {
      const application = await tx.get(applicationPath);
      if (action === "kykPrepare") {
        if (application && (application.kykCatalogId !== dormId || application.email !== email)) {
          throw new ServiceError("failed-precondition", "Bu hesabın kayıtlı bir başvurusu var.");
        }
        if (!isDormId(dormId)) throw new ServiceError("invalid-argument", "Geçerli bir KYK yurdu seç.");
        const dorm = await tx.get("kykRegistry/" + dormId);
        if (!dorm) throw new ServiceError("not-found", "Seçtiğin yurt katalogda bulunamadı.");
        if (dorm.officialEmail && dorm.officialEmail !== email && !dorm.activated && !dorm.legacyProtected) {
          throw new ServiceError("permission-denied", "Bu yurt için katalogda gösterilen kurumsal adresi kullan.");
        }
        if (!application) {
          const name = typeof args.name === "string" ? args.name.trim() : "";
          if (name.length < 3 || name.length > 120) throw new ServiceError("invalid-argument", "Adını ve soyadını kontrol et.");
          tx.set(applicationPath, { name, email, kykCatalogId: dormId, dormId: dorm.legacyDormId || dormId,
            status: "EMAIL_PENDING", submittedAt: Date.now() });
        }
        return { staffStatus: application?.status || "EMAIL_PENDING" };
      }
      if (!application?.kykCatalogId || application.email !== email) {
        throw new ServiceError("failed-precondition", "Kurumsal kayıt başvurusu bulunamadı.");
      }
      if (application.status !== "EMAIL_PENDING") return { staffStatus: application.status };
      const dorm = await tx.get("kykRegistry/" + application.kykCatalogId);
      if (dorm?.officialEmail === email && !dorm.activated && !dorm.legacyProtected) {
        return { activate: application.kykCatalogId, name: application.name };
      }
      tx.set(applicationPath, { ...application, status: "PENDING", verifiedAt: Date.now() });
      return { staffStatus: "PENDING" };
    });
    if (!result.activate) return result;
    return kykAccess(db, request.auth, result.activate, "kykActivate", { name: result.name }, ServiceError);
  }
  if (action === "kykPending") {
    const email = managerEmail(request.auth.token?.email);
    const matches = email.endsWith("@gsb.gov.tr") ? await db.findKykMailbox(email) : [];
    return { dormId: matches.length === 1 && !matches[0].data.legacyProtected && !matches[0].data.activated ? matches[0].id : "" };
  }
  if (["kykActivate", "kykInviteAccept", "kykInvites", "kykInviteCreate", "kykInviteRotate", "kykInviteRevoke"].includes(action)) {
    return kykAccess(db, request.auth, dormId, action, args, ServiceError);
  }
  const emailVerified = request.auth.token?.email_verified === true;
  if (action === "staffStatus") {
    const application = await db.get(applicationPath);
    return { staffStatus: application?.status || "", kykCatalogId: application?.kykCatalogId || "" };
  }
  if (action === "staffApply") {
    const email = managerEmail(request.auth.token?.email);
    if (!emailVerified) throw new ServiceError("permission-denied", "Önce e-posta adresini doğrula.");
    if (!email || !isDormId(dormId) || typeof args.name !== "string" ||
        args.name.trim().length < 3 || args.name.trim().length > 120) {
      throw new ServiceError("invalid-argument", "Ad ve yurt bilgilerini kontrol et.");
    }
    return db.runTransaction(async tx => {
      const existing = await tx.get(applicationPath);
      if (existing?.status) return { staffStatus: existing.status };
      const catalog = await tx.get("dormCatalog/" + dormId);
      if (catalog?.active !== true) throw new ServiceError("not-found", "Etkin bir yurt seç.");
      tx.set(applicationPath, {
        email, name: args.name.trim(), dormId,
        status: "PENDING", submittedAt: Date.now(),
      });
      return { staffStatus: "PENDING" };
    });
  }
  if (action === "managerAccess") return managerAccess(db, request.auth);
  if (action === "catalog") {
    const dorms = await db.listDorms(true);
    const grantId = managerIdentity(request.auth);
    const dormIds = grantId && dorms.some(({ data }) => ownMember(data, uid)?.managerGrant)
      ? grantedDorms(await db.get(managerGrantPath(grantId))) : [];
    return { dorms: dorms.map(doc => {
      const member = ownMember(doc.data, uid);
      return dormSummary(doc.id, doc.data,
        member?.role === "ADMIN" && !mayManage(member, request.auth, grantId, dormIds, doc.id) ? undefined : member);
    }) };
  }
  if (!isDormId(dormId)) {
    throw new ServiceError("invalid-argument", "Geçersiz yurt.");
  }
  if (action === "activateManager") return activateManager(db, request.auth, dormId);
  const path = "dorms/" + dormId;
  if (action === "staffReviews") {
    const dorm = await db.get(path);
    await requireStaffReviewer(db, dorm, request.auth, dormId);
    return { applications: (await db.listStaffApplications(dormId)).map(({ id, data }) => ({ ...data, id })) };
  }
  const response = await db.runTransaction(async tx => {
    const state = await tx.get(path);
    if (!state?.active) throw new ServiceError("not-found", "Yurt bulunamadı.");
    const member = ownMember(state, uid);
    if (action === "staffReview") {
      await requireStaffReviewer(tx, state, request.auth, dormId);
      return reviewStaff(tx, path, state, dormId, uid, args);
    }
    if (member?.role === "ADMIN") {
      const grantId = managerIdentity(request.auth);
      const allowed = member.managerGrant === grantId && grantId
        ? grantedDorms(await tx.get(managerGrantPath(grantId))) : [];
      if (!mayManage(member, request.auth, grantId, allowed, dormId)) {
        throw new ServiceError("permission-denied", "Bu yurt için aktif yönetici yetkin yok.");
      }
      if (member.staffScope && !staffActions.has(action)) {
        throw new ServiceError("permission-denied", "Bu işlem yurt yönetim hesabına ayrılmıştır.");
      }
    }
    if (member?.role !== "ADMIN" && !emailVerified) {
      throw new ServiceError("permission-denied", "Önce e-posta adresini doğrula.");
    }
    if (action !== "enroll" && !member) throw new ServiceError("permission-denied", "Yurt üyeliği gerekiyor.");
    const now = Date.now();
    if (action === "enroll" && (!member || member.status === "REJECTED")) {
      if (!Object.values(state.members).some(item => item.role === "ADMIN" && item.status === "ACTIVE" && !item.staffScope)) {
        throw new ServiceError("failed-precondition", "Yurt yönetim hesabı etkin değil.");
      }
      const limitRef = path + "/enrollmentLimits/" + uid;
      const limit = await tx.get(limitRef);
      const window = now + 15 * 60000;
      const attempts = limit?.expiresAt > now ? limit.attempts : 0;
      if (attempts >= 5) return { enrollmentError: "resource-exhausted" };
      const ipRef = request.ipKey ? path + "/enrollmentIpLimits/" + request.ipKey : null;
      const ipLimit = ipRef && await tx.get(ipRef);
      const ipAttempts = ipLimit?.expiresAt > now ? ipLimit.attempts : 0;
      if (ipAttempts >= 20) return { enrollmentError: "resource-exhausted" };
      if (!state.joinCode || args.code !== state.joinCode) {
        tx.set(limitRef, {
          attempts: attempts + 1,
          expiresAt: attempts ? limit.expiresAt : window,
        });
        if (ipRef) tx.set(ipRef, {
          attempts: ipAttempts + 1,
          expiresAt: ipAttempts ? ipLimit.expiresAt : window,
        });
        return { enrollmentError: "failed-precondition" };
      }
      if (limit) tx.delete(limitRef);
    }
    const before = JSON.stringify(state);
    advance(state, now);
    if (action !== "status") {
      try {
        change(state, uid, action, args, now, state.joinCode);
        advance(state, now);
      } catch (error) {
        throw new ServiceError("failed-precondition", error.message);
      }
    }
    saveDorm(tx, path, state, before);
    if (action === "settings") saveCatalog(tx, dormId, state);
    return snapshot(state, uid, now);
  });
  if (response.enrollmentError === "resource-exhausted") {
    throw new ServiceError("resource-exhausted", "Çok fazla kod denemesi yapıldı. 15 dakika sonra tekrar dene.");
  }
  if (response.enrollmentError) throw new ServiceError("failed-precondition", "Katılım kodu geçersiz.");
  return response;
}

async function requireStaffReviewer(store, dorm, auth, dormId) {
  const member = dorm && ownMember(dorm, auth.uid);
  const grantId = managerIdentity(auth);
  const authorizedDorms = grantId && member?.managerGrant === grantId
    ? grantedDorms(await store.get(managerGrantPath(grantId))) : [];
  if (dorm?.active !== true || member?.staffScope || member?.canReviewStaff !== true ||
      !mayManage(member, auth, grantId, authorizedDorms, dormId)) {
    throw new ServiceError("permission-denied", "Bu yurtta personel başvurusu inceleme yetkin yok.");
  }
}

async function reviewStaff(tx, path, state, dormId, reviewer, { applicantId, decision }) {
  if (!/^[a-zA-Z0-9_-]{1,128}$/.test(typeof applicantId === "string" ? applicantId : "") ||
      applicantId === reviewer || !["APPROVED", "REJECTED"].includes(decision)) {
    throw new ServiceError("invalid-argument", "Geçersiz başvuru kararı.");
  }
  const ref = "staffApplications/" + applicantId;
  const application = await tx.get(ref);
  if (application?.dormId !== dormId || application.status !== "PENDING") {
    throw new ServiceError("not-found", "Bekleyen başvuru bulunamadı.");
  }
  const now = Date.now();
  if (decision === "APPROVED") {
    if (ownMember(state, applicantId)?.role === "ADMIN" ||
        state.machines.some(machine => machine.uid === applicantId) || state.queue.some(entry => entry.uid === applicantId)) {
      throw new ServiceError("failed-precondition", "Adayın mevcut yönetici veya aktif kullanım kaydını kontrol et.");
    }
    state.members[applicantId] = { name: application.name, role: "ADMIN", status: "ACTIVE", staffEmail: application.email };
  }
  state.audit.unshift({ uid: reviewer, action: "staffReview", target: applicantId, reason: decision, at: now });
  tx.set(path, state);
  tx.set(ref, { ...application, status: decision, reviewedAt: now, reviewedBy: reviewer });
  return { staffStatus: decision };
}

async function deleteAccount(db, request, deleteUser) {
  if (!request.auth) throw new ServiceError("unauthenticated", "Oturum açman gerekiyor.");
  const { uid, token } = request.auth;
  const preview = request.data?.preview === true;
  if (!preview && (!Number.isFinite(token.auth_time) || Date.now() / 1000 - token.auth_time > 300)) {
    throw new ServiceError("failed-precondition", "Çıkış yapıp tekrar giriş yap.");
  }
  await db.runTransaction(async tx => {
    const dorms = await tx.listDorms(false);
    const grantId = managerIdentity(request.auth);
    const allowed = grantId && dorms.some(({ data }) => ownMember(data, uid)?.managerGrant === grantId)
      ? grantedDorms(await tx.get(managerGrantPath(grantId))) : [];
    if (!preview) tx.delete("staffApplications/" + uid);
    for (const doc of dorms) {
      const state = doc.data;
      const path = "dorms/" + doc.id;
      const member = ownMember(state, uid);
      const admin = member?.role === "ADMIN" && (!member.managerGrant || mayManage(member, request.auth, grantId, allowed, doc.id));
      if (admin || state.machines.some(item => item.uid === uid)) {
        throw new ServiceError("failed-precondition", "Aktif kullanım veya yönetici yetkisi varken hesap silinemez.");
      }
      if (preview) continue;
      tx.delete(path + "/enrollmentLimits/" + uid);
      if (!state.members[uid]) continue;
      delete state.members[uid];
      state.queue = state.queue.filter(item => item.uid !== uid);
      state.history = state.history.filter(item => item.uid !== uid);
      state.inbox = state.inbox.filter(item => item.uid !== uid);
      for (const item of state.faults) if (item.uid === uid) item.uid = "";
      for (const item of state.audit) if (item.uid === uid || item.target === uid) {
        if (item.uid === uid) item.uid = "";
        if (item.target === uid) item.target = "";
      }
      tx.set(path, state);
    }
  });
  if (!preview) await deleteUser(uid);
  return { ok: true };
}

module.exports = { ServiceError, laundry, advanceDorms, deleteAccount };
