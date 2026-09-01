package com.deepseek.dshshell.runtime

import android.content.Context
import com.deepseek.dshshell.state.AppState
import com.deepseek.dshshell.state.DshState
import com.deepseek.dshshell.util.Logs
import com.deepseek.dshshell.util.Prefs
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * dsh 进程控制：用 proot 在沙盒内拉起 dsh web，读 stdout 就绪信号，监控退出。
 *
 * 就绪信号来自 dsh web 的 stdout：`dsh web: http://127.0.0.1:3080`。
 * 进程意外退出 → ERROR（可重启）；手动 stop → STOPPED（沙盒保留）。
 */
class ProcessManager(
    private val context: Context,
    private val runtime: RuntimeManager,
) {

    private val procRef = AtomicReference<Process?>()
    @Volatile private var stopping = false
    @Volatile private var reportedReady = false
    /** 自动启动场景的剩余自动重试次数（waitForExit 意外退出时递减，内部重试不重置） */
    @Volatile private var autoRetryLeft = 0

    val isRunning: Boolean get() = procRef.get()?.isAlive == true

    /**
     * 启动 dsh（幂等：已在运行则忽略）。onReady 在主线程回调。
     * @param autoRetry 自动启动场景下 dsh 意外退出时自动重试（最多 MAX_AUTO_RETRIES 次），
     *                  避免首启因残留端口/时序问题启动即退后需要用户手动再次启动。
     */
    fun start(onReady: () -> Unit = {}, autoRetry: Boolean = false) {
        // 顶层启动：重置自动重试预算（内部重试经 startRetry 保留剩余次数）
        autoRetryLeft = if (autoRetry) MAX_AUTO_RETRIES else 0
        startInternal(onReady)
    }

    private fun startInternal(onReady: () -> Unit) {
        val alive = procRef.get()?.isAlive == true
        if (alive) return
        stopping = false
        reportedReady = false
        AppState.update { it.copy(dshState = DshState.STARTING, lastError = null) }

        // 兜底：清理可能残留的 3080 端口占用（如上次会话被强杀遗留的 node），
        // 否则新实例绑定端口会 EADDRINUSE 直接退出 code=1
        killDshProcesses()

        // noexec/SELinux 兜底：启动前重新探测一次 proot 可执行位置
        val proot = runtime.resolveProot()
        Logs.file(
            context,
            "启动 dsh: proot=${proot.absolutePath} exists=${proot.exists()} " +
                "canExec=${proot.canExecute()} size=${proot.length()} " +
                "dir=${runtime.runtimeDir.absolutePath} dirExists=${runtime.runtimeDir.exists()} " +
                "mode=${Prefs.runMode}",
        )
        // 启动前生成宿主 DNS（读 Android 当前网络），供下方 -b 挂载进沙盒 /etc/resolv.conf
        runtime.ensureResolvConf()
        // 确保沙盒内已注入 dsh-market 插件（随 APK assets 携带；幂等，已注入则跳过）
        runtime.installDshMarket()
        val cmd = buildCommand()
        val webSh = File(runtime.runtimeDir, "usr/local/bin/dsh-web.sh")
        Logs.file(context, "web.sh exists=${webSh.exists()} canExec=${webSh.canExecute()}")
        Logs.file(context, "proot 启动: ${cmd.joinToString(" ")}")
        Logs.d("proot 启动: ${cmd.joinToString(" ")}")
        try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(runtime.runtimeDir)
                .apply {
                    val env = environment()
                    env["DSH_RUN_MODE"] = Prefs.runMode
                    // bionic 版 proot 是动态链接：LD_LIBRARY_PATH 指向 nativeLibraryDir
                    //（libtalloc.so / libandroid-shmem.so 所在），PROOT_LOADER 指定 guest
                    // 执行桥。不再需要 GLIBC_TUNABLES（那是静态 glibc proot 的 rseq 规避，
                    // bionic 不注册 rseq，也从根上避开 seccomp 的 SIGSYS(159)）。
                    runtime.applyProotEnv(this)
                }
                .start()
            procRef.set(proc)
            // 递增 dsh 启动代号：预览 WebView 据此在重启后重载页面以匹配新进程（修复重启时预览画面重叠）
            AppState.update { it.copy(dshEpoch = it.dshEpoch + 1) }
            thread(name = "dsh-io") { pump(proc, onReady) }
            thread(name = "dsh-wait") { waitForExit(proc) }
        } catch (e: Exception) {
            Logs.e("启动 dsh 失败", e)
            Logs.file(context, "启动 dsh 失败: ${e.javaClass.simpleName}: ${e.message}")
            procRef.set(null)
            AppState.update {
                it.copy(dshState = DshState.ERROR, lastError = "${e.javaClass.simpleName}: ${e.message ?: "启动失败"}")
            }
        }
    }

    /**
     * 停止 dsh（沙盒保留）。
     *
     * proc.destroy() 只杀 proot，proot 被打断后来不及用 --kill-on-exit 清理 guest，
     * 残留的 node 仍占着 3080 端口，下次启动会 EADDRINUSE（code=1）。
     * 因此这里改为对 proot 进程组先 SIGTERM 后 SIGKILL，连带杀掉 guest dsh，释放端口。
     */
    fun stop() {
        stopping = true
        val proc = procRef.getAndSet(null)
        if (proc == null) {
            // 无跟踪进程：兜底清理残留的 3080 端口占用
            killDshProcesses()
            AppState.update { it.copy(dshState = DshState.STOPPED) }
            return
        }
        try {
            killProcessTree(proc)
        } catch (e: Exception) {
            Logs.e("停止 dsh 失败", e)
        }
        // 兜底：无论进程树清理是否成功，都再按端口/沙盒进程把残留强杀一次，
        // 确保 3080 释放，否则下次启动必 EADDRINUSE（code=1）
        killDshProcesses()
        AppState.update { it.copy(dshState = DshState.STOPPED) }
    }

    /** 重启 dsh（运行中/异常均可）。stop() 已连带杀掉进程组并清理端口，短暂等待后重启。 */
    fun restart(onReady: () -> Unit = {}) {
        thread(name = "dsh-restart") {
            Logs.file(context, "重启 dsh: 停止旧实例")
            stop()
            // 等端口完全释放，避免新实例 EADDRINUSE
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
                return@thread
            }
            Logs.file(context, "重启 dsh: 启动新实例")
            start(onReady)
        }
    }

    /**
     * 结束 proot 及其后代 dsh 进程，释放端口 3080。
     *
     * proc.destroy() 只结束 proot 本体，proot 被打断后来不及用 --kill-on-exit 清理 guest，
     * 残留的 node 仍占着 3080 端口，下次启动会 EADDRINUSE（code=1）。
     * 因此除结束 proot 外，再扫描 /proc 强杀沙盒内残留进程（node/脚本）与端口持有者。
     */
    private fun killProcessTree(proc: Process) {
        // 1) 先给 proot 发 SIGTERM（--kill-on-exit 会连带清理沙盒内进程）
        try {
            proc.destroy()
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }
        // 2) 扫描 /proc 清理：proot / node / 脚本 / 端口持有者
        killDshProcesses()
        // 3) 兜底强杀 proot 本体
        try {
            proc.destroyForcibly()
        } catch (_: Exception) {
        }
    }

    /**
     * 扫描 /proc 强杀所有 dsh 相关进程（proot、沙盒内 node/脚本、占用 3080 的残留进程）。
     * 不依赖 Process.pid()（真机不可用）与 java.nio（Android 未完整实现），
     * 通过 cmdline / exe 路径 + 端口 inode 定位，全部为同 UID 进程，可安全 kill。
     */
    private fun killDshProcesses() {
        try {
            val prootPath = runtime.prootFile.absolutePath
            val runtimePath = runtime.runtimeDir.absolutePath
            val myPid = android.os.Process.myPid()
            val targets = LinkedHashSet<Int>()

            // 端口持有者（残留 node 必然持有 3080）：IPv4 + IPv6 连接表全查
            findPortInodes(PORT).forEach { inode ->
                findPidBySocketInode(inode)?.let { targets.add(it) }
            }

            // 按 cmdline / exe 匹配沙盒进程（排除自身）
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == myPid) return@forEach
                if (isDshProcess(pid, prootPath, runtimePath)) targets.add(pid)
            }

            if (targets.isEmpty()) {
                Logs.file(context, "停止 dsh: 无残留进程需清理")
                return
            }
            Logs.file(context, "停止 dsh: 清理残留进程 ${targets.joinToString(",")}")
            // 先 SIGTERM（给 proot --kill-on-exit 收尾机会），短暂等待后 SIGKILL
            targets.forEach { execQuietly("kill", "-15", it.toString()) }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
            targets.forEach { execQuietly("kill", "-9", it.toString()) }
        } catch (e: Exception) {
            Logs.file(context, "清理 dsh 进程失败: ${e.message}")
        }
    }

    /**
     * 判断 pid 是否为 dsh 相关进程（proot / 沙盒内 node / 脚本）。
     * 注意：沙盒内 node 的 exe 被 proot 改写为 libproot-loader.so、cmdline 是 /usr/bin/node，
     * 因此仅靠 exe 路径匹配不到，需额外匹配 cmdline 里的 node/proot/runtime 关键词。
     */
    private fun isDshProcess(pid: Int, prootPath: String, runtimePath: String): Boolean {
        val cmd = try {
            File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')
        } catch (_: Exception) {
            ""
        }
        if (cmd.contains("proot") || cmd.contains("node") || cmd.contains(runtimePath)) return true
        val exe = try {
            File("/proc/$pid/exe").canonicalPath
        } catch (_: Exception) {
            ""
        }
        return exe.startsWith(runtimePath) || exe == prootPath
    }

    /**
     * 在 /proc/net/tcp 与 /proc/net/tcp6 中查找本地端口对应的 socket inode。
     * dsh 的 webserver 监听 IPv6 双栈，端口记录在 tcp6 里；只查 tcp 会永远定位不到残留 node。
     */
    private fun findPortInodes(port: Int): List<String> {
        val result = mutableListOf<String>()
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val tcp = File(path)
            if (!tcp.exists()) continue
            try {
                tcp.readLines().drop(1).forEach { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 10) {
                        val hexPort = p[1].substringAfter(':', "")
                        val lp = hexPort.toIntOrNull(16) ?: return@forEach
                        if (lp == port) result.add(p[9]) // inode 列
                    }
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    /** 扫描 /proc 下各进程的 fd 目录，找到持有指定 socket inode 的进程 PID */
    private fun findPidBySocketInode(inode: String): Int? {
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return null
        val myPid = android.os.Process.myPid()
        for (d in entries) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val fds = File(d, "fd").listFiles() ?: continue
            for (fd in fds) {
                val target = readLink(fd.absolutePath) ?: continue
                if (target.contains("socket:[$inode]")) return pid
            }
        }
        return null
    }

    /**
     * 读取符号链接目标。socket fd 的链接是 `socket:[inode]`；
     * java.nio 与 File.canonicalPath 在 Android 上对这类特殊链接不可靠，用系统 readlink 命令。
     */
    private fun readLink(path: String): String? = try {
        val proc = ProcessBuilder("/system/bin/readlink", path).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (out.isEmpty() || out.startsWith("readlink:")) null else out
    } catch (_: Exception) {
        null
    }

    private fun execQuietly(vararg args: String) {
        try {
            ProcessBuilder(*args).redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {
            // 失败不阻塞主流程
        }
    }

    /** 读取 stdout：找就绪信号 + 记录日志。 */
    private fun pump(proc: Process, onReady: () -> Unit) {
        try {
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) Logs.file(context, "dsh: $line")
                    if (!reportedReady && line.contains(READY_MARKER)) {
                        reportedReady = true
                        // 已成功就绪：本次启动成功，清除自动重试预算（重试只覆盖启动即退的瞬时故障）
                        autoRetryLeft = 0
                        Logs.file(context, "dsh 就绪")
                        AppState.update { it.copy(dshState = DshState.RUNNING, lastError = null) }
                        MainThread.run { onReady() }
                    }
                }
            }
        } catch (e: Exception) {
            Logs.d("dsh stdout 读取中断: $e")
        }
    }

    /** 监控退出：手动停止 → STOPPED，意外退出 → ERROR（自动启动场景先自动重试）。 */
    private fun waitForExit(proc: Process) {
        try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            return
        }
        if (procRef.get() !== proc) return // 已被 stop() 摘除
        procRef.compareAndSet(proc, null)
        if (stopping) {
            Logs.file(context, "dsh 已停止（手动）")
            AppState.update { it.copy(dshState = DshState.STOPPED) }
            return
        }
        val code = proc.exitValue()
        Logs.e("dsh 退出，code=$code")
        Logs.file(context, "dsh 意外退出，code=$code，可查看上方 dsh 输出定位原因")
        if (autoRetryLeft > 0) {
            autoRetryLeft--
            val left = autoRetryLeft
            Logs.file(context, "dsh 意外退出，自动重启（剩余自动重试 $left 次）")
            AppState.update { it.copy(dshState = DshState.STARTING, lastError = null) }
            thread(name = "dsh-auto-retry") {
                try {
                    // 等待：给残留进程/端口释放留时间
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (stopping) return@thread // 重试期间用户手动停止则放弃
                startRetry()
            }
        } else {
            AppState.update {
                it.copy(
                    dshState = DshState.ERROR,
                    lastError = "dsh 进程退出（code=$code）",
                )
            }
        }
    }

    /** 自动重试启动：保留剩余重试预算，不当作新的一次顶层启动。 */
    private fun startRetry() {
        Logs.file(context, "自动重试启动 dsh（剩余 $autoRetryLeft 次）")
        startInternal {}
    }

    private fun buildCommand(): List<String> {
        val cmd = mutableListOf(
            runtime.prootFile.absolutePath,
            "-r", runtime.runtimeDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            // 显式指定沙盒内工作目录为 /root（缺省 / 在部分 ROM 上路径解析异常）
            "--cwd=/root",
            // proot 退出时连带结束沙盒内 dsh，避免残留进程
            "--kill-on-exit",
        )
        if (Prefs.mountSdcard) {
            cmd += listOf("-b", "/sdcard:/mnt/sdcard")
        }
        // 沙盒内联网依赖 DNS：把宿主生成的 resolv.conf 挂载到 /etc/resolv.conf，
        // 覆盖 rootfs 内置的构建机内网 DNS（10.97.212.201），否则终端解析不了任何域名
        cmd += listOf("-b", "${runtime.resolvConfFile.absolutePath}:/etc/resolv.conf")
        // 沙盒内启动脚本（rootfs 内绝对路径）
        cmd += "/usr/local/bin/dsh-web.sh"
        return cmd
    }

    companion object {
        /** dsh web 就绪信号（stdout 行前缀） */
        const val READY_MARKER = "dsh web: http://127.0.0.1:"
        /** dsh web 端口：残留进程占用它会导致新实例 EADDRINUSE */
        const val PORT = 3080
        /** 自动启动场景下 dsh 意外退出后的自动重试次数 */
        private const val MAX_AUTO_RETRIES = 3
        /** 自动重试前的等待（ms）：给残留进程/端口释放留时间 */
        private const val RETRY_DELAY_MS = 1200L
    }
}

/** 主线程执行器（就绪回调切回主线程） */
private object MainThread {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    fun run(block: () -> Unit) = handler.post(block)
}
