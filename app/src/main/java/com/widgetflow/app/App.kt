package com.widgetflow.app

import android.app.Application
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.util.CrashLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.init(this)
        // 旧版「数据源+小部件」合并配置自动拆分为独立的数据源与小部件
        ConfigStore.migrate(this)
    }
}
