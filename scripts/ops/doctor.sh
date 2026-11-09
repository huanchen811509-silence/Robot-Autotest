#!/usr/bin/env bash
# 交付包体检（FR-v3.11-D01 / TC-D01）：装之前先判「这台机器能不能跑」。
#
#   bash scripts/ops/doctor.sh            # 开发机全项（含端口 / HTTP / 数据库连通）
#   ./bin/doctor.sh --ci                 # CI 只查工具链与产物，不要求服务已启动
#   ./bin/doctor.sh --json               # 机器可读读数，便于归档
#
# 只读：不启停进程、不写库、不改文件。口令只在环境文件里读，不打印。
set -euo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

MODE="full"
JSON=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ci) MODE="ci"; shift ;;
    --json) JSON=1; shift ;;
    --help|-h) printf '用法: doctor.sh [--ci] [--json]\n'; usage_tail; exit 0 ;;
    *) die "未知参数 $1（用 --help 看用法）" ;;
  esac
done

PASS=0; WARN=0; FAIL=0
RESULTS=()

record() {
  local state="$1" name="$2" detail="$3"
  case "$state" in
    PASS) PASS=$((PASS + 1)) ;;
    WARN) WARN=$((WARN + 1)) ;;
    FAIL) FAIL=$((FAIL + 1)) ;;
  esac
  RESULTS+=("$state|$name|$detail")
  [[ "$JSON" == "1" ]] || printf '[ops] %-4s %-22s %s\n' "$state" "$name" "$detail"
}

check_cmd() {
  local cmd="$1" label="$2"
  if has_cmd "$cmd"; then
    record PASS "$label" "$(command -v "$cmd")"
  else
    record FAIL "$label" "未安装 $cmd"
  fi
}

check_cmd java java
check_cmd node node
check_cmd curl curl
check_cmd tar tar
if [[ "$MODE" == "full" ]]; then
  check_cmd mysql mysql-client
fi

if jar_path >/dev/null 2>&1; then
  record PASS backend-jar "$(basename "$(jar_path)")"
else
  record FAIL backend-jar "$JAR_DIR 下没有可运行的 jar（先构建或检查交付包完整性）"
fi

if [[ -f "$WEB_DIR/index.html" ]]; then
  record PASS frontend-dist "$WEB_DIR/index.html"
else
  record FAIL frontend-dist "缺少前端产物 index.html"
fi

if [[ -f "$ENTRY_FILE" ]]; then
  record PASS entry-script "$(basename "$ENTRY_FILE")"
else
  record FAIL entry-script "缺少统一入口 serve-entry.mjs"
fi

if [[ -d "$DB_DIR/migration" ]] && compgen -G "$DB_DIR/migration/V*.sql" >/dev/null; then
  count="$(find "$DB_DIR/migration" -maxdepth 1 -name 'V*.sql' | wc -l | tr -d ' ')"
  record PASS db-migrations "$count 个迁移脚本"
else
  record FAIL db-migrations "缺少 db/migration/V*.sql"
fi

if [[ -f "$DEPLOY_DIR/robot-autotest-entry.service" ]]; then
  record PASS systemd-unit "样例齐备"
else
  record WARN systemd-unit "缺少 unit 样例（可用 nohup 降级运行）"
fi

if [[ -f "$DOC_DIR/DELIVERY-GUIDE.md" ]]; then
  record PASS delivery-guide "随包文档齐备"
else
  record WARN delivery-guide "缺少交付指南（源码树布局属正常）"
fi

if [[ -f "$ROOT/MANIFEST.json" ]]; then
  record PASS manifest "MANIFEST.json / layout=$LAYOUT"
else
  record WARN manifest "源码树布局，无 MANIFEST.json"
fi

record PASS version "$(version_of) / layout=$LAYOUT / root=$APP_HOME"

if [[ "$MODE" == "full" ]]; then
  if [[ -f "$ENV_FILE" ]]; then
    record PASS env-file "$ENV_FILE（未打印内容）"
  else
    record WARN env-file "未配置 $ENV_FILE，启动前必须补齐"
  fi

  if port_open "$BACKEND_PORT"; then
    record PASS port-backend ":$BACKEND_PORT 已监听"
  else
    record WARN port-backend ":$BACKEND_PORT 未监听（未部署或服务未启动）"
  fi

  if port_open "$ENTRY_PORT"; then
    code="$(http_code "http://127.0.0.1:$ENTRY_PORT/healthz")"
    if [[ "$code" == "200" ]]; then
      record PASS entry-health "/healthz 200"
    else
      record WARN entry-health "/healthz 返回 $code"
    fi
  else
    record WARN entry-health ":$ENTRY_PORT 未监听"
  fi

  if has_cmd mysql && load_env; then
    if [[ -n "${DB_NAME:-}" && -n "${DB_USERNAME:-}" ]]; then
      if MYSQL_PWD="${DB_PASSWORD:-}" mysql -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" \
          -u"$DB_USERNAME" "$DB_NAME" -N -B -e 'SELECT 1' >/dev/null 2>&1; then
        record PASS database "连通 $DB_NAME"
      else
        record WARN database "连不上 $DB_NAME（未建库或凭据未配置）"
      fi
    else
      record WARN database "环境文件未给 DB_NAME / DB_USERNAME"
    fi
  else
    record WARN database "跳过（无 mysql 客户端或环境文件）"
  fi

  if has_cmd df; then
    avail="$(df -Pm "$APP_HOME" 2>/dev/null | tail -1 | awk '{print $4}')"
    if [[ -n "$avail" ]] && (( avail >= 2048 )); then
      record PASS disk-free "${avail} MiB 可用"
    else
      record WARN disk-free "可用空间 ${avail:-未知} MiB，建议 ≥2048 MiB"
    fi
  fi
fi

if [[ "$JSON" == "1" ]]; then
  printf '{\n  "layout": "%s",\n  "version": "%s",\n  "mode": "%s",\n  "pass": %s,\n  "warn": %s,\n  "fail": %s,\n  "checks": [\n' \
    "$LAYOUT" "$(version_of)" "$MODE" "$PASS" "$WARN" "$FAIL"
  total=${#RESULTS[@]}
  for i in "${!RESULTS[@]}"; do
    IFS='|' read -r state name detail <<< "${RESULTS[$i]}"
    printf '    {"state": "%s", "name": "%s", "detail": "%s"}%s\n' \
      "$state" "$name" "${detail//\"/\'}" "$([[ $i -lt $((total - 1)) ]] && echo ',' || echo '')"
  done
  printf '  ]\n}\n'
fi

log "体检结束 PASS=$PASS WARN=$WARN FAIL=$FAIL（WARN 不算通过，也不算致命）"
[[ "$FAIL" -eq 0 ]] || exit 1
printf 'DOCTOR PASS fails=%s\n' "$FAIL"
