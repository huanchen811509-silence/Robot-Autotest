#!/usr/bin/env bash
# ENVX-01 / ENVX-02（FR-v2.0-55）：一条命令「构建 + 部署 + 健康检查」。
#
#   bash scripts/deploy.sh                 # 完整部署（会停掉本脚本上次启动的进程）
#   bash scripts/deploy.sh --no-serve     # 只构建，不启停进程（CI / 旁观时用）
#   bash scripts/deploy.sh --verify       # 只做部署自校验（不构建、不重启），四项判据
#
# --verify 的四项判据（FR-v5.6-62）：
#   1 构建：jar 内 build-info.properties 的 commit == HEAD
#   2 重启：监听 8081 的进程启动时刻晚于 jar 修改时刻
#   3 健康：8081 /api/_ping 与 8001 /healthz 均 200
#   4 运行期：/api/_ping 的 version == VERSION；可登录时再比 build-info 的 commit == HEAD
# 为什么要它：源码对齐不等于部署对齐。jar 重建了但没重启，接口照样 200，
# 新能力却全是旧行为；「部署成功」的结论只能由这四项一起给，不能由 ping 200 给。
#
# 口令只从 ~/.config/robot-autotest/env 读，不写死、不打印。
# 代码回滚走 scripts/rollback.sh（HRD-04 三步），本脚本只负责「部署当前版本」。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p var logs

NO_SERVE=0
VERIFY_ONLY=0
# 不能写 `[[ ... ]] && X=1`：set -e 下条件为假时整个 && 列表返回 1，脚本会静默退出
for arg in "$@"; do
  case "$arg" in
    --no-serve) NO_SERVE=1 ;;
    --verify) VERIFY_ONLY=1 ;;
    *) echo "[deploy] FAIL 未知参数：$arg（支持 --no-serve / --verify）"; exit 2 ;;
  esac
done

ENV_FILE="${ROBOT_ENV_FILE:-$HOME/.config/robot-autotest/env}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

# ---------------------------------------------------------------- 部署自校验
# 四项各自独立判定，任一 FAIL 整体非 0；SKIP 单列，不并进通过数（否则就是假绿）。
verify_deploy() {
  local fails=0 skips=0
  local head_sha jar bi_commit pid elapsed jar_epoch now start_epoch
  local version_file api_ping api_version ping_code health_code
  local token runtime_commit login

  head_sha="$(git rev-parse --short HEAD)"
  version_file="$(tr -d '[:space:]' < VERSION)"
  jar="$(ls -1 backend/target/*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"

  echo "[verify] 判据 1/4 构建产物与 HEAD 一致"
  if [[ -z "$jar" ]]; then
    echo "[verify] FAIL 1/4 构建：backend/target 下没有 jar"
    fails=$((fails + 1))
  else
    bi_commit="$(unzip -p "$jar" BOOT-INF/classes/build-info.properties 2>/dev/null \
      | sed -n 's/^git\.commit=//p' | head -1)"
    if [[ -z "$bi_commit" ]]; then
      echo "[verify] FAIL 1/4 构建：jar 内没有 build-info.properties（未跑 gen_build_info.py）"
      fails=$((fails + 1))
    elif [[ "$bi_commit" != "$head_sha" ]]; then
      echo "[verify] FAIL 1/4 构建：jar 记的是 $bi_commit，HEAD 是 $head_sha（改完没重建）"
      fails=$((fails + 1))
    else
      echo "[verify] PASS 1/4 构建：jar commit=$bi_commit 与 HEAD 一致"
    fi
  fi

  echo "[verify] 判据 2/4 运行进程晚于 jar（重启生效）"
  pid="$(ss -ltnpH 'sport = :8081' 2>/dev/null | grep -o 'pid=[0-9]*' \
    | cut -d= -f2 | sort -u | head -1 || true)"
  if [[ -z "$pid" ]]; then
    echo "[verify] FAIL 2/4 重启：8081 没有监听进程"
    fails=$((fails + 1))
  elif [[ -z "$jar" ]]; then
    echo "[verify] SKIP 2/4 重启：没有 jar 可比启动时刻"
    skips=$((skips + 1))
  else
    elapsed="$(ps -p "$pid" -o etimes= 2>/dev/null | tr -d ' ' || true)"
    jar_epoch="$(stat -c %Y "$jar" 2>/dev/null || echo 0)"
    now="$(date +%s)"
    start_epoch=$((now - ${elapsed:-0}))
    if [[ "$start_epoch" -lt "$jar_epoch" ]]; then
      echo "[verify] FAIL 2/4 重启：进程 $pid 起于 $(date -d "@$start_epoch" '+%m-%d %H:%M:%S')，"\
"早于 jar 的 $(date -d "@$jar_epoch" '+%m-%d %H:%M:%S')——跑的是旧 jar，"\
"执行 systemctl --user restart robot-autotest-backend"
      fails=$((fails + 1))
    else
      echo "[verify] PASS 2/4 重启：进程 $pid 起于 $(date -d "@$start_epoch" '+%m-%d %H:%M:%S')，晚于 jar"
    fi
  fi

  echo "[verify] 判据 3/4 健康检查"
  ping_code="$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://127.0.0.1:8081/api/_ping || true)"
  health_code="$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://127.0.0.1:8001/healthz || true)"
  if [[ "$ping_code" == "200" && "$health_code" == "200" ]]; then
    echo "[verify] PASS 3/4 健康：8081 /api/_ping=$ping_code，8001 /healthz=$health_code"
  else
    echo "[verify] FAIL 3/4 健康：8081 /api/_ping=$ping_code，8001 /healthz=$health_code"
    fails=$((fails + 1))
  fi

  echo "[verify] 判据 4/4 运行期版本与提交"
  api_ping="$(curl -s --noproxy '*' http://127.0.0.1:8081/api/_ping || true)"
  api_version="$(printf '%s' "$api_ping" | python3 -c \
    'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
print((d.get("data") or {}).get("version",""))' 2>/dev/null || true)"
  if [[ -z "$api_version" ]]; then
    echo "[verify] FAIL 4/4 运行期：/api/_ping 读不到 version"
    fails=$((fails + 1))
  elif [[ "$api_version" != "$version_file" ]]; then
    echo "[verify] FAIL 4/4 运行期：/api/_ping version=$api_version，VERSION=$version_file"
    fails=$((fails + 1))
  else
    runtime_commit=""
    if [[ -n "${QA_E2E_PASSWORD:-}" ]]; then
      login="$(curl -s --noproxy '*' -X POST http://127.0.0.1:8081/api/auth/login \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"qa-e2e-admin\",\"password\":\"$QA_E2E_PASSWORD\"}" || true)"
      token="$(printf '%s' "$login" | python3 -c \
        'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
print((d.get("data") or {}).get("token",""))' 2>/dev/null || true)"
      if [[ -n "$token" ]]; then
        runtime_commit="$(curl -s --noproxy '*' -H "Authorization: Bearer $token" \
          http://127.0.0.1:8081/api/system/build-info | python3 -c \
          'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
data=d.get("data") or {}
print(data.get("commit") or data.get("gitCommit") or "")' 2>/dev/null || true)"
      fi
    fi
    if [[ -z "$runtime_commit" ]]; then
      echo "[verify] SKIP 4/4 运行期：version=$api_version 与 VERSION 一致；"\
"但未取到需登录的 build-info commit（未配置 QA_E2E_PASSWORD 或登录失败），commit 未比"
      skips=$((skips + 1))
    elif [[ "${runtime_commit:0:7}" != "$head_sha" ]]; then
      echo "[verify] FAIL 4/4 运行期：build-info commit=$runtime_commit，HEAD=$head_sha"
      fails=$((fails + 1))
    else
      echo "[verify] PASS 4/4 运行期：version=$api_version，commit=$runtime_commit 与 HEAD 一致"
    fi
  fi

  echo "[verify] 结论：PASS=$((4 - fails - skips)) FAIL=$fails SKIP=$skips"
  if [[ "$fails" -gt 0 ]]; then
    return 1
  fi
  return 0
}

if [[ "$VERIFY_ONLY" == "1" ]]; then
  verify_deploy
  exit $?
fi

echo "[deploy] 1/5 生成构建信息（关于页的版本 / commit / 依赖）"
python3 scripts/gen_build_info.py
cp CHANGELOG.md frontend/public/CHANGELOG.md

echo "[deploy] 2/5 构建后端 jar 与前端 dist"
bash scripts/build.sh

JAR="$(ls -1 backend/target/*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
[[ -n "$JAR" ]] || { echo "[deploy] FAIL 没找到后端 jar"; exit 1; }
[[ -f frontend/dist/index.html ]] || { echo "[deploy] FAIL 没找到前端 dist"; exit 1; }

if [[ "$NO_SERVE" == "1" ]]; then
  echo "[deploy] --no-serve：构建完成，未启停任何进程"
  exit 0
fi

: "${DB_PASSWORD:?缺少 DB_PASSWORD，请在 $ENV_FILE 配置}"

stop_by_pidfile() {
  local file="$1"
  if [[ -f "$file" ]]; then
    local pid
    pid="$(cat "$file")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "[deploy] 停旧进程 $pid ($file)"
      kill "$pid" 2>/dev/null || true
      sleep 2
    fi
    rm -f "$file"
  fi
}

echo "[deploy] 3/5 停旧进程（systemd unit + pid 文件 + 端口兜底）"
# 2026-11-06 实测：本地旁观实例由 systemd --user 托管（enable-linger），
# 不再 nohup —— nohup 起的进程随 wsl.exe 会话结束被 SIGTERM 杀掉，入口会静默消失。
stop_unit() {
  local unit="$1"
  if systemctl --user cat "$unit" >/dev/null 2>&1; then
    echo "[deploy] 停 unit $unit"
    systemctl --user stop "$unit" 2>/dev/null || true
  fi
}
stop_unit robot-autotest-entry.service
stop_unit robot-autotest-backend.service
stop_by_pidfile var/backend.pid
stop_by_pidfile var/entry.pid

# 2026-11-06 实测缺陷：W1 期间手工起过一次后端/入口，没写 pid 文件。
# 之后跑 deploy.sh 时新 jar 因 8081 被占用直接启动失败，但 8001 上旧入口还活着，
# /healthz 与 /api/health 照样 200 —— 部署脚本报 PASS，新接口却全是 404。
# 只认本仓库的进程特征，不误杀别人占用的端口。
stop_by_port() {
  local port="$1" pattern="$2" pid
  local pids
  pids="$(ss -ltnpH "sport = :${port}" 2>/dev/null \
    | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u || true)"
  for pid in $pids; do
    if [[ -n "$pid" ]] && ps -p "$pid" -o args= | grep -q -- "$pattern"; then
      echo "[deploy] 停端口 ${port} 上的遗留进程 ${pid}（pid 文件缺失/失效）"
      kill "$pid" 2>/dev/null || true
    fi
  done
}

stop_by_port 8081 "backend/target/"
stop_by_port 8001 "serve-entry.mjs"

# 端口释放是异步的，不等就启动会复现上面的假绿
for port in 8081 8001; do
  for _ in $(seq 1 20); do
    if ! ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .; then
      break
    fi
    sleep 1
  done
  if ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .; then
    echo "[deploy] FAIL 端口 ${port} 仍被占用，拒绝继续（避免旧进程假绿）"
    exit 1
  fi
done

echo "[deploy] 4/5 启动后端 :8081 与统一入口 :8001"
# 托管口径：有 systemd --user unit 就交给 unit（常驻、可 restart、开机自起）；
# 没有 unit（新克隆未安装）才退回 nohup，并在输出里明示「会话结束会停」。
if systemctl --user cat robot-autotest-backend.service >/dev/null 2>&1 \
   && systemctl --user cat robot-autotest-entry.service >/dev/null 2>&1; then
  echo "[deploy] 托管方式：systemd --user（robot-autotest-backend / robot-autotest-entry）"
  systemctl --user start robot-autotest-backend.service
  for _ in $(seq 1 60); do
    ss -ltnH "sport = :8081" 2>/dev/null | grep -q . && break
    sleep 1
  done
  ss -ltnH "sport = :8081" 2>/dev/null | grep -q . \
    || { echo "[deploy] FAIL 后端 8081 未监听，看 journalctl --user -u robot-autotest-backend"; exit 1; }
  systemctl --user start robot-autotest-entry.service
else
  echo "[deploy] 托管方式：nohup（未安装 systemd unit，wsl.exe 会话结束会停）"
  nohup java -jar "$JAR" --planning.scheduler-enabled=true \
    > logs/backend.log 2>&1 &
  echo $! > var/backend.pid
  for _ in $(seq 1 60); do
    ss -ltnH "sport = :8081" 2>/dev/null | grep -q . && break
    sleep 1
  done
  nohup node scripts/serve-entry.mjs > logs/entry.log 2>&1 &
  echo $! > var/entry.pid
fi

echo "[deploy] 5/5 健康检查（最多 60 秒）"
ok=0
for _ in $(seq 1 60); do
  if curl -fsS --noproxy '*' http://127.0.0.1:8001/healthz >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 1
done
[[ "$ok" == "1" ]] || { echo "[deploy] FAIL 入口健康检查超时，看 logs/entry.log"; exit 1; }

echo "[deploy] /healthz    $(curl -fsS --noproxy '*' http://127.0.0.1:8001/healthz)"
echo "[deploy] /api/health $(curl -fsS --noproxy '*' http://127.0.0.1:8001/api/health)"
echo "[deploy] 首页        $(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://127.0.0.1:8001/)"
echo "[deploy] 深链接      $(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://127.0.0.1:8001/settings)"
# 部署成功的结论不能由「进程活着」给：jar 重建了却没重启，接口同样 200。
if ! verify_deploy; then
  echo "[deploy] FAIL 部署自校验未通过（见上方四判据），不要对外称已部署"
  exit 1
fi

echo "[deploy] PASS 访问 http://127.0.0.1:8001"
