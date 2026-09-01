package com.deepseek.dshshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.deepseek.dshshell.R
import com.deepseek.dshshell.util.Logs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

/** 弹窗展示 filesDir/logs/dsh.log 的最近内容：文字可选中，支持一键复制、一键分享到飞书 */
fun showLogDialog(context: Context) {
    val f = File(context.filesDir, "logs/dsh.log")
    val full = if (f.exists()) f.readText() else context.getString(R.string.log_empty)
    val content = if (full.length > 20000) "…（仅显示末尾 20000 字符）\n\n" + full.takeLast(20000) else full

    val tv = TextView(context).apply {
        text = content
        setTextIsSelectable(true)
        textSize = 12f
        setPadding(48, 24, 48, 24)
    }
    val scroll = ScrollView(context).apply {
        addView(tv)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.6f).toInt(),
        )
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.dash_btn_log)
        .setView(scroll)
        .setNegativeButton(R.string.log_share_feishu) { _, _ ->
            shareLogToFeishu(context, full)
        }
        .setNeutralButton(R.string.log_copy) { _, _ ->
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // 剪贴板经 Binder 传递，超大文本会触发 TransactionTooLargeException 导致闪退，
            // 这里截断到安全长度；完整日志请用「导出」。
            val maxClip = 200_000
            val clipText = if (full.length > maxClip) {
                "…（日志过长，仅复制末尾 ${maxClip} 字符；完整请用「导出」）\n\n" + full.takeLast(maxClip)
            } else {
                full
            }
            cm.setPrimaryClip(ClipData.newPlainText("dsh.log", clipText))
            toast(context, context.getString(R.string.log_copied))
        }
        .setPositiveButton(R.string.log_close, null)
        .show()
}

/** 一键分享到飞书：优先唤醒飞书 App 接收日志文本；未安装/不支持时回退系统分享面板。
 *  优先用 Activity 上下文启动（最可靠）；并把每一步写进运行日志（便于确认触发与诊断）。 */
fun shareLogToFeishu(context: Context, text: String) {
    val cap = 500_000
    val body = if (text.length > cap) {
        "…（日志过长，仅分享末尾 ${cap} 字符；完整请用「导出」）\n\n" + text.takeLast(cap)
    } else {
        text
    }
    Logs.file(context, "分享到飞书：请求分享（日志长度 ${text.length}，实际 ${body.length}）")

    val act = context as? android.app.Activity
    fun launch(intent: Intent): Boolean {
        return try {
            if (act != null) {
                act.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.applicationContext.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            Logs.file(context, "分享到飞书：启动失败 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // 先定向唤起飞书（按常见包名依次尝试）
    for (pkg in listOf("com.ss.android.lark", "com.larksuite.mobile", "com.bytedance.lark")) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DeepSeek Harness 运行日志")
            putExtra(Intent.EXTRA_TEXT, body)
            setPackage(pkg)
        }
        if (launch(send)) {
            Logs.file(context, "分享到飞书：已唤起飞书（$pkg）")
            toast(context, "已唤起飞书，请在飞书中选择发送")
            return
        } else {
            Logs.file(context, "分享到飞书：$pkg 不可用，尝试下一源")
        }
    }

    // 回退：系统分享面板（列出可接收的应用，选飞书即可）
    val chooser = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DeepSeek Harness 运行日志")
            putExtra(Intent.EXTRA_TEXT, body)
        },
        context.getString(R.string.log_share_feishu),
    )
    if (launch(chooser)) {
        Logs.file(context, "分享到飞书：已打开系统分享面板")
        toast(context, "请选择飞书完成分享")
    } else {
        Logs.file(context, "分享到飞书：系统分享面板也无法打开")
        toast(context, "分享失败：未找到可接收的应用")
    }
}
