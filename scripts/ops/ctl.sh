#!/usr/bin/env bash
# 服务启停（FR-v3.11-D02）：start / stop / restart，只认本安装根的进程。
#
#   ./bin/ctl.sh start
#   ./bin/ctl.sh stop
#   ./bin/ctl.sh restart
#
# 与 scripts/deploy.sh 的区别：deploy.sh 会**重新构建**；ctl.sh 只动已安装产物，
# 适用于交付包（里面没有源码、没有 Maven / npm）。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

ACTION="${1:-}"
case "$ACTION" in
  start|stop|restart|status) ;;
  --help|-h)
    printf '用法: ctl.sh {start|stop|restart|status}\n'; usage_tail; exit 0 ;;
  *) die "用法: ctl.sh {start|stop|restart|status}" ;;
esac

[[ "$ACTION" == "status" ]] && { exec "$OPS_DIR/status.sh"; }

mkdir -p "$LOG_DIR" "$RUN_DIR"

JAR=""
if jar_path >/dev/null 2>&1; then JAR="$(jar_path)"; fi

# systemd --user unit 是**用户级全局资源**：开发机的 unit 指向 ~/robot-autotest，
# 与临时解包目录无关。若只判「unit 存在」就 stop，会把别处正在跑的服务停掉。
# 因此只有本安装根带着 install.sh --systemd 写的托管标记，才允许碰 systemd。
use_systemd() {
  [[ -f "$RUN_DIR/systemd-managed" ]] || return 1
  has_cmd systemctl || return 1
  systemctl --user cat "$1" >/dev/null 2>&1
}

stop_by_pidfile() {
  local file="$1" pid
  [[ -f "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  # 只杀 pid 文件记录的进程；pid 文件是本脚本自己写的，不扫全机。
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    log "停止 pid=$pid ($(basename "$file"))"
    kill "$pid" 2>/dev/null || true
    sleep 2
  fi
  rm -f "$file"
}

stop_services() {
  if use_systemd robot-autotest-entry.service; then
    log '停止 systemd unit robot-autotest-entry'
    systemctl --user stop robot-autotest-entry.service || true
  fi
  if use_systemd robot-autotest-backend.service; then
    log '停止 systemd unit robot-autotest-backend'
    systemctl --user stop robot-autotest-backend.service || true
  fi
  stop_by_pidfile "$RUN_DIR/entry.pid"
  stop_by_pidfile "$RUN_DIR/backend.pid"
  for port in "$BACKEND_PORT" "$ENTRY_PORT"; do
    for _ in $(seq 1 10); do
      port_open "$port" || break
      sleep 1
    done
  done
}

start_services() {
  [[ -n "$JAR" ]] || die "缺少后端 jar：$JAR_DIR（交付包不完整，先跑 bin/doctor.sh）"
  [[ -f "$WEB_DIR/index.html" ]] || die "缺少前端产物：$WEB_DIR/index.html"
  [[ -f "$ENTRY_FILE" ]] || die "缺少统一入口：$ENTRY_FILE"
  load_env || die "缺少凭据文件 $ENV_FILE，请先按 docs/DELIVERY-GUIDE.md 配置"
  : "${DB_PASSWORD:?环境文件未给 DB_PASSWORD}"

  if use_systemd robot-autotest-backend.service && use_systemd robot-autotest-entry.service; then
    log '托管方式：systemd --user'
    systemctl --user start robot-autotest-backend.service
  else
    log '托管方式：nohup（未安装 systemd unit；会话结束可能被终止，见交付指南）'
    nohup java -jar "$JAR" --planning.scheduler-enabled=true \
      > "$LOG_DIR/backend.log" 2>&1 &
    printf '%s\n' "$!" > "$RUN_DIR/backend.pid"
  fi

  for _ in $(seq 1 60); do
    port_open "$BACKEND_PORT" && break
    sleep 1
  done
  port_open "$BACKEND_PORT" || die "后端 :$BACKEND_PORT 未监听，看 $LOG_DIR/backend.log"

  if use_systemd robot-autotest-entry.service; then
    systemctl --user start robot-autotest-entry.service
  else
    # serve-entry.mjs 用 ENTRY_DIST 定位静态产物；交付包里它在 app/frontend，
    # 与源码树的 frontend/dist 不同，必须显式给，否则入口会报「frontend dist not built」。
    ENTRY_PORT="$ENTRY_PORT" ENTRY_BACKEND="http://127.0.0.1:$BACKEND_PORT" \
      ENTRY_DIST="$WEB_DIR" \
      nohup node "$ENTRY_FILE" > "$LOG_DIR/entry.log" 2>&1 &
    printf '%s\n' "$!" > "$RUN_DIR/entry.pid"
  fi

  healthy=0
  for _ in $(seq 1 60); do
    if [[ "$(http_code "http://127.0.0.1:$ENTRY_PORT/healthz")" == "200" ]]; then
      healthy=1; break
    fi
    sleep 1
  done
  [[ "$healthy" == "1" ]] || die "入口健康检查超时，看 $LOG_DIR/entry.log"
  ok "已启动：http://127.0.0.1:$ENTRY_PORT"
}

case "$ACTION" in
  stop) stop_services; ok '已停止' ;;
  start) start_services ;;
  restart) stop_services; start_services ;;
esac
