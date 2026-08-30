package com.widgetflow.app

import android.app.Application
import com.widgetflow.app.util.CrashLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.init(this)
    }
}
