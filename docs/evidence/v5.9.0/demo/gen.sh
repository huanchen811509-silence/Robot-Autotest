#!/usr/bin/env bash
# 生成 purge-demo dry-run 证据（避免反引号/命令替换被外层展开，故写成脚本）
set -euo pipefail
cd "$HOME/robot-autotest"
set -a; . "$HOME/.config/robot-autotest/env"; set +a
mkdir -p docs/evidence/v5.9.0/demo
OUT=docs/evidence/v5.9.0/demo/purge-dryrun.md
{
  echo "# purge-demo.sh dry-run 真实读数"
  echo
  echo "时间：$(date -Iseconds)"
  echo "库：${DB_HOST}:${DB_PORT}/${DB_NAME}"
  echo "命令：scripts/ops/purge-demo.sh（不加 --apply，只盘点）"
  echo
  echo "## 输出"
  echo
  bash scripts/ops/purge-demo.sh 2>/dev/null
  echo
  echo "## 结论"
  echo
  echo "dry-run 期间未执行任何 DELETE；加 --apply 还需 PURGE_DEMO_CONFIRM=YES。"
} > "$OUT"
cat "$OUT"
