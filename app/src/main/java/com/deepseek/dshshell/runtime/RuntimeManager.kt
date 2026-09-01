package com.deepseek.dshshell.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.deepseek.dshshell.BuildConfig
import com.deepseek.dshshell.state.AppState
import com.deepseek.dshshell.state.SandboxState
import com.deepseek.dshshell.util.Logs
import com.deepseek.dshshell.util.Prefs
import com.deepseek.dshshell.util.UpdateChecker
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 沙盒（rootfs）生命周期：一次性解压 + 版本管理 + 原子切换。
 *
 * 目录布局（app 私有目录 filesDir）：
 *   runtime/          解压后的 rootfs（沙盒 / 目录）
 *   runtime/.runtime-version  已解压版本标记
 *   proot             宿主侧 proot 静态二进制（可执行）
 *   logs/dsh.log      dsh 日志
 *
 * 可执行位（error=13 / noexec / W^X）：Android 对 targetSdk>=29 的 app 进程强制
 * W^X（app_data_file 不可 execve），因此 proot 以原生库 libproot.so 打进 APK，
 * 安装时由系统解压到 nativeLibraryDir（untrusted_app 唯一允许 execve 的目录）。
 * 其余候选目录（files/cache/codeCache）作回退——多数 ROM 未强制 W^X 时同样可执行。
 */
class RuntimeManager(private val context: Context) {

    private val base: File get() = context.filesDir
    val runtimeDir: File get() = File(base, "runtime")
    private val markerFile: File get() = File(base, ".runtime-version")
    /** 重解压时暂存用户家目录的备份位置（filesDir/runtime-user-backup） */
    private val userBackupDir: File get() = File(base, "runtime-user-backup")
    /** 宿主侧生成的 resolv.conf，proot -b 挂载进沙盒 /etc/resolv.conf 以打通 DNS */
    val resolvConfFile: File get() = File(base, "resolv.conf")
    private val assets get() = context.assets
    @Volatile private var extracting = false

    /** 系统解压的原生库目录（SELinux 允许 execve 的目录） */
    val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir

    /** bionic 版 proot 主程序（nativeLibraryDir/libproot.so），动态链接，随 APK 打包 */
    val nativeLibProot: File?
        get() = try {
            val f = File(nativeLibDir, "libproot.so")
            if (f.exists() && f.isFile) f else null
        } catch (_: Exception) {
            null
        }

    /** proot 的静态 loader（PROOT_LOADER），proot 用它执行 guest 二进制 */
    private val nativeLibLoader: File?
        get() = try {
            val f = File(nativeLibDir, "libproot-loader.so")
            if (f.exists() && f.isFile) f else null
        } catch (_: Exception) {
            null
        }

    /** proot 依赖库目录（libtalloc.so / libandroid-shmem.so 与 proot 同目录） */
    private val prootLibDir: String get() = nativeLibDir

    /** PROOT_TMP_DIR：proot 临时目录（app 私有目录内） */
    private val prootTmpDir: File get() = File(base, "proot-tmp")

    /** 组装 proot 运行所需环境变量（动态 bionic proot 缺一不可） */
    fun applyProotEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = prootLibDir
        env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        nativeLibLoader?.let { env["PROOT_LOADER"] = it.absolutePath }
        // 沙盒内 PATH：dsh-web.sh 里 `exec node` 靠 PATH 找到 /usr/local/bin/node
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // Android 宿主环境的 TMPDIR 指向 /data/user/0/.../cache（宿主路径），
        // 泄漏进 proot 后沙盒内该路径不存在，dsh 的 spill-local 插件 mkdtemp 报 ENOENT 退出。
        // 覆盖为沙盒内已存在的 /tmp（debootstrap rootfs 自带 1777 可写）。
        env["TMPDIR"] = "/tmp"
        env["TMP"] = "/tmp"
        env["TEMP"] = "/tmp"
        // PROOT_TMP_DIR 必须先创建，否则 proot 启动即报
        // "can't canonicalize .../files/proot-tmp: No such file or directory"
        try {
            prootTmpDir.mkdirs()
        } catch (_: Exception) {
        }
    }

    /**
     * 生成宿主侧 resolv.conf：读取 Android 当前网络的 DNS 服务器，拿不到时回退公网 DNS。
     * rootfs 内置 /etc/resolv.conf 是构建机内网 IP（打包残留），手机端解析不了，
     * 故用 proot -b 把宿主这份挂载进沙盒，打通沙盒内联网。
     */
    fun ensureResolvConf() {
        val dns = queryDnsServers()
        try {
            resolvConfFile.writeText(dns.joinToString("") { "nameserver $it\n" })
        } catch (e: Exception) {
            Logs.file(context, "写 resolv.conf 失败: ${e.message}")
        }
        Logs.file(context, "resolv.conf <- ${dns.joinToString(",")}")
    }

    private fun queryDnsServers(): List<String> {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return fallbackDns()
            val lp = cm.getLinkProperties(network) ?: return fallbackDns()
            val dns = lp.dnsServers.mapNotNull { it.hostAddress }
            if (dns.isNotEmpty()) return dns
        } catch (_: Exception) {
        }
        return fallbackDns()
    }

    private fun fallbackDns(): List<String> = listOf("223.5.5.5", "119.29.29.29")

    /** 默认 proot 位置（优先已探测到的可执行路径） */
    val prootFile: File
        get() {
            val cached = Prefs.prootPath
            if (cached != null) {
                val f = File(cached)
                if (f.exists() && f.canExecute()) return f
            }
            nativeLibProot?.let { if (it.canExecute()) return it }
            return File(nativeLibDir, "libproot.so")
        }

    /** proot 候选目录：动态 bionic proot 依赖同目录的 3 个 companion 库，
     *  只能从 nativeLibraryDir 运行（app 私有目录 W^X 不可 execve） */
    private fun prootCandidates(): List<File> = listOfNotNull(nativeLibProot)

    /** 探测某个 proot 二进制是否能真正 execve（-V 只打印版本，无副作用） */
    private fun probeExecutable(f: File): Boolean {
        if (!f.exists() || !f.canExecute()) {
            Logs.file(context, "proot 探测 ${f.absolutePath}: 不存在或不可执行")
            return false
        }
        return try {
            val p = ProcessBuilder(f.absolutePath, "-V")
                .redirectErrorStream(true)
                // 动态 bionic proot 依赖同目录的 libtalloc.so / libandroid-shmem.so，
                // 必须通过 LD_LIBRARY_PATH 提供给 linker64，否则 -V 直接加载失败
                .apply { applyProotEnv(this) }
                .start()
            val done = p.waitFor(5, TimeUnit.SECONDS)
            val ok = if (!done) {
                p.destroyForcibly()
                false
            } else {
                p.exitValue() == 0
            }
            Logs.file(
                context,
                "proot 探测 ${f.absolutePath}: LD_LIBRARY_PATH=$prootLibDir " +
                    "PROOT_LOADER=${nativeLibLoader?.absolutePath ?: "缺"} " +
                    "result=${if (ok) "OK" else "FAIL"} exit=${if (done) p.exitValue() else "timeout"}",
            )
            ok
        } catch (e: Exception) {
            Logs.file(context, "proot 探测 ${f.absolutePath} 异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 遍历候选并探测，返回第一个可执行的（缓存到 Prefs） */
    fun resolveProot(): File {
        val resolved = prootCandidates().firstOrNull { probeExecutable(it) }
        if (resolved != null) {
            Prefs.prootPath = resolved.absolutePath
            return resolved
        }
        // 全部失败：退回默认位置，便于 UI 报错与日志诊断
        Prefs.prootPath = null
        return File(nativeLibDir, "libproot.so")
    }

    /** 记录数据目录挂载信息（排查 error=13 / noexec 用） */
    fun logMountDiagnostics() {
        try {
            val target = base.absolutePath
            val mounts = File("/proc/mounts").readLines()
            val hit = mounts.filter { l -> l.contains("/data") || target.startsWith(l.split(" ")[1]) }
            hit.take(8).forEach { l ->
                Logs.file(context, "mount: $l")
            }
            Logs.file(
                context,
                "proot 候选: ${prootCandidates().joinToString(" | ") { "${it.absolutePath}(${it.exists()})" }}",
            )
        } catch (e: Exception) {
            Logs.file(context, "mount 诊断失败: ${e.message}")
        }
    }

    /** 启动前记录沙盒关键路径诊断（验证符号链接解压是否生效，定位 execve/ENOTDIR 失败） */
    fun logGuestDiagnostics() {
        val probes = listOf(
            "/bin" to "根/bin(→usr/bin)",
            "/usr/bin" to "usr/bin",
            "/bin/sh" to "bin/sh(启动脚本 shebang)",
            "/usr/bin/dash" to "dash(实际 shell)",
            "/usr/local/bin" to "usr/local/bin",
            "/usr/local/bin/node" to "node",
            "/usr/local/bin/dsh-web.sh" to "dsh-web.sh",
            "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" to "dsh bin.js",
            "/lib" to "根/lib(→usr/lib)",
            "/usr/lib/aarch64-linux-gnu" to "arm64 运行库目录",
            "/root" to "root 家目录(--cwd)",
        )
        probes.forEach { (rel, label) ->
            val f = File(runtimeDir, rel.trimStart('/'))
            val type = when {
                !f.exists() -> "缺失"
                f.isDirectory -> "目录"
                else -> "文件"
            }
            val isLink = try { Files.isSymbolicLink(f.toPath()) } catch (_: Exception) { false }
            Logs.file(
                context,
                "沙盒 $label: $rel exists=${f.exists()} type=$type symlink=$isLink " +
                    "canExec=${f.canExecute()} size=${f.length()}",
            )
        }
        // dsh-web.sh 头部（校验 shebang 与启动命令）
        val sh = File(runtimeDir, "usr/local/bin/dsh-web.sh")
        if (sh.isFile) {
            val head = sh.readText().lineSequence().take(5).joinToString(" | ")
            Logs.file(context, "dsh-web.sh 头部: $head")
        }
    }

    /** APK 内置 runtime 版本（assets/runtime/runtime.version） */
    val builtinVersion: String?
        get() = try {
            assets.open("runtime/runtime.version").bufferedReader().use { it.readLine()?.trim()?.ifEmpty { null } }
        } catch (_: Exception) {
            null
        }

    /** 已解压版本（.runtime-version 标记） */
    val installedVersion: String?
        get() = try {
            markerFile.readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }

    val sandboxReady: Boolean get() = runtimeDir.isDirectory && markerFile.exists() && prootFile.exists()

    /** 当前生效的 dsh 版本（优先用户一键更新后的版本） */
    fun currentDshVersion(): String? {
        val upd = Prefs.updatedDshVersion
        if (!upd.isNullOrBlank()) return upd
        return UpdateChecker.embeddedDshVersion(builtinVersion) ?: builtinVersion
    }

    /** 是否需要（重新）解压：标记缺失或版本不一致 */
    fun needsExtract(): Boolean {
        if (!runtimeDir.isDirectory) return true
        if (!markerFile.exists()) return true
        if (!prootFile.exists()) return true
        val builtin = builtinVersion
        if (builtin != null && builtin != installedVersion) {
            // 用户已一键更新过 dsh：不再因版本标记差异强制重解压（否则会清掉新装的 dsh）
            if (Prefs.updatedDshVersion == null) return true
        }
        return false
    }

    /** 后台线程解压；onDone/onError 回调在后台线程，UI 侧自行切主线程 */
    fun startExtraction(onDone: () -> Unit, onError: (String) -> Unit) {
        if (extracting) return // 防并发重复解压
        extracting = true
        thread(name = "runtime-extract") {
            val start = SystemClock.elapsedRealtime()
            try {
                AppState.update {
                    it.copy(sandboxState = SandboxState.EXTRACTING, extractProgress = 0f, lastError = null)
                }
                extractToTmp()
                atomicCommit()
                Logs.file(context, "runtime 就绪 v=${builtinVersion} 耗时=${SystemClock.elapsedRealtime() - start}ms")
                AppState.update {
                    it.copy(
                        sandboxState = SandboxState.READY,
                        extractProgress = 1f,
                        sandboxVersion = builtinVersion,
                    )
                }
                onDone()
            } catch (e: Exception) {
                Logs.e("解压失败", e)
                AppState.update { it.copy(sandboxState = SandboxState.UNEXTRACTED, lastError = e.message) }
                onError(e.message ?: "解压失败")
            } finally {
                extracting = false
            }
        }
    }

    /** 删除已解压沙盒（设置页"清除沙盒数据"），下次启动重新解压。
     *  proot 原生库由系统管理（nativeLibraryDir），不在此删除。 */
    /** 覆盖安装升级检测：只要当前沙盒已被旧版本解压过（解压标记存在）且版本号与上次记录不一致，
     *  就删除解压标记，使下次启动自动重新解压沙盒（/root、/home 用户数据经 backup/restore 迁回，不丢失）。
     *  注意：不能用 last>0 判断——lastVersionCode 字段可能在旧版本从未写入（last=0），
     *  但沙盒其实已解压，仍需检测到版本变化并重解压（这是覆盖 5.1→5.2 未重解压的原因）。 */
    fun handleUpgradeIfNeeded() {
        val current = BuildConfig.VERSION_CODE
        val last = try { Prefs.lastVersionCode } catch (_: Exception) { 0 }
        try {
            val sandboxInit = markerFile.exists()
            if (sandboxInit && current != last) {
                Logs.file(context, "版本变化 v$last→v$current 且沙盒已解压：触发重新解压沙盒（保留用户数据）")
                // 升级时清理一次"历史已移除预装插件/技能"的残留（如飞书），避免旧数据随重解压保留后仍显示。
                // 只在升级时机执行一次，不每次启动都跑；且仅删本文件 removedPlugins 清单项，不影响用户手动装的插件。
                purgeRemovedPreinstalls()
                markerFile.delete()
                File(base, "runtime.tmp").deleteRecursively()
            } else if (!sandboxInit) {
                // 沙盒尚未解压（首次安装），无需标记，正常走首启解压
                Logs.file(context, "沙盒未解压（疑似首次安装），按正常流程解压")
            }
        } catch (e: Exception) {
            Logs.e("升级检测失败", e)
        } finally {
            Prefs.lastVersionCode = current
        }
    }

    fun clearSandbox() {
        runtimeDir.deleteRecursively()
        markerFile.delete()
        File(base, "runtime.tmp").deleteRecursively()
        userBackupDir.deleteRecursively()
        Prefs.prootPath = null
        AppState.update { it.copy(sandboxState = SandboxState.UNEXTRACTED, sandboxVersion = null) }
    }

    /**
     * 把 dsh 恢复到出厂默认：移除所有用户 profile（自装/自研插件层）与 home 级
     * cordis.patch.yml 用户配置层，用于修复因自装/自研插件或配置改动导致的无法启动。
     *
     * 保留：API Key（.credentials.yaml）、会话、工作区以及所有产出的文件/文件夹。
     *
     * @return (成功?, 说明)
     */
    fun resetDshToDefault(): Pair<Boolean, String> {
        val dshHome = File(runtimeDir, "root/.dsh")
        if (!dshHome.isDirectory) {
            Logs.file(context, "恢复 dsh 默认配置：沙盒 dsh 目录不存在，无需重置")
            return true to "沙盒 dsh 目录不存在，无需重置"
        }
        try {
            val removed = mutableListOf<String>()
            // 移除所有用户 profile（含自装/自研插件层）—— 插件导致的启动失败靠这一步修复。
            val profiles = File(dshHome, "profiles")
            if (profiles.exists()) {
                if (profiles.deleteRecursively()) removed.add("profiles")
                else Logs.file(context, "恢复 dsh 默认设置：删除 profiles 失败")
            }
            // 删除 home 级用户配置层 cordis.patch.yml
            val homePatch = File(dshHome, "cordis.patch.yml")
            if (homePatch.exists() && homePatch.delete()) removed.add("cordis.patch.yml")
            // 保留：API Key(.credentials.yaml)、会话、工作区与产出文件（均在 $DSH_HOME 的非 profiles 区域 / $HOME）
            Logs.file(context, "恢复 dsh 默认设置：移除 ${removed.size} 项（${removed.joinToString("、")}）")
            return true to removed.joinToString("、").ifEmpty { "无自装插件/配置" }
        } catch (e: Exception) {
            Logs.e("恢复 dsh 默认设置失败", e)
            return false to (e.message ?: "未知错误")
        }
    }

    /**
     * 把 dsh-market 插件注入沙盒（随 APK assets 携带，避免改动 100M runtime.tar.xz 破坏符号链接）。
     * 在解压后 / 每次启动 dsh 前调用，幂等。注入内容：
     *   - dshmarket bundle 与其缺失依赖 → dsh/node_modules（dshmarket / undici / schemastery / cosmokit / standard-schema）
     *   - 把 dshmarket 加入 web profile 的 dsh.profile.bundles（bundle 机制：server 插件 + 前端 client 一起加载）
     * 全部 try/catch 兜底：注入失败不影响 dsh 启动。
     */
    fun installDshMarket() {
        try {
            // 先对 profiles/node_modules 做一次坏残留自愈：旧版曾把 dsh 自带的 js-yaml/argparse/cordis 等
            // 拷成真实目录（而它们本应由 healProfilesModuleFallback 以符号链接提供），残留会导致 dsh 启动报
            // 「js-yaml exists and is not a symlink」；删除后 dsh 启动时会重建正确的 symlink，升级路径不再需要
            // 手动清沙盒数据。白名单（随 APK 拷入的预装插件）与已有符号链接一律保留。
            healProfilesNodeModules()
            // dsh 的 cordis loader 是从 profile 目录 import 各 bundle 的，因此 dshmarket 及其依赖必须放进
            // 「flat module fallback」目录 $DSH_HOME/profiles/node_modules（healProfilesModuleFallback 会把
            // cordis / dsh-settings 等 dsh 依赖也 symlink 进来），dsh 的 loader 才能解析到并加载 dshmarket。
            val profileNm = File(runtimeDir, "root/.dsh/profiles/node_modules")
            // 每次先清旧再拷；但「属 dsh 自带依赖闭包」的包（schemastery/cosmokit 等）绝不能拷贝成真实目录
            // —— dsh 启动会在 profiles/node_modules 为它们建符号链接，真实目录会报
            // "xxx exists and is not a symlink"。这类包交给 dsh 自愈（copyPluginAsset 会自动跳过）。
            copyPluginAsset(profileNm, "dsh-market/dshmarket", File(profileNm, "dshmarket"))
            copyPluginAsset(profileNm, "dsh-market/undici", File(profileNm, "undici"))
            copyPluginAsset(profileNm, "dsh-market/@deepseek-ai/schemastery", File(profileNm, "@deepseek-ai/schemastery"))
            copyPluginAsset(profileNm, "dsh-market/@deepseek-ai/cosmokit", File(profileNm, "@deepseek-ai/cosmokit"))
            copyPluginAsset(profileNm, "dsh-market/@standard-schema/spec", File(profileNm, "@standard-schema/spec"))

            // 预装 dsh-zen-remote（移动端适配开源插件）：插件包 + 其 web-push 依赖树装入 profiles/node_modules
            copyPluginAsset(profileNm, "dsh-zen-remote", File(profileNm, "dsh-zen-remote"))
            val zenDeps = try { assets.list("dsh-zen-remote-deps") } catch (_: Exception) { null } ?: emptyArray()
            for (name in zenDeps) copyPluginAsset(profileNm, "dsh-zen-remote-deps/$name", File(profileNm, name))

            // 预装本地插件包：记忆插件（memory-evolve 已补 cordis.patch.yml 以便作为 bundle 加载）
            copyPluginAsset(profileNm, "dsh-memory-evolve", File(profileNm, "dsh-memory-evolve"))

            // 通过 bundle 机制把 dshmarket、dsh-zen-remote、dsh-memory-evolve 加入 web profile 的
            // dsh.profile.bundles：dsh 启动时才同时加载其 server 插件与前端 client。
            ensureWebProfileHasMarket()
            Logs.file(context, "dshmarket bundle 注入完成（bundles 已含 dshmarket，依赖装入 profiles/node_modules）")
        } catch (e: Exception) {
            Logs.e("dshmarket 注入失败", e)
        }
    }

    /**
     * 清理沙盒里旧版（含飞书插件的版本）残留的飞书插件/技能。
     *
     * 重解压沙盒时会保留用户家目录 /root（会话、工作区、API Key 等），因此上一版拷入：
     *  - /root/.dsh/skills 下的飞书技能（lark-base/lark-doc/...，来自 skills-lark）
     *  - /root/.dsh/profiles/node_modules 下的飞书插件（dsh-feishu-auth、dsh-client-ui-feishu-auth、@larksuite）
     * 会残留在沙盒，导致 DSH 设置页/技能页仍显示飞书项。本方法把这些旧残留删除，幂等。
     */
    /**
     * 清理"历史曾预装、现已被移除"的预装插件/技能残留。
     *
     * 重解压沙盒会保留用户家目录 /root（会话、工作区、API Key 等），因此上一版随 APK 预装、
     * 但当前版本已移除的插件/技能，其文件仍残留在 /root/.dsh 下，导致 DSH 设置页/技能页显示旧内容。
     *
     * 重要：这里只删除本文件 REMOVED_PREINSTALLS 明确列出的"已移除预装"目录，
     * 不会删除用户通过 dshmarket 手动安装的插件（那些记录在 profile 的 dependencies，不在此清单）。
     * 未来若要移除其它预装插件，把其包名加入 REMOVED_PREINSTALLS 即可。
     */
    private fun purgeRemovedPreinstalls() {
        // v0.5.20 移除：飞书授权插件 + 飞书设置页 + lark-cli（@larksuite/cli）预装
        val removedPlugins = listOf("dsh-feishu-auth", "dsh-client-ui-feishu-auth", "@larksuite")
        // 历史预装的飞书技能（skills-lark 拷入的 lark-* 目录）
        val removedSkillPrefix = "lark-"
        val skillsDir = File(runtimeDir, "root/.dsh/skills")
        if (skillsDir.isDirectory) {
            skillsDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(removedSkillPrefix)) {
                    try {
                        f.deleteRecursively()
                        Logs.file(context, "清理已移除预装技能 ${f.name}")
                    } catch (_: Exception) { /* 忽略 */ }
                }
            }
        }
        val pnm = File(runtimeDir, "root/.dsh/profiles/node_modules")
        if (pnm.isDirectory) {
            for (name in removedPlugins) {
                val d = File(pnm, name)
                if (d.exists()) {
                    try {
                        d.deleteRecursively()
                        Logs.file(context, "清理已移除预装插件 $name")
                    } catch (_: Exception) { /* 忽略 */ }
                }
            }
        }
    }

    /**
     * 洁净 profiles/node_modules：坏残留自愈（升级路径专属修复）。
     *
     * profiles/node_modules 是 cordis loader 加载预装插件的「flat module fallback」目录，里面只应有两类东西：
     *  ① 随 APK 拷入、需保留的预装插件（白名单，真实目录）；
     *  ② dsh 启动时由 healProfilesModuleFallback 生成的依赖符号链接。
     * 旧版本曾把 dsh 自带的 js-yaml/argparse/cordis 等拷成「真实目录」，导致 dsh 启动报
     * 「js-yaml exists and is not a symlink」。本方法把：白名单之外 + 真实目录（非 symlink） +
     * 且确实属于 dsh 依赖闭包（$DSH_HOME 之外 dsh/node_modules 下能找到，heal 会重新 symlink 回来）
     * 的坏残留删除，交给 dsh 启动时重建正确符号链接。幂等，每次启动 dsh 前调用。
     */
    private fun healProfilesNodeModules() {
        val profilesNm = File(File(runtimeDir, "root/.dsh/profiles"), "node_modules")
        if (!profilesNm.isDirectory) return
        // dsh 安装的依赖闭包目录：healProfilesModuleFallback 据它把 dsh 依赖 symlink 进 profiles/node_modules
        val dshNm = File(File(runtimeDir, "usr/local/lib/node_modules/@deepseek-ai/dsh"), "node_modules")

        fun purge(path: File, rel: String) {
            // 已是符号链接 → heal 生成的正常依赖，保留
            val isLink = try { Files.isSymbolicLink(path.toPath()) } catch (_: Exception) { false }
            if (isLink || !path.isDirectory) return
            // 只清理「属于 dsh 依赖闭包、却被拷成真实目录」的条目（dsh 启动时会为它们重建符号链接）。
            // 白名单外来的预装插件（dshmarket/zen/memory 等）不在 dsh 闭包内，天然保留。
            val inDshClosure = try { File(dshNm, rel).exists() } catch (_: Exception) { false }
            if (!inDshClosure) return
            try {
                path.deleteRecursively()
                Logs.file(context, "坏残留清理: profiles/node_modules/$rel（属 dsh 自带依赖且被拷成真实目录，改交由 dsh heal 重建 symlink）")
            } catch (e: Exception) {
                Logs.file(context, "坏残留清理失败 $rel: ${e.message}")
            }
        }

        profilesNm.listFiles()?.forEach { top ->
            val name = top.name
            if (name.startsWith("@")) {
                // scope 目录（如 @deepseek-ai）：进内部逐包处理，scope 目录本体不动
                if (top.isDirectory && !try { Files.isSymbolicLink(top.toPath()) } catch (_: Exception) { false }) {
                    top.listFiles()?.forEach { sub -> purge(sub, "@${name.substring(1)}/${sub.name}") }
                }
            } else {
                purge(top, name)
            }
        }
    }

    /** 目标包是否属于 dsh 自带依赖闭包（dsh/node_modules 下能查到）。
     *  dsh 的 healProfilesModuleFallback 会为这类包在 profiles/node_modules 建符号链接；
     *  若被我们拷成真实目录，dsh 启动会报 "xxx exists and is not a symlink"，因此这类包应交给 dsh 自愈。 */
    private fun isDshOwnedModule(rel: String): Boolean = try {
        File(File(runtimeDir, "usr/local/lib/node_modules/@deepseek-ai/dsh"), "node_modules/$rel").exists()
    } catch (_: Exception) {
        false
    }

    /** 把预装资源拷入 profiles/node_modules；若目标包属 dsh 自带依赖闭包则跳过拷贝（交给 dsh 建符号链接），避免真实目录冲突。 */
    private fun copyPluginAsset(profileNm: File, assetDir: String, dest: File) {
        val rel = try {
            profileNm.toPath().relativize(dest.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            dest.name
        }
        if (isDshOwnedModule(rel)) {
            Logs.file(context, "预装依赖 $rel 属 dsh 自带依赖，由 dsh 启动时以符号链接提供，跳过拷贝")
            return
        }
        copyAssetClean(assetDir, dest)
    }

    /** 确保 web profile 存在且 dsh.profile.bundles 含 dshmarket（保留 base/web-app），并补齐 profile 骨架 */
    private fun ensureWebProfileHasMarket() {
        val dshHome = File(runtimeDir, "root/.dsh")
        val profiles = File(dshHome, "profiles")
        profiles.mkdirs()
        val webDir = File(profiles, "web")
        webDir.mkdirs()
        val manifestFile = File(webDir, "package.json")
        val bundles = mutableListOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app")
        if (manifestFile.exists()) {
            try {
                val j = org.json.JSONObject(manifestFile.readText())
                val arr = j.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")
                if (arr != null) {
                    bundles.clear()
                    for (i in 0 until arr.length()) bundles.add(arr.getString(i))
                }
            } catch (_: Exception) {
                // 读不出就按默认 base+web-app 重建
            }
        }
        if (!bundles.contains("dshmarket")) bundles.add("dshmarket")
        if (!bundles.contains("dsh-zen-remote")) bundles.add("dsh-zen-remote")
        if (!bundles.contains("dsh-memory-evolve")) bundles.add("dsh-memory-evolve")
        val arr = org.json.JSONArray()
        bundles.forEach { arr.put(it) }
        val manifest = org.json.JSONObject()
            .put("name", "dsh-profile-web")
            .put("private", true)
            .put("dependencies", org.json.JSONObject())
            .put("dsh", org.json.JSONObject().put("profile", org.json.JSONObject().put("bundles", arr)))
        manifestFile.writeText(manifest.toString(2) + "\n")
        val patch = File(webDir, "cordis.patch.yml")
        if (!patch.exists()) patch.writeText("# dsh profile web 用户 patch 层（覆盖/关闭 bundle 行用）\n[]\n")
        val ws = File(webDir, "pnpm-workspace.yaml")
        if (!ws.exists()) ws.writeText("packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n")
        Logs.file(context, "web profile bundles=${bundles.joinToString("、")}")
    }

    /** 从 assets 拷贝单个资源文件到目标路径 */
    private fun copyAsset(asset: String, dest: File) {
        try {
            dest.parentFile?.mkdirs()
            assets.open(asset).use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }
        } catch (e: Exception) {
            Logs.file(context, "copyAsset 失败 $asset → $dest: ${e.message}")
        }
    }

    /** 第一次解压/启动时注入 dshmarket，每次先清旧再拷，避免旧版拷坏的残留结构阻塞加载。 */
    private fun copyAssetClean(assetDir: String, dest: File) {
        try {
            if (dest.exists()) dest.deleteRecursively()
        } catch (_: Exception) {
            // 忽略删除失败
        }
        copyAssetDir(assetDir, dest)
    }

    /** 从 assets 拷贝目录（递归）。用 assets.list 的子项数判定目录/文件，一次避开三个坑：
     *  ① 不可用 list==null 判文件（Android 对文件路径可能返回空数组而非 null，会把文件误 mkdir 成目录 → dsh 读 package.json 报 EISDIR）；
     *  ② 不可用 open 判目录（某些实现对目录 open 不抛异常，会把目录当文件拷 → dsh 找不到 bundle 包）；
     *  ③ 不可用 openFd 判文件（AAPT 压缩 .js/.json 后 openFd 抛，会把文件误判成目录）。
     *  目录 list 必然返回非空子项数组；文件 list 返回 null 或空数组。用 list.size>0 判定目录即可。 */
    private fun copyAssetDir(assetDir: String, dest: File) {
        val list = try { assets.list(assetDir) } catch (_: Exception) { null }
        val isDir = list != null && list.isNotEmpty()
        if (isDir) {
            dest.mkdirs()
            for (name in list!!) copyAssetDir("$assetDir/$name", File(dest, name))
        } else {
            // 文件：用 open 拷贝（open 对文件始终可读，含压缩资源）
            try {
                dest.parentFile?.mkdirs()
                assets.open(assetDir).use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }
            } catch (e: Exception) {
                Logs.file(context, "copyAsset 失败 $assetDir → $dest: ${e.message}")
            }
        }
    }

    /** assets/runtime 下是否存在完整产物（用于 UI 提示） */
    val assetsComplete: Boolean
        get() = listOf("runtime/runtime.tar.xz", "runtime/runtime.version")
            .all { n -> try { assets.open(n).close(); true } catch (_: Exception) { false } }

    /** 沙盒内路径 → 宿主文件（浏览/导入导出共用），相对 / 映射到 runtimeDir */
    fun sandboxPathToFile(sandboxPath: String): File {
        val rel = sandboxPath.trimStart('/')
        return if (rel.isEmpty()) runtimeDir else File(runtimeDir, rel)
    }

    /** dsh 一键更新结果 */
    data class UpdateOutcome(val ok: Boolean, val newVersion: String?, val message: String)

    /**
     * 沙盒内一键更新 dsh：npm 官方源优先，网络不可达自动切国内镜像（处理 git/源不可访问）。
     * 需先由调用方停止 dsh（避免文件占用），完成后由调用方重启 dsh 生效。
     * @return UpdateOutcome(成功?, 新版本?, 提示)
     */
    fun runDshUpdate(): UpdateOutcome {
        val proot = resolveProot()
        if (!proot.exists() || !proot.canExecute()) {
            return UpdateOutcome(false, null, "proot 不可执行，无法在沙盒内更新")
        }
        if (!runtimeDir.isDirectory || !markerFile.exists()) {
            return UpdateOutcome(false, null, "沙盒未就绪，请先启动沙盒再更新")
        }
        // 官方源优先，失败自动切国内镜像
        val registries = listOf(
            null to "官方源",
            "https://registry.npmmirror.com" to "国内镜像",
        )
        var lastErr = ""
        for ((registry, name) in registries) {
            val cmd = mutableListOf(
                "/usr/local/bin/node",
                "/usr/local/lib/node_modules/npm/bin/npm-cli.js",
                "install", "-g",
                "--no-audit", "--no-fund",
                "--prefix", "/usr/local",
            )
            if (registry != null) cmd += listOf("--registry", registry)
            cmd += "@deepseek-ai/dsh@latest"
            Logs.file(context, "dsh 更新[$name]: ${cmd.joinToString(" ")}")
            val (code, out) = runInSandbox(timeoutSec = 600, args = *cmd.toTypedArray())
            if (code == 0) {
                // dsh 升级成功。不在此手动重新注入预装插件/依赖：重启 dsh 的完整启动流程会执行
                // installDshMarket（自愈 profiles/node_modules 依赖 + 重新注入预装插件 bundles），
                // 此时 dsh 新版本已就位，依赖与预装插件能正确对齐；手动提前注入反而可能因 dsh
                // 依赖尚未就绪引发时序异常。用户通过 dshmarket 手动安装的插件不在 installDshMarket
                // 重建范围内，不会被清除。
                val newVer = queryInstalledDshVersion(proot)
                Prefs.updatedDshVersion = newVer
                return UpdateOutcome(true, newVer, "更新成功（$name）${newVer?.let { "· dsh v$it" } ?: ""}".trim())
            }
            lastErr = "更新失败[$name] code=$code: ${out.trim().takeLast(400)}"
            Logs.file(context, lastErr)
        }
        return UpdateOutcome(false, null, lastErr)
    }

    /** 查询沙盒内当前安装的 dsh 版本（npm 全局 bin.js --version） */
    private fun queryInstalledDshVersion(proot: File): String? {
        val cmd = listOf(
            "/usr/local/bin/node",
            "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "--version",
        )
        val (code, out) = runInSandbox(timeoutSec = 60, args = *cmd.toTypedArray())
        if (code != 0) return null
        return out.trim().lines().lastOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length < 64 }
    }

    /** APK 占用空间（应用私有数据 + 缓存），供设置页展示 */
    fun storageUsageBytes(): Long {
        fun dirSize(f: File): Long {
            if (!f.exists()) return 0L
            if (f.isFile) return f.length()
            var total = 0L
            f.listFiles()?.forEach { total += dirSize(it) }
            return total
        }
        return dirSize(base) + dirSize(context.cacheDir)
    }

    /**
     * 在沙盒内执行命令（走 proot，不做网络隔离）。
     * @return (exitCode, 合并输出)
     */
    fun runInSandbox(vararg args: String, timeoutSec: Long = 180): Pair<Int, String> {
        val proot = resolveProot()
        if (!proot.exists() || !proot.canExecute()) return -1 to "proot 不可执行"
        val cmd = mutableListOf(
            proot.absolutePath,
            "-r", runtimeDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "--cwd=/root",
        )
        if (Prefs.mountSdcard) cmd += listOf("-b", "/sdcard:/mnt/sdcard")
        // 沙盒内联网依赖 DNS：挂载宿主生成的 resolv.conf 到 /etc/resolv.conf
        ensureResolvConf()
        cmd += listOf("-b", "${resolvConfFile.absolutePath}:/etc/resolv.conf")
        cmd += args.toList()
        Logs.file(context, "沙盒执行: ${cmd.joinToString(" ")}")
        return try {
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(runtimeDir)
                // bionic 版 proot 是动态链接：需通过 LD_LIBRARY_PATH 找到同目录的
                // libtalloc.so / libandroid-shmem.so，并指定 PROOT_LOADER（guest 执行桥）
                .apply { applyProotEnv(this) }
                .start()
            val out = StringBuilder()
            thread(name = "sandbox-io") {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        out.append(line).append('\n')
                        Logs.file(context, "沙盒: $line")
                    }
                } catch (_: Exception) {
                }
            }
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                -1 to "执行超时（${timeoutSec}s）"
            } else {
                p.exitValue() to out.toString()
            }
        } catch (e: Exception) {
            Logs.e("沙盒执行失败", e)
            -1 to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 解压提速：
     *  - 不再做 SHA-256 全量校验（APK 签名已保证 assets 完整性），省去一次 100MB 全读
     *  - 更大的缓冲区（XZ 1MB、条目拷贝 512KB）
     *  - 进度更新节流（最多约 10Hz，避免每块 8KB 刷一次 StateFlow）
     */
    private fun extractToTmp() {
        val tmpDir = File(base, "runtime.tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val total = try {
            assets.openFd("runtime/runtime.tar.xz").length
        } catch (_: Exception) {
            assets.open("runtime/runtime.tar.xz").available().toLong()
        }
        assets.open("runtime/runtime.tar.xz").use { raw ->
            XZInputStream(BufferedInputStream(raw, 1024 * 1024)).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var lastUi = 0L
                    var symlinks = 0
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isSymbolicLink) symlinks++
                        extractEntry(tar, entry, File(tmpDir, entry.name), total)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi >= 100) {
                            lastUi = now
                            val progress = if (total > 0) (tar.bytesRead.toFloat() / total) else 0f
                            AppState.update { it.copy(extractProgress = progress.coerceIn(0f, 1f)) }
                        }
                    }
                    Logs.file(context, "解压完成: 共 $symlinks 个符号链接（已按链接还原，不再退化为普通文件）")
                    AppState.update { it.copy(extractProgress = 1f) }
                }
            }
        }
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, target: File, total: Long) {
        if (entry.isDirectory) {
            target.mkdirs()
            return
        }
        target.parentFile?.mkdirs()
        // rootfs 内含 240 个相对符号链接（/bin->usr/bin、/usr/bin/sh->dash、
        // /usr/local/bin/dsh->../lib/node_modules/@deepseek-ai/dsh/lib/bin.js 等）。
        // 若按普通文件解压，会把链接目标文本写成文件内容：/bin 变成"非目录"占位，
        // proot 解析启动脚本 shebang（#!/bin/sh）时会命中 ENOTDIR（execve ... Not a directory）。
        if (entry.isSymbolicLink) {
            target.delete()
            try {
                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                return
            } catch (e: Exception) {
                Logs.file(context, "符号链接解压失败 ${entry.name} -> ${entry.linkName}: ${e.message}")
                // 兜底：尽量把链接指向的真实文件内容拷过来，避免留下空占位
                val real = File(target.parentFile, entry.linkName)
                if (real.exists() && real.isFile) {
                    try {
                        real.copyTo(target, overwrite = true)
                    } catch (_: Exception) {
                    }
                }
                return
            }
        }
        // 硬链接：链接目标是归档内的完整路径，指向 rootfs 中已有文件时拷贝其内容
        if (entry.isLink) {
            val root = File(base, "runtime.tmp")
            val real = File(root, entry.linkName.trimStart('.', '/').replace('/', File.separatorChar))
            if (real.exists() && real.isFile) {
                try {
                    real.copyTo(target, overwrite = true)
                    if ((entry.mode and 0b001_001_001) != 0) target.setExecutable(true, false)
                    return
                } catch (_: Exception) {
                }
            }
            Logs.file(context, "硬链接目标缺失，按空文件解压 ${entry.name} -> ${entry.linkName}")
        }
        FileOutputStream(target).use { out ->
            val buf = ByteArray(512 * 1024)
            while (true) {
                val n = tar.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        // FileOutputStream 写出的文件默认 0644、无 +x；rootfs 里 dash/node/sh 等
        // 二进制必须带 +x，否则 exec 脚本时内核找不到可执行解释器 → EACCES「Permission denied」。
        if ((entry.mode and 0b001_001_001) != 0) {
            target.setExecutable(true, false)
        }
    }

    /**
     * 重解压前把用户家目录（/root、/home）移出 runtime 暂存。
     * rootfs 里 /root、/home 属用户可写区，dsh 的 API Key（/root/.dsh/.credentials.yaml）、
     * 会话/工作区等都在里面；升级 rootfs 时若直接 deleteRecursively 会一起被清掉。
     */
    private fun backupUserHome() {
        val srcRuntime = runtimeDir
        if (!srcRuntime.isDirectory) return
        val backup = userBackupDir
        backup.deleteRecursively()
        backup.mkdirs()
        for (name in listOf("root", "home")) {
            val src = File(srcRuntime, name)
            if (!src.exists()) continue
            val dst = File(backup, name)
            try {
                if (!src.renameTo(dst)) {
                    // rename 失败（极少见）时退化为递归拷贝
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
            } catch (e: Exception) {
                Logs.file(context, "备份用户目录失败 $name: ${e.message}")
            }
        }
    }

    /** 新 rootfs 就绪后把用户家目录迁回 */
    private fun restoreUserHome() {
        val backup = userBackupDir
        if (!backup.isDirectory) return
        for (name in listOf("root", "home")) {
            val src = File(backup, name)
            if (!src.exists()) continue
            val dst = File(runtimeDir, name)
            dst.deleteRecursively()
            try {
                if (!src.renameTo(dst)) {
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
            } catch (e: Exception) {
                Logs.file(context, "恢复用户目录失败 $name: ${e.message}")
            }
        }
        backup.deleteRecursively()
    }

    /** 临时目录 → runtime/ 原子切换 + 写版本标记 + 置可执行位 + 探测 proot */
    private fun atomicCommit() {
        val tmpDir = File(base, "runtime.tmp")
        backupUserHome()
        if (runtimeDir.exists()) runtimeDir.deleteRecursively()
        check(tmpDir.renameTo(runtimeDir)) { "原子切换失败" }
        restoreUserHome()
        markerFile.writeText(builtinVersion ?: "unknown")

        // proot 原生库由系统解压到 nativeLibraryDir（SELinux 允许 execve 的目录），
        // 该目录同时也是 libtalloc.so / libandroid-shmem.so / libproot-loader.so 所在，
        // 动态 bionic proot 必须整体从 nativeLibraryDir 运行，不能拷贝到 app 私有目录
        //（W^X/noexec 会拒绝 execve，且拷贝后会缺 companion 库）。
        val chosen = prootCandidates().firstOrNull { probeExecutable(it) }
        Prefs.prootPath = chosen?.absolutePath
        Logs.file(
            context,
            "proot 部署: 候选=${prootCandidates().joinToString { it.absolutePath }} " +
                "选中=${chosen?.absolutePath ?: "无(被拒)"} loader=${nativeLibLoader?.absolutePath ?: "缺"}",
        )

        val sh = File(runtimeDir, "usr/local/bin/dsh-web.sh")
        if (sh.exists()) makeExecutable(sh)
    }

    /**
     * 置可执行位并校验。File.setExecutable 在部分 ROM/文件系统上会静默失败，
     * 失败时兜底用 shell chmod，并把结果写进日志便于排查（error=13 EACCES 的常见根因）。
     */
    private fun makeExecutable(f: File) {
        if (!f.exists()) return
        f.setReadable(true, false)
        f.setExecutable(true, false)
        if (!f.canExecute()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("chmod", "755", f.absolutePath))
                p.waitFor()
            } catch (e: Exception) {
                Logs.e("chmod 失败: ${f.absolutePath}", e)
            }
        }
        Logs.file(
            context,
            "可执行位 ${f.name}: exists=${f.exists()} canExec=${f.canExecute()} size=${f.length()}",
        )
    }
}
