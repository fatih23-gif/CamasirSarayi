const test = require("node:test");
const assert = require("node:assert/strict");
const { advance, change } = require("./laundry");

const minute = 60000;
const now = 1700000000000;

function fixture(status = "AVAILABLE") {
  const student = { role: "STUDENT", status: "ACTIVE" };
  return {
    members: { first: { ...student }, second: { ...student }, third: { ...student },
      pending: { ...student, status: "PENDING" }, admin: { role: "ADMIN", status: "ACTIVE" } },
    rooms: [{ id: "main", name: "Bodrum Kat" }, { id: "other", name: "İkinci Kat" }],
    machines: [{ id: "1", name: "Makine 1", room: "main", number: 1, status, uid: "" }],
    queue: [], history: [], faults: [], inbox: [], audit: [],
  };
}
function run(state, uid, action, args = {}, time = now) {
  advance(state, time);
  change(state, uid, action, args, time, "MUS123");
  advance(state, time);
}
function join(state, user, time = now) {
  run(state, user, "join", { room: "main" }, time);
}

test("son boş makine bir kişiye ayrılır, ikinci başvuru sırada kalır", () => {
  const state = fixture();
  join(state, "first");
  join(state, "second");
  assert.equal(state.machines[0].uid, "first");
  assert.equal(state.machines[0].expires, now + 5 * minute);
  assert.deepEqual(state.queue.map(item => item.uid), ["second"]);
  assert.throws(() => join(state, "first"), /Zaten/);
});

test("hak tam bitiş anında geçersiz ve FIFO ile sonraki öğrenciye geçer", () => {
  const state = fixture();
  join(state, "first");
  join(state, "second");
  join(state, "third");
  assert.throws(() => run(state, "first", "start", { id: "1", minutes: 30 }, now + 5 * minute));
  assert.equal(state.machines[0].uid, "second");
  assert.deepEqual(state.queue.map(item => item.uid), ["third"]);
});

test("100 dakika kaydedilir; süre sonunda ancak boşaltma onayıyla hak devredilir", () => {
  const state = fixture();
  join(state, "first");
  join(state, "second");
  run(state, "first", "start", { id: "1", minutes: 100 });
  assert.throws(() => run(state, "first", "start", { id: "1", minutes: 100 }));
  advance(state, now + 100 * minute);
  assert.equal(state.machines[0].status, "AWAITING_UNLOAD");
  assert.throws(() => run(state, "second", "unload", { id: "1" }, now + 101 * minute));
  run(state, "first", "unload", { id: "1" }, now + 101 * minute);
  assert.equal(state.machines[0].uid, "second");
  assert.equal(state.history[0].minutes, 100);
});

test("başka salonun boş makinesi bekleyen öğrenciye atanmaz", () => {
  const state = fixture("OUT_OF_SERVICE");
  state.machines.push({ id: "2", room: "other", status: "AVAILABLE" });
  join(state, "first");
  assert.equal(state.machines[1].status, "AVAILABLE");
  assert.equal(state.queue.length, 1);
});

test("bekleyen üyelik ve öğrenci tarafından yönetim işlemi reddedilir", () => {
  const state = fixture();
  assert.throws(() => join(state, "pending"), /Aktif/);
  assert.throws(() => run(state, "first", "machine", { name: "Makine 2" }), /yetkisi/);
  assert.throws(() => run(state, "first", "member", { uid: "pending", status: "ACTIVE" }));
});

test("katılım kodu sadece bekleyen öğrenci başvurusu oluşturur", () => {
  const state = fixture();
  assert.throws(() => run(state, "new", "enroll", { code: "WRONG1", name: "Yeni Öğrenci" }));
  run(state, "new", "enroll", { code: "MUS123", name: "Yeni Öğrenci", role: "ADMIN" });
  assert.equal(state.members.new.role, "STUDENT");
  assert.equal(state.members.new.status, "PENDING");
  run(state, "admin", "member", { uid: "new", status: "ACTIVE" });
  assert.equal(state.members.new.status, "ACTIVE");
});

test("aktif yıkama yanlışlıkla boş yapılamaz, uzatma kaydı korunur", () => {
  const state = fixture();
  join(state, "first");
  run(state, "first", "start", { id: "1", minutes: 30 });
  assert.throws(() => run(state, "admin", "machine", { id: "1", status: "AVAILABLE", name: "Makine 1", room: "main", number: 1 }));
  assert.throws(() => run(state, "first", "extend", { id: "1", minutes: 25 }));
  assert.throws(() => run(state, "admin", "extend", { id: "1", minutes: 45 }), /işlem hakkın yok/);
  run(state, "first", "extend", { id: "1", minutes: 45 });
  assert.throws(() => run(state, "admin", "unload", { id: "1" }, now + 45 * minute), /kapatamazsın/);
  run(state, "first", "unload", { id: "1" }, now + 45 * minute);
  assert.equal(state.history[0].updates[0].previous, 30);
});

test("yönetici sıraya, kullanım hakkına ve aktif üyeliğe müdahale edemez", () => {
  const state = fixture();
  join(state, "first");
  join(state, "second");
  assert.throws(() => run(state, "admin", "remove", { uid: "first", reason: "Eski istemci" }));
  assert.throws(() => run(state, "admin", "leave"));
  assert.throws(() => run(state, "admin", "member", { uid: "second", status: "SUSPENDED" }));
  assert.throws(() => run(state, "admin", "machine", {
    id: "1", status: "OUT_OF_SERVICE", name: "Makine 1", roomName: "Bodrum Kat", number: 1,
  }));
  assert.equal(state.machines[0].uid, "first");
  assert.deepEqual(state.queue.map(item => item.uid), ["second"]);
});

test("elle yazılan çamaşırhane oluşturulur ve aynı ad yeniden kullanılır", () => {
  const state = fixture();
  const machine = { name: "Makine", number: 1, status: "AVAILABLE" };
  run(state, "admin", "machine", { ...machine, roomName: "  Arka   Bina " });
  const room = state.rooms.find(item => item.name === "Arka Bina");
  assert.ok(room);
  assert.equal(state.machines[1].room, room.id);
  assert.throws(() => run(state, "admin", "machine", { ...machine, roomName: "ARKA BİNA" }), /zaten kullanılıyor/);
  run(state, "admin", "machine", { ...machine, number: 2, roomName: "ARKA BİNA" });
  assert.equal(state.machines[2].room, room.id);
  assert.equal(state.rooms.length, 3);
});

test("yönetici bekleyen öğrencinin salonundaki son makineyi taşıyamaz", () => {
  const state = fixture("OUT_OF_SERVICE");
  join(state, "first");
  assert.throws(() => run(state, "admin", "machine", {
    id: "1", name: "Makine 1", number: 1, status: "OUT_OF_SERVICE", roomName: "Yeni Kat",
  }), /son makinesi/);
  assert.equal(state.machines[0].room, "main");
  assert.equal(state.rooms.length, 2);
});

test("aynı öğrenci bir dakika içinde ikinci arıza bildirimi oluşturamaz", () => {
  const state = fixture();
  const report = { id: "1", description: "Makine çalışmıyor." };
  run(state, "first", "report", report);
  assert.throws(() => run(state, "first", "report", report, now + 59000), /1 dakika/);
  run(state, "first", "report", report, now + minute);
  assert.equal(state.faults.length, 2);
});

test("boş makine detayından istenen makine ayrılabilir", () => {
  const state = fixture();
  state.machines.push({ id: "2", number: 2, room: "main", status: "AVAILABLE", name: "Makine 2" });
  run(state, "first", "join", { id: "2" });
  assert.equal(state.machines[0].status, "AVAILABLE");
  assert.equal(state.machines[1].uid, "first");
  assert.ok(state.machines[1].claimId);
  advance(state, now + 4 * minute);
  advance(state, now + 4 * minute + 1000);
  assert.equal(state.inbox.filter(item => item.title === "Sıra hakkın bitmek üzere").length, 1);
});
