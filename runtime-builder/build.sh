#!/usr/bin/env bash
# runtime-builder 一键构建（里程碑 2：固化 runtime 构建流程）
# 用法：./build.sh        # 完整构建（幂等，可重复执行；改 config.sh 版本号后重跑即得新包）
#  强制重建某一步：rm -rf work/rootfs（步骤1）/ rm -f work/proot（步骤0）后重跑
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

for step in 0-build-proot 1-download-rootfs 2-assemble 3-package; do
  echo "============================= $step ============================="
  "./$step.sh"
done

echo
echo "构建完成，产物在 dist/："
ls -lh dist/
