package com.deepseek.dshshell.util

import android.content.Context

/**
 * 外壳设置（SharedPreferences）。
 * 在 DshApp.onCreate 里 init 一次，之后各处直接读写属性。
 */
object Prefs {
    private const val NAME = "shell_prefs"
    private var sp: android.content.SharedPreferences? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** 打开应用是否自动"解压 → 启动 dsh" */
    var autoStart: Boolean
        get() = sp?.getBoolean("auto_start", true) ?: true
        set(v) { sp?.edit()?.putBoolean("auto_start", v)?.apply() }

    /** 是否 -b /sdcard:/mnt/sdcard（改动后重启 dsh 生效） */
    var mountSdcard: Boolean
        get() = sp?.getBoolean("mount_sdcard", false) ?: false
        set(v) { sp?.edit()?.putBoolean("mount_sdcard", v)?.apply() }

    /** 运行模式：standard / minimal */
    var runMode: String
        get() = sp?.getString("run_mode", "standard") ?: "standard"
        set(v) { sp?.edit()?.putString("run_mode", v)?.apply() }

    /** 上次安装的 APK versionCode：用于覆盖安装升级后，首次启动自动重新解压沙盒 */
    var lastVersionCode: Int
        get() = sp?.getInt("last_version_code", 0) ?: 0
        set(v) { sp?.edit()?.putInt("last_version_code", v)?.apply() }

    /** 主题模式：system / light / dark */
    var themeMode: String
        get() = sp?.getString("theme_mode", "system") ?: "system"
        set(v) { sp?.edit()?.putString("theme_mode", v)?.apply() }

    /** 强调色主题键：brand / ocean / violet / deep（对应 themes.xml 中 Theme.DshShell.*） */
    var accentKey: String
        get() = sp?.getString("accent_key", "brand") ?: "brand"
        set(v) { sp?.edit()?.putString("accent_key", v)?.apply() }

    /** 已探测到的可执行 proot 路径（用于绕过 noexec/SELinux 限制） */
    var prootPath: String?
        get() = sp?.getString("proot_path", null)
        set(v) { sp?.edit()?.putString("proot_path", v)?.apply() }

    /** 用户一键更新后的 dsh 版本（优先于内置版本展示） */
    var updatedDshVersion: String?
        get() = sp?.getString("updated_dsh_version", null)
        set(v) { sp?.edit()?.putString("updated_dsh_version", v)?.apply() }

    /** 预览页刷新按钮的左/上边距（可拖动，位置持久化；-1 表示未设置，用默认右下角） */
    var refreshBtnLeft: Int
        get() = sp?.getInt("refresh_btn_left", -1) ?: -1
        set(v) { sp?.edit()?.putInt("refresh_btn_left", v)?.apply() }

    var refreshBtnTop: Int
        get() = sp?.getInt("refresh_btn_top", -1) ?: -1
        set(v) { sp?.edit()?.putInt("refresh_btn_top", v)?.apply() }

    /** 预览页返回按钮的左/上边距（可拖动，位置持久化；-1 表示未设置，用默认左下角） */
    var backBtnLeft: Int
        get() = sp?.getInt("back_btn_left", -1) ?: -1
        set(v) { sp?.edit()?.putInt("back_btn_left", v)?.apply() }

    var backBtnTop: Int
        get() = sp?.getInt("back_btn_top", -1) ?: -1
        set(v) { sp?.edit()?.putInt("back_btn_top", v)?.apply() }

    /** 通用 Int 读写（如上次已读公告版本号） */
    fun getInt(key: String, def: Int): Int = sp?.getInt(key, def) ?: def
    fun setInt(key: String, v: Int) { sp?.edit()?.putInt(key, v)?.apply() }
}
