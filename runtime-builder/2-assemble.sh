#!/usr/bin/env bash
# 步骤 2：向 rootfs 装配 node + dsh（arm64 官方预编译包，无需编译工具链）
# 幂等：node/dsh 已存在则跳过对应步骤
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

[ -x "$ROOTFS_DIR/bin/sh" ] || { echo "[2] rootfs 不存在，先跑 1-download-rootfs.sh"; exit 1; }
[ -x "$(command -v "$QEMU_BIN")" ] || { echo "[2] 缺少 $QEMU_BIN（qemu-user）"; exit 1; }

# 2a. 解压官方 node-linux-arm64
if [ ! -x "$ROOTFS_DIR/usr/local/bin/node" ]; then
  TARBALL="$WORK_DIR/node-v${NODE_VERSION}-linux-arm64.tar.xz"
  if [ ! -f "$TARBALL" ]; then
    echo "[2] 下载 node-v${NODE_VERSION}-linux-arm64 ..."
    curl -fsSL -o "$TARBALL" \
      "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz"
  fi
  mkdir -p "$ROOTFS_DIR/usr/local"
  tar -xJf "$TARBALL" -C "$ROOTFS_DIR/usr/local" --strip-components=1
fi

# 2b. 校验 arm64 node 可执行（qemu-user 显式执行，-L 指定 sysroot）
echo "[2] 校验 arm64 node ..."
"$QEMU_BIN" -L "$ROOTFS_DIR" "$ROOTFS_DIR/usr/local/bin/node" -e \
  "console.log('node', process.version, process.arch, process.platform)"

# 2c. 在 arm64 下用 npm 安装 dsh（锁定版本，自动选中 arm64 预编译包）
NPM_CLI="$ROOTFS_DIR/usr/local/lib/node_modules/npm/bin/npm-cli.js"
if [ ! -d "$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh" ]; then
  echo "[2] npm 安装 @deepseek-ai/dsh@${DSH_VERSION}（arm64 qemu 模拟，约数分钟）..."
  env -u NODE_OPTIONS HOME=/root \
    "$QEMU_BIN" -L "$ROOTFS_DIR" \
    "$ROOTFS_DIR/usr/local/bin/node" "$NPM_CLI" \
    install -g --no-audit --no-fund --cache "$WORK_DIR/npm-cache" \
    "@deepseek-ai/dsh@${DSH_VERSION}"
fi

# 2d. 校验 dsh 与 arm64 原生预编译包
echo "[2] 校验 dsh 与 arm64 原生模块 ..."
env -u NODE_OPTIONS HOME=/root "$QEMU_BIN" -L "$ROOTFS_DIR" \
  "$ROOTFS_DIR/usr/local/bin/node" \
  "$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" --version

NM="$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules"
for pkg in \
  "$NM/@koromix/koffi-linux-arm64" \
  "$NM/@img/sharp-linux-arm64" \
  "$NM/@img/sharp-wasm32" \
  "$NM/node-pty/prebuilds/linux-arm64/pty.node"; do
  [ -e "$pkg" ] || { echo "[2] 缺少 arm64 预编译包: $pkg"; exit 1; }
done

# 2e. arm64 下加载 sharp，确认 libvips 依赖齐全
echo "[2] 校验 sharp（arm64 加载）..."
env -u NODE_OPTIONS HOME=/root "$QEMU_BIN" -L "$ROOTFS_DIR" \
  "$ROOTFS_DIR/usr/local/bin/node" -e \
  "require('$NM/sharp'); console.log('sharp OK')"

echo "[2] OK: dsh@${DSH_VERSION} 装配完成"
