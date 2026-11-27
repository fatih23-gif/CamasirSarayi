package com.camasirsarayi.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.SecureRandom

internal class DemoLaundry(private val preferences: SharedPreferences) {
    private var state = preferences.getString("demo_state", null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.optJSONObject("member") != null && it.optJSONArray("machines") != null }
        ?: initialState()
    private val member: JSONObject get() = state.getJSONObject("member")

    private fun initialState(): JSONObject {
        val now = System.currentTimeMillis()
        val statuses = listOf("AVAILABLE", "RUNNING", "AWAITING_UNLOAD", "AVAILABLE", "RUNNING", "OUT_OF_SERVICE")
        val machines = statuses.mapIndexed { index, status ->
            json("id" to (index + 1).toString(), "name" to "Makine ${index + 1}", "number" to index + 1,
                "room" to "main", "status" to status, "mine" to false, "minutes" to 90,
                "start" to now - 48 * 60000, "end" to now + (if (index == 4) 85 else 42) * 60000)
        }
        return json("dorm" to "Muş Öğrenci Yurdu", "member" to json("name" to "Ayşe Yılmaz", "role" to "STUDENT", "status" to "ACTIVE"),
            "rooms" to JSONArray(listOf(json("id" to "main", "name" to "Bodrum Kat • Ana Çamaşırhane"))),
            "machines" to JSONArray(machines), "queue" to JSONArray(), "position" to 0,
            "history" to JSONArray(), "faults" to JSONArray(), "members" to JSONArray(), "inbox" to JSONArray(), "audit" to JSONArray())
    }

    fun call(action: String, args: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val admin = member.optString("role") == "ADMIN"
        val machines = state.items("machines")
        val machine = machines.firstOrNull { it.optString("id") == args.optString("id") }
        val mine = machines.firstOrNull { it.optBoolean("mine") }
        val queue = state.items("queue").toMutableList()
        for (item in machines) {
            val status = item.optString("status")
            if (status == "CLAIMED" && item.optLong("expires") <= now) release(item)
            if (status == "RUNNING" && item.optLong("end") <= now) {
                item.put("status", "AWAITING_UNLOAD")
                if (item.optBoolean("mine")) notify("Yıkaman tamamlandı", item.optString("name"), now)
            }
        }
        when (action) {
            "catalog" -> return json("dorms" to JSONArray(listOf(json("id" to "mus", "name" to state.optString("dorm"), "city" to "Muş", "district" to "Merkez"))))
            "demo" -> {
                val role = args.optString("role")
                member.put("role", role).put("name", if (role == "ADMIN") "Yurt Yöneticisi" else "Ayşe Yılmaz").put("status", "ACTIVE")
            }
            "enroll" -> {
                val code = state.optString("joinCode")
                require(code.isNotEmpty() && args.optString("code") == code) { "Katılım kodu geçersiz." }
                member.put("name", args.optString("name")).put("role", "STUDENT").put("status", "PENDING")
                val members = state.items("members").filterNot { it.optString("id") == "demo-student" }
                state.put("members", JSONArray(members + json("id" to "demo-student", "name" to args.optString("name"), "status" to "PENDING", "role" to "STUDENT")))
            }
            "student" -> state.items("members").firstOrNull { it.optString("id") == "demo-student" }?.let {
                member.put("name", it.optString("name")).put("status", it.optString("status")).put("role", "STUDENT")
            }
            "join" -> {
                require(!admin) { "Sıraya yalnızca öğrenciler katılabilir." }
                require(mine == null && queue.none { it.optBoolean("mine") }) { "Zaten sıran, hakkın veya yıkaman var." }
                queue.add(json("mine" to true, "uid" to "demo-student", "name" to member.optString("name"), "at" to now,
                    "room" to (machine?.optString("room") ?: args.optString("room", "main"))))
                notify("Sıraya katıldın", "Ortak çamaşırhane sırasına eklendin.", now)
            }
            "leave" -> {
                require(!admin) { "Yönetici öğrenci sırasını değiştiremez." }
                queue.removeAll { it.optBoolean("mine") }
                if (mine?.optString("status") == "CLAIMED") release(mine)
            }
            "start", "extend" -> {
                val required = if (action == "start") "CLAIMED" else "RUNNING"
                require(machine != null && machine.optString("status") == required &&
                    !admin && machine.optBoolean("mine")) { "Bu işlem için geçerli hakkın yok." }
                val minutes = args.optInt("minutes")
                require(minutes in 1..240) { "1–240 arasında dakika gir." }
                if (action == "start") machine.put("start", now)
                val end = machine.optLong("start") + minutes * 60000L
                require(action == "start" || end > machine.optLong("end")) { "Yeni toplam süre daha uzun olmalı." }
                machine.put("status", "RUNNING").put("minutes", minutes).put("end", end)
            }
            "unload" -> {
                require(machine?.optString("status") == "AWAITING_UNLOAD" &&
                    !admin && machine.optBoolean("mine")) { "Makine boşaltılmayı beklemiyor." }
                state.put("history", JSONArray(listOf(json("name" to machine.optString("name"), "minutes" to machine.optInt("minutes"), "at" to now)) + state.items("history")))
                release(machine)
            }
            "report" -> {
                val description = args.optString("description").trim()
                require(machine != null && description.length >= 5) { "Makine seç ve en az 5 karakter açıklama yaz." }
                state.put("faults", JSONArray(listOf(json("id" to UUID.randomUUID().toString(), "name" to machine.optString("name"),
                    "description" to description, "type" to args.optString("type"), "reporter" to member.optString("name"), "status" to "Açık", "at" to now)) + state.items("faults")))
                notify("Arıza bildirimi alındı", machine.optString("name"), now)
            }
            "read" -> state.items("inbox").find { it.optString("id") == args.optString("id") }?.put("read", true)
            "fault" -> state.items("faults").find { it.optString("id") == args.optString("id") }?.put("status", args.optString("status"))
            "member" -> state.items("members").find { it.optString("id") == args.optString("uid") }?.put("status", args.optString("status"))
            "machine" -> {
                require(admin) { "Yönetici yetkisi gerekiyor." }
                val number = args.optInt("number")
                val name = args.optString("name").trim()
                val status = args.optString("status")
                require(number in 1..9999 && name.isNotEmpty()) { "Makine adı ve numarası gerekli." }
                val roomName = args.optString("roomName").trim().replace(Regex("\\s+"), " ")
                require(roomName.length in 1..80) { "Geçerli bir çamaşırhane adı gir." }
                val rooms = state.items("rooms")
                val locale = java.util.Locale.forLanguageTag("tr-TR")
                fun key(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).lowercase(locale)
                val existingRoom = rooms.firstOrNull { key(it.optString("name")) == key(roomName) }
                val room = existingRoom ?: json("id" to UUID.randomUUID().toString(), "name" to roomName)
                val roomId = room.optString("id")
                require(machines.none { it !== machine && it.optString("room") == roomId && it.optInt("number") == number }) {
                    "Makine numarası bu çamaşırhanede zaten kullanılıyor."
                }
                val previous = machine?.optString("status")
                if (machine != null && previous in listOf("CLAIMED", "RUNNING", "AWAITING_UNLOAD")) {
                    require(status == previous && roomId == machine.optString("room")) { "Aktif kullanım sırasında durum ve salon değiştirilemez." }
                } else {
                    require(status in listOf("AVAILABLE", "OUT_OF_SERVICE")) { "Geçersiz makine durumu." }
                }
                val oldRoom = machine?.optString("room")
                require(roomId == oldRoom || queue.none { it.optString("room") == oldRoom } ||
                    machines.any { it !== machine && it.optString("room") == oldRoom }) { "Sıra bekleyen salonun son makinesi taşınamaz." }
                if (existingRoom == null) state.put("rooms", JSONArray(rooms + room))
                val updated = machine ?: json("id" to UUID.randomUUID().toString(), "mine" to false)
                updated.put("number", number).put("name", name).put("room", roomId).put("status", status)
                if (machine == null) state.put("machines", JSONArray(machines + updated))
            }
            "rotateJoinCode" -> {
                require(admin) { "Yönetici yetkisi gerekiyor." }
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                val random = SecureRandom()
                var code: String
                do {
                    code = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
                } while (code == state.optString("joinCode"))
                state.put("joinCode", code)
            }
            "settings" -> {
                require(args.optString("name").trim().length >= 3) { "En az 3 karakter yurt adı gir." }
                state.put("dorm", args.optString("name").trim())
            }
            "deleteAccount" -> {
                state = initialState()
                preferences.edit().remove("demo_state").apply()
                return JSONObject()
            }
        }
        for (item in state.items("machines")) {
            if (item.optString("status") != "AVAILABLE") continue
            val first = queue.firstOrNull { it.optString("room") == item.optString("room") } ?: continue
            queue.remove(first)
            item.put("status", "CLAIMED").put("mine", first.optBoolean("mine")).put("owner", first.optString("uid")).put("expires", now + 300000)
            notify("Sıra hakkın başladı", item.optString("name"), now)
        }
        state.put("queue", JSONArray(queue)).put("position", queue.indexOfFirst { it.optBoolean("mine") } + 1).put("now", now)
        preferences.edit().putString("demo_state", state.toString()).apply()
        return JSONObject(state.toString()).apply {
            if (member.optString("role") != "ADMIN") remove("joinCode")
            if (member.optString("role") == "ADMIN") {
                items("machines").forEach { it.put("mine", false) }
                put("position", 0)
            }
        }
    }

    private fun release(machine: JSONObject) {
        machine.put("status", "AVAILABLE").put("mine", false).put("owner", "").put("expires", 0).put("end", 0).put("start", 0)
    }

    private fun notify(title: String, body: String, now: Long) {
        state.put("inbox", JSONArray(listOf(json("id" to UUID.randomUUID().toString(), "title" to title, "body" to body, "at" to now, "read" to false)) + state.items("inbox")))
    }
}
