package com.camasirsarayi.hafta2

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val screen = layoutInflater.inflate(R.layout.screen_welcome, null)
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
        findViewById<View>(R.id.student_login).setOnClickListener {
            Toast.makeText(this, "Öğrenci ekranları 2. aşamada eklenecek.", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.admin_login).setOnClickListener {
            Toast.makeText(this, "Yönetici ekranları 3. aşamada eklenecek.", Toast.LENGTH_SHORT).show()
        }
    }
}
