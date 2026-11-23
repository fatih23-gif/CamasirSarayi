package com.camasirsarayi.hafta2

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private enum class Screen(val layout: Int) {
        WELCOME(R.layout.screen_welcome),
        LOGIN(R.layout.screen_student_login),
        REGISTER(R.layout.screen_student_register)
    }

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
                Toast.makeText(this, "Yönetici ekranları 3. aşamada eklenecek.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        findViewById<View>(R.id.back).setOnClickListener { goBack() }
        findViewById<View>(R.id.switch_screen).setOnClickListener {
            show(if (current == Screen.LOGIN) Screen.REGISTER else Screen.LOGIN)
        }
        findViewById<View?>(R.id.forgot)?.setOnClickListener {
            feedback("Bu sürümde şifre sıfırlama e-postası gönderilmiyor.")
        }
        findViewById<View>(R.id.submit).setOnClickListener { validateForm() }
    }

    private fun goBack() {
        when (current) {
            Screen.REGISTER -> show(Screen.LOGIN)
            Screen.LOGIN -> show(Screen.WELCOME)
            Screen.WELCOME -> finish()
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
        when {
            name != null && name.text.toString().trim().length < 3 ->
                name.error = "Adını ve soyadını gir."
            !Patterns.EMAIL_ADDRESS.matcher(email.text.toString().trim()).matches() ->
                email.error = "Geçerli bir e-posta adresi gir."
            password.text.length < 8 ->
                password.error = "Şifre en az 8 karakter olmalı."
            repeat != null && repeat.text.toString() != password.text.toString() ->
                repeat.error = "Şifreler eşleşmiyor."
            current == Screen.REGISTER ->
                feedback("Form geçerli. Bu sürümde hesap oluşturulmadı.")
            else -> feedback("Form geçerli. Bu sürümde oturum açılmadı.")
        }
    }
}
