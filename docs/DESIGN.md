# DeepSeek Harness Android APK 技术方案（自用版）

- 状态：评审中
- 目标：可安装 APK，内置 Linux 沙盒 + Node.js + dsh，WebView 直接出 Agent 界面
- 范围：团队自用（单机型优先、自签名 keystore、仅 arm64）
- 明确不做：公开分发、多架构、模型本地离线推理

## 1. 总体架构

```
┌────────────────────────── Android 应用沙盒（app 私有目录）──────────────────────────┐
│                                                                                    │
│  MainActivity/WebView ──http://127.0.0.1:3080──► HarnessService（前台服务）         │
│                                                        │                            │
│                                              proot -r $ROOTFS                      │
│                                                        │                            │
│                        ┌────────────────────────────────▼─────────────────────┐    │
│                        │  proot 伪 chroot（Linux 沙盒，无 root 权限）           │    │
│                        │  /root (fs) /home (fs) /dev /proc /sys (绑定)          │    │
│                        │   ├── node (linux-arm64)                               │    │
│                        │   └── @deepseek-ai/dsh  (bin.js web --no-open)         │    │
│                        │        └── dsh 内部 sandbox/subprocess + 权限审批插件   │    │
│                        └───────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────────────────┘
```

隔离链三层：**Android 应用沙盒 → proot chroot → dsh 自身沙箱/审批**。

## 2. 组件设计

### 2.1 运行环境包（打包进 APK assets）

| 组件 | 选型 | 说明 |
|---|---|---|
| Rootfs | **Debian/Ubuntu arm64 最小 rootfs** | 必须 glibc——官方 Node 二进制的预编译依赖都是 glibc，Alpine 的 musl 会踩坑（sharp 等） |
| Node.js | 官方 `node-v22-linux-arm64`（或 24） | dsh 要求 node ^22.19 或 >=24 |
| dsh | `@deepseek-ai/dsh`（锁版本） | 快速迭代期，必须固定版本号 |
| 原生模块 | **官方 linux-arm64 预编译包** | 已实测：node-pty 自带 `prebuilds/linux-arm64/pty.node`；koffi 走平台包 `@koromix/koffi-linux-arm64`；sharp 走 `@img/sharp-linux-arm64`(+libvips)，`@img/sharp-wasm32` 自动作回退。**均无需编译** |
| proot | 静态 arm64 二进制 | 参考 termux-packages 的 proot 构建 |
| 启动脚本 | `/usr/local/bin/dsh-web.sh` | 设置 HOME/$DSH_HOME，执行 node |

原生模块**全部用官方 linux-arm64 预编译包**（里程碑 1 已在 arm64 模拟环境实测通过），首启只解压不编译，rootfs 内**不需要编译工具链**（clang/make/cmake 等全部不需要）。

### 2.2 Android 应用层（Kotlin）

**四个页面**（单 Activity + Fragment，Jetpack Navigation）：

- `DashboardFragment`（**控制台主页**）
  - 状态卡：沙盒状态（未解压 / 解压中 / 就绪 + 版本号）、dsh 状态（已停止 / 启动中 / 运行中 / 异常）
  - 控制开关：**启动沙盒 / 启动 dsh / 重启 dsh / 关闭 dsh**（按钮可用性由状态机驱动，见 2.2.1）
  - 入口：预览页 / 沙盒文件 / 设置 / 日志
- `PreviewFragment`（**dsh 预览页，单独一页**）
  - 全屏 WebView 加载 `http://127.0.0.1:3080`（`setJavaScriptEnabled(true)`、`setDomStorageEnabled(true)`——Web UI 的 WebSocket 流式渲染需要）
  - 仅当 dsh 状态为**运行中**才 loadUrl；否则显示占位提示 + 返回控制台
- `FilesFragment`（**沙盒文件浏览 + 导入/导出**，见 2.2.2）
  - 树形浏览沙盒文件/目录，按**沙盒内路径**展示（如 `/root/.dsh/...`）
  - 选中文件可**导出**到本机；在目标目录可**导入**本机文件到沙盒
- `SettingsFragment`（**设置**，见 2.2.3）

**服务与状态**

- `HarnessService`（前台服务）：常驻保活 dsh 进程。Android 12+ 声明 `FOREGROUND_SERVICE` + `foregroundServiceType="dataSync"`，常驻低优先级通知
- `RuntimeManager`：沙盒（rootfs）的**一次性解压与版本管理**（见第 3 节）
- `ProcessManager`：dsh 进程的启停/重启，读 stdout 里 `dsh web: http://127.0.0.1:3080` 作为就绪信号
- `UiState`（StateFlow）：沙盒/ dsh 双状态 + 版本号 + 日志，Fragment 收集后渲染状态卡、控制按钮可用性、预览页准入

### 2.2.1 沙盒与 dsh 生命周期状态机

```
沙盒： UNEXTRACTED → EXTRACTING → READY
dsh：  STOPPED → STARTING → RUNNING ──启动失败/崩溃──▶ ERROR
```

| 开关 | 动作 | 前置条件 | 状态迁移 |
|---|---|---|---|
| 启动沙盒 | `RuntimeManager.extract()` | 未解压 / 解压中 | → READY（幂等，已 READY 直接完成） |
| 启动 dsh | `ProcessManager.start()` | 沙盒 READY 且 dsh STOPPED | STOPPED → STARTING → RUNNING |
| 重启 dsh | `ProcessManager.restart()` | dsh RUNNING / ERROR | RUNNING → STOPPED → STARTING → RUNNING |
| 关闭 dsh | `ProcessManager.stop()` | dsh STARTING / RUNNING | → STOPPED（沙盒保留，**不解压不删除**） |

- 应用打开后自动执行"解压 → 启动 dsh"（沿用一次性解压，后续秒级启动），控制台同时提供手动开关
- ERROR（启动失败/崩溃）→ 控制台可"重启 dsh"，预览页显示错误 + 日志入口

### 2.2.2 沙盒文件浏览与导入导出

rootfs 就是 app 私有目录下的**普通文件**，文件操作**不依赖 dsh 进程**（沙盒停着也能浏览/导入导出），直接在 Kotlin 层读写，无需进 proot。

**路径映射**（浏览器按沙盒内路径展示，实际落在宿主路径）：

| 沙盒内路径 | 宿主路径 |
|---|---|
| `/` | `filesDir/runtime/` |
| `/root`（默认工作目录，dsh 产出在 `$DSH_HOME=/root/.dsh`） | `filesDir/runtime/root` |
| `/home` | `filesDir/runtime/home` |
| `/tmp` | `filesDir/runtime/tmp` |

- 默认定位 `/root`；快捷入口：`/root`、`/home`、`/tmp`；隐藏 `usr/`、`etc/`、`proc/` 等系统目录，避免误操作
- 文件/目录显示大小、修改时间；文本类小文件可预览内容

**导入导出**（走 Storage Access Framework，**无需存储权限**）：

- 导出：`ACTION_CREATE_DOCUMENT` 选本机目标 → 复制沙盒文件到所选位置
- 导入：`ACTION_OPEN_DOCUMENT` 选本机文件 → 复制到当前浏览目录（默认 `/root`）
- 实现：`contentResolver.openInputStream / copyTo`，不申请 `READ/WRITE_EXTERNAL_STORAGE`
- `/mnt/sdcard` 是绑定到本机共享存储的挂载点，不经沙盒浏览器，直接用系统文件管理即可

### 2.2.3 设置项

| 设置 | 说明 |
|---|---|
| API Key | 引导到 Web UI 填写（存 `$DSH_HOME/.credentials.yaml`）；外壳展示"已配置/未配置" |
| 挂载 /sdcard | 是否 `-b /sdcard:/mnt/sdcard`，改动后重启 dsh 生效 |
| 运行模式 | 标准 / 极简（启动参数差异，见 2.4） |
| 自启 | 打开应用是否自动"解压 → 启动 dsh" |
| 前台服务通知 | 通知开关（前台服务仍需常驻通知，可降为最低优先级） |
| 保活白名单 | 引导把应用加入系统电池优化白名单（国产 ROM 激进杀后台） |
| 日志 | 查看 / 导出 dsh 日志（`filesDir/logs/dsh.log`） |
| 数据管理 | 清除沙盒数据（下次启动重解压）、重置 dsh 配置；展示 runtime 版本 / APK 版本 |
| 移动端适配 | 预览页 WebView 注入 viewport 与自适应 CSS，缓解 dsh 桌面界面在手机上的挤压、左右拥挤、内容显示不完整；默认关闭，需手动开启 |
| DSH 恢复默认设置 | 移除自装/自研插件与用户配置层（profiles/ 与 cordis.patch.yml），回到仅内置官方插件，修复因插件损坏导致的无法启动；保留 API Key、对话内容与产出文件 |

> v0.5.0 起内置开源 `dshmarket` 插件（DSH 可视化插件市场，GitHub [dsh-market/dsh-market](https://github.com/dsh-market/dsh-market)，npm 包 `dshmarket@1.38.1`，含前端 client bundle）。插件随 APK assets（`assets/dsh-market/`）携带（含其缺失运行时依赖 `undici`、`@deepseek-ai/schemastery`、`@deepseek-ai/cosmokit`、`@standard-schema/spec`），运行时由 `RuntimeManager.installDshMarket()` 把这些依赖装入沙盒 dsh 的 `node_modules`，并把 `dshmarket` 加入 **web profile 的 `dsh.profile.bundles`**（bundle 机制：dsh 启动时同时加载其 server 插件与前端 client，设置页出现插件市场）。不改动 100M runtime.tar.xz，避免 Windows 解包破坏符号链接。

### 2.3 启动命令（服务内执行）

```
proot -r $ROOTFS \
  -b /dev -b /proc -b /sys \
  -b /sdcard:/mnt/sdcard \
  env HOME=/root DSH_HOME=/root/.dsh \
  node --expose-internals /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --no-open
```

- `--no-open`：不要在沙盒里调浏览器
- 绑定 `/dev /proc /sys`：node/dsh 运行必需；`/sdcard` 按需挂载让 Agent 可访问用户文件（可做成开关）
- proot **不做网络命名空间**，沙盒内的 `127.0.0.1` 与安卓本机共享，WebView 可直接连接
- API Key：在 Web UI 设置里填，存到 `$DSH_HOME/.credentials.yaml`

### 2.4 沙盒与安全

- Agent 只能写 rootfs 内的 `/root`（即 app 私有目录），天然出不去
- 启用 dsh 的 **permission/approval 插件**：Agent 要跑 Shell/写文件时，通过 Web UI 弹窗由用户确认
- 运行模式：默认 **标准模式**；真机性能不足时降级 **极简模式**（只留 bash + 文件编辑器）

## 3. 运行环境生命周期：一次性解压 + 版本号增量机制

### 3.1 核心原则：只解压一次，之后常驻

解压目标放在 **app 私有数据目录**。此目录在 APK 重启后**不会被清除**，只在两种情况下清空：

- 用户手动"清除应用数据"
- 卸载应用

因此 rootfs 解压是**一次性的**（首次安装后的一次性成本），后续每次启动只做 O(1) 的标记检查，直接拉起进程，秒级启动。

### 3.2 目录与版本标记

```
${RUNTIME_BASE}/            # 运行环境根目录（随渠道二选一，见 3.4）
├── runtime/                # 解压后的 rootfs + node + dsh
│   └── ...
└── .runtime-version        # 标记文件：记录当前已解压的 runtime 版本
```

- APK 内置 runtime 版本号：构建期生成，写入 `assets/runtime.version`（内容如 `dsh-0.1.1-rc.2_node-24.1.0_r1`）
- 解压成功后，把该版本号写入 `runtime/.runtime-version`
- 每次启动比对：标记版本 == APK 内置版本 → 跳过解压；否则触发（重）解压

### 3.3 启动流程（RuntimeManager）

```
启动
 │
 ├─ 读取 assets/runtime.version（BUILTIN_VER）
 ├─ 读取 runtime/.runtime-version（INSTALLED_VER）
 │
 ├─ INSTALLED_VER 存在且 == BUILTIN_VER？
 │      └─ 是 → 直接进入启动 dsh 进程（秒级）
 │
 ├─ 否 → 进入解压流程（一次性成本）：
 │     1. 清理旧 runtime 目录（若 INSTALLED_VER 存在且 != BUILTIN_VER）
 │     2. 解压 assets/runtime.tar.xz → runtime/
 │     3. chmod +x proot、启动脚本
 │     4. 写入 runtime/.runtime-version = BUILTIN_VER
 │     5. 进入启动 dsh 进程
 │
 └─ 解压过程带进度提示 UI（首次 1~2 分钟）
```

### 3.4 存储位置（二选一，做成构建开关）

| 位置 | 说明 | 取舍 |
|---|---|---|
| 内部存储 `filesDir`（默认） | `/data/data/<包名>/files/`，随应用数据清除而清除 | 最稳、最隐私；占内部空间（~80–120MB） |
| 外部存储 `getExternalFilesDir()` | app 专属目录，无需额外权限 | 省内部空间；外部存储卸载残留、个别机型行为差异 |

### 3.5 边界情况

| 场景 | 行为 |
|---|---|
| 正常重启 APK | 标记匹配，跳过解压，秒级启动 |
| 升级 APK（未清数据） | 标记版本 < 内置版本 → 增量重解压一次（仅当新版内置了新的 runtime 包时；仅改 UI 代码则版本号不变，不重解压） |
| 用户清除应用数据 | runtime 目录被清 → 下次启动重新完整解压 |
| 卸载重装 | 同上 |
| 手动"关闭 dsh"后再次打开应用 | 沙盒标记仍在 → 跳过解压，控制台重新"启动 dsh"即可（秒级） |
| 解压中断（被杀/断电） | 用"先解压到临时目录 → 成功后原子替换 + 写标记"策略，中断不会留下半个坏 runtime |

> 解压采用 **临时目录 + 原子切换**：解压到 `runtime.tmp/`，完成后 `mv` 成 `runtime/` 并写版本标记，避免中断导致半成品 rootfs。

## 4. 宿主机构建流程（里程碑 2：已固化为脚本）

`runtime-builder/` 下脚本，`./build.sh` 一键串联（0→1→2→3，幂等可重复执行；改 `config.sh` 版本号后重跑即得新包）：

```
runtime-builder/
├── config.sh             # 版本/镜像/目录集中配置；runtime_version() 生成版本号
│                         #   例：dsh-0.1.1-rc.2_node-24.1.0_r1
├── build.sh              # 一键编排入口
├── 0-build-proot.sh      # 交叉编译静态 arm64 proot（termux/proot@v5.1.107.92）
│                         #   + 从镜像拉 arm64 libtalloc.a 静态链接 → work/proot
├── 1-download-rootfs.sh  # debootstrap arm64 minbase（ubuntu-ports 镜像）
│                         #   + 手动补运行库（--foreign 下 --include 不生效）：
│                         #     libstdc++6 libgcc-s1 bash libtinfo6 libpcre2-8-0
├── 2-assemble.sh         # 解压官方 node-linux-arm64 → rootfs
│                         #   + qemu 下 npm i -g @deepseek-ai/dsh（自动选中
│                         #     arm64 预编译包，零编译）+ 校验 dsh/sharp 加载
├── 3-package.sh          # 精简（清 npm 缓存/文档/测试/*.map）+ 写 dsh-web.sh
│                         #   + 压缩 runtime.tar.xz + 写 runtime.version
│                         #   + 附带 proot 二进制 → dist/
└── dist/                 # 产物：runtime.tar.xz（100M）、runtime.version、proot（977K）
```

Android 工程（`app/`，Kotlin + Gradle）引 `dist/` 产物（`runtime.tar.xz`、`runtime.version`、`proot`）打 assets 构建 APK。

构建要点：
- x86 宿主机用 `qemu-aarch64-static -L <rootfs> <binary>` 显式执行 arm64 二进制（无 binfmt_misc 也能跑）
- proot 必须**静态链接**（运行在 Android 宿主 bionic 上，不能依赖 glibc 动态库）；交叉编译时须在命令行显式传 `CC=aarch64-linux-gnu-gcc`（环境变量里的 `CC=cc` 不会被 `?=` 覆盖）
- arm64 单一 ABI：arch.h 对 ARM64 `#undef HAS_LOADER_32BIT`（须 undef 而非置 false，GNUmakefile 用 ifdef 判断非空即构建 32 位 loader，而 aarch64 无 `-m32`）

## 5. 里程碑

1. **Spike 验证 ✅（已完成）**：结论见下"里程碑 1 结果"
2. **runtime-builder 脚本 ✅（已完成）**：结论见下"里程碑 2 结果"
3. Android 壳工程（控制台 + 预览页 + 设置 + 沙盒文件浏览/导入导出 + 前台服务 + proot 启动 + RuntimeManager 解压/版本管理 + 沙盒/dsh 生命周期开关）
4. 真机联调（端口就绪检测、后台保活、发热/耗电）
5. 打包签名，交付自用 APK

### 里程碑 1 结果（2026-08-31 实测）

| 验证项 | 结果 |
|---|---|
| dsh 安装/运行（x64 主机） | ✅ dsh 0.1.1-rc.2，npm 装 511 包，`dsh web` 正常 |
| Web UI 出页面 | ✅ HTTP 200，`<title>DeepSeek Harness</title>`，端口 3080 |
| 原生模块 arm64 预编译包 | ✅ node-pty `prebuilds/linux-arm64/pty.node`；koffi `@koromix/koffi-linux-arm64`；sharp `@img/sharp-linux-arm64`+`sharp-libvips-linux-arm64`，`sharp-wasm32` 自动随装。**零编译** |
| arm64 运行时（qemu-aarch64-static -L 模拟） | ✅ arm64 node v24.1.0（`arch: arm64, platform: linux`）正常；arm64 下 npm 装 dsh 自动选中 arm64 预编译包；`dsh web` HTTP 200 出页面 |
| 启动命令 | ✅ `node --expose-internals .../dsh/lib/bin.js web --no-open` 可行 |

> 沙盒环境限制：容器无 CAP_SYS_ADMIN，无法注册 binfmt_misc；用 `qemu-aarch64-static -L <rootfs> <binary>` 显式执行 arm64 二进制绕过。真机上的最终运行验证仍属里程碑 4。
> 实测体积参考：含 dsh 的 arm64 rootfs 未精简 ~564M（dsh 包 + npm 缓存为主），精简后 `runtime.tar.xz` **100M**（xz -T0）。

### 里程碑 2 结果（2026-08-31 实测）

| 验证项 | 结果 |
|---|---|
| proot 静态 arm64 二进制 | ✅ 交叉编译成功（termux/proot v5.1.107.92 + 静态 libtalloc.a），strip 后 977K；`qemu -V` 正常。qemu-user 下 ptrace 受限无法整链模拟，真机原生验证属里程碑 4 |
| runtime-builder 脚本幂等 | ✅ `./build.sh` 从零/重复执行均可跑通（0→1→2→3） |
| 装配 | ✅ arm64 node v24.1.0 + dsh 0.1.1-rc.2（npm 511 包）；sharp/koffi/node-pty 均为 arm64 预编译包，零编译 |
| 打包产物 | ✅ `dist/`：runtime.tar.xz 100M、runtime.version=`dsh-0.1.1-rc.2_node-24.1.0_r1`、proot 977K |
| 从产物启动 dsh web | ✅ 解压 runtime.tar.xz → qemu 模拟 arm64 跑 `dsh web --no-open`，输出就绪信号 `dsh web: http://127.0.0.1:3080`，curl **HTTP 200** |
| 启动脚本 | ✅ rootfs 内 `/usr/local/bin/dsh-web.sh`（HOME/DSH_HOME + `node --expose-internals ... web --no-open`） |

> 验证踩坑：`env` 是宿主 x86-64 二进制，不能放在 `qemu ... ` 后面当来宾程序执行（会静默退出）；正确写法 `env VAR=... qemu-aarch64-static ... node ...`。

### 里程碑 3 构建与自检说明（Android 壳工程）

> 本沙盒环境**无 Android SDK**，壳工程源码已完整就绪，需在装有 Android Studio 的机器上构建。

- 构建：Android Studio 打开 `deepseek-harness-android/`，等待 Gradle 同步后 `Build → Build APK(s)`。
  - 需先跑 `runtime-builder/build.sh` 生成 `dist/`（runtime.tar.xz + runtime.version + proot），`preBuild` 会自动把它们同步进 `assets/runtime/` 并生成 `runtime.sha256`；产物缺失时构建跳过 assets 同步（仅缺运行环境，APK 仍可出包，首启会提示）。
- 自检点：
  1. 首启：控制台显示"解压中"进度条 → "已就绪 + 版本号" → dsh"启动中"→"运行中"。
  2. 预览页：dsh 运行中自动加载 `http://127.0.0.1:3080`；切 tab 回来 WebView 不丢会话。
  3. 文件页：浏览 `/root`，隐藏 usr/etc/proc 等系统目录；导入/导出走 SAF。
  4. 设置页：自启/挂载 sdcard/运行模式即时保存；清除沙盒数据后下次启动重解压。
  5. 二次启动：跳过解压，秒级启动（`logs/dsh.log` 有 `runtime 就绪` 记录）。
- 命令行自检（宿主机先跑 runtime-builder 后）：
  ```
  cd deepseek-harness-android
  ./gradlew :app:assembleDebug          # 需 Android SDK（local.properties 或 ANDROID_HOME）
  ls app/build/outputs/apk/debug/app-debug.apk
  ```

## 6. 风险与待验证点

- **node-pty 在 arm64 下编译** ✅ 已排除：官方提供 linux-arm64 预编译包，无需编译
- **手机性能**：标准模式 Agent 循环在手机上偏慢，可能要默认极简模式
- **后台保活**：部分国产 ROM 激进杀后台，需引导用户加白名单
- **dsh 版本漂移**：dev 预览期破坏性变更，锁版本（当前锁 0.1.1-rc.2）
- **首启体积**：rootfs 压缩后约 150~250MB，首启解压约 1~2 分钟（仅首次）
- **proot 在真机的 syscall 兼容性**：需真机验证（里程碑 4）

## 7. v0.5.0 版本说明（本次发布）

**新增 / 优化**
- 「移动端适配」开关（默认关）：预览 WebView 注入 viewport 与自适应 CSS，缓解 dsh 桌面界面在手机上的挤压 / 左右拥挤 / 内容显示不完整。
- 「DSH 恢复默认设置」：移除自装/自研插件层（`profiles/`）与用户配置 overlay（`cordis.patch.yml`），回到仅内置官方插件，修复因插件损坏导致的无法启动；保留 API Key、对话内容与产出文件。
- 修复重启 dsh 后预览页画面重叠：`ShellUiState` 增加 `dshEpoch`，dsh 每次成功启动/重启递增，预览 WebView 据此在重启后 `reload` 匹配新进程。
- 运行日志每条带毫秒级时间戳（`yyyy-MM-dd HH:mm:ss.SSS`），便于查看/导出定位。
- 内置开源 `dshmarket` 可视化插件市场（GitHub [dsh-market/dsh-market](https://github.com/dsh-market/dsh-market)，npm 包 dshmarket@1.38.1，含前端 client bundle，在 dsh Web 界面浏览/搜索/一键安装/管理社区插件）。随 APK assets 携带，运行时由 `RuntimeManager.installDshMarket()` 注入沙盒 dsh 的 `node_modules`，通过 `dsh-web.sh --patch` 加载。

**版本**：0.4.9 → 0.5.0（versionCode 15）

**待真机验证**
- `dshmarket`（开源插件 v1.38.1，相对较新）在 dsh 0.1.1-rc.2 上的 cordis 加载与前端渲染（本环境无法真机验证；若 dsh 启动异常可用「DSH 恢复默认设置」回退，且因采用 assets 携带未改动 runtime.tar.xz，可安全回退）。
- 移动端适配 CSS 对 dsh 设置页 / 侧栏的具体效果（通用 selector 需真机微调）。
