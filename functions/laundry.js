const { randomInt, randomUUID } = require("node:crypto");

const minute = 60000;
function requireRule(condition, message) {
  if (!condition) throw new Error(message);
}
function notify(state, uid, title, body, now, kind = "", machineId = "") {
  state.inbox.unshift({ id: randomUUID(), uid, title, body, at: now, read: false, kind, machineId });
}
function notifyManagers(state, title, body, now, kind) {
  for (const [uid, member] of Object.entries(state.members)) {
    if (member.role === "ADMIN" && member.status === "ACTIVE" && (!member.staffScope || kind === "fault-report")) {
      notify(state, uid, title, body, now, kind);
    }
  }
}
function advance(state, now) {
  for (const machine of state.machines) {
    if (machine.status === "CLAIMED" && machine.expires <= now) {
      notify(state, machine.uid, "Sıra hakkın sona erdi", machine.name, now);
      release(machine);
    }
    if (machine.status === "CLAIMED" && !machine.reminded && machine.expires - now <= minute) {
      machine.reminded = true;
      notify(state, machine.uid, "Sıra hakkın bitmek üzere", machine.name, now);
    }
    if (machine.status === "RUNNING" && machine.end <= now) {
      machine.status = "AWAITING_UNLOAD";
      if (machine.uid) notify(state, machine.uid, "Yıkaman tamamlandı", machine.name + " · Çamaşırlarını al.", now, "wash-complete", machine.id);
    }
  }
  state.queue = state.queue.filter(entry => state.members[entry.uid]?.status === "ACTIVE");
  for (let index = 0; index < state.queue.length;) {
    const entry = state.queue[index];
    const available = state.machines.filter(machine => machine.status === "AVAILABLE" && machine.room === entry.room);
    const machine = available.find(item => item.id === entry.preferred) || available[0];
    if (!machine) {
      index++;
      continue;
    }
    state.queue.splice(index, 1);
    Object.assign(machine, {
      status: "CLAIMED", uid: entry.uid, claimId: randomUUID(),
      issuedAt: now, expires: now + 5 * minute, reminded: false,
    });
    notify(state, entry.uid, "Sıra hakkın başladı", machine.name + " · 5 dakikan var.", now, "claim");
  }
}
function release(machine) {
  Object.assign(machine, { status: "AVAILABLE", uid: "", claimId: "", issuedAt: 0, expires: 0, end: 0, start: 0, minutes: 0, durationUpdates: [] });
}
function clean(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}
function change(state, uid, action, args, now, joinCode) {
  const member = state.members[uid];
  if (action === "enroll") {
    requireRule(!member || member.status === "REJECTED", "Zaten bir üyelik kaydın var.");
    requireRule(joinCode && args.code === joinCode, "Katılım kodu geçersiz.");
    const name = clean(args.name, 80);
    requireRule(name.length >= 3, "Adını ve soyadını gir.");
    state.members[uid] = { name, status: "PENDING", role: "STUDENT" };
    notifyManagers(state, "Yeni öğrenci başvurusu", name, now, "member-request");
    return;
  }
  requireRule(member?.status === "ACTIVE", "Aktif yurt üyeliği gerekiyor.");
  const admin = member.role === "ADMIN";
  const machine = state.machines.find(item => item.id === args.id);
  const own = state.machines.find(item => item.uid === uid);
  const audit = (reason) => state.audit.unshift({ uid, action, target: args.id || args.uid || "", reason, at: now });
  switch (action) {
    case "join": {
      requireRule(!admin && !own && !state.queue.some(item => item.uid === uid), "Zaten sıran, hakkın veya yıkaman var.");
      const room = machine?.room || args.room;
      requireRule(state.rooms.some(item => item.id === room), "Çamaşırhane bulunamadı.");
      state.queue.push({ uid, room, at: now, preferred: machine?.id || "" });
      notify(state, uid, "Sıraya katıldın", "Ortak çamaşırhane sırasına eklendin.", now, "queue");
      break;
    }
    case "leave":
      requireRule(!admin, "Yönetici öğrenci sırasını değiştiremez.");
      state.queue = state.queue.filter(item => item.uid !== uid);
      if (own?.status === "CLAIMED") release(own);
      break;
    case "start":
    case "extend": {
      requireRule(machine && !admin && machine.uid === uid, "Bu makinede işlem hakkın yok.");
      requireRule(Number.isInteger(args.minutes) && args.minutes >= 1 && args.minutes <= 240, "1–240 arasında dakika gir.");
      if (action === "start") {
        requireRule(machine.status === "CLAIMED" && machine.expires > now, "Kullanım hakkının süresi doldu.");
        machine.start = now;
        machine.status = "RUNNING";
        machine.expires = 0;
        machine.durationUpdates = [];
      } else {
        requireRule(machine.status === "RUNNING", "Yıkama artık çalışmıyor.");
        requireRule(machine.start + args.minutes * minute > machine.end, "Yeni toplam süre önceki süreden uzun olmalı.");
        machine.durationUpdates.push({ uid, previous: machine.minutes, minutes: args.minutes, at: now });
      }
      machine.minutes = args.minutes;
      machine.end = machine.start + args.minutes * minute;
      break;
    }
    case "unload":
      requireRule(machine?.status === "AWAITING_UNLOAD" && !admin && machine.uid === uid, "Bu yıkamayı kapatamazsın.");
      state.history.unshift({ uid: machine.uid, name: machine.name, minutes: machine.minutes, at: now, updates: machine.durationUpdates || [] });
      release(machine);
      break;
    case "report": {
      const description = clean(args.description, 500);
      requireRule(!state.faults.some(item => item.uid === uid && item.at > now - minute), "Yeniden arıza bildirmek için 1 dakika bekle.");
      requireRule(machine && description.length >= 5, "Makine seç ve en az 5 karakter açıklama yaz.");
      state.faults.unshift({ id: randomUUID(), name: machine.name, uid, description, type: clean(args.type, 60), status: "Açık", at: now });
      notify(state, uid, "Arıza bildirimi alındı", machine.name, now);
      notifyManagers(state, "Yeni arıza bildirimi", machine.name, now, "fault-report");
      break;
    }
    case "read": {
      const item = state.inbox.find(item => item.id === args.id && item.uid === uid);
      if (item) item.read = true;
      break;
    }
    case "machine": {
      requireRule(admin, "Yönetici yetkisi gerekiyor.");
      requireRule(!args.id || machine, "Düzenlenecek makine bulunamadı.");
      const name = clean(args.name, 60);
      requireRule(name && Number.isInteger(args.number) && args.number > 0 && args.number <= 9999, "Makine adı ve geçerli numara gir.");
      const roomName = typeof args.roomName === "string" ? args.roomName.trim().replace(/\s+/g, " ") : "";
      requireRule(roomName.length <= 80, "Çamaşırhane adı en çok 80 karakter olabilir.");
      const roomKey = value => value.normalize("NFC").toLocaleLowerCase("tr-TR");
      const existingRoom = roomName
        ? state.rooms.find(room => roomKey(room.name) === roomKey(roomName))
        : state.rooms.find(room => room.id === args.room);
      requireRule(roomName || existingRoom, "Geçerli bir çamaşırhane adı gir.");
      const room = existingRoom || { id: randomUUID(), name: roomName };
      requireRule(!state.machines.some(item => item !== machine && item.room === room.id && item.number === args.number), "Makine numarası bu çamaşırhanede zaten kullanılıyor.");
      if (machine && ["CLAIMED", "RUNNING", "AWAITING_UNLOAD"].includes(machine.status)) {
        requireRule(args.status === machine.status && room.id === machine.room, "Kullanım hakkı veya yıkama aktifken durum ve salon değiştirilemez.");
      } else {
        requireRule(["AVAILABLE", "OUT_OF_SERVICE"].includes(args.status), "Geçersiz makine durumu.");
      }
      if (machine && room.id !== machine.room && state.queue.some(entry => entry.room === machine.room)
          && !state.machines.some(item => item !== machine && item.room === machine.room)) {
        throw new Error("Sıra bekleyen çamaşırhanenin son makinesi taşınamaz.");
      }
      if (!existingRoom) state.rooms.push(room);
      const item = machine || { id: randomUUID(), uid: "", start: 0, end: 0, expires: 0, minutes: 0 };
      Object.assign(item, { name, number: args.number, room: room.id, status: args.status });
      if (!machine) state.machines.push(item);
      audit("Makine kaydedildi.");
      break;
    }
    case "member": {
      const target = state.members[args.uid];
      requireRule(admin && target?.role === "STUDENT", "Bu üyelik değiştirilemez.");
      requireRule(["ACTIVE", "REJECTED", "SUSPENDED"].includes(args.status), "Geçersiz üyelik durumu.");
      requireRule(args.status === "ACTIVE" || (!state.machines.some(item => item.uid === args.uid) && !state.queue.some(entry => entry.uid === args.uid)), "Öğrencinin sıra veya makine işleminin tamamlanmasını bekle.");
      target.status = args.status;
      audit(args.status);
      break;
    }
    case "fault": {
      const report = state.faults.find(item => item.id === args.id);
      requireRule(admin && report && ["Açık", "İncelenen", "Çözülen"].includes(args.status), "Bildirim durumu değiştirilemedi.");
      report.status = args.status;
      notify(state, report.uid, "Arıza durumu güncellendi", report.name + " · " + args.status, now);
      audit(args.status);
      break;
    }
    case "rotateJoinCode": {
      requireRule(admin, "Yönetici yetkisi gerekiyor.");
      let code;
      do {
        code = String(randomInt(100000, 1000000));
      } while (code === state.joinCode);
      state.joinCode = code;
      audit("Katılım kodu yenilendi.");
      break;
    }
    case "settings": {
      requireRule(admin, "Yönetici yetkisi gerekiyor.");
      const name = clean(args.name, 80);
      requireRule(name.length >= 3, "En az 3 karakter yurt adı gir.");
      state.name = name;
      audit("Yurt bilgileri güncellendi.");
      break;
    }
    default:
      throw new Error("Bilinmeyen işlem.");
  }
}
module.exports = { advance, change };
