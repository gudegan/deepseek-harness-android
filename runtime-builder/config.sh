#!/usr/bin/env bash
# runtime-builder 公共配置（里程碑 2）
# 修改版本号后重跑 1→2→3 即可生成新的 runtime 包

set -euo pipefail

# 版本配置
DSH_VERSION="${DSH_VERSION:-0.1.1-rc.2}"   # 锁定的 dsh 版本（npm）
NODE_VERSION="${NODE_VERSION:-24.1.0}"      # 官方 Node 版本（linux-arm64）
SUITE="${SUITE:-noble}"                     # debootstrap 发行版代号
MIRROR="${MIRROR:-http://ports.ubuntu.com/ubuntu-ports/}"
RUNTIME_REV="${RUNTIME_REV:-r5}"            # 打包修订号（打包内容变化时递增；r5=本次内置包，触发升级重解压）

# 目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="${WORK_DIR:-$SCRIPT_DIR/work}"
DIST_DIR="${DIST_DIR:-$SCRIPT_DIR/dist}"
ROOTFS_DIR="$WORK_DIR/rootfs"

# 工具
QEMU_BIN="${QEMU_BIN:-qemu-aarch64-static}"   # 沙盒内需可执行 arm64 二进制（qemu-user）

# 生成 runtime 版本号（写入 runtime.version，供 APK 做一次性解压/增量重解压比对）
runtime_version() { echo "dsh-${DSH_VERSION}_node-${NODE_VERSION}_${RUNTIME_REV}"; }
