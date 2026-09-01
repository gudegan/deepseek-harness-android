package com.deepseek.dshshell.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.deepseek.dshshell.BuildConfig
import com.deepseek.dshshell.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * App 自更新流程编排：启动检测新版 → 可选弹窗 → 下载（带进度条）→ 触发安装。
 *
 * 非强制：仅当设置页「启动自动检查更新」开启且检测到新版时弹可选对话框，
 * 用户点「立即更新」才下载并进入系统安装界面，点「稍后」则不弹、不下载。
 */
object AppUpdateFlow {

    /** 启动时检测（设置开关开启才执行）；有新版则弹可选对话框，否则静默。 */
    fun checkAndPrompt(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (!Prefs.autoCheckUpdate) return
        val current = BuildConfig.VERSION_NAME
        scope.launch {
            val result = AppUpdater.check()
            val info = result.info
            if (info == null) {
                // 检测失败（网络不可达等）：给出提示，避免与「无更新」混淆
                if (result.error != null) {
                    toast(context, context.getString(R.string.app_update_check_fail, result.error))
                }
                return@launch
            }
            if (!AppUpdater.isNewer(info.versionName, current)) return@launch
            prompt(context, info)
        }
    }

    /** 设置页手动「检查 App 更新」：无论结果都给出明确反馈（有更新→可选下载；已最新/失败→提示）。 */
    fun checkManual(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        val current = BuildConfig.VERSION_NAME
        scope.launch {
            val result = AppUpdater.check()
            val info = result.info
            if (info == null) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.app_update_title)
                    .setMessage(context.getString(R.string.app_update_check_fail, result.error ?: "未知错误"))
                    .setPositiveButton(R.string.log_close, null)
                    .show()
                return@launch
            }
            if (AppUpdater.isNewer(info.versionName, current)) {
                prompt(context, info)
            } else {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.app_update_title)
                    .setMessage(context.getString(R.string.app_update_latest, info.versionName))
                    .setPositiveButton(R.string.log_close, null)
                    .show()
            }
        }
    }

    /** 弹可选更新对话框：立即更新 / 稍后。 */
    private fun prompt(context: Context, info: AppUpdater.UpdateInfo) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.app_update_title))
            .setMessage(
                context.getString(R.string.app_update_found, info.versionName, BuildConfig.VERSION_NAME)
            )
            .setPositiveButton(R.string.app_update_now) { _, _ ->
                downloadAndInstall(context, info)
            }
            .setNegativeButton(R.string.app_update_later, null)
            .show()
    }

    /** 下载 APK（多源回退）→ 安装；下载期间显示进度条对话框。 */
    @SuppressLint("SetTextI18n")
    private fun downloadAndInstall(context: Context, info: AppUpdater.UpdateInfo) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())

        // 进度对话框：标题 + 水平进度条 + 百分比文本
        val tvPercent = TextView(app).apply {
            text = "0%"
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressBar = ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val panel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(tvPercent)
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.app_update_downloading)
            .setView(panel)
            .setCancelable(false)
            .create()
        main.post { dialog.show() }

        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = AppUpdater.download(app, info.apkUrl) { p ->
                    main.post {
                        progressBar.progress = p.coerceIn(0, 100)
                        tvPercent.text = "$p%"
                    }
                }
                main.post {
                    dialog.dismiss()
                    installApk(app, file)
                }
            } catch (e: Exception) {
                main.post {
                    dialog.dismiss()
                    toast(app, app.getString(R.string.app_update_download_fail, e.message ?: ""))
                }
            }
        }
    }

    /** 触发系统安装，必要时先引导授予「安装未知应用」权限。 */
    private fun installApk(context: Context, file: File) {
        // Android 8+ 需「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_update_install_permission_title)
                .setMessage(R.string.app_update_install_permission_hint)
                .setPositiveButton(R.string.app_update_go_grant) { _, _ ->
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } catch (_: Exception) {
                        toast(context, context.getString(R.string.app_update_install_permission_manual))
                    }
                }
                .setNegativeButton(R.string.log_close, null)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            toast(context, context.getString(R.string.app_update_install_fail, e.message ?: ""))
        }
    }

    private fun toast(context: Context, msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
