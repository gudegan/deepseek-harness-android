# DeepSeek Harness Android（DSH 安卓移植版）

在 Android 手机上 **免 ROOT、免 Termux** 直接运行 [DeepSeek Harness（dsh）](https://github.com/deepseek-ai/deepseek-harness)——DeepSeek 官方的 Agent 运行时。

实现思路：用 **PRoot 用户态沙箱** 在 App 私有目录里模拟一个 Linux 根文件系统，把 Debian rootfs + Node.js + npm + dsh 全部装进 APK，安装即用，无需任何外部依赖。

> 项目仅为个人/社区学习用途，非 DeepSeek 官方出品。

## 功能特性

- **免 ROOT**：PRoot 是纯用户态实现，不依赖内核特权。
- **免 Termux**：运行时与启动器一体打包，不依赖第三方终端环境。
- **装完即用**：首次启动解压 rootfs，一键拉起 dsh web（http://127.0.0.1:3080）。
- **仅 arm64-v8a**：与社区同类项目一致，面向现代 Android 手机。
- 预装 DSH 社区插件：`dshmarket`（插件市场）、`dsh-zen-remote`（移动端适配）、`dsh-memory-evolve`（分层记忆/待办/技能）。
- 运行日志一键复制 / 一键分享到飞书 App（系统分享，非插件）。

## 技术架构

- **PRoot 沙箱**：以 `-r rootfs -b /dev -b /proc -b /sys` 挂载宿主目录，可选挂载 `/sdcard`。
- **rootfs**：Debian base（arm64），内置 Node.js、npm 与 `@deepseek-ai/dsh`。
- **进程模型**：`ProcessManager` 用 proot 拉起 `/usr/local/bin/dsh-web.sh` → node 启动 dsh，监听 stdout 就绪信号。
- **WebUI**：App 内嵌 WebView 或系统浏览器打开 `http://127.0.0.1:3080`。
- **签名**：自用签名，`keystore/` 已 gitignore，不入库。
- **runtime 资产**：`app/src/main/assets/runtime/`（约 100M+）由 Gradle 任务从 `runtime-builder/dist` 同步生成，gitignore，不入库。

## 环境要求（构建）

- JDK 17
- Android SDK（`compileSdk 34`，`minSdk 26`，`targetSdk 34`）
- Gradle（项目未内置 `gradlew`，用系统 Gradle 8.x，如 `gradle assembleRelease`）

## 构建 APK

```bash
# 需先在本机 Android SDK 与 JDK 17；项目目录下执行
gradle assembleRelease --no-daemon
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 构建前需准备签名：`keystore/keystore.properties`（含 `storeFile`/密码等）。没有则 APK 以 debug 签名或构建失败（自用签名不入库）。

## 发布到 Git / GitHub

本仓库**未包含** `runtime/`（大文件）与 `keystore/`（签名口令），`.gitignore` 已排除。发布步骤如下：

### 1. 初始化仓库

```bash
cd deepseek-harness-android
git init
git add .
git commit -m "init: DeepSeek Harness Android 移植版"
```

### 2. 关联远程并推送

```bash
# 在 GitHub 上新建空仓库后
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git branch -M main
git push -u origin main
```

### 3. 打标签发布（可选）

```bash
git tag -a v0.5.20 -m "DeepSeek Harness Android v0.5.20"
git push origin v0.5.20
```

### 4. 处理大文件（runtime）

`runtime.tar.xz` 等大文件不入 git（体积大、由构建生成）。如需分发含完整运行时的产物，用 **GitHub Releases** 上传 APK（而非放进 git 历史）：

```bash
# 用 GitHub CLI 或网页上传到 Release
gh release create v0.5.20 app/build/outputs/apk/release/app-release.apk
```

## 目录说明

```
deepseek-harness-android/
├── app/                # Android 应用（Kotlin）
│   └── src/main/
│       ├── assets/     # 预装插件、skills、runtime（runtime gitignore）
│       ├── java/       # 源码（MainActivity / RuntimeManager / 各 Fragment）
│       └── res/        # 布局、图标、主题
├── runtime-builder/    # 构建 Debian rootfs + Node + dsh 的脚本（生成 runtime.tar.xz）
├── docs/               # 设计文档（DESIGN.md）
├── keystore/           # 签名（gitignore，勿入库）
└── build.gradle.kts    # 应用构建配置
```

## ⚠️ 注意事项

- **切勿提交** `keystore/`（签名口令）、`local.properties`（本机 SDK 路径）、`.gradle/`、`app/build/`——`.gitignore` 已排除。
- **勿把 runtime/ 大文件提交进 git 历史**（用 Release 分发 APK 更合适）。
- 用 `git status` 检查敏感文件是否误入库，可用 `git rm --cached <文件>` 从暂存区移除。
- 本项目依赖 DSH 社区插件（dshmarket/dsh-zen-remote/dsh-memory-evolve），其许可证与版权遵循各自仓库。

## 许可

本项目基于 [MIT 许可证](LICENSE) 开源，版权归项目作者所有。所依赖的 DSH 社区插件（dshmarket / dsh-zen-remote / dsh-memory-evolve 等）的许可证与版权遵循各自仓库。
