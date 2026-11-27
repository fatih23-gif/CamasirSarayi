package com.camasirsarayi.app

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.camasirsarayi.app.databinding.ViewHomeBinding
import org.json.JSONObject
import java.util.Calendar

private fun studentGreeting(hour: Int): String = when (hour) {
    in 5..11 -> "GÜNAYDIN"
    in 12..17 -> "İYİ GÜNLER"
    in 18..22 -> "İYİ AKŞAMLAR"
    else -> "İYİ GECELER"
}

internal fun statusName(status: String) = when (status) {
    "AVAILABLE" -> "Boş"
    "RUNNING" -> "Çalışıyor"
    "AWAITING_UNLOAD" -> "Bekliyor"
    "OUT_OF_SERVICE" -> "Arızalı"
    "CLAIMED" -> "Ayrıldı"
    else -> "Bilinmiyor"
}
internal fun statusColor(status: String) = when (status) {
    "AVAILABLE" -> green
    "AWAITING_UNLOAD" -> amber
    "OUT_OF_SERVICE" -> pink
    else -> lightSlate
}
internal fun MainActivity.machineSummary(machine: JSONObject): String = when (machine.optString("status")) {
    "AVAILABLE" -> "Kullanıma hazır"
    "RUNNING" -> "${((machine.optLong("end") - now + 59999) / 60000).coerceAtLeast(0)} dk kaldı"
    "AWAITING_UNLOAD" -> "Boşaltılmayı bekliyor"
    "OUT_OF_SERVICE" -> "Kullanım dışı"
    "CLAIMED" -> "Sıradaki öğrenciye ayrıldı"
    else -> ""
}
internal fun MainActivity.openMachine(machine: JSONObject, page: String = if (admin) "machineForm" else "detail") {
    model.machineId = machine.optString("id")
    route(page)
}
internal fun MainActivity.machineRow(machine: JSONObject): Row {
    val status = machine.optString("status")
    return Row(machine.optString("name"), machineSummary(machine), R.drawable.ic_machine,
        statusName(status), statusColor(status)) { openMachine(machine) }
}

internal fun MainActivity.home() {
    val home = ViewHomeBinding.inflate(layoutInflater, ui.content, false)
    add(ui.content, home.root, 0)
    home.eyebrow.text = when {
        operationsStaff -> "PERSONEL PANELİ"
        admin -> "YÖNETİM PANELİ"
        else -> studentGreeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }
    val firstName = member.optString("name", "Öğrenci").substringBefore(' ')
    home.greeting.text = if (admin && !operationsStaff) "Hoş geldin, Yönetici" else "Merhaba, ${firstName}"
    home.dorm.text = state.optString("dorm")
    home.bell.isVisible = !admin
    home.bell.background = rounded(0x33FFFFFF, dp(14).toFloat(), 0x55FFFFFF)
    home.bell.setOnClickListener { route("notifications") }
    val parent = home.sections
    val statuses = listOf("AVAILABLE", "RUNNING", "AWAITING_UNLOAD", "OUT_OF_SERVICE")
    if (operationsStaff) {
        rows(listOf(
            Row("Makine Yönetimi", "Makine durumları ve düzenleme") { route("machines") },
            Row("Arıza Bildirimleri", "Yurdun arıza kayıtları", R.drawable.ic_fault) { route("faults") },
            Row("Bildirimler", "Yeni arıza bildirimleri", R.drawable.ic_bell) { route("notifications") },
        ), parent)
        return
    }
    if (!admin) {
        section("Makine Durumları", "Tümünü Gör", parent) { route("machines") }
        stats(statuses.map { status -> Triple(machines.count { it.optString("status") == status }.toString(), statusName(status), statusColor(status)) }, parent)
        if (model.offline) notice("Son görüntülenen bilgiler eski olabilir.", pink, parent)
        section("Makineler", "Tümünü Gör", parent) { route("machines") }
        rows(machines.take(3).map(::machineRow), parent, "Yurda henüz makine eklenmemiş.", compact = true)
        val count = state.items("queue").size
        val position = state.optInt("position")
        val active = mine
        if (active != null) {
            val status = active.optString("status")
            rows(listOf(Row(active.optString("name"), if (status == "CLAIMED") "Kullanım hakkın aktif" else machineSummary(active),
                R.drawable.ic_machine, statusName(status), statusColor(status)) {
                route(if (status == "CLAIMED") "queue" else "wash")
            }), parent, compact = true)
        }
        rows(listOf(Row(if (position > 0) "Sıradaki konumun: ${position}" else "Sırada ${count} öğrenci var",
            "Sıraya Git →", R.drawable.ic_queue) { route("queue") }), parent, compact = true)
        return
    }
    stats(listOf(Triple(machines.size.toString(), "Toplam", green),
        Triple(machines.count { it.optString("status") == "AVAILABLE" }.toString(), "Boş", lightSlate),
        Triple(machines.count { it.optString("status") == "RUNNING" }.toString(), "Çalışıyor", amber),
        Triple(machines.count { it.optString("status") == "AWAITING_UNLOAD" }.toString(), "Bekliyor", pink)), parent)
    section("Hızlı İşlemler", parent = parent)
    val openFaults = state.items("faults").count { it.optString("status") == "Açık" }
    rows(listOf(
        Row("Makine Yönetimi", "Makine ekle, düzenle, durum değiştir") { route("machines") },
        Row("Sıra Yönetimi", "Bekleyen ${state.items("queue").size} öğrenciyi görüntüle", R.drawable.ic_queue) { route("queue") },
        Row("Arıza Bildirimleri", "${openFaults} açık arıza bildirimi", R.drawable.ic_fault, openFaults.toString(), pink) { route("faults") },
        Row("Üye Yönetimi", "Yurda katılım ve kullanıcılar", R.drawable.ic_shield) { route("members") },
        Row("Yurt Katılım Kodu", "Kod oluştur ve paylaş", R.drawable.ic_shield) { route("joinCode") }
    ), parent, compact = true)
    section("Bugünün Özeti", "Ayrıntılar →", parent) { route("reports") }
    val today = state.items("history").filter { date(it.optLong("at")).substringBefore('·') == date(now).substringBefore('·') }
    val average = if (today.isEmpty()) "—" else "${today.map { it.optInt("minutes") }.average().toInt()} dk"
    val notices = state.items("faults").count { date(it.optLong("at")).substringBefore('·') == date(now).substringBefore('·') }
    stats(listOf(Triple(today.size.toString(), "Yıkama", getColor(R.color.card).let { if (it == Color.WHITE) it else lightSlate }),
        Triple(average, "Ort. süre", lightSlate), Triple(notices.toString(), "Bildirim", pink)), parent)
    val broken = machines.count { it.optString("status") == "OUT_OF_SERVICE" }
    center("${state.items("members").count { it.optString("status") == "ACTIVE" }} aktif üye · ${broken} arızalı makine", 12, parent = parent)
}

internal fun MainActivity.activeRoom(): String {
    return rooms.firstOrNull { it.optString("id") == model.roomId }?.optString("id")
        ?: rooms.firstOrNull()?.optString("id").orEmpty()
}
internal fun MainActivity.roomPicker(action: () -> Unit) {
    if (rooms.size < 2) return
    val room = rooms.find { it.optString("id") == activeRoom() }
    button(room?.optString("name").orEmpty(), lightSlate, navyTitle) {
        choose("Çamaşırhane seç", rooms.map { it.optString("name") }) {
            model.roomId = rooms[it].optString("id")
            action()
        }
    }
}
internal fun MainActivity.machineList() {
    if (model.offline) notice("Çevrimdışısın\nBu bilgiler eski olabilir.", pink)
    roomPicker { render() }
    val search = field("Makine ara")
    val statuses = listOf("", "AVAILABLE", "RUNNING", "AWAITING_UNLOAD", "OUT_OF_SERVICE", "CLAIMED")
    val filter = LinearLayout(this)
    add(ui.content, filter)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    add(ui.content, list)
    val candidates = machines.filter { it.optString("room") == activeRoom() }
    fun reload() {
        filter.removeAllViews()
        val selectedStatus = model.filter
        val quick = (if (admin) listOf("", "AVAILABLE", "OUT_OF_SERVICE") else listOf("", "AVAILABLE", "RUNNING"))
            .let { if (selectedStatus in it) it else it.take(2) + selectedStatus }
        for (status in quick) {
            val count = candidates.count { status.isBlank() || it.optString("status") == status }
            val label = if (status.isBlank()) "Tümü" else statusName(status)
            filter.addView(text("${label} (${count})", 12, if (status == selectedStatus) Color.WHITE else navyTitle).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER
                background = rounded(if (status == selectedStatus) navy else lightSlate, dp(18).toFloat())
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { model.filter = status; reload() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        }
        filter.addView(text("≡", 22, getColor(R.color.ink)).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            contentDescription = "Tüm durum filtreleri"
            setOnClickListener {
                choose("Duruma göre filtrele", statuses.map { if (it.isBlank()) "Tümü" else statusName(it) }) {
                    model.filter = statuses[it]
                    reload()
                }
            }
        }, LinearLayout.LayoutParams(dp(48), -2))
        list.removeAllViews()
        rows(candidates.filter { model.filter.isBlank() || it.optString("status") == model.filter }
            .filter { it.optString("name").contains(search.text.toString().trim(), true) }
            .map(::machineRow), list, "Bu filtreye uygun makine yok.")
    }
    search.doAfterTextChanged { reload() }
    reload()
    if (admin) button("+  Yeni Makine Ekle", online = true) { model.machineId = ""; route("machineForm") }
    else notice("Durumlar öğrencilerin ve yönetimin kayıtlarına dayanır.")
}

internal fun MainActivity.machineDetail() {
    val machine = selected ?: return notice("Makine artık listede bulunmuyor.")
    val status = machine.optString("status")
    icon(R.drawable.ic_machine, 102)
    center(machine.optString("name"), 28, getColor(R.color.ink))
    val room = rooms.find { it.optString("id") == machine.optString("room") }
    center(room?.optString("name").orEmpty())
    notice(statusName(status) + " · " + machineSummary(machine), statusColor(status))
    if (status == "RUNNING") {
        timerCard("Tahmini kalan süre", machine.optLong("end"), machine.optLong("end") - machine.optLong("start"))
        stats(listOf(Triple(clock(machine.optLong("start")), "Başlangıç", lightSlate),
            Triple(clock(machine.optLong("end")), "Tahmini bitiş", lightSlate)))
    }
    if (status == "AVAILABLE") {
        val waiting = state.items("queue").any { it.optString("room") == machine.optString("room") }
        notice(if (waiting) "Bekleyenler için ortak sıra uygulanır." else "Kullanım hakkı 5 dakika geçerlidir. Makineyi yerinde çalıştır.")
        if (mine == null && state.optInt("position") == 0) button(if (waiting) "Sıraya Git" else "Kullanım Hakkı Al", online = true) {
            if (waiting) route("queue")
            else request("join", mapOf("id" to machine.optString("id"))) { route("queue", true) }
        }
        if (mine != null && !machine.optBoolean("mine")) {
            notice("Başka bir makine için aktif kullanım hakkın bulunuyor. Yeni hak almak için mevcut işlemini tamamla veya iptal et.")
        }
    }
    if (machine.optBoolean("mine")) button("İşlemimi Gör") { route(if (status == "CLAIMED") "queue" else "wash") }
    button("Arıza Bildir", pink, Color.rgb(165, 33, 61), online = true) { route("report") }
}

internal fun MainActivity.studentQueue() {
    if (rooms.isEmpty()) return notice("Yurduna henüz çamaşırhane eklenmedi.")
    val active = mine
    if (active?.optString("status") == "CLAIMED") {
        notice("✓  Sıra sana geldi!\n${active.optString("name")} senin için ayrıldı.", green)
        timerCard("KALAN HAKKIN", active.optLong("expires"), 300000)
        notice("Makineyi fiziksel olarak çalıştırdıktan sonra süreyi uygulamaya gir.")
        button("Yıkamayı Başlat", online = true) { openMachine(active, "duration") }
        button("Hakkımdan Vazgeç", pink, Color.rgb(165, 33, 61), online = true) {
            confirm("Hakkından vazgeç", "Makine sıradaki öğrenciye devredilecek.") { request("leave") }
        }
        return
    }
    if (active != null) {
        notice("Aktif yıkaman var.")
        button("Yıkamamı Gör") { route("wash") }
        return
    }
    val position = state.optInt("position")
    if (position == 0) roomPicker { render() }
    icon(R.drawable.ic_queue, 45)
    center(if (position > 0) "Makine için sıra bekliyorsun" else "Bekleme sırasına katıl", 24, getColor(R.color.ink))
    val count = state.items("queue").count { it.optString("room") == activeRoom() }
    if (position > 0) {
        center(position.toString(), 54, navyTitle)
        center("Önünde ${position - 1} öğrenci var.")
        val joined = state.items("queue").firstOrNull { it.optBoolean("mine") }
        notice("Sıraya girişin: ${clock(joined?.optLong("at") ?: 0)}. Sıra sana geldiğinde 5 dakikan var.")
        button("Sıradan Ayrıl", pink, Color.rgb(165, 33, 61), online = true) {
            confirm("Sıradan ayrıl", "Tekrar katılırsan sıranın sonuna eklenirsin.") { request("leave") }
        }
    } else {
        stats(listOf(Triple(count.toString(), "Bekleyen öğrenci", lightSlate), Triple("5 dk", "Kullanım hakkı", green)))
        notice("Ortak sıra katılma zamanına göre ilerler. Uygun bir makine boşaldığında sıradaki öğrenciye atanır.")
        button("Sıraya Katıl", online = true) {
            confirm("Sıraya katıl", "Sıran geldiğinde kullanımı başlatmak için 5 dakikan olacak.") {
                request("join", mapOf("room" to activeRoom()))
            }
        }
    }
}

internal fun MainActivity.timerCard(label: String, target: Long, total: Long) {
    val container = android.widget.FrameLayout(this)
    var progress = 0f
    val arc = object : android.view.View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(13).toFloat()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val inset = dp(9).toFloat()
            val bounds = android.graphics.RectF(inset, inset, width - inset, height - inset)
            paint.color = lightSlate
            canvas.drawOval(bounds, paint)
            paint.color = navy
            canvas.drawArc(bounds, -90f, 360f * progress, false, paint)
        }
    }
    val size = dp(230)
    container.addView(arc, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
    val caption = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    val clock = text("", 36, getColor(R.color.ink)).apply { gravity = Gravity.CENTER }
    caption.addView(text(label, 11, getColor(R.color.muted)).apply { gravity = Gravity.CENTER })
    caption.addView(clock)
    container.addView(caption, android.widget.FrameLayout.LayoutParams(-1, size, Gravity.CENTER))
    add(ui.content, container, 18)
    countdown = {
        val remaining = (target - now).coerceAtLeast(0)
        clock.text = countdownText(remaining)
        progress = (remaining.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
        arc.invalidate()
    }
    countdown?.invoke()
}

internal fun MainActivity.duration() {
    val machine = selected ?: return notice("Makine bulunamadı.")
    val status = machine.optString("status")
    val extend = status == "RUNNING"
    if (status !in listOf("CLAIMED", "RUNNING")) return notice("Kullanım hakkı veya yıkama artık aktif değil.")
    if (admin || !machine.optBoolean("mine")) return notice("Bu yıkama üzerinde işlem yetkin yok.")
    heading(machine.optString("name"), if (extend) "Yeni toplam süreyi seç." else "Makinede ayarladığın süreyi seç.")
    val initial = if (extend) machine.optInt("minutes").coerceIn(0, 240) else 0
    val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
    val hours = durationWheel(controls, "Saat", 4, initial / 60)
    val minutes = durationWheel(controls, "Dakika", if (hours.value == 4) 0 else 59, initial % 60)
    add(ui.content, controls)
    val preview = text("", 14, getColor(R.color.muted))
    add(ui.content, preview)
    fun total() = hours.value * 60 + minutes.value
    fun updatePreview() {
        val start = if (extend) machine.optLong("start") else now
        preview.text = if (total() == 0) "En az 1 dakika seç."
        else "Toplam %d dakika · Tahmini bitiş %s".format(total(), clock(start + total() * 60000L))
    }
    hours.setOnValueChangedListener { _, _, value ->
        if (value == 4) minutes.value = 0
        minutes.maxValue = if (value == 4) 0 else 59
        updatePreview()
    }
    minutes.setOnValueChangedListener { _, _, _ -> updatePreview() }
    updatePreview()
    notice("Makineyi kendi panelinden çalıştır. Bu seçim uygulamadaki süre takibini günceller.")
    val action = if (extend) "Süreyi Güncelle" else "Yıkamayı Başlat"
    button(action, online = true) {
        val duration = total()
        if (duration == 0 || (extend && duration <= machine.optInt("minutes"))) {
            message(if (extend) "Öncekinden daha uzun bir süre seç." else "En az 1 dakika seç.")
            return@button
        }
        confirm(action, "Toplam %d dakika olarak kaydedilsin mi?".format(duration)) {
            request(if (extend) "extend" else "start", mapOf("id" to machine.optString("id"), "minutes" to duration)) {
                route("wash", true)
            }
        }
    }
}

private fun MainActivity.durationWheel(parent: LinearLayout, label: String, maximum: Int, initial: Int): android.widget.NumberPicker {
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    column.addView(text(label, 14))
    val picker = android.widget.NumberPicker(this).apply {
        minValue = 0
        maxValue = maximum
        value = initial
        wrapSelectorWheel = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = label
    }
    column.addView(picker)
    parent.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    return picker
}

internal fun MainActivity.activeWash() {
    val machine = mine ?: return notice("Aktif yıkaman bulunmuyor.")
    val status = machine.optString("status")
    val finished = status == "AWAITING_UNLOAD"
    icon(if (finished) R.drawable.ic_check else R.drawable.ic_machine, 86)
    center(if (finished) "Yıkama tamamlandı!" else machine.optString("name"), 28, getColor(R.color.ink))
    if (finished) {
        center("${machine.optString("name")} · ${machine.optInt("minutes")} dakika")
        notice("Boşaltılmayı bekliyor\nÇamaşırlar alınmadan makine boş görünmez.", amber)
        button("Çamaşırlarımı Aldım", Color.rgb(17, 130, 100), online = true) { unload(machine) }
        notice("Onaydan sonra makine sıradaki öğrenciye sunulur.")
    } else {
        timerCard("TAHMİNİ KALAN SÜRE", machine.optLong("end"), machine.optLong("end") - machine.optLong("start"))
        stats(listOf(Triple(clock(machine.optLong("start")), "Başlangıç", lightSlate),
            Triple(clock(machine.optLong("end")), "Tahmini bitiş", lightSlate)))
        button("Süreyi Güncelle", lightSlate, navyTitle, online = true) { openMachine(machine, "duration") }
    }
}

internal fun MainActivity.unload(machine: JSONObject) {
    confirm("Makineyi boşalt", "Çamaşırların fiziksel olarak alındığını onaylıyor musun?") {
        request("unload", mapOf("id" to machine.optString("id"))) { route("history", true) }
    }
}

internal fun MainActivity.history() {
    val records = state.items("history")
    val monthStart = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    notice("Bu ay toplam\n${records.count { it.optLong("at") >= monthStart }} yıkama")
    rows(records.map { Row(it.optString("name"), "${date(it.optLong("at"))} · ${it.optInt("minutes")} dk",
        badge = "Tamamlandı", color = green) }, empty = "Henüz tamamlanan yıkaman yok.")
}

internal fun MainActivity.reportFault() {
    heading("Bir sorun mu var?", "Yönetimin makineyi kontrol edebilmesi için bildir.")
    if (machines.isEmpty()) return notice("Bildirilecek makine yok.")
    var machine = selected ?: machines.first()
    val types = listOf("Makine çalışmıyor", "Su sızıntısı", "Ses veya titreşim", "Diğer")
    var type = types.first()
    val machineLabel = text(machine.optString("name"), 16)
    add(ui.content, machineLabel)
    button("Makine Seç", lightSlate, navyTitle) {
        choose("Makine", machines.map { it.optString("name") }) {
            machine = machines[it]
            machineLabel.text = machine.optString("name")
        }
    }
    val typeLabel = text(type, 16)
    add(ui.content, typeLabel)
    button("Sorun Türü Seç", lightSlate, navyTitle) {
        choose("Sorun türü", types) { type = types[it]; typeLabel.text = type }
    }
    val description = field("Açıklama", "Makinede ne olduğunu yaz", multiline = true)
    button("Bildirimi Gönder", online = true) {
        request("report", mapOf("id" to machine.optString("id"), "type" to type, "description" to description.text.toString())) {
            route("notifications", true)
        }
    }
}

internal fun MainActivity.notifications() {
    val filters = listOf("Tümü", "Okunmamış", "Okunmuş")
    button("Filtre: ${filters[model.inboxFilter]}", lightSlate, navyTitle) {
        choose("Bildirim filtresi", filters) { index ->
            model.inboxFilter = index
            model.inboxLimit = 20
            render()
            ui.scroll.scrollTo(0, 0)
        }
    }
    val inbox = state.items("inbox").filter { item ->
        when (model.inboxFilter) {
            1 -> !item.optBoolean("read")
            2 -> item.optBoolean("read")
            else -> true
        }
    }.sortedByDescending { it.optLong("at") }
    rows(inbox.take(model.inboxLimit).map { item ->
        Row(item.optString("title"), item.optString("body") + "\n" + date(item.optLong("at")), R.drawable.ic_bell,
            if (item.optBoolean("read")) "" else "Yeni") {
            val destination = when (item.optString("kind")) {
                "queue", "claim" -> "queue"
                "wash-complete" -> if (mine?.optString("status") == "AWAITING_UNLOAD" &&
                    mine?.optString("id") == item.optString("machineId")) "wash" else "history"
                "member-request" -> if (admin) "members" else null
                "fault-report" -> if (admin) "faults" else null
                else -> when (item.optString("title")) {
                    "Sıraya katıldın", "Sıra hakkın başladı", "Sıra hakkın bitmek üzere", "Sıra hakkın sona erdi" -> "queue"
                    "Yıkaman tamamlandı" -> if (mine?.optString("status") == "AWAITING_UNLOAD") "wash" else "history"
                    else -> null
                }
            }
            if (model.offline || item.optBoolean("read")) destination?.let { route(it) }
            else request("read", mapOf("id" to item.optString("id"))) {
                if (destination != null) route(destination) else render()
            }
        }
    }, empty = if (model.inboxFilter == 0) "Henüz bildirimin yok." else "Bu filtreye uygun bildirim yok.")
    if (inbox.size > model.inboxLimit) {
        button("Daha Fazla Göster", lightSlate, navyTitle) {
            val scrollPosition = ui.scroll.scrollY
            model.inboxLimit += 20
            render()
            ui.scroll.post { ui.scroll.scrollTo(0, scrollPosition) }
        }
    }
}
