package com.deepseek.dshshell.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.deepseek.dshshell.MainActivity
import com.deepseek.dshshell.R
import com.deepseek.dshshell.runtime.ProcessManager
import com.deepseek.dshshell.runtime.RuntimeManager
import com.deepseek.dshshell.state.AppState
import com.deepseek.dshshell.state.DshState
import com.deepseek.dshshell.state.SandboxState
import com.deepseek.dshshell.util.Logs
import com.deepseek.dshshell.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台服务：常驻保活 dsh 进程，并作为 RuntimeManager/ProcessManager 的宿主。
 *
 * 通过 Action 接收外壳命令（解压/启停/重启 dsh），状态经 AppState(StateFlow) 下发 UI。
 * ACTION_BOOT 时按"自动启动"设置执行 解压 → 启动 dsh。
 */
class HarnessService : Service() {

    private lateinit var runtime: RuntimeManager
    private lateinit var process: ProcessManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var booted = false

    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeManager(this)
        process = ProcessManager(this, runtime)
        createChannel()
        startInForeground()
        // 状态变化 → 刷新通知
        AppState.ui
            .onEach { updateNotification(it.dshState) }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXTRACT -> ensureExtracted()
            ACTION_START_DSH -> startDsh()
            ACTION_STOP_DSH -> process.stop()
            ACTION_RESTART_DSH -> restartDsh()
            ACTION_UPDATE_DSH -> startDshUpdate()
            ACTION_SHUTDOWN -> shutdown()
            ACTION_BOOT, null -> bootstrap() // null：进程被杀后系统重启服务，同样执行自启
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        process.stop()
        super.onDestroy()
    }

    /** 首次进入：同步状态；若开了"自动启动"则 解压 → 启动 dsh */
    private fun bootstrap() {
        if (booted) return
        booted = true
        // 覆盖安装升级检测：升级后首次启动自动触发重新解压沙盒（保留用户数据）
        runtime.handleUpgradeIfNeeded()
        syncInitialState()
        if (Prefs.autoStart) {
            // 自动启动：dsh 意外退出时自动重试，避免首启因残留端口/时序问题启动即退后需要手动再次启动
            ensureExtracted { startDsh(autoRetry = true) }
        }
    }

    /** 把文件系统上的实际状态同步进 AppState（进程被系统回收重启服务后恢复 UI） */
    private fun syncInitialState() {
        val state = AppState.ui.value
        val sandboxState = when {
            runtime.sandboxReady -> SandboxState.READY
            runtime.needsExtract() -> SandboxState.UNEXTRACTED
            else -> SandboxState.UNEXTRACTED
        }
        AppState.update {
            it.copy(
                sandboxState = sandboxState,
                sandboxVersion = runtime.installedVersion ?: runtime.builtinVersion,
                dshState = if (process.isRunning) DshState.RUNNING else state.dshState,
            )
        }
    }

    /** 需要则解压（幂等），完成回调在后台线程 */
    private fun ensureExtracted(onDone: () -> Unit = {}) {
        if (runtime.sandboxReady && !runtime.needsExtract()) {
            AppState.update {
                it.copy(
                    sandboxState = SandboxState.READY,
                    sandboxVersion = runtime.installedVersion ?: runtime.builtinVersion,
                )
            }
            onDone()
            return
        }
        runtime.startExtraction(onDone = { onDone() }, onError = { /* 状态已由 RuntimeManager 更新 */ })
    }

    /** 启动 dsh；autoRetry=true 时 dsh 意外退出自动重试（用于自动启动路径） */
    private fun startDsh(autoRetry: Boolean = false) {
        if (!runtime.sandboxReady || runtime.needsExtract()) {
            // 沙盒未就绪：先解压再启动
            ensureExtracted { process.start(autoRetry = autoRetry) }
            return
        }
        process.start(autoRetry = autoRetry)
    }

    private fun restartDsh() {
        if (!runtime.sandboxReady) {
            ensureExtracted { process.start() }
            return
        }
        process.restart()
    }

    /**
     * 一键更新 dsh：停 dsh → 沙盒内 npm 更新（多源回退）→ 重启 dsh 使新版本生效。
     * 进度与结果经 AppState.updating / updateMessage 下发 UI。
     */
    private fun startDshUpdate() {
        if (AppState.ui.value.updating) return // 防并发
        Logs.file(this, "开始一键更新 dsh")
        AppState.update { it.copy(updating = true, updateMessage = null, lastError = null) }
        scope.launch(Dispatchers.IO) {
            // 先停掉运行中的 dsh，避免占用文件/端口导致更新或重启失败
            process.stop()
            val outcome = runtime.runDshUpdate()
            withContext(Dispatchers.Main) {
                AppState.update { it.copy(updating = false, updateMessage = outcome.message) }
                if (outcome.ok) {
                    Logs.file(this@HarnessService, "一键更新成功: ${outcome.message}")
                    // 重启 dsh 加载新版本
                    if (runtime.sandboxReady) process.start()
                } else {
                    Logs.file(this@HarnessService, "一键更新失败: ${outcome.message}")
                }
            }
        }
    }

    /** 关闭沙盒：停止 dsh + 停止前台服务（沙盒目录保留，下次启动秒启） */
    private fun shutdown() {
        process.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- 前台通知 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun startInForeground() {
        val n = buildNotification(AppState.ui.value.dshState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(dsh: DshState): Notification {
        val content = when (dsh) {
            DshState.RUNNING -> getString(R.string.notif_running)
            DshState.STARTING -> getString(R.string.notif_starting)
            DshState.ERROR -> getString(R.string.notif_error)
            DshState.STOPPED -> getString(R.string.notif_stopped)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(dsh: DshState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(dsh))
    }

    companion object {
        private const val CHANNEL_ID = "harness"
        private const val NOTIF_ID = 1

        const val ACTION_BOOT = "com.deepseek.dshshell.action.BOOT"
        const val ACTION_EXTRACT = "com.deepseek.dshshell.action.EXTRACT"
        const val ACTION_START_DSH = "com.deepseek.dshshell.action.START_DSH"
        const val ACTION_STOP_DSH = "com.deepseek.dshshell.action.STOP_DSH"
        const val ACTION_RESTART_DSH = "com.deepseek.dshshell.action.RESTART_DSH"
        const val ACTION_UPDATE_DSH = "com.deepseek.dshshell.action.UPDATE_DSH"
        const val ACTION_SHUTDOWN = "com.deepseek.dshshell.action.SHUTDOWN"

        fun start(context: Context, action: String) {
            val i = Intent(context, HarnessService::class.java).setAction(action)
            if (action == ACTION_BOOT) {
                context.startForegroundService(i)
            } else {
                try {
                    context.startService(i)
                } catch (_: Exception) {
                    context.startForegroundService(i)
                }
            }
        }
    }
}
