#!/usr/bin/env bash
# 运行态总览（只读）：版本 / 进程 / 端口 / 健康 / 最近日志 / 备份时间。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) printf '用法: status.sh\n'; usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

printf '==============================================================\n'
printf ' AIDC AutoTest 运行态  %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
printf '==============================================================\n'
printf ' 版本        %s\n' "$(version_of)"
printf ' 布局        %s\n' "$LAYOUT"
printf ' 安装根      %s\n' "$APP_HOME"
printf ' 环境文件    %s\n' "$([[ -f "$ENV_FILE" ]] && echo '已配置' || echo '缺失')"
printf ' 日志目录    %s\n' "$LOG_DIR"
printf '--------------------------------------------------------------\n'

for port in "$BACKEND_PORT" "$ENTRY_PORT"; do
  if port_open "$port"; then
    printf ' 端口 %-6s LISTEN\n' ":$port"
  else
    printf ' 端口 %-6s DOWN\n' ":$port"
  fi
done

for url in "http://127.0.0.1:$ENTRY_PORT/healthz" "http://127.0.0.1:$BACKEND_PORT/actuator/health"; do
  printf ' %-46s %s\n' "$url" "$(http_code "$url")"
done

if has_cmd systemctl; then
  for unit in robot-autotest-backend.service robot-autotest-entry.service; do
    if systemctl --user cat "$unit" >/dev/null 2>&1; then
      state="$(systemctl --user is-active "$unit" 2>/dev/null || true)"
      printf ' unit %-38s %s\n' "$unit" "$state"
    else
      printf ' unit %-38s %s\n' "$unit" '未安装（走 pid 文件 / nohup）'
    fi
  done
fi

for f in "$RUN_DIR/backend.pid" "$RUN_DIR/entry.pid"; do
  if [[ -f "$f" ]]; then
    pid="$(cat "$f")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      printf ' pid  %-38s %s (alive)\n' "$(basename "$f")" "$pid"
    else
      printf ' pid  %-38s %s (stale)\n' "$(basename "$f")" "$pid"
    fi
  fi
done

printf '--------------------------------------------------------------\n'
for f in "$LOG_DIR/backend.log" "$LOG_DIR/entry.log"; do
  if [[ -f "$f" ]]; then
    printf ' 日志 %-38s %s 字节 / %s\n' "$(basename "$f")" "$(wc -c < "$f" | tr -d ' ')" "$(date -r "$f" '+%Y-%m-%d %H:%M')"
  else
    printf ' 日志 %-38s %s\n' "$(basename "$f")" '无'
  fi
done

if [[ -d "$BACKUP_DIR" ]]; then
  latest="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.sql.gz' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 || true)"
  if [[ -n "$latest" ]]; then
    printf ' 最近备份    %s\n' "${latest#* }"
  else
    printf ' 最近备份    无\n'
  fi
else
  printf ' 最近备份    无（备份目录不存在）\n'
fi
printf '==============================================================\n'
