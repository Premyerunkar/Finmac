package com.sierratechnologies.finmac

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class FinmacApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}