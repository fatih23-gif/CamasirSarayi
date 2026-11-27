package com.camasirsarayi.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import java.util.Locale

internal fun MainActivity.screenTitle(page: String): String = when (page) {
    "login" -> "Öğrenci Girişi"
    "adminLogin" -> "Yönetici / Personel Girişi"
    "register" -> "Öğrenci Kaydı"
    "adminRegister" -> "Personel Başvurusu"
    "adminAccess" -> "Personel Erişimi"
    "staffStatus" -> "Başvuru Durumu"
    "verifyEmail" -> if (repo.kykDraftDorm.isNotBlank()) "Kurumsal E-postanı Doğrula" else "E-postanı Doğrula"
    "kykRegister" -> "Yurt Yönetici Kaydı"
    "kykInvitations" -> "Personel Davetleri"
    "kykInviteAccept" -> "Personel Daveti"
    "staffReviews" -> "Personel Başvuruları"
    "dorms" -> "Yurdunu Seç"
    "enroll" -> "Yurt Katılım Kodu"
    "pending" -> "Üyelik Durumu"
    "machines" -> if (admin) "Makine Yönetimi" else "Makineler"
    "detail" -> "Makine Detayı"
    "queue" -> if (admin) "Sıra Yönetimi" else if (mine?.optString("status") == "CLAIMED") "Sıra Hakkım" else "Dijital Sıra"
    "wash" -> if (mine?.optString("status") == "AWAITING_UNLOAD") "Yıkama Tamamlandı" else "Aktif Yıkamam"
    "duration" -> if (selected?.optString("status") == "RUNNING") "Süreyi Güncelle" else "Yıkama Başlat"
    "history" -> "Geçmiş"
    "report" -> "Arıza Bildir"
    "notifications" -> "Bildirimler"
    "profile" -> if (admin) "Yönetici Profili" else "Profil"
    "machineForm" -> if (selected == null) "Makine Ekle" else "Makine Düzenle"
    "faults" -> "Arıza Bildirimleri"
    "members" -> "Yurt Üyelikleri"
    "settings" -> "Yurt Ayarları"
    "joinCode" -> "Yurt Katılım Kodu"
    "reports" -> "Raporlar"
    else -> ""
}

internal fun MainActivity.welcome() {
    ui.content.setPadding(dp(24), dp(24), dp(24), dp(24))
    center("Çamaşır Sarayı", 34, getColor(R.color.ink))
    center("Yurt Çamaşırhane Yönetim Sistemi", 16)
    add(ui.content, text("Nasıl devam etmek istiyorsunuz?", 20, getColor(R.color.ink)).apply {
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
    }, 20)
    welcomeChoice("Öğrenci İşlemleri", "Öğrenci girişi ve kaydı", R.drawable.ic_profile, "login")
    welcomeChoice("Yönetici / Personel İşlemleri", "Yurt yönetimi ve personel işlemleri", R.drawable.ic_shield, "adminLogin")
}

private fun MainActivity.welcomeChoice(title: String, description: String, icon: Int, destination: String) {
    val card = com.camasirsarayi.app.databinding.ItemRowBinding.inflate(layoutInflater, ui.content, false)
    card.root.radius = dp(24).toFloat()
    card.root.cardElevation = dp(2).toFloat()
    card.root.getChildAt(0).minimumHeight = dp(116)
    card.name.text = title
    card.name.textSize = 17f
    card.name.setTypeface(card.name.typeface, Typeface.BOLD)
    card.detail.text = description
    card.detail.textSize = 13f
    card.icon.setImageResource(icon)
    card.icon.setColorFilter(getColor(R.color.nav_active))
    card.icon.background = rounded(lightSlate, dp(13).toFloat())
    card.badge.visibility = android.view.View.GONE
    card.root.isFocusable = true
    card.root.setOnClickListener { route(destination) }
    add(ui.content, card.root, 14)
}

private fun MainActivity.accountHeader(title: String, description: String, artwork: Int? = null) {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(6))
        setBackgroundResource(R.drawable.auth_header)
    }
    if (artwork != null) icon(artwork, 30, card, getColor(R.color.nav_active))
    center(title, 22, getColor(R.color.ink), card)
    center(description, 14, getColor(R.color.muted), card)
    add(ui.content, card, 16)
}

internal fun MainActivity.accountLink(label: String, onClick: () -> Unit) {
    add(ui.content, text(label, 14, getColor(R.color.nav_active)).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }, 0)
}

internal fun MainActivity.login(adminLogin: Boolean) {
    accountHeader(if (adminLogin) "Personel Hesabı" else "Öğrenci Hesabı", "Hesabına giriş yap.")
    val email = field("E-posta", if (adminLogin) "Kurumsal e-posta adresin" else "E-posta adresin", email = true)
    val password = field("Şifre", password = true)
    accountLink("Şifremi unuttum") {
        val auth = repo.auth
        if (auth == null) message("Şifre sıfırlama için Firebase bağlantısı gerekiyor.")
        else if (!Patterns.EMAIL_ADDRESS.matcher(email.text.toString().trim()).matches()) email.error = "Geçerli bir e-posta yaz."
        else {
            auth.setLanguageCode("tr")
            auth.sendPasswordResetEmail(email.text.toString().trim()).addOnCompleteListener {
            message(if (it.isSuccessful) "Sıfırlama bağlantısı gönderildi." else "Bağlantı gönderilemedi.")
            }
        }
    }
    button("Giriş Yap") {
        if (model.busy) return@button
        val address = email.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) return@button email.formError("Geçerli bir e-posta adresi yaz.")
        if (password.text.isNullOrBlank()) return@button password.formError("Şifreni yaz.")
        val auth = repo.auth
        if (auth == null) { message("Canlı giriş için Firebase kurulumu gerekiyor."); return@button }
        model.adminSignIn = adminLogin
        model.busy = true
        auth.signInWithEmailAndPassword(address, password.text.toString()).addOnCompleteListener {
            model.busy = false
            if (isDestroyed) return@addOnCompleteListener
            if (it.isSuccessful) resumeAccount(adminLogin)
            else message(if (it.exception is FirebaseNetworkException) "İnternet bağlantısını kontrol et."
                else "Giriş yapılamadı. E-posta ve şifreni kontrol et.")
        }
    }
    if (adminLogin) {
        accountLink("Yurt Yönetici Kaydı") { route("kykRegister") }
        accountLink("Personel Davetini Kullan") { route("kykInviteAccept") }
        notice("Bu bölüm yurt yönetimi ve yetkili personel içindir.")
    } else accountLink("Hesabın yok mu? Kayıt Ol") { route("register") }
}

internal fun MainActivity.register(adminRegistration: Boolean) {
    if (adminRegistration) {
        accountHeader("Yetkili Hesabı Oluştur", "Görev doğrulaması için başvuru yap.", R.drawable.ic_shield)
    } else accountHeader("Öğrenci Hesabı Oluştur", "Yurt çamaşırhanesine katılmak için hesap aç.")
    val existingUser = repo.auth?.currentUser?.takeIf { adminRegistration }
    val name = field("Ad Soyad", "Adını ve soyadını yaz")
    name.setText(existingUser?.displayName.orEmpty())
    val email = if (existingUser == null) field("E-posta", "E-posta adresi", email = true) else null
    var staffDorm = ""
    if (adminRegistration) {
        val choice = text("Yurt seç", 16, getColor(R.color.nav_active))
        choice.minHeight = dp(48)
        choice.gravity = Gravity.CENTER_VERTICAL
        section("Görev Yapılan Yurt")
        add(ui.content, choice)
        choice.setOnClickListener {
            if (model.busy) return@setOnClickListener
            model.busy = true
            repo.staffDorms { result ->
                model.busy = false
                if (isDestroyed || model.page != "adminRegister") return@staffDorms
                result.onSuccess { catalog ->
                    val dorms = catalog.items("dorms").sortedBy { it.optString("name") }
                    if (dorms.isEmpty()) message("Kayıtlı yurt bulunamadı.")
                    else choose("Görev Yapılan Yurt", dorms.map { "${it.optString("name")} · ${it.optString("city")}" }) { index ->
                        staffDorm = dorms[index].optString("id")
                        choice.text = dorms[index].optString("name")
                    }
                }.onFailure { message("Yurt listesi yüklenemedi. Bağlantını kontrol edip tekrar dene.") }
            }
        }
    }
    val password = if (existingUser == null) field("Şifre", "En az 8 karakter", password = true) else null
    val repeated = if (existingUser == null) field("Şifre tekrar", password = true) else null
    button(if (adminRegistration) "Başvuru Yap" else "Kayıt Ol") {
        if (model.busy) return@button
        model.name = name.text.toString().trim()
        model.email = email?.text?.toString()?.trim() ?: existingUser?.email.orEmpty()
        val secret = password?.text?.toString().orEmpty()
        if (model.name.length !in 3..120) return@button name.formError("Ad ve soyad 3–120 karakter olmalı.")
        if (email != null && !Patterns.EMAIL_ADDRESS.matcher(model.email).matches()) return@button email.formError("Geçerli bir e-posta adresi yaz.")
        if (adminRegistration && staffDorm.isBlank()) return@button message("Görev yaptığın yurdu seç.")
        if (password != null && secret.length < 8) return@button password.formError("Şifre en az 8 karakter olmalı.")
        if (repeated != null && secret != repeated.text.toString()) return@button repeated.formError("Şifreler eşleşmiyor.")
        val auth = repo.auth
        if (auth == null) { message("Gerçek hesap oluşturmak için Firebase kurulumu gerekiyor."); return@button }
        model.adminSignIn = adminRegistration
        if (existingUser != null) {
            if (auth.currentUser?.uid != existingUser.uid) return@button message("Oturumunu yeniden aç.")
            repo.staffDraftName = model.name
            repo.staffDraftDorm = staffDorm
            repo.staffSession = true
            if (existingUser.isEmailVerified) submitStaffApplication()
            else showEmailVerification(send = true)
            return@button
        }
        model.busy = true
        auth.createUserWithEmailAndPassword(model.email, secret).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                model.busy = false
                message(when (task.exception) {
                    is FirebaseAuthUserCollisionException -> "Bu e-posta zaten kayıtlı. Giriş yapabilirsin."
                    is FirebaseNetworkException -> "İnternet bağlantısını kontrol et."
                    else -> "Hesap oluşturulamadı. Bilgilerini kontrol et."
                })
                return@addOnCompleteListener
            }
            val user = task.result.user ?: run { model.busy = false; return@addOnCompleteListener }
            repo.staffSession = adminRegistration
            if (adminRegistration) {
                repo.staffDraftName = model.name
                repo.staffDraftDorm = staffDorm
            }
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(model.name).build()).addOnCompleteListener {
                model.busy = false
                if (!isDestroyed) {
                    showEmailVerification(send = true)
                }
            }
        }
    }
    if (existingUser == null) accountLink("Zaten hesabın var mı? Giriş yap") { route(if (adminRegistration) "adminLogin" else "login", true) }
    else accountLink("Çıkış Yap") { signOut() }
}

private fun MainActivity.submitStaffApplication() {
    request("staffApply", mapOf("name" to repo.staffDraftName.ifBlank { model.name }, "dormId" to repo.staffDraftDorm)) {
        repo.staffDraftDorm = ""
        repo.staffDraftName = ""
        model.staffStatus = it.optString("staffStatus")
        route("staffStatus", true)
    }
}

internal fun MainActivity.showEmailVerification(send: Boolean = false) {
    route("verifyEmail", true)
    if (send) sendVerification()
}

private fun MainActivity.sendVerification(name: String = model.name) {
    if (repo.kykDraftDorm.isNotBlank()) prepareKyk(name, ::dispatchVerification)
    else dispatchVerification()
}

private fun MainActivity.dispatchVerification() {
    val auth = repo.auth ?: return
    val user = auth.currentUser ?: return
    if (model.busy) return
    val now = System.currentTimeMillis()
    if (now - repo.verificationSentAt in 0 until 60000) {
        actionFeedback("Yeni bağlantı istemek için bir dakika bekle.")
        return
    }
    repo.verificationSentAt = now
    model.busy = true
    actionFeedback("Doğrulama bağlantısı gönderiliyor…", pending = true)
    auth.setLanguageCode("tr")
    user.sendEmailVerification().addOnCompleteListener { result ->
        model.busy = false
        if (!isDestroyed && repo.auth?.currentUser?.uid == user.uid) {
            actionFeedback(if (result.isSuccessful) "Bağlantı gönderildi. E-posta kutunu kontrol et."
                else "Bağlantı gönderilemedi. Biraz sonra yeniden gönder.")
        }
    }
}

internal fun MainActivity.verifyEmail() {
    val user = repo.auth?.currentUser ?: return
    val corporate = repo.kykDraftDorm.isNotBlank()
    val activationName = if (corporate && user.displayName.isNullOrBlank()) field("Ad Soyad", value = model.name) else null
    notice(if (corporate) "Kurumsal e-posta adresine gönderilen doğrulama bağlantısını aç."
        else "Hesabını kullanabilmek için e-posta adresine gönderilen doğrulama bağlantısını aç.")
    center(user.email.orEmpty(), 16, getColor(R.color.ink))
    button("Bağlantıyı Yeniden Gönder") {
        val name = activationName?.text?.toString()?.trim()
        if (activationName != null && name.orEmpty().length < 3) activationName.formError("Adını ve soyadını yaz.")
        else sendVerification(name ?: model.name)
    }
    button("Doğrulamayı Kontrol Et", lightSlate, navyTitle) {
        if (model.busy) return@button
        val name = activationName?.text?.toString()?.trim()
        if (activationName != null && name.orEmpty().length < 3) {
            activationName.formError("Adını ve soyadını yaz.")
            return@button
        }
        model.busy = true
        actionFeedback("E-posta doğrulaması kontrol ediliyor…", pending = true)
        user.reload().onSuccessTask { user.getIdToken(true) }.addOnCompleteListener { result ->
            model.busy = false
            if (isDestroyed || repo.auth?.currentUser?.uid != user.uid || model.page != "verifyEmail") return@addOnCompleteListener
            when {
                result.exception is com.google.firebase.auth.FirebaseAuthInvalidUserException -> {
                    signOut()
                    actionFeedback("Oturumun sona erdi. Yeniden giriş yap.")
                }
                !result.isSuccessful -> actionFeedback("Doğrulama durumu alınamadı. Bağlantını kontrol et.")
                !user.isEmailVerified -> actionFeedback("E-posta henüz doğrulanmadı. Gelen bağlantıyı aç.")
                else -> {
                    actionFeedback("E-posta adresin doğrulandı.")
                    when {
                        corporate -> activateKyk(name ?: model.name)
                        model.pendingInvite.isNotBlank() -> acceptKykInvitation(model.pendingInvite)
                        else -> resumeAccount(model.adminSignIn)
                    }
                }
            }
        }
    }
    accountLink("Çıkış Yap") { signOut() }
}

internal fun MainActivity.staffStatus() {
    val title = when (model.staffStatus) {
        "PENDING" -> if (model.kykStaffStatus) "Yönetim Yetkisi Bekleniyor" else "Yetkili Onayı Bekleniyor"
        "REJECTED" -> "Başvuru Reddedildi"
        "APPROVED", "RESTRICTED" -> "Erişim Kısıtlandı"
        else -> "Personel Başvurusu"
    }
    accountHeader(title, when (model.staffStatus) {
        "PENDING" -> if (model.kykStaffStatus) "Kurumsal e-postan doğrulandı. Yönetim paneli, yurt yetkin doğrulandığında veya yetkili bir personel davetini kabul ettiğinde açılır."
            else "Personel başvurunuz alındı. Görev ve yurt bilgileriniz yetkili kişi tarafından inceleniyor. Başvurunuz onaylandığında yönetim paneline erişebilirsiniz."
        "REJECTED" -> "Başvurunuz onaylanmadı. Kurumunuzla iletişime geçebilirsiniz."
        "APPROVED", "RESTRICTED" -> "Bu hesaba bağlı etkin yönetim yetkisi bulunmuyor."
        else -> "Görev yaptığın yurdu seçerek başvurunu tamamla."
    })
    button("Durumu Yenile") { resumeAccount(true) }
    if (model.staffStatus.isBlank() || model.kykStaffStatus && model.staffStatus == "PENDING") {
        accountLink("Personel Davetini Kullan") { route("kykInviteAccept") }
    }
    if (model.staffStatus.isBlank() &&
        repo.auth?.currentUser?.email?.endsWith("@gsb.gov.tr", ignoreCase = true) == true) {
        accountLink("Yurt Başvurusunu Tamamla") { route("kykRegister") }
    }
    accountLink("Çıkış Yap") { signOut() }
}

internal fun MainActivity.resumeAccount(adminOnly: Boolean = repo.staffSession) {
    val user = repo.auth?.currentUser ?: return
    val staff = adminOnly || repo.staffDraftDorm.isNotBlank() || repo.kykDraftDorm.isNotBlank()
    model.adminSignIn = staff
    repo.staffSession = staff
    model.email = user.email.orEmpty()
    model.name = user.displayName.orEmpty()
    if (staff) {
        loadManagerAccess(openCurrentDorm = true)
        return
    }
    request("staffStatus") {
        if (it.optString("staffStatus").isNotBlank()) resumeAccount(true)
        else resumeStudent(user)
    }
}

private fun MainActivity.resumeStudent(user: com.google.firebase.auth.FirebaseUser) {
    request("catalog") { result ->
        model.catalog = result.items("dorms")
        val memberships = model.catalog.filter { it.optString("status").isNotBlank() }
        val managers = memberships.filter {
            it.optString("role") == "ADMIN" && it.optString("status") == "ACTIVE"
        }
        if (managers.isNotEmpty()) {
            val managerDorm = managers.firstOrNull { it.optString("id") == repo.dormId } ?: managers.first()
            openManagerDorm(managerDorm)
            return@request
        }
        if (!user.isEmailVerified) {
            showEmailVerification()
            return@request
        }
        val linked = memberships.firstOrNull { it.optString("id") == repo.dormId } ?: memberships.firstOrNull()
        if (linked == null) {
            val draft = model.catalog.firstOrNull { it.optString("id") == repo.studentDraftDorm }
            model.dorm = draft
            route("dorms", true)
            if (draft != null) route("enroll")
        } else {
            repo.dormId = linked.optString("id")
            request("status") {
                route(if (member.optString("status") == "ACTIVE") "home" else "pending", true)
            }
        }
    }
}

internal fun MainActivity.loadManagerAccess(openCurrentDorm: Boolean = false) {
    request("managerAccess") { result ->
        model.managerDorms = result.items("dorms")
        model.managerEmailUnverified = result.optBoolean("needsVerification")
        val current = model.managerDorms.firstOrNull {
            it.optString("id") == repo.dormId && it.optString("role") == "ADMIN"
        }
        val soleAdmin = model.managerDorms.singleOrNull()?.takeIf { it.optString("role") == "ADMIN" }
        val destination = current ?: soleAdmin
        if (openCurrentDorm && destination != null) openManagerDorm(destination)
        else if (model.managerDorms.isEmpty()) {
            if (repo.kykDraftDorm.isNotBlank()) {
                if (repo.auth?.currentUser?.isEmailVerified == true) activateKyk() else showEmailVerification()
                return@request
            }
            if (openCurrentDorm && model.email.endsWith("@gsb.gov.tr", ignoreCase = true) && !result.optBoolean("restricted")) {
                request("staffStatus") { status ->
                    when (status.optString("staffStatus")) {
                        "EMAIL_PENDING" -> {
                            repo.kykDraftDorm = status.optString("kykCatalogId")
                            if (repo.auth?.currentUser?.isEmailVerified == true) activateKyk() else showEmailVerification()
                        }
                        "" -> request("kykPending") { pending ->
                            repo.kykDraftDorm = pending.optString("dormId")
                            if (repo.kykDraftDorm.isNotBlank() && repo.auth?.currentUser?.isEmailVerified == true) activateKyk()
                            else finishStaffRouting(false, status)
                        }
                        else -> finishStaffRouting(false, status)
                    }
                }
                return@request
            }
            finishStaffRouting(result.optBoolean("restricted"))
        } else if (openCurrentDorm) route("adminAccess", true) else render()
    }
}

private fun MainActivity.finishStaffRouting(restricted: Boolean, known: org.json.JSONObject? = null) {
    if (repo.auth?.currentUser?.isEmailVerified != true) {
        showEmailVerification()
        return
    }
    fun showStatus(status: org.json.JSONObject) {
        model.staffStatus = status.optString("staffStatus")
        model.kykStaffStatus = status.optString("kykCatalogId").isNotBlank()
        if (model.staffStatus.isBlank() && repo.staffDraftDorm.isNotBlank()) submitStaffApplication()
        else {
            if (model.staffStatus.isBlank() && restricted) model.staffStatus = "RESTRICTED"
            route("staffStatus", true)
        }
    }
    if (known == null) request("staffStatus", after = ::showStatus) else showStatus(known)
}

private fun MainActivity.openManagerDorm(dorm: org.json.JSONObject) {
    val id = dorm.optString("id")
    val action = if (dorm.optString("role") == "ADMIN") "status" else "activateManager"
    request(action, mapOf("dormId" to id)) {
        repo.staffSession = true
        repo.dormId = id
        model.roomId = ""
        model.machineId = ""
        route("home", true)
    }
}

internal fun MainActivity.managerAccess() {
    val user = repo.auth?.currentUser ?: return
    accountHeader("Yurt yönetimi", user.email.orEmpty(), R.drawable.ic_building)
    val needsVerification = model.managerEmailUnverified == true
    if (needsVerification && user.email != null) {
        notice("Kurumunun bildirdiği adresi doğrulamak için e-postana gelen bağlantıyı aç.")
        button("Doğrulama Bağlantısı Gönder") {
            if (model.busy) return@button
            model.busy = true
            repo.auth?.setLanguageCode("tr")
            user.sendEmailVerification().addOnCompleteListener {
                model.busy = false
                if (!isDestroyed) message(if (it.isSuccessful) "Doğrulama bağlantısı gönderildi." else "Bağlantı gönderilemedi. Biraz sonra tekrar dene.")
            }
        }
        button("E-postamı Doğruladım", lightSlate, navyTitle) {
            if (model.busy) return@button
            model.busy = true
            user.reload().continueWithTask { reload ->
                if (!reload.isSuccessful) throw reload.exception ?: IllegalStateException("Hesap yenilenemedi.")
                user.getIdToken(true)
            }.addOnCompleteListener {
                model.busy = false
                if (isDestroyed) return@addOnCompleteListener
                if (it.isSuccessful) loadManagerAccess() else message("Hesap durumu yenilenemedi. Tekrar dene.")
            }
        }
    } else {
        if (model.managerDorms.isNotEmpty()) {
            section("Yetkili olduğun yurtlar")
            rows(model.managerDorms.map { dorm ->
                Row(dorm.optString("name"), "${dorm.optString("city")} • ${dorm.optString("district")}", R.drawable.ic_building) {
                    openManagerDorm(dorm)
                }
            })
            val reviewable = model.managerDorms.filter { it.optBoolean("canReviewStaff") }
            if (reviewable.isNotEmpty()) button("Personel Başvuruları", lightSlate, navyTitle) {
                choose("İncelenecek yurt", reviewable.map { it.optString("name") }) { index ->
                    openStaffReviews(reviewable[index].optString("id"))
                }
            }
        } else if (model.managerEmailUnverified != null) {
            notice("Bu hesaba bağlı yurt görünmüyor. Kurumunun bildirdiği e-posta adresiyle giriş yaptığını kontrol et.")
        }
        button("Tekrar Dene", lightSlate, navyTitle) { loadManagerAccess(openCurrentDorm = true) }
    }
    if (model.managerDorms.isEmpty()) accountLink("Personel Başvurusu") { route("adminRegister") }
    accountLink("Çıkış Yap") { signOut() }
}

private fun MainActivity.openStaffReviews(dormId: String) {
    request("staffReviews", mapOf("dormId" to dormId)) {
        model.staffReviewDorm = dormId
        model.staffApplications = it.items("applications")
        route("staffReviews")
    }
}

internal fun MainActivity.staffReviews() {
    rows(model.staffApplications.map { application ->
        Row(application.optString("name"), application.optString("email"), R.drawable.ic_profile) {
            choose("Başvuru kararı", listOf("Yurt yöneticisi olarak onayla", "Reddet")) { index ->
                val decision = if (index == 0) "APPROVED" else "REJECTED"
                confirm("Başvuru kararı", "Bu başvuru için seçtiğin kararı kaydetmek istiyor musun?") {
                    request("staffReview", mapOf("dormId" to model.staffReviewDorm,
                        "applicantId" to application.optString("id"), "decision" to decision)) {
                        openStaffReviews(model.staffReviewDorm)
                    }
                }
            }
        }
    }, empty = "Bekleyen personel başvurusu yok.")
}

private fun MainActivity.loadDorms() = request("catalog") {
    model.catalog = it.items("dorms")
    route("dorms")
}

internal fun MainActivity.dorms() {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(8))
        isFocusableInTouchMode = true
    }
    ui.dormBody.addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
    add(body, text("Yurdunun adını yazarak kolayca bulabilirsin.", 15), 0)
    val searchBox = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        isHintEnabled = false
        setStartIconDrawable(R.drawable.ic_search)
        val radius = dp(20).toFloat()
        setBoxCornerRadii(radius, radius, radius, radius)
        setBoxBackgroundColor(getColor(R.color.card))
    }
    val search = TextInputEditText(searchBox.context).apply {
        hint = "Yurt adı, il veya ilçe ara..."
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
        minHeight = dp(58)
        setText(model.dormSearch)
    }
    searchBox.addView(search, LinearLayout.LayoutParams(-1, -2))
    add(body, searchBox, 12)
    val status = text("", 14)
    add(body, status, 8)
    val adapter = RowAdapter()
    val results = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@dorms)
        this.adapter = adapter
        itemAnimator = null
    }
    body.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
    body.requestFocus()
    val collator = java.text.Collator.getInstance(Locale.forLanguageTag("tr"))
    var index = emptyList<Pair<String, Row>>()
    var catalogUnavailable = false
    fun filter() {
        val terms = dormSearchKey(model.dormSearch).split(' ').filter(String::isNotEmpty)
        val matches = index.filter { (key, _) -> terms.all(key::contains) }.map { it.second }
        status.text = when {
            catalogUnavailable -> "Tam katalog yüklenemedi. Yeniden dene."
            model.publicDorms == null -> "Yurt kataloğu yükleniyor…"
            matches.isEmpty() -> "Aramanla eşleşen yurt bulunamadı"
            else -> ""
        }
        status.isClickable = catalogUnavailable
        status.visibility = if (status.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        adapter.submitList(matches) { results.scrollToPosition(0) }
    }
    fun rebuild() {
        val available = model.catalog.associateBy { it.optString("id") }
        val public = model.publicDorms.orEmpty()
        val aliases = public.map { it.optString("enrollmentId", it.optString("id")) }.toSet()
        val entries = (public + available.values.filter { it.optString("id") !in aliases })
            .sortedWith { a, b -> collator.compare(a.optString("name"), b.optString("name")) }
        index = entries.map { dorm ->
            val active = available[dorm.optString("enrollmentId", dorm.optString("id"))]
            val name = dorm.optString("name")
            val city = dorm.optString("city")
            val district = dorm.optString("district")
            val select: (() -> Unit)? = if (active == null) null else ({
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(search.windowToken, 0)
                model.dorm = active
                route("enroll")
            })
            dormSearchKey("$name $city $district") to Row(name, "$city / $district", R.drawable.ic_building,
                if (active != null) "Aktif" else "Henüz hizmette değil", if (active != null) green else lightSlate,
                identity = dorm.optString("id"), click = select)
        }
        filter()
    }
    fun loadPublic() {
        catalogUnavailable = false
        filter()
        repo.kykDorms { result ->
            if (isDestroyed || status.parent == null || body.parent == null) return@kykDorms
            result.onSuccess { model.publicDorms = it.items("dorms") }
                .onFailure { catalogUnavailable = true }
            rebuild()
        }
    }
    status.setOnClickListener { if (catalogUnavailable) loadPublic() }
    search.doAfterTextChanged { model.dormSearch = it.toString(); filter() }
    rebuild()
    if (model.publicDorms == null) loadPublic()
}

internal fun MainActivity.enroll() {
    val dorm = model.dorm ?: run {
        notice("Başvuru göndermek için önce yurdunu seç.")
        button("Yurt Seç") { route("dorms", true) }
        return
    }
    accountHeader(dorm.optString("name"), "Yurt yönetiminin verdiği katılım kodunu gir.", R.drawable.ic_building)
    val code = field("Yurt katılım kodu", "6 karakter").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(6))
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 22f
        letterSpacing = .22f
    }
    val nameInput = if (model.name.isBlank()) field("Ad Soyad") else null
    notice("Katılım kodu başvuruyu açar. Yönetim üyeliğini onayladığında makineleri görebilirsin.")
    button("Yurda Katıl") {
        val value = code.text.toString().trim().uppercase(Locale.ROOT)
        if (!Regex("[A-Z0-9]{6}").matches(value)) { code.error = "Yönetimin verdiği 6 karakterli kodu gir."; return@button }
        val name = nameInput?.text?.toString()?.trim() ?: model.name
        if (name.length < 3) { message("Adını ve soyadını gir."); return@button }
        repo.dormId = dorm.optString("id")
        request("enroll", mapOf("code" to value, "name" to name)) { route("pending", true) }
    }
}

internal fun MainActivity.pending() {
    val status = member.optString("status")
    heading("Yurt üyeliği", state.optString("dorm"))
    notice(when (status) {
        "REJECTED" -> "Başvurun reddedildi. Yurt yönetimiyle iletişime geçebilirsin."
        "SUSPENDED" -> "Üyeliğin askıya alındı."
        else -> "Üyeliğin yönetici onayı bekliyor."
    })
    button("Durumu Kontrol Et", online = true) { refreshMembership() }
    if (status == "REJECTED") button("Tekrar Başvur", lightSlate, navyTitle) { loadDorms() }
    button("Çıkış Yap", pink, Color.rgb(165, 33, 61)) { signOut() }
}

internal fun MainActivity.profile() {
    icon(R.drawable.ic_profile, 70)
    center(member.optString("name"), 25, getColor(R.color.ink))
    center(repo.auth?.currentUser?.email ?: model.email)
    center(if (operationsStaff) "Çamaşırhane Personeli" else if (admin) "Yetkili Yönetici" else state.optString("dorm"))
    if (admin && !demo) {
        val account = repo.auth?.currentUser
        if (account != null && !account.isEmailVerified) {
            button("E-postamı Doğrula", lightSlate, navyTitle) { route("verifyEmail") }
        }
        if (member.optBoolean("kykOwner")) button("Personel Davet Et", lightSlate, navyTitle) { loadKykInvitations() }
        button("Yetkili Olduğum Yurtlar", lightSlate, navyTitle) {
            model.adminSignIn = true
            route("adminAccess")
            loadManagerAccess()
        }
    }
    rows(listOfNotNull(
        if (operationsStaff) null else Row(if (admin) "Yurt Bilgileri" else "Yurt üyeliğim", state.optString("dorm"), R.drawable.ic_building) {
            if (admin) route("settings") else choose("Yurt üyeliğim", listOf(state.optString("dorm") + " · Aktif")) {}
        },
        if (operationsStaff) null else Row(if (admin) "Yurt Üyelikleri" else "Kullanım Geçmişi", "", if (admin) R.drawable.ic_queue else R.drawable.ic_history) {
            route(if (admin) "members" else "history")
        },
        Row("Görünüm", "Sistem, açık veya koyu", R.drawable.ic_home) {
            choose("Görünüm", listOf("Sistem", "Açık", "Koyu")) {
                setNightPreference(listOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)[it])
            }
        },
        Row("Bildirimler", "Uygulama içi bildirim kutusu", R.drawable.ic_bell) { route("notifications") },
        Row("Hesap Bilgileri", "E-posta ve şifre", R.drawable.ic_profile) {
            val email = repo.auth?.currentUser?.email
            if (email == null) message("Demo hesabı") else confirm("Şifre sıfırlama", "Bağlantı ${email} adresine gönderilecek.") {
                repo.auth?.setLanguageCode("tr")
                repo.auth?.sendPasswordResetEmail(email)?.addOnCompleteListener {
                    message(if (it.isSuccessful) "Bağlantı gönderildi." else "Bağlantı gönderilemedi.")
                }
            }
        },
        Row("Gizlilik ve Veriler", "Üyelik, yıkama geçmişi ve hesap işlemleri", R.drawable.ic_shield) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Gizlilik ve Veriler")
                .setMessage("E-posta girişte, adın yurt üyeliğinde kullanılır. Çamaşırhane işlemleri için sıra ve yıkama kayıtları tutulur. Yardım için yurt yönetimiyle iletişime geç.")
                .setNegativeButton("Kapat", null)
                .setPositiveButton("Hesabımı sil") { _, _ ->
                    confirm("Hesabı sil", "Üyelik ve kişisel geçmiş kayıtların silinecek.") {
                        deleteSignedInAccount()
                    }
                }.show()
        }
    ))
    button("Çıkış Yap", pink, Color.rgb(165, 33, 61)) { signOut() }
}
