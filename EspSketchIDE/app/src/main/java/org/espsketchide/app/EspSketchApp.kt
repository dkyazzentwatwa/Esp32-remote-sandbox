package org.espsketchide.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.espsketchide.app.settings.AppSettings

class EspSketchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppSettings(this).themeMode.nightMode)
    }
}
