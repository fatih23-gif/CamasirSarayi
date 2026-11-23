package com.camasirsarayi.hafta2

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private enum class Screen(val layout: Int) {
        WELCOME(R.layout.screen_welcome),
        LOGIN(R.layout.screen_student_login),
        REGISTER(R.layout.screen_student_register),
        ADMIN_LOGIN(R.layout.screen_admin_login),
        ADMIN_APPLY(R.layout.screen_admin_apply),
        STAFF_INVITE(R.layout.screen_staff_invite)
    }

    private val sampleDorms = mapOf("Muş" to "1071 Öğrenci Yurdu", "Denizli" to "Biruni Öğrenci Yurdu")
    private var city: String? = null
    private var dorm: String? = null

    private var current = Screen.WELCOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        show(savedInstanceState?.getString("screen")?.let(Screen::valueOf) ?: Screen.WELCOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", current.name)
        super.onSaveInstanceState(outState)
    }

    private fun show(destination: Screen) {
        current = destination
        val screen = layoutInflater.inflate(destination.layout, null)
        setContentView(screen)
        WindowCompat.getInsetsController(window, screen).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (destination == Screen.WELCOME) {
            findViewById<View>(R.id.student_login).setOnClickListener { show(Screen.LOGIN) }
            findViewById<View>(R.id.admin_login).setOnClickListener {
                show(Screen.ADMIN_LOGIN)
            }
            return
        }

        findViewById<View>(R.id.back).setOnClickListener { goBack() }
        findViewById<View>(R.id.switch_screen).setOnClickListener {
            show(when (current) {
                Screen.LOGIN -> Screen.REGISTER
                Screen.REGISTER -> Screen.LOGIN
                Screen.ADMIN_LOGIN -> Screen.ADMIN_APPLY
                else -> Screen.ADMIN_LOGIN
            })
        }
        findViewById<View?>(R.id.forgot)?.setOnClickListener {
            feedback("Bu sürümde şifre sıfırlama e-postası gönderilmiyor.")
        }
        findViewById<View?>(R.id.invitation_link)?.setOnClickListener { show(Screen.STAFF_INVITE) }
        if (destination == Screen.ADMIN_APPLY) bindDormPicker()
        findViewById<View>(R.id.submit).setOnClickListener { validateForm() }
    }

    private fun goBack() {
        when (current) {
            Screen.REGISTER -> show(Screen.LOGIN)
            Screen.ADMIN_APPLY, Screen.STAFF_INVITE -> show(Screen.ADMIN_LOGIN)
            Screen.LOGIN, Screen.ADMIN_LOGIN -> show(Screen.WELCOME)
            Screen.WELCOME -> finish()
        }
    }

    private fun bindDormPicker() {
        val cityButton = findViewById<Button>(R.id.city)
        val dormButton = findViewById<Button>(R.id.dorm)
        cityButton.text = city ?: "İl seç"
        dormButton.text = dorm ?: "KYK Yurdu seç"
        cityButton.setOnClickListener {
            val options = sampleDorms.keys.toTypedArray()
            AlertDialog.Builder(this).setTitle("İl seç (örnek liste)").setItems(options) { _, index ->
                city = options[index]
                dorm = null
                cityButton.text = city
                dormButton.text = "KYK Yurdu seç"
                feedback("")
            }.show()
        }
        dormButton.setOnClickListener {
            val option = sampleDorms[city]
            if (option == null) feedback("Önce il seç.")
            else AlertDialog.Builder(this).setTitle("KYK Yurdu seç (örnek liste)")
                .setItems(arrayOf(option)) { _, _ ->
                    dorm = option
                    dormButton.text = option
                    feedback("")
                }.show()
        }
    }

    private fun feedback(message: String) {
        findViewById<TextView>(R.id.feedback).text = message
    }

    private fun validateForm() {
        feedback("")
        val name = findViewById<EditText?>(R.id.name)
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val repeat = findViewById<EditText?>(R.id.repeat_password)
        val invite = findViewById<EditText?>(R.id.invite_code)
        when {
            invite != null && invite.text.isNullOrBlank() -> invite.error = "Davet kodunu gir."
            current == Screen.ADMIN_APPLY && dorm == null -> feedback("İl ve yurt seç.")
            name != null && name.text.toString().trim().length < 3 ->
                name.error = "Adını ve soyadını gir."
            !Patterns.EMAIL_ADDRESS.matcher(email.text.toString().trim()).matches() ->
                email.error = "Geçerli bir e-posta adresi gir."
            password.text.length < 8 ->
                password.error = "Şifre en az 8 karakter olmalı."
            repeat != null && repeat.text.toString() != password.text.toString() ->
                repeat.error = "Şifreler eşleşmiyor."
            current == Screen.ADMIN_APPLY -> feedback("Form geçerli. Başvuru gönderilmedi ve yönetici yetkisi verilmedi.")
            current == Screen.STAFF_INVITE -> feedback("Form geçerli. Davet sunucuda doğrulanmadı ve hesap oluşturulmadı.")
            current == Screen.REGISTER ->
                feedback("Form geçerli. Bu sürümde hesap oluşturulmadı.")
            else -> feedback("Form geçerli. Bu sürümde oturum açılmadı.")
        }
    }
}
