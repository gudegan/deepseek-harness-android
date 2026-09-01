package com.deepseek.dshshell.util

import androidx.appcompat.app.AppCompatDelegate
import com.deepseek.dshshell.R

/**
 * 外观主题：模式（跟随系统/浅色/深色）+ 强调色预设（后期可扩展自定义主题）。
 *
 * 强调色通过切换 Activity 的 theme 资源实现（themes.xml 中 Theme.DshShell.*）。
 * 新增预设只需：themes.xml 加一个子样式 + 这里加一条映射。
 */
object ThemeUtil {

    /** 强调色预设：键 → 主题资源 */
    val ACCENTS = linkedMapOf(
        "brand" to R.style.Theme_DshShell,
        "ocean" to R.style.Theme_DshShell_Ocean,
        "violet" to R.style.Theme_DshShell_Violet,
        "deep" to R.style.Theme_DshShell_Deep,
    )

    /** 根据保存的强调色键返回主题资源 */
    fun themeResource(accentKey: String): Int = ACCENTS[accentKey] ?: R.style.Theme_DshShell

    /** 根据主题模式应用日夜模式（AppCompatDelegate） */
    fun applyNightMode(mode: String) {
        val night = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(night)
    }
}
