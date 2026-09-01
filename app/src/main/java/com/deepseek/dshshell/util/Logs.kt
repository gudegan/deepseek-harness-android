package com.deepseek.dshshell.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志：logcat + filesDir/logs/dsh.log（循环覆盖）。
 *  每一条都带可读的毫秒级时间戳（yyyy-MM-dd HH:mm:ss.SSS），
 *  便于在「查看日志」弹窗 / 导出的日志里精确定位每条记录发生的时间。 */
object Logs {
    private const val TAG = "DshShell"
    private const val MAX_BYTES = 8 * 1024 * 1024

    // 供日志文件使用的毫秒级时间戳。SimpleDateFormat 非线程安全，统一同步访问。
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun timestamp(): String = tsFmt.format(Date(System.currentTimeMillis()))

    /** 带可读时间戳的一行（供 logcat 与日志文件统一使用） */
    private fun stamped(msg: String): String = "${timestamp()} $msg"

    fun d(msg: String) = Log.d(TAG, msg)

    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

    fun file(context: Context, msg: String) {
        // 同步写 logcat，便于 adb logcat 完整捕获；logcat 自身也带时间，这里保证内容一致
        Log.d(TAG, msg)
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "dsh.log")
            if (f.length() > MAX_BYTES) {
                val old = File(dir, "dsh.log.1")
                if (f.renameTo(old)) old.delete()
            }
            f.appendText("${timestamp()} $msg\n")
        } catch (_: Exception) {
        }
    }

    /** 新会话（App 进程启动）时刷新运行日志：上个会话的 dsh.log 归档为 dsh.log.old，
     *  使每次重启 APK 后「运行日志」从空开始，同时保留上一会话便于回溯。 */
    fun refresh(context: Context) {
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "dsh.log")
            if (f.exists() && f.length() > 0) {
                val old = File(dir, "dsh.log.old")
                old.delete()
                f.renameTo(old)
            }
        } catch (_: Exception) {
        }
    }
}
