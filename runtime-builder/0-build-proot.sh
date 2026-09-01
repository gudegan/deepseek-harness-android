#!/usr/bin/env bash
# 步骤 0：交叉编译静态 arm64 proot（运行在 Android 宿主机，随 APK 打包）
# 产物：$WORK_DIR/proot（静态 arm64，strip 后 ~1MB）
# 幂等：proot 已生成则跳过；强制重建：rm -f work/proot
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

PROOT_SRC="$WORK_DIR/proot-src"
TALLOC_ROOT="$WORK_DIR/talloc-root"
PROOT_REPO="${PROOT_REPO:-https://github.com/termux/proot.git}"
PROOT_TAG="${PROOT_TAG:-v5.1.107.92}"   # 锁定的 termux proot 版本

CROSS="${CROSS:-aarch64-linux-gnu-}"
[ -x "$(command -v ${CROSS}gcc)" ] || { echo "[0] 缺少交叉编译器 ${CROSS}gcc（apt install gcc-aarch64-linux-gnu）"; exit 1; }

if [ -x "$WORK_DIR/proot" ]; then
  echo "[0] proot 已生成，跳过：$WORK_DIR/proot"
  exit 0
fi

# 0a. 获取 proot 源码
if [ ! -d "$PROOT_SRC/.git" ]; then
  echo "[0] clone termux/proot @ $PROOT_TAG ..."
  git clone --depth 1 --branch "$PROOT_TAG" "$PROOT_REPO" "$PROOT_SRC"
fi

# 0b. 静态链接需要 libtalloc.a（arm64），从镜像拉 .deb 解出
if [ ! -f "$TALLOC_ROOT/usr/lib/aarch64-linux-gnu/libtalloc.a" ]; then
  echo "[0] 准备 arm64 libtalloc 静态库 ..."
  mkdir -p "$WORK_DIR/debs"
  PKGS_INDEX="$WORK_DIR/debs/Packages.gz"
  [ -f "$PKGS_INDEX" ] || curl -fsSL -o "$PKGS_INDEX" "$MIRROR/dists/$SUITE/main/binary-arm64/Packages.gz"
  fetch_deb_fn() { # $1=包名 → 输出 .deb 路径
    local fn
    fn="$(zcat "$PKGS_INDEX" | awk -v p="$1" 'BEGIN{f=0} /^Package: /{f=($2==p)} f&&/^Filename:/{print $2; exit}')"
    [ -n "$fn" ] || { echo "[0] 找不到 $1"; return 1; }
    local out="$WORK_DIR/debs/$(basename "$fn")"
    [ -f "$out" ] || curl -fsSL -o "$out" "$MIRROR/$fn"
    echo "$out"
  }
  for pkg in libtalloc-dev libtalloc2; do
    deb="$(fetch_deb_fn "$pkg")" || exit 1
    dpkg-deb -x "$deb" "$TALLOC_ROOT"
  done
fi

# 0c. 打补丁：arm64 单一 ABI，禁用 32 位 loader（aarch64 无 -m32）
#     必须 #undef 而非置 false —— GNUmakefile 用 ifdef（非空即启用）
ARCH_H="$PROOT_SRC/src/arch.h"
if ! grep -q '#undef HAS_LOADER_32BIT' "$ARCH_H"; then
  echo "[0] 补丁 arch.h：ARM64 禁用 32 位 loader ..."
  sed -i 's/    #define HAS_LOADER_32BIT true/    #undef HAS_LOADER_32BIT/' "$ARCH_H"
fi

# 0d. 交叉编译（静态）。注意：
#     - 环境变量里可能有 CC=cc，须在命令行显式传 CC 覆盖（Makefile 用 ?= 不会覆盖环境变量）
#     - CPPFLAGS/LDFLAGS 用环境变量传，Makefile 里是 +=，会被追加而非覆盖
echo "[0] 交叉编译 proot（${CROSS}，静态）..."
make -C "$PROOT_SRC/src" CROSS_COMPILE="$CROSS" CC="${CROSS}gcc" clean >/dev/null 2>&1 || true
env \
  CPPFLAGS="-I$TALLOC_ROOT/usr/include" \
  LDFLAGS="-static -L$TALLOC_ROOT/usr/lib/aarch64-linux-gnu" \
  make -C "$PROOT_SRC/src" CROSS_COMPILE="$CROSS" CC="${CROSS}gcc"

# 0e. strip 并放入 work/，供 3-package.sh 附带
echo "[0] strip + 安装 proot → $WORK_DIR/proot ..."
${CROSS}strip "$PROOT_SRC/src/proot"
cp "$PROOT_SRC/src/proot" "$WORK_DIR/proot"

echo "[0] OK: $(file -b "$WORK_DIR/proot")"
