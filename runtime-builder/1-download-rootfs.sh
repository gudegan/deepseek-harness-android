#!/usr/bin/env bash
# 步骤 1：下载并解出 arm64 最小 rootfs（debootstrap --foreign + 补运行库）
# 幂等：rootfs 已存在则跳过；如需强制重建：rm -rf work/rootfs 后重跑
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

[ -x "$(command -v debootstrap)" ] || { echo "缺少 debootstrap，先安装：apt install debootstrap"; exit 1; }

# debootstrap --foreign 模式下 --include 不会解包，运行库需单独从镜像拉 .deb 解入
# （node/dsh 运行所需：libstdc++6、libgcc-s1、bash 及其依赖）
EXTRA_DEBS="libstdc++6 libgcc-s1 bash libtinfo6 libpcre2-8-0"

fetch_deb() { # $1=包名 → 输出下载到的 .deb 路径
  local pkg="$1"
  local fn
  fn="$(curl -s "$MIRROR/dists/$SUITE/main/binary-arm64/Packages.gz" | \
        zcat | awk -v p="$pkg" 'BEGIN{f=0} /^Package: /{f=($2==p)} f&&/^Filename:/{print $2; exit}')"
  [ -n "$fn" ] || { echo "  [1] 找不到 $pkg 的 arm64 包"; return 1; }
  local out="$WORK_DIR/debs/$(basename "$fn")"
  [ -f "$out" ] || curl -fsSL -o "$out" "$MIRROR/$fn"
  echo "$out"
}

if [ -x "$ROOTFS_DIR/bin/sh" ] && [ -e "$ROOTFS_DIR/lib/aarch64-linux-gnu/libstdc++.so.6" ]; then
  echo "[1] rootfs 已就绪，跳过下载：$ROOTFS_DIR"
else
  if [ ! -x "$ROOTFS_DIR/bin/sh" ]; then
    echo "[1] debootstrap arm64 minbase (suite=$SUITE) ..."
    mkdir -p "$WORK_DIR"
    debootstrap --arch=arm64 --foreign --variant=minbase \
      "$SUITE" "$ROOTFS_DIR" "$MIRROR"
  fi

  echo "[1] 补充运行库：$EXTRA_DEBS"
  mkdir -p "$WORK_DIR/debs"
  for pkg in $EXTRA_DEBS; do
    printf "  %-14s " "$pkg"
    deb="$(fetch_deb "$pkg")" || exit 1
    dpkg-deb -x "$deb" "$ROOTFS_DIR"
    echo "OK"
  done
fi

echo "[1] 校验基础运行库..."
for lib in \
  "$ROOTFS_DIR"/lib/aarch64-linux-gnu/libstdc++.so.6 \
  "$ROOTFS_DIR"/lib/aarch64-linux-gnu/libgcc_s.so.1 \
  "$ROOTFS_DIR/bin/bash" \
  "$ROOTFS_DIR"/lib/aarch64-linux-gnu/libtinfo.so.6 \
  "$ROOTFS_DIR"/usr/lib/aarch64-linux-gnu/libpcre2-8.so.0; do
  [ -e "$lib" ] || { echo "[1] 缺少 $lib"; exit 1; }
done
echo "[1] OK: rootfs 就绪（$(du -sh "$ROOTFS_DIR" | cut -f1)）"
