package com.camasirsarayi.app

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthCredential
import com.google.firebase.auth.OAuthProvider
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun MainActivity.providerButtons(adminOnly: Boolean, separator: Boolean = true) {
    val googleConfigured = resources.getIdentifier("default_web_client_id", "string", packageName) != 0
    if (demo || (!googleConfigured && !BuildConfig.APPLE_SIGN_IN_ENABLED)) return
    if (separator) center("veya", 13)
    if (googleConfigured) providerButton("Google ile devam et", R.drawable.ic_google, getColor(R.color.card), getColor(R.color.ink), getColor(R.color.line)) {
        signInWithGoogle(adminOnly)
    }
    if (BuildConfig.APPLE_SIGN_IN_ENABLED) providerButton("Apple ile devam et", R.drawable.ic_apple, Color.BLACK, Color.WHITE, Color.rgb(120, 120, 120)) {
        signInWithApple(adminOnly)
    }
}

private fun MainActivity.providerButton(label: String, logo: Int, fill: Int, foreground: Int, border: Int, click: () -> Unit) {
    add(ui.content, MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        letterSpacing = 0f
        minHeight = dp(54)
        cornerRadius = dp(16)
        insetTop = 0
        insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(fill)
        setTextColor(foreground)
        strokeColor = ColorStateList.valueOf(border)
        strokeWidth = dp(1)
        setIconResource(logo)
        iconTint = null
        iconSize = dp(21)
        iconPadding = dp(10)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        rippleColor = ColorStateList.valueOf(if (fill == Color.BLACK) 0x29FFFFFF else 0x1F000000)
        setOnClickListener { click() }
    }, 8)
}

private fun MainActivity.signInWithGoogle(adminOnly: Boolean) {
    val auth = repo.auth ?: return
    if (model.busy) return
    // Bu kaynak yalnızca Firebase'in yapılandırıldığı derlemelerde üretilir.
    val clientIdResource = resources.getIdentifier("default_web_client_id", "string", packageName)
    if (clientIdResource == 0) return message("Firebase'de Google girişini etkinleştirip güncel yapılandırma dosyasını ekle.")
    model.adminSignIn = adminOnly
    model.busy = true
    lifecycleScope.launch {
        try {
            val option = GetSignInWithGoogleOption.Builder(getString(clientIdResource)).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(this@signInWithGoogle)
                .getCredential(context = this@signInWithGoogle, request = request)
            val credential = response.credential
            check(credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            handleProviderSignIn(auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)), "Google")
        } catch (error: CancellationException) {
            model.busy = false
            throw error
        } catch (_: GetCredentialCancellationException) {
            model.busy = false
        } catch (_: Exception) {
            model.busy = false
            message("Google hesabı alınamadı. Bağlantıyı ve cihazındaki Google hesabını kontrol et.")
        }
    }
}

private fun appleProvider() = OAuthProvider.newBuilder("apple.com").apply {
    setScopes(listOf("email", "name"))
    addCustomParameter("locale", "tr")
}.build()

private fun MainActivity.signInWithApple(adminOnly: Boolean) {
    val auth = repo.auth ?: return
    if (model.busy) return
    model.adminSignIn = adminOnly
    model.busy = true
    handleProviderSignIn(auth.pendingAuthResult ?: auth.startActivityForSignInWithProvider(this, appleProvider()), "Apple")
}

internal fun MainActivity.resumeAppleSignIn(): Boolean {
    val pending = repo.auth?.pendingAuthResult ?: return false
    model.busy = true
    if (model.appleDeletion) finishAppleDeletion(pending)
    else handleProviderSignIn(pending, "Apple")
    return true
}

private fun MainActivity.handleProviderSignIn(task: Task<AuthResult>, provider: String) {
    task.addOnCompleteListener {
        if (isDestroyed) {
            if (provider == "Google" || !isChangingConfigurations) model.busy = false
            return@addOnCompleteListener
        }
        model.busy = false
        when {
            it.isSuccessful -> resumeAccount(model.adminSignIn)
            it.exception is FirebaseAuthUserCollisionException ->
                message("Bu e-posta zaten kayıtlı. Önce hesabı oluşturduğun giriş yöntemini kullan.")
            it.isCanceled -> Unit
            else -> message("$provider ile giriş tamamlanamadı. Sağlayıcı ayarlarını ve bağlantını kontrol et.")
        }
    }
}

internal fun MainActivity.clearProviderSession() {
    if (demo) return
    lifecycleScope.launch {
        try {
            CredentialManager.create(this@clearProviderSession).clearCredentialState(ClearCredentialStateRequest())
        } catch (error: CancellationException) {
            throw error
        } catch (_: androidx.credentials.exceptions.ClearCredentialException) {
            // Firebase oturumu zaten kapatıldı.
        }
    }
}

internal fun MainActivity.deleteSignedInAccount() {
    val auth = repo.auth
    val user = auth?.currentUser
    if (auth == null || user == null || user.providerData.none { it.providerId == "apple.com" }) {
        request("deleteAccount") { signOut() }
        return
    }
    request("deleteAccount", mapOf("preview" to true)) {
        model.busy = true
        model.appleDeletion = true
        finishAppleDeletion(user.startActivityForReauthenticateWithProvider(this, appleProvider()))
    }
}

private fun MainActivity.finishAppleDeletion(task: Task<AuthResult>) {
    val auth = repo.auth ?: return
    task.addOnCompleteListener { result ->
        if (isDestroyed) return@addOnCompleteListener
        model.appleDeletion = false
        val token = if (result.isSuccessful) (result.result.credential as? OAuthCredential)?.accessToken else null
        if (token.isNullOrEmpty()) {
            model.busy = false
            if (!isDestroyed) message("Hesabını silmek için Apple doğrulamasını tamamla.")
            return@addOnCompleteListener
        }
        auth.revokeAccessToken(token).addOnCompleteListener { revoked ->
            model.busy = false
            if (isDestroyed) return@addOnCompleteListener
            if (revoked.isSuccessful) request("deleteAccount") { signOut() }
            else message("Apple erişimi iptal edilemedi. Hesap silinmedi.")
        }
    }
}
