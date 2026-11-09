#!/usr/bin/env bash
# 日志查看（只读）。默认打印最近 80 行；-f 持续跟随；--service 选后端或入口。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

SERVICE="all"
LINES=80
FOLLOW=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --service) need_arg "$@"; SERVICE="$2"; shift 2 ;;
    --lines) need_arg "$@"; LINES="$2"; shift 2 ;;
    -f|--follow) FOLLOW=1; shift ;;
    --help|-h)
      printf '用法: logs.sh [--service backend|entry|all] [--lines N] [-f]\n'; usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

case "$SERVICE" in
  backend) FILES=("$LOG_DIR/backend.log") ;;
  entry)   FILES=("$LOG_DIR/entry.log") ;;
  all)     FILES=("$LOG_DIR/backend.log" "$LOG_DIR/entry.log") ;;
  *) die "--service 只能是 backend / entry / all" ;;
esac

found=0
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    warn "日志不存在：$f"
    continue
  fi
  found=1
  printf '\n===== %s =====\n' "$f"
  if [[ "$FOLLOW" == "1" ]]; then
    # 多文件时 -f 只跟最后一个，避免互相阻塞；单文件是常见用法。
    tail -n "$LINES" -f "$f"
  else
    tail -n "$LINES" "$f"
  fi
done
[[ "$found" == "1" ]] || die "没有可查看的日志（先启动服务或检查 ROBOT_LOG_DIR）"
