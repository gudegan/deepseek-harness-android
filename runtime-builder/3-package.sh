#!/usr/bin/env bash
# 步骤 3：精简 rootfs → 压缩 runtime.tar.xz → 生成 runtime.version + 启动脚本 → dist/
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

[ -d "$ROOTFS_DIR/usr/local/lib/node_modules/@deepseek-ai/dsh" ] || \
  { echo "[3] dsh 未装配，先跑 2-assemble.sh"; exit 1; }

echo "[3] 精简 rootfs ..."
rm -rf "$WORK_DIR/npm-cache"

# 清 npm 安装中的测试/文档等冗余（保守，仅清明确无用的）
find "$ROOTFS_DIR/usr/local/lib/node_modules" -type d \
  \( -name test -o -name tests -o -name __tests__ -o -name docs \) \
  -prune -exec rm -rf {} + 2>/dev/null || true
find "$ROOTFS_DIR/usr/local/lib/node_modules" -name "*.map" -delete 2>/dev/null || true

# 启动脚本（在 proot 沙盒内执行）
echo "[3] 写入 /usr/local/bin/dsh-web.sh ..."
mkdir -p "$ROOTFS_DIR/usr/local/bin"
cat > "$ROOTFS_DIR/usr/local/bin/dsh-web.sh" <<'EOF'
#!/bin/sh
# dsh web 启动脚本（运行在 proot 沙盒内）
export HOME="${HOME:-/root}"
export DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
exec node --expose-internals /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --no-open
EOF
chmod +x "$ROOTFS_DIR/usr/local/bin/dsh-web.sh"

# 版本号
RUNTIME_VER="$(runtime_version)"
echo "[3] runtime.version = $RUNTIME_VER"
mkdir -p "$DIST_DIR"
echo "$RUNTIME_VER" > "$DIST_DIR/runtime.version"

# 压缩（多线程 xz）
echo "[3] 压缩 runtime.tar.xz（含 dsh 的 rootfs，$(du -sh "$ROOTFS_DIR" | cut -f1)）..."
tar -C "$ROOTFS_DIR" --use-compress-program='xz -T0' -cf "$DIST_DIR/runtime.tar.xz" .

# 附带 proot 二进制（若已构建/下载）
if [ -x "$WORK_DIR/proot" ]; then
  cp "$WORK_DIR/proot" "$DIST_DIR/proot"
  echo "[3] 附带 proot 二进制"
fi

echo "[3] OK，产出："
ls -lh "$DIST_DIR"
