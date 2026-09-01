package com.deepseek.dshshell

import android.app.Application
import com.deepseek.dshshell.util.Logs
import com.deepseek.dshshell.util.Prefs
import com.deepseek.dshshell.util.ThemeUtil

class DshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // 每次重启 APK 刷新运行日志（上个会话归档到 dsh.log.old）
        Logs.refresh(this)
        // 应用已保存的主题模式（日夜），避免 Activity 创建前闪白
        ThemeUtil.applyNightMode(Prefs.themeMode)
    }
}
