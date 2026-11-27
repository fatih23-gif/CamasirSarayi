const { randomBytes, createHash } = require("node:crypto");
const { isDormId, managerEmail, managerGrantId } = require("./manager-access");

const activationPath = id => "kykActivations/" + id;
const registryPath = id => "kykRegistry/" + id;
const inviteHash = token => createHash("sha256").update(token).digest("hex");
const inviteLifetime = 48 * 60 * 60 * 1000;
const staffActions = new Set(["status", "machine", "fault", "read"]);

async function kykAccess(db, auth, dormId, action, args, ServiceError) {
  const verified = auth.token?.email_verified === true;
  const email = managerEmail(auth.token?.email);
  const uid = auth.uid;
  const error = (code, message) => { throw new ServiceError(code, message); };
  if (!isDormId(dormId)) error("invalid-argument", "Geçerli bir yurt seç.");
  if (!verified || !email) error("permission-denied", "Önce e-posta adresini doğrula.");
  const path = "dorms/" + dormId;
  const now = Date.now();
  const mailboxes = action === "kykActivate" ? await db.findKykMailbox(email) : [];
  return db.runTransaction(async tx => {
    const state = await tx.get(path);
    const activation = await tx.get(activationPath(dormId));
    if (action === "kykActivate") {
      if (activation?.ownerUid === uid && state?.members?.[uid]?.status === "ACTIVE" &&
          state.members[uid].staffEmail === email) return { dormId };
      const registry = await tx.get(registryPath(dormId));
      const catalog = await tx.get("dormCatalog/" + dormId);
      const mailboxPath = "kykMailboxClaims/" + managerGrantId(email);
      const claim = await tx.get(mailboxPath);
      if (activation || state || catalog?.active === true || claim) {
        error("already-exists", "Yurdun mevcut yönetim hesabıyla giriş yap veya personel daveti iste.");
      }
      if (mailboxes.length !== 1 || mailboxes[0].id !== dormId ||
          !registry || registry.legacyProtected || registry.legacyDormId || registry.officialEmail !== email ||
          !email.endsWith("@gsb.gov.tr") || !registry.emailSourceUrl) {
        error("permission-denied", "Yurdun kayıtlı kurumsal e-posta adresi doğrulanamadı.");
      }
      const provided = auth.token.name || args.name;
      const name = typeof provided === "string" ? provided.trim().slice(0, 120) : "";
      if (name.length < 3) error("invalid-argument", "Hesabın ad ve soyad bilgisi eksik.");
      const publicActivePath = "kykPublicCatalog/active";
      const publicActive = await tx.get(publicActivePath);
      const applicationPath = "staffApplications/" + uid;
      const application = await tx.get(applicationPath);
      const identity = { name: registry.name, city: registry.city, district: registry.district, active: true };
      tx.set(path, {
        ...identity, rooms: [], machines: [], members: {
          [uid]: { name, role: "ADMIN", status: "ACTIVE", staffEmail: email, kykOwner: true },
        }, queue: [], history: [], faults: [], inbox: [],
        audit: [{ uid, action: "kykActivate", reason: "Kurumsal posta erişimi doğrulandı.", at: now }],
      });
      tx.set("dormCatalog/" + dormId, identity);
      tx.set(registryPath(dormId), { ...registry, activated: true });
      tx.set(publicActivePath, { dormIds: [...new Set([...(publicActive?.dormIds || []), dormId])] });
      tx.set(activationPath(dormId), { ownerUid: uid, email, verifiedAt: now, invites: [] });
      tx.set(mailboxPath, { dormId, ownerUid: uid, verifiedAt: now });
      if (application?.status === "EMAIL_PENDING" && application.kykCatalogId === dormId && application.email === email) {
        tx.set(applicationPath, { ...application, status: "APPROVED", verifiedAt: now });
      }
      return { dormId };
    }
    if (!state?.active || !activation) error("not-found", "Bu yurtta personel daveti kullanılamıyor.");
    const invites = activation.invites || [];
    const members = state.members;
    if (action === "kykInviteAccept") {
      if (typeof args.token !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(args.token)) {
        error("invalid-argument", "Davet kodunu kontrol et.");
      }
      const invitation = invites.find(item => item.hash === inviteHash(args.token) && item.email === email);
      const current = Object.hasOwn(members, uid) && members[uid];
      if (!invitation || invitation.expiresAt <= now || invitation.status !== "PENDING" ||
          current && !(current.status === "SUSPENDED" && current.staffScope === "OPERATIONS" && current.staffEmail === email)) {
        error("permission-denied", "Davet kullanılamıyor. E-postanı ve davetin geçerliliğini kontrol et.");
      }
      invitation.status = "ACCEPTED";
      invitation.uid = uid;
      members[uid] = { name: invitation.name, role: "ADMIN", status: "ACTIVE", staffEmail: email, staffScope: "OPERATIONS" };
      state.audit.unshift({ uid, action, at: now });
      tx.set(path, state);
      tx.set(activationPath(dormId), activation);
      return { dormId };
    }
    const owner = Object.hasOwn(members, uid) && members[uid];
    if (activation.ownerUid !== uid || activation.email !== email ||
        owner?.role !== "ADMIN" || owner.status !== "ACTIVE" || owner.staffEmail !== email) {
      error("permission-denied", "Bu yurtta personel yönetme yetkin yok.");
    }
    if (action === "kykInvites") {
      return { invitations: invites.filter(item => item.status === "ACCEPTED" ||
        item.status === "PENDING" && item.expiresAt > now).map(({ id, name, email, status, expiresAt }) =>
        ({ id, name, email, status, expiresAt })) };
    }
    if (action === "kykInviteCreate") {
      const invitedEmail = managerEmail(args.email);
      const name = typeof args.name === "string" ? args.name.trim() : "";
      if (!invitedEmail || invitedEmail === email || name.length < 3 || name.length > 120) {
        error("invalid-argument", "Personelin adını ve kendi e-posta adresini gir.");
      }
      activation.invites = invites.filter(item => item.status === "ACCEPTED" ||
        item.status === "PENDING" && item.expiresAt > now);
      if (activation.invites.length >= 50 || activation.invites.some(item => item.email === invitedEmail)) {
        error("failed-precondition", "Bu e-posta için zaten erişim veya bekleyen davet var ya da yurt davet sınırına ulaşıldı.");
      }
      const token = randomBytes(32).toString("base64url");
      const id = randomBytes(12).toString("hex");
      activation.invites.push({ id, hash: inviteHash(token), email: invitedEmail, name,
        status: "PENDING", expiresAt: now + inviteLifetime });
      state.audit.unshift({ uid, action, target: id, at: now });
      tx.set(path, state);
      tx.set(activationPath(dormId), activation);
      return { invitation: dormId + "." + token };
    }
    const invitation = invites.find(item => item.id === args.inviteId && item.status !== "REVOKED");
    if (!invitation) error("not-found", "Davet bulunamadı.");
    if (action === "kykInviteRotate") {
      if (invitation.status !== "PENDING" || invitation.expiresAt <= now) {
        error("failed-precondition", "Yalnızca geçerli ve bekleyen bir davetin kodu yenilenebilir.");
      }
      const token = randomBytes(32).toString("base64url");
      invitation.hash = inviteHash(token);
      invitation.expiresAt = now + inviteLifetime;
      state.audit.unshift({ uid, action, target: invitation.id, at: now });
      tx.set(path, state);
      tx.set(activationPath(dormId), activation);
      return { invitation: dormId + "." + token };
    }
    const member = invitation.uid && members[invitation.uid];
    if (member?.staffScope === "OPERATIONS" && member.staffEmail === invitation.email) member.status = "SUSPENDED";
    invitation.status = "REVOKED";
    state.audit.unshift({ uid, action, target: invitation.id, at: now });
    tx.set(path, state);
    tx.set(activationPath(dormId), activation);
    return {};
  });
}

module.exports = { kykAccess, staffActions };
