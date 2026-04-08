package com.mobilespeaker.app

import android.app.Application
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import com.mobilespeaker.app.util.AppLogger

class MobileSpeakerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        CrashDiagnostics.init(this)
    }
}
