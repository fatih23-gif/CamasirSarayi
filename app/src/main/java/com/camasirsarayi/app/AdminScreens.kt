package com.camasirsarayi.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.graphics.Color
import android.widget.LinearLayout

internal fun MainActivity.machineForm() {
    val machine = selected
    heading(machine?.optString("name") ?: "Yeni makine", "Yurdunun çamaşırhanesindeki makineyi tanımla.")
    val name = field("Makine adı", "Makine 7", machine?.optString("name").orEmpty())
    val number = field("Makine numarası", "7", machine?.optInt("number")?.toString().orEmpty(), number = true)
    val roomId = machine?.optString("room")
    val roomName = rooms.find { it.optString("id") == roomId }?.optString("name").orEmpty()
    val room = field("Çamaşırhane adı", "Örneğin: Bodrum Kat", roomName)
    val previous = machine?.optString("status") ?: "AVAILABLE"
    var status = previous
    if (previous in listOf("CLAIMED", "RUNNING", "AWAITING_UNLOAD")) {
        room.isEnabled = false
        notice("Makine durumu: " + statusName(previous))
    } else {
        val options = listOf("AVAILABLE", "OUT_OF_SERVICE")
        val statusLabel = text(statusName(status), 15)
        add(ui.content, statusLabel)
        button("Makine Durumu Seç", lightSlate, navyTitle) {
            choose("Makine durumu", options.map(::statusName)) { status = options[it]; statusLabel.text = statusName(status) }
        }
    }
    button(if (machine == null) "Makineyi Kaydet" else "Değişiklikleri Kaydet", online = true) {
        val value = number.text.toString().toIntOrNull()
        if (value == null || value !in 1..9999) { number.error = "Geçerli bir numara gir."; return@button }
        val location = room.text.toString().trim()
        if (location.length !in 1..80) { room.error = "1–80 karakterlik çamaşırhane adı gir."; return@button }
        request("machine", mapOf("id" to model.machineId, "name" to name.text.toString(),
            "number" to value, "roomName" to location, "status" to status)) { saved ->
            val savedMachine = saved.items("machines").firstOrNull { it.optString("id") == model.machineId }
                ?: saved.items("machines").lastOrNull()
            model.roomId = savedMachine?.optString("room").orEmpty()
            route("machines", true)
        }
    }
}

internal fun MainActivity.adminQueue() {
    roomPicker { render() }
    val entries = state.items("queue").filter { it.optString("room") == activeRoom() }
    val claims = machines.filter { it.optString("room") == activeRoom() && it.optString("status") == "CLAIMED" }
    val names = state.items("members").associate { it.optString("id") to it.optString("name") }
    notice("${entries.size} öğrenci\nAktif bekleme sırası")
    section("Bekleyen öğrenciler")
    rows(entries.mapIndexed { index, item ->
        Row("${index + 1}. ${item.optString("name")}", "Katılım ${clock(item.optLong("at"))}", R.drawable.ic_profile)
    }, empty = "Sırada bekleyen öğrenci yok.")
    section("Aktif haklar")
    rows(claims.map { machine ->
        val owner = machine.optString("owner")
        Row(machine.optString("name"), (names[owner] ?: "Öğrenci") + " · " + clock(machine.optLong("expires")),
            R.drawable.ic_queue, "Ayrıldı")
    }, empty = "Aktif kullanım hakkı yok.")
}

internal fun MainActivity.faultList() {
    val statuses = listOf("Açık", "İncelenen", "Çözülen")
    val selected = model.filter.ifBlank { statuses.first() }
    val reports = state.items("faults")
    button("${selected} (${reports.count { it.optString("status") == selected }})", lightSlate, navyTitle) {
        choose("Bildirim durumu", statuses) { model.filter = statuses[it]; render() }
    }
    rows(reports.filter { it.optString("status") == selected }.map { item ->
        Row(item.optString("name"), "${item.optString("description")}\n${item.optString("reporter")} · ${date(item.optLong("at"))}",
            R.drawable.ic_fault, selected, pink) {
            if (model.offline) message("İşlem için internet bağlantısı gerekiyor.")
            else choose("İşlem Yap / Durumu Güncelle", statuses) {
                request("fault", mapOf("id" to item.optString("id"), "status" to statuses[it]))
            }
        }
    }, empty = "Bu durumda arıza bildirimi yok.")
    notice("Makine yönetiminden arızalı bir makineyi kullanım dışı bırakabilirsin.")
}

internal fun MainActivity.members() {
    val members = state.items("members")
    stats(listOf(Triple(members.count { it.optString("status") == "ACTIVE" }.toString(), "Aktif üye", green),
        Triple(members.count { it.optString("status") == "PENDING" }.toString(), "Onay bekliyor", lightSlate)))
    val pending = members.filter { it.optString("status") == "PENDING" }
    section("Onay bekleyenler")
    rows(pending.map { item ->
        Row(item.optString("name"), "Yeni üyelik talebi", R.drawable.ic_profile, "İncele", amber) {
            if (model.offline) message("İşlem için internet bağlantısı gerekiyor.")
            else choose("Üyelik başvurusu", listOf("Onayla", "Reddet")) {
                request("member", mapOf("uid" to item.optString("id"), "status" to if (it == 0) "ACTIVE" else "REJECTED"))
            }
        }
    }, empty = "Onay bekleyen üye yok.")
    section("Üyeler")
    rows(members.filterNot { it in pending }.map { item ->
        val status = item.optString("status")
        val role = item.optString("role")
        Row(item.optString("name"), if (role == "ADMIN") "Yetkili yönetici" else "Öğrenci", R.drawable.ic_profile,
            when (status) { "ACTIVE" -> "Aktif"; "SUSPENDED" -> "Askıda"; else -> "Reddedildi" },
            if (status == "ACTIVE") green else pink, click = if (role == "ADMIN") null else ({
                if (model.offline) message("İşlem için internet bağlantısı gerekiyor.")
                else choose("Üyeliği güncelle", listOf("Etkinleştir", "Askıya Al")) {
                    request("member", mapOf("uid" to item.optString("id"), "status" to if (it == 0) "ACTIVE" else "SUSPENDED"))
                }
            }))
    }, empty = "Henüz üye yok.")
}

internal fun MainActivity.dormSettings() {
    icon(R.drawable.ic_building, 65)
    center(state.optString("dorm"), 22, getColor(R.color.ink))
    val name = field("Yurt adı", value = state.optString("dorm"))
    button("Yurt Bilgilerini Kaydet", online = true) { request("settings", mapOf("name" to name.text.toString())) }
    section("Çamaşırhaneler")
    rows(rooms.map { room -> Row(room.optString("name"), "${machines.count { it.optString("room") == room.optString("id") }} makine") })
    section("Yönetici Yetkileri")
    rows(state.items("members").filter { it.optString("role") == "ADMIN" }.map { Row(it.optString("name"), "Yetkili yönetici", R.drawable.ic_shield) })
    button("Yurt Katılım Kodu", lightSlate, navyTitle) { route("joinCode") }
}

internal fun MainActivity.manageJoinCode() {
    if (!admin) return notice("Bu ekrana yalnızca yurt yöneticileri erişebilir.")
    heading(state.optString("dorm"), "Öğrencilerin başvuru sırasında gireceği kodu buradan oluştur.")
    val code = state.optString("joinCode")
    if (code.isEmpty()) {
        notice("Bu yurt için henüz katılım kodu oluşturulmadı.")
        button("Katılım Kodu Oluştur", online = true) { request("rotateJoinCode") }
        return
    }
    notice("Aktif katılım kodu")
    center(code, 36, getColor(R.color.ink))
    button("Kodu Kopyala", lightSlate, navyTitle) {
        val clipboard = getSystemService(ClipboardManager::class.java)
            ?: return@button message("Pano şu anda kullanılamıyor.")
        val clip = ClipData.newPlainText("Yurt katılım kodu", code)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        message("Katılım kodu kopyalandı.")
    }
    button("Yeni Kod Oluştur", online = true) {
        confirm("Katılım kodunu değiştir", "Eski kodla yeni başvuru yapılamayacak. Mevcut üyelikler geçerli kalacak.") {
            request("rotateJoinCode")
        }
    }
}

internal fun MainActivity.reports() {
    val ranges = linkedMapOf("Haftalık" to 7, "Aylık" to 30, "Yıllık" to 365)
    val selected = model.filter.ifBlank { "Haftalık" }
    val cutoff = now - ranges.getValue(selected) * 86400000L
    val history = state.items("history").filter { it.optLong("at") >= cutoff }
    val faults = state.items("faults").count { it.optLong("at") >= cutoff }
    button(selected, lightSlate, navyTitle) {
        choose("Rapor aralığı", ranges.keys.toList()) { model.filter = ranges.keys.toList()[it]; render() }
    }
    val average = if (history.isEmpty()) "—" else "${history.map { it.optInt("minutes") }.average().toInt()} dk"
    stats(listOf(Triple(history.size.toString(), "Toplam yıkama", green), Triple(average, "Ort. süre", lightSlate),
        Triple(faults.toString(), "Arıza kaydı", pink)))
    section("Son 7 günün kullanımı")
    val graph = LinearLayout(this).apply { gravity = android.view.Gravity.BOTTOM; setPadding(0, dp(8), 0, dp(3)) }
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val counts = (6 downTo 0).map { offset ->
        val day = (calendar.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -offset) }.timeInMillis
        date(day).substringBefore('·') to history.count { date(it.optLong("at")).substringBefore('·') == date(day).substringBefore('·') }
    }
    val maximum = (counts.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    for ((day, count) in counts) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
        column.addView(android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(navy)
                cornerRadius = dp(8).toFloat()
            }
            contentDescription = "${day}: ${count} yıkama"
        }, LinearLayout.LayoutParams(-1, dp(4 + 105 * count / maximum)).apply { marginStart = dp(4); marginEnd = dp(4) })
        column.addView(text(day.trim().substringBefore(' '), 11, getColor(R.color.muted)))
        graph.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    }
    add(ui.content, graph, 20)
    section("Makine kullanımı")
    rows(history.groupingBy { it.optString("name") }.eachCount().entries.sortedByDescending { it.value }.map {
        Row(it.key, "${it.value} tamamlanan yıkama")
    }, empty = "Bu aralıkta tamamlanan yıkama yok.")
}
