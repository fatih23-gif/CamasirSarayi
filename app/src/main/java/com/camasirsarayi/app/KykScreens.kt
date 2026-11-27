package com.camasirsarayi.app

import android.content.Intent
import android.util.Patterns
import android.text.InputType
import android.widget.EditText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import org.json.JSONObject
import java.util.Locale

internal fun MainActivity.kykRegister() {
    val existing = repo.auth?.currentUser
    add(ui.content, text("KYK yurdunu seçerek kurumsal e-posta adresinle hesap oluşturabilirsin.", 15), 4)
    val name = field("Ad Soyad", "Adını ve soyadını yaz", value = existing?.displayName.orEmpty(), radius = 20, height = 58)
    val turkish = Locale.forLanguageTag("tr")
    val citySort = java.text.Collator.getInstance(turkish)
    var city = ""
    var selected: JSONObject? = null
    var catalog = emptyList<JSONObject>()
    lateinit var cityInput: EditText
    lateinit var dormInput: EditText
    lateinit var mailInput: EditText
    var mailKeyListener: android.text.method.KeyListener? = null
    fun setMailbox(address: String) {
        val mailbox = address.ifBlank { existing?.email.orEmpty() }
        mailInput.keyListener = if (mailbox.isBlank()) mailKeyListener else null
        mailInput.isFocusableInTouchMode = mailbox.isBlank()
        mailInput.isFocusable = mailbox.isBlank()
        if (mailbox.isNotBlank()) mailInput.clearFocus()
        mailInput.setText(mailbox)
    }
    cityInput = field("İl Seçimi", "İl seç", radius = 20, height = 58, picker = {
        fun pickCity() {
            val cities = catalog.map { it.optString("city") }.distinct().sortedWith(citySort)
            if (cities.isEmpty()) return message("KYK kataloğunda henüz kayıt yok.")
            choose("İl seç", cities) { index ->
                city = cities[index]
                selected = null
                cityInput.setText(city)
                dormInput.setText("")
                setMailbox("")
            }
        }
        if (catalog.isNotEmpty()) pickCity()
        else if (!model.busy) {
            model.busy = true
            repo.kykDorms { result ->
                model.busy = false
                if (isDestroyed || model.page != "kykRegister") return@kykDorms
                result.onSuccess { catalog = it.items("dorms"); pickCity() }
                    .onFailure { message("KYK kataloğu yüklenemedi. Bağlantını kontrol edip İl seç düğmesine tekrar bas.") }
            }
        }
    })
    dormInput = field("KYK Yurdu Seçimi", "KYK Yurdu seç", radius = 20, height = 58, picker = {
        if (city.isBlank()) message("Önce il seç.")
        else {
            val dorms = catalog.filter { it.optString("city") == city }
            choose("KYK Yurdu seç", dorms.map { "${it.optString("name")} · ${it.optString("district")}" }) { index ->
                val dorm = dorms[index]
                selected = dorm
                dormInput.setText(dorm.optString("name"))
                setMailbox(dorm.optString("officialEmail"))
            }
        }
    })
    mailInput = field("Kurumsal E-posta", "Kurumsal e-posta adresin", email = true, radius = 20, height = 58)
    mailInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    mailKeyListener = mailInput.keyListener
    setMailbox("")
    val password = if (existing == null) field("Şifre", "En az 8 karakter", password = true, radius = 20, height = 58) else null
    val repeat = if (existing == null) field("Şifre Tekrar", password = true, radius = 20, height = 58) else null
    button(if (existing == null) "Yurt Hesabını Oluştur" else "Başvuruyu Tamamla") {
        val dorm = selected ?: return@button message("İlini ve yurdunu seç.")
        val email = mailInput.text.toString().trim().lowercase(Locale.ROOT)
        val displayName = name.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() || email.substringAfterLast('@') != "gsb.gov.tr") {
            return@button mailInput.formError("Geçerli bir @gsb.gov.tr adresi gir.")
        }
        if (displayName.length !in 3..120) return@button name.formError("Ad ve soyad 3–120 karakter olmalı.")
        if (existing != null) {
            if (!email.equals(existing.email, ignoreCase = true)) {
                return@button mailInput.formError("Bu yurt için gösterilen e-posta hesabıyla giriş yap.")
            }
            repo.kykDraftDorm = dorm.optString("id")
            repo.staffSession = true
            model.name = displayName
            model.adminSignIn = true
            if (existing.isEmailVerified) activateKyk(displayName) else showEmailVerification(send = true)
        } else if (password != null && repeat != null) {
            if (password.text.length < 8) return@button password.formError("Şifre en az 8 karakter olmalı.")
            if (password.text.toString() != repeat.text.toString()) return@button repeat.formError("Şifreler eşleşmiyor.")
            createKykAccount(displayName, email, password.text.toString(), dorm.optString("id"))
        }
    }
    if (existing == null) accountLink("Zaten hesabın var mı? Giriş Yap") { route("adminLogin") }
    accountLink("Personel Davetini Kullan") { route("kykInviteAccept") }
}

private fun MainActivity.createKykAccount(name: String, email: String, password: String, dormId: String, invite: String = "") {
    val auth = repo.auth ?: return message("Firebase bağlantısı gerekiyor.")
    if (model.busy) return
    if (auth.currentUser != null) return message("Yeni hesap açmak için önce çıkış yap.")
    model.busy = true
    auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { result ->
        val user = if (result.isSuccessful) result.result.user else null
        if (user == null) {
            model.busy = false
            if (!isDestroyed) message(when (result.exception) {
                is FirebaseAuthUserCollisionException -> "Bu e-posta kayıtlı. Yönetici / Personel Girişi ekranından devam et."
                is FirebaseNetworkException -> "İnternet bağlantısını kontrol et."
                else -> "Hesap oluşturulamadı. Bilgilerini kontrol et."
            })
            return@addOnCompleteListener
        }
        repo.staffSession = true
        repo.kykDraftDorm = dormId
        model.name = name
        model.adminSignIn = true
        model.pendingInvite = invite
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).addOnCompleteListener {
            model.busy = false
            if (!isDestroyed) showEmailVerification(send = true)
        }
    }
}

internal fun MainActivity.activateKyk(displayName: String = model.name) {
    val dormId = repo.kykDraftDorm
    if (dormId.isBlank()) return
    if (displayName.trim().length < 3 && repo.auth?.currentUser?.displayName.orEmpty().length < 3) {
        showEmailVerification()
        return
    }
    prepareKyk(displayName) {
        request("kykComplete") { result ->
            repo.kykDraftDorm = ""
            val activatedDorm = result.optString("dormId")
            if (activatedDorm.isNotBlank()) {
                repo.dormId = activatedDorm
                resumeAccount(true)
            } else {
                model.staffStatus = result.optString("staffStatus")
                model.kykStaffStatus = true
                route("staffStatus", true)
            }
        }
    }
}

internal fun MainActivity.prepareKyk(displayName: String = model.name, next: () -> Unit) {
    val name = displayName.ifBlank { repo.auth?.currentUser?.displayName.orEmpty() }.trim()
    request("kykPrepare", mapOf("dormId" to repo.kykDraftDorm, "name" to name)) { next() }
}

internal fun MainActivity.kykInviteAccept() {
    val user = repo.auth?.currentUser
    val code = field("Personel davet kodu", "Yurt yönetiminin gönderdiği kod")
    val name = if (user == null) field("Ad Soyad") else null
    val email = if (user == null) field("E-posta", email = true) else null
    val password = if (user == null) field("Şifre", "En az 8 karakter", password = true) else null
    val repeat = if (user == null) field("Şifre Tekrar", password = true) else null
    button(if (user == null) "Hesap Oluştur" else "Daveti Kabul Et") {
        val value = code.text.toString().trim()
        if (!Regex("[A-Za-z0-9_-]{1,80}\\.[A-Za-z0-9_-]{43}").matches(value)) return@button code.formError("Geçerli bir davet kodu gir.")
        if (user != null) {
            repo.staffSession = true
            model.adminSignIn = true
            model.pendingInvite = value
            if (user.isEmailVerified) acceptKykInvitation(value) else showEmailVerification(send = true)
        } else if (name != null && email != null && password != null && repeat != null) {
            val address = email.text.toString().trim()
            val displayName = name.text.toString().trim()
            if (displayName.length !in 3..120) return@button name.formError("Ad ve soyad 3–120 karakter olmalı.")
            if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) return@button email.formError("Geçerli bir e-posta adresi yaz.")
            if (password.text.length < 8) return@button password.formError("Şifre en az 8 karakter olmalı.")
            if (password.text.toString() != repeat.text.toString()) return@button repeat.formError("Şifreler eşleşmiyor.")
            createKykAccount(displayName, address, password.text.toString(), "", value)
        }
    }
    if (user == null) accountLink("Zaten hesabın var mı? Giriş Yap") { route("adminLogin") }
    else accountLink("Çıkış Yap") { signOut() }
}

internal fun MainActivity.acceptKykInvitation(value: String) {
    val (dormId, token) = value.split('.', limit = 2)
    request("kykInviteAccept", mapOf("dormId" to dormId, "token" to token)) {
        model.pendingInvite = ""
        repo.dormId = dormId
        resumeAccount(true)
    }
}

internal fun MainActivity.loadKykInvitations() {
    request("kykInvites") {
        model.kykInvitations = it.items("invitations")
        route("kykInvitations")
    }
}

internal fun MainActivity.kykInvitations() {
    notice("Personel yalnızca makine ve arıza işlemlerine erişir. Davet 48 saat geçerlidir.")
    val name = field("Ad Soyad")
    val email = field("Personelin E-posta Adresi", email = true)
    button("Davet Oluştur") {
        val displayName = name.text.toString().trim()
        val address = email.text.toString().trim()
        if (displayName.length !in 3..120) return@button name.formError("Ad ve soyad 3–120 karakter olmalı.")
        if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) return@button email.formError("Geçerli bir e-posta adresi yaz.")
        request("kykInviteCreate", mapOf("name" to displayName, "email" to address)) {
            showKykCode(address, it.optString("invitation"))
            loadKykInvitations()
        }
    }
    rows(model.kykInvitations.map { invitation ->
        val status = if (invitation.optString("status") == "ACCEPTED") "Etkin" else "Bekliyor"
        Row(invitation.optString("name"), "${invitation.optString("email")} · $status", R.drawable.ic_profile) {
            val args = mapOf("inviteId" to invitation.optString("id"))
            fun revoke() {
                confirm("Daveti veya erişimi kaldır", "Bu personelin davetini ve varsa erişimini kaldırmak istiyor musun?") {
                    request("kykInviteRevoke", args) { loadKykInvitations() }
                }
            }
            if (status == "Etkin") revoke()
            else choose("Bekleyen davet", listOf("Yeni Kod Oluştur ve Paylaş", "Daveti İptal Et")) { choice ->
                if (choice == 0) {
                    confirm("Yeni davet kodu", "Yeni kod oluşturulduğunda önceki kod geçersiz olur.") {
                        request("kykInviteRotate", args) {
                            showKykCode(invitation.optString("email"), it.optString("invitation"))
                            loadKykInvitations()
                        }
                    }
                } else revoke()
            }
        }
    }, empty = "Etkin davet veya personel yok.")
}

private fun MainActivity.showKykCode(email: String, code: String) {
    if (code.isBlank()) return message("Davet kodu alınamadı.")
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Personel Davet Kodu")
        .setMessage(code)
        .setNeutralButton("Kapat", null)
        .setNegativeButton("Kopyala") { _, _ ->
            val clip = android.content.ClipData.newPlainText("Personel davet kodu", code)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
            message("Kod kopyalandı.")
        }
        .setPositiveButton("Paylaş") { _, _ ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_TEXT, code)
            }
            startActivity(Intent.createChooser(send, "Personel davetini paylaş"))
        }.show()
}
