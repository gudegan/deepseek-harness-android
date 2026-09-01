package com.deepseek.dshshell.util

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.BulletSpan
import android.util.TypedValue
import android.widget.TextView
import com.deepseek.dshshell.BuildConfig
import com.deepseek.dshshell.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 优化公告（更新日志）：每次发布新版本，在 entries 顶部追加一条记录。
 * 新版本安装后首次打开自动弹出；设置页可随时查看完整历史。
 */
object Changelog {

    data class Entry(val versionCode: Int, val versionName: String, val notes: List<String>)

    private const val KEY_LAST_SEEN = "last_seen_changelog_vc"

    private val entries = listOf(
        Entry(
            versionCode = 37,
            versionName = "v0.5.22",
            notes = listOf(
                "预览页新增返回按钮：默认左下角、可拖动移动（位置持久化），点击时 dsh 网页能回退则回退上一页、否则返回上一个 tab；并把预览页悬浮按钮的拖动/持久化逻辑抽取为通用 setupDraggableButton（刷新按钮默认右下角、返回按钮默认左下角共用）",
            ),
        ),
        Entry(
            versionCode = 36,
            versionName = "v0.5.21",
            notes = listOf(
                "彻底清理飞书残留：v0.5.20 虽已从源码删除飞书插件/技能，但重解压沙盒会保留用户家目录 /root，旧版拷入的飞书技能（root/.dsh/skills 下 lark-*）与飞书插件（profiles/node_modules）仍残留、导致设置页/技能页显示飞书项。现新增启动清理（purgeFeishuRemnants）删除这些残留；另新增 README.md 发布文档（含构建与发布到 Git/GitHub 步骤）",
            ),
        ),
        Entry(
            versionCode = 35,
            versionName = "v0.5.20",
            notes = listOf(
                "移除预装飞书插件与相关功能：飞书授权（dsh-feishu-auth/dsh-client-ui-feishu-auth）、lark-cli（@larksuite/cli）、飞书技能（skills-lark）因飞书授权必须配置应用凭证（appId/appSecret）、不便于分发他人使用，整套移除；DSH 设置页不再显示「飞书关联」项，运行日志/分享到飞书等系统分享功能保留",
            ),
        ),
        Entry(
            versionCode = 34,
            versionName = "v0.5.19",
            notes = listOf(
                "修复飞书授权时报「Failed to install lark-cli: spawnSync curl ENOENT」：@larksuite/cli 的 run.js 首次运行需用 curl 下载 linux-arm64 二进制，但 proot 沙盒最小 rootfs 无 curl 命令，下载失败。现预先下载官方 linux-arm64 版 lark-cli 二进制（lark-cli-1.0.92-linux-arm64，ELF aarch64 静态链接）并预装进 assets 的 lark-cli/bin，运行时拷入沙盒并显式设可执行位，run.js 检测到二进制已存在即直接使用，无需沙盒 curl/联网下载",
            ),
        ),
        Entry(
            versionCode = 33,
            versionName = "v0.5.18",
            notes = listOf(
                "修复飞书「发起授权」仍失败：dsh-feishu-auth 的 apply 把 registerAuthRoute(ctx) 放在了 tools 服务判断之后，一旦 tools 服务缺失就提前 return，导致 /plugins/dsh-feishu-auth 的 web 路由（发起授权/刷新状态/完成）从未注册，前端授权请求连不到后端。现把 registerAuthRoute 提到函数开头（tools 判断之前），确保路由始终注册；同时给 runLark 增加详细日志（命令/输出/退出码）便于定位 lark-cli 问题",
            ),
        ),
        Entry(
            versionCode = 32,
            versionName = "v0.5.17",
            notes = listOf(
                "修复飞书授权失败「lark-cli: not found」：飞书插件 dsh-feishu-auth 用 spawn(LARK_CLI) 调 lark-cli，但 proot 沙盒（Debian arm64 rootfs）未装 lark-cli。现预装 @larksuite/cli（scripts+package.json+纯 JS 依赖，排除无用 Windows exe）到 APK assets，运行时拷入沙盒 profiles/node_modules/@larksuite/cli，并在启动环境注入 LARK_CLI 指向其 scripts/run.js；首次发起授权时 run.js 会联网下载 linux-arm64 二进制",
            ),
        ),
        Entry(
            versionCode = 31,
            versionName = "v0.5.16",
            notes = listOf(
                "启动动画图标与 App 图标更换为 DeepSeek 官方原生鲸鱼：从 DSH 官方 favicon（favicon-official.svg）扒取官方鲸鱼矢量图，按 Harness 品牌黑色（fill=#000）渲染为 512×512 PNG，替换启动页鲸鱼（dsh_whale_blue.png）与桌面 App 图标（dsh_icon.png）；启动页黑鲸鱼置于白色圆底上对比清晰",
            ),
        ),
        Entry(
            versionCode = 30,
            versionName = "v0.5.15",
            notes = listOf(
                "修复飞书插件设置页仍不显示：dsh-feishu-auth 服务端已正常加载（日志确认 ns=feishu-auth 注册），但负责渲染「飞书关联」UI 的 client 插件 dsh-client-ui-feishu-auth 此前作为 client-only 走 dependencies + dshmarket 热挂载 shim，实测该 shim 未执行（无 hot-mount 记录），前端 UI 始终不加载。现改为与 dsh-memory-evolve 相同方式——在其 package.json 补 dsh.bundle.patch 声明并加入 web profile bundles，DSH 将其作为 bundle 同时加载前端 client，设置页可正常显示「飞书关联」",
            ),
        ),
        Entry(
            versionCode = 29,
            versionName = "v0.5.14",
            notes = listOf(
                "预览页刷新按钮默认改为右下角、支持单指拖动移动（拖到哪记住哪，重启仍保持）；修复预装飞书插件在设置页不生效：dsh-client-ui-feishu-auth 是仅有 dsh.client、无 dsh.bundle 的 client-only 插件，此前未写入 web profile 的 dependencies，dshmarket 启动时不做 client-only shim 导致其前端设置页从不加载；现把它写入 dependencies，DSH 设置页可正常显示「飞书关联」（App ID/App Secret/授权域/授权状态/发起授权）",
            ),
        ),
        Entry(
            versionCode = 28,
            versionName = "v0.5.13",
            notes = listOf(
                "修复启动 dsh 报错「profile bundle dsh-memory-evolve declares no dsh.bundle in its package.json」：v0.5.7 起把 dsh-memory-evolve 加入 web profile 的 dsh.profile.bundles 以作为 bundle 加载，但其 package.json 的 dsh 对象只写了 client.inject/platform，缺少 dsh.bundle.patch 声明（cordis.patch.yml 已补但未在 package.json 声明）。dsh-app-boot 加载 bundle 时发现无 dsh.bundle 声明即抛错，dsh 启动即退、自动重试 3 次全失败。现已在其 package.json 的 dsh 对象补上 \"bundle\": { \"patch\": \"./cordis.patch.yml\" }，与 dsh-feishu-auth/dsh-zen-remote/dshmarket 等正常 bundle 声明格式一致，dsh 可正常加载该 bundle 的 server 插件与前端 client",
            ),
        ),
        Entry(
            versionCode = 27,
            versionName = "v0.5.12",
            notes = listOf(
                "修复 v0.5.11 引入的启动崩溃：此前把 @deepseek-ai/cosmokit、@deepseek-ai/schemastery 等 dsh 自带依赖拷成了真实目录，与 dsh 启动时 healProfilesModuleFallback 为其建立的符号链接冲突，dsh 报「xxx exists and is not a symlink」直接退出、自动重试 3 次全失败。现改为凡属 dsh 自带依赖闭包的包一律跳过拷贝、交由 dsh 以符号链接提供（不会与 dsh 冲突）；坏残留自愈也不再因白名单排除这类包，覆盖安装后无需手动清沙盒数据，插件加载更稳",
            ),
        ),
        Entry(
            versionCode = 25,
            versionName = "v0.5.10",
            notes = listOf(
                "修复启动 dsh 报错「js-yaml exists and is not a symlink」：js-yaml/argparse 是 dsh 自带依赖，heal 会自动为它们建 symlink，不再拷贝成真实目录（否则冲突导致 dsh 崩）；分享到飞书增加 Toast 反馈（已唤起飞书/请选择飞书），并在 Manifest 声明 queries 以能定位飞书",
            ),
        ),
        Entry(
            versionCode = 24,
            versionName = "v0.5.9",
            notes = listOf(
                "进一步修复「分享到飞书」：改用 Activity 上下文启动（最可靠），并把整条分享链路写进运行日志（触发/定向包名及结果/回退/异常），便于确认是否点击生效与定位原因",
            ),
        ),
        Entry(
            versionCode = 23,
            versionName = "v0.5.8",
            notes = listOf(
                "修复「分享到飞书」点击无效果：改为优先唤醒飞书 App（定向包名，Manifest 声明 queries 权限）接收日志，未安装则回退系统分享面板；统一用 Application 上下文启动，避免非 Activity context 静默失败",
            ),
        ),
        Entry(
            versionCode = 22,
            versionName = "v0.5.7",
            notes = listOf(
                "修复启动页 DeepSeek 图标显示为蓝色方块：改用透明背景、直接填充 DeepSeek 品牌蓝(#4D6BFE)的鲸鱼图标；预装 dsh-feishu-auth（飞书账号授权）、dsh-client-ui-feishu-auth（飞书设置页）、dsh-memory-evolve（分层记忆/待办/技能，已补 cordis.patch.yml）及飞书 skills（lark-*）",
            ),
        ),
        Entry(
            versionCode = 21,
            versionName = "v0.5.6",
            notes = listOf(
                "移除自研「移动端适配」开关（改由预装的 dsh-zen-remote 开源插件做移动端适配）；预装 dsh-zen-remote（手机外壳/手势/键盘避让/PWA 等，含 web-push 依赖，装入 profiles/node_modules 并加入 web profile bundles）；预览页刷新按钮改为纯图标按钮、点击带动画",
            ),
        ),
        Entry(
            versionCode = 20,
            versionName = "v0.5.5",
            notes = listOf(
                "优化移动端适配（之前开启后仍左右拥挤）：WebView 改用 useWideViewPort+overview 按设备宽渲染、支持双指缩放兜底；注入自适应 CSS 让宽内容（表格/代码/图片）防横向溢出、关键面板与小屏内边距优化，缓解左右拥挤/内容裁切",
            ),
        ),
        Entry(
            versionCode = 19,
            versionName = "v0.5.4",
            notes = listOf(
                "修复内置 dshmarket 后 dsh 启动报 ERR_MODULE_NOT_FOUND：dsh 的 cordis loader 从 profile 目录 import 插件，dshmarket 及其依赖改为装入 profiles/node_modules（并补齐 js-yaml/argparse）；运行日志一键分享到飞书（日志弹窗新增「分享到飞书」）；精简启动日志（去掉超长的完整环境变量、逐路径沙盒、mount 诊断）减少日志过长",
            ),
        ),
        Entry(
            versionCode = 18,
            versionName = "v0.5.3",
            notes = listOf(
                "修复覆盖安装升级未自动重解压：lastVersionCode 字段此前旧版本从未写入，旧判断 last>0 导致 last=0 时跳过升级；改为「沙盒已解压且版本号与记录不一致即触发重解压」，已装 5.2 的设备装本版会正确重解压（保留用户数据）",
            ),
        ),
        Entry(
            versionCode = 17,
            versionName = "v0.5.2",
            notes = listOf(
                "覆盖安装升级后首次启动自动重新解压沙盒：记录上次安装的 versionCode，检测到升级即删解压标记触发重解压（保留 API Key、对话与产出文件），确保每次升级都用干净的新沙盒重新注入 dshmarket 等，避免旧版本残留坏结构",
            ),
        ),
        Entry(
            versionCode = 16,
            versionName = "v0.5.1",
            notes = listOf(
                "修复内置 dshmarket 后 dsh 启动失败（EISDIR / cannot resolve profile bundle dshmarket）：RuntimeManager.copyAssetDir 的文件/目录判定不可靠，改用 assets.list 子项数判定目录、并在注入前先清旧再拷，保证 dshmarket 与其依赖正确落盘",
            ),
        ),
        Entry(
            versionCode = 15,
            versionName = "v0.5.0",
            notes = listOf(
                "新增「移动端适配」开关：为 dsh 网页注入自适应样式（针对 dsh 桌面布局收缩固定宽面板、表格可横向滚动、允许放大缩小），缓解手机上界面挤压/内容不完整；开启后自动刷新预览页生效（默认关闭，需在设置中手动开启）",
                "预览页新增「刷新」按钮：一键 reload 当前 dsh 页面",
                "新增「DSH 恢复默认设置」：自装/自研插件或配置改动导致 dsh 无法启动时一键恢复出厂默认（移除插件与用户配置层）；保留 API Key、对话内容与产出文件",
                "修复重启 dsh 后预览页画面重叠：dsh 重启后预览 WebView 自动重载以匹配新进程，聊天记录与页面状态不受影响",
                "运行日志每条新增毫秒级时间戳（yyyy-MM-dd HH:mm:ss.SSS），便于查看/导出定位问题",
                "内置开源 dsh-market 插件（dshmarket 可视化插件市场）：在 dsh Web 界面浏览/搜索/一键安装/管理社区插件",
            ),
        ),
        Entry(
            versionCode = 14,
            versionName = "v0.4.9",
            notes = listOf(
                "修复首次启动「自动启动 dsh」后自动关闭：dsh 意外退出（如残留端口占用）时自动重试最多 3 次，无需再手动再次启动",
                "修复升级后沙盒不重解压：runtime 版本标记随内置包递增（r4→r5），升级后自动触发重解压并刷新版本号",
            ),
        ),
        Entry(
            versionCode = 13,
            versionName = "v0.4.8",
            notes = listOf(
                "dsh 版本检测与一键更新增强：官方 npm 源不可达时自动切换国内镜像（npmmirror），网络不佳也能查到/装上最新版",
                "更新结果直接展示在设置页，更新成功后自动重启 dsh 使新版本生效",
            ),
        ),
        Entry(
            versionCode = 12,
            versionName = "v0.4.7",
            notes = listOf(
                "新增「自动启动」开关：打开应用自动解压沙盒并启动 dsh",
                "修复 dsh 启动即退 code=1（EADDRINUSE）：启动前自动清理残留的 3080 端口占用（IPv4+IPv6 双栈定位），手动停止时彻底释放端口",
                "dsh 进程生命周期管理：proot 退出时连带结束沙盒内进程（--kill-on-exit），不再残留 node 进程",
            ),
        ),
        Entry(
            versionCode = 11,
            versionName = "v0.4.6",
            notes = listOf(
                "彻底修复预览页切 tab 重载：WebView 所有权提升到 Activity，切换时不再销毁重建，页面状态完整保留",
            ),
        ),
        Entry(
            versionCode = 10,
            versionName = "v0.4.5",
            notes = listOf(
                "预览页切 tab 不再刷新：WebView 常驻 Activity 容器，切页仅切换可见性，聊天记录与页面状态完整保留",
                "优化公告改为可上下滚动，完整展示所有历史版本（此前内容过长被截断，只看得到最新版）",
                "文件管理增强：支持删除、长按多选、批量导出、批量删除",
                "导入支持一次选择多个文件",
                "控制台新增「关于 / 快速上手」详细说明",
            ),
        ),
        Entry(
            versionCode = 9,
            versionName = "v0.4.4",
            notes = listOf(
                "修复：新建工作区后发送对话报 EACCES（dsh 会话落盘用硬链接，被 Android SELinux 禁止），"
                    + "改为 rename 原子发布",
            ),
        ),
        Entry(
            versionCode = 8,
            versionName = "v0.4.3",
            notes = listOf(
                "修复 dsh 启动即退 code=1：Android 宿主 TMPDIR（/data/user/0/.../cache）泄漏进沙盒，"
                    + "spill-local 插件 mkdtemp 报 ENOENT；现把沙盒内临时目录覆盖为 /tmp",
                "修复「复制日志」闪退：超大日志写入剪贴板触发崩溃，改为截断复制（完整用「导出」）",
                "修复旧版本直接更新后不重解压：runtime 版本标记此前被构建任务覆盖未生效，现已正确递增",
            ),
        ),
        Entry(
            versionCode = 7,
            versionName = "v0.4.2",
            notes = listOf(
                "修复 dsh 启动 execve ... Permission denied：解压时还原 tar 里的可执行位（dash/node/sh 等二进制恢复 +x），"
                    + "脚本 shebang 解释器可正常执行",
                "系统主题默认改为跟随系统（新装默认不再固定深色）",
            ),
        ),
        Entry(
            versionCode = 6,
            versionName = "v0.4.1",
            notes = listOf(
                "修复 dsh 启动 execve ... Not a directory：rootfs 内含 240 个符号链接（/bin→usr/bin、/bin/sh→dash 等），"
                    + "此前解压未还原链接，导致 /bin 等目录退化成普通文件，proot 解析启动脚本 shebang 时失败",
                "解压时按 tar 记录还原符号链接，并增加解压完成统计与沙盒关键路径诊断日志",
                "启动参数补齐 --cwd=/root、--kill-on-exit，自动创建 PROOT_TMP_DIR 并注入沙盒内 PATH",
                "完整运行日志：proot 以 verbose 级输出、完整环境变量、logcat 同步写入",
            ),
        ),
        Entry(
            versionCode = 5,
            versionName = "v0.4.0",
            notes = listOf(
                "版本号重构为 0.4.0，开源化准备：沉淀开发过程文档，标注借鉴的开源项目",
                "运行链路沿用 bionic PRoot 方案（libproot.so + loader + talloc + android-shmem）",
                "构建链路优化：JDK 17 + Gradle 8.14.5，稳定产出 arm64 APK",
            ),
        ),
        Entry(
            versionCode = 4,
            versionName = "v0.3.1",
            notes = listOf(
                "修复 dsh 进程 SIGSYS(159)：静态 glibc proot 被 Android seccomp 拦截 rseq 等系统调用导致崩溃",
                "改用 bionic 版 proot：bionic 不注册 rseq，从根上避开 seccomp 拦截，不再需要 GLIBC_TUNABLES",
                "proot 作为原生库打包进 APK，自动注入 LD_LIBRARY_PATH / PROOT_LOADER / PROOT_TMP_DIR",
            ),
        ),
        Entry(
            versionCode = 3,
            versionName = "v0.3.0",
            notes = listOf(
                "修复 proot error=13：proot 改为原生库打包，安装时由系统解压到可执行目录",
                "解压提速：去掉冗余 SHA-256 全量校验、加大缓冲，首启更快",
                "dsh 一键更新：检测到新版本可沙盒内 npm 自动更新并重启（官方源不可达自动切国内镜像）",
                "设置页新增：沙盒/dsh 状态、dsh 版本、APK 占用空间显示",
                "外观主题：跟随系统/浅色/深色 + 4 套强调色切换",
                "启动动画：DeepSeek 品牌蓝鲸图标 + 扩散光环 + 文字淡入",
            ),
        ),
        Entry(
            versionCode = 2,
            versionName = "v0.2.0",
            notes = listOf(
                "新增「关闭沙盒」开关（停止 dsh 与常驻服务，沙盒保留）",
                "修复 proot 无执行权限导致的 error=13（自动 chmod 兜底）",
                "dsh 异常后「启动 dsh」可一键重试",
                "启动日志更详细，日志弹窗支持选中与一键复制",
                "更换黑色 DeepSeek 图标",
            ),
        ),
        Entry(
            versionCode = 1,
            versionName = "v0.1.0",
            notes = listOf(
                "首个版本：内置沙盒运行 DeepSeek Agent（dsh）",
                "控制台 / dsh 预览 / 沙盒文件浏览与导入导出 / 设置",
            ),
        ),
    )

    /** 安装新版本后首次打开时弹出完整更新历史（含以往所有版本） */
    fun maybeShowUpdateDialog(context: Context) {
        val current = BuildConfig.VERSION_CODE
        val seen = Prefs.getInt(KEY_LAST_SEEN, 0)
        if (current <= seen) return
        Prefs.setInt(KEY_LAST_SEEN, current)
        showDialog(context, entries)
    }

    /** 设置页手动查看完整更新历史 */
    fun showFullChangelog(context: Context) = showDialog(context, entries)

    private fun showDialog(context: Context, list: List<Entry>) {
        val sb = SpannableStringBuilder()
        list.sortedByDescending { it.versionCode }.forEachIndexed { i, e ->
            if (i > 0) sb.append("\n\n")
            sb.append("【${e.versionName}】\n")
            e.notes.forEach { n ->
                sb.append("\n")
                val start = sb.length
                sb.append(n)
                sb.setSpan(BulletSpan(24), start, sb.length, 0)
            }
        }
        // 用可滚动、可选择的 TextView 承载完整历史，避免内容过长被弹窗截断
        val tv = TextView(context).apply {
            text = sb
            textSize = 14f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(
                dp(24), dp(8), dp(24), dp(8)
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_changelog)
            .setView(tv)
            .setPositiveButton(R.string.log_close, null)
            .show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            android.content.res.Resources.getSystem().displayMetrics,
        ).toInt()
}
