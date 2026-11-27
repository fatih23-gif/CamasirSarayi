package com.camasirsarayi.app

import android.app.Application
import com.google.firebase.FirebaseApp

class LaundryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isNotEmpty()) installAppCheck()
    }
}
