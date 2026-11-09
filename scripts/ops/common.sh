#!/usr/bin/env bash
# 交付包运维脚本公共层（v3.11.0-delivery / FR-v3.11-D01）。
#
# 两种布局共用同一套脚本，避免「源码树一套、交付包另一套」迟早漂移：
#   bundle 布局：脚本位于 <prefix>/bin/，同层有 MANIFEST.json —— CI 打出的 tar.gz 解压后就是这种。
#   source 布局：脚本位于 <repo>/scripts/ops/ —— 开发机上直接 `bash scripts/ops/doctor.sh`。
#
# 本文件只做「定位 + 输出 + 只读探测」，不启动、不停、不删任何东西。
# 所有凭据只从环境文件读，绝不打印、绝不写进日志（FR-v0.1-07）。

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$OPS_DIR/../MANIFEST.json" ]]; then
  LAYOUT="bundle"
  ROOT="$(cd "$OPS_DIR/.." && pwd)"
  JAR_DIR="$ROOT/app/backend"
  WEB_DIR="$ROOT/app/frontend"
  EDITOR_DIR="$ROOT/app/editor"
  ENTRY_FILE="$ROOT/entry/serve-entry.mjs"
  DB_DIR="$ROOT/db"
  DEPLOY_DIR="$ROOT/deploy"
  DOC_DIR="$ROOT/docs"
  UNIT_DIR="$ROOT/deploy"
else
  LAYOUT="source"
  ROOT="$(cd "$OPS_DIR/../.." && pwd)"
  JAR_DIR="$ROOT/backend/target"
  WEB_DIR="$ROOT/frontend/dist"
  EDITOR_DIR="$ROOT/packages/editor/dist"
  ENTRY_FILE="$ROOT/scripts/serve-entry.mjs"
  DB_DIR="$ROOT/db"
  DEPLOY_DIR="$ROOT/deploy"
  DOC_DIR="$ROOT/docs"
  UNIT_DIR="$ROOT/deploy"
fi

APP_HOME="${APP_HOME:-$ROOT}"
ENV_FILE="${ROBOT_ENV_FILE:-$HOME/.config/robot-autotest/env}"
LOG_DIR="${ROBOT_LOG_DIR:-$APP_HOME/logs}"
RUN_DIR="${ROBOT_RUN_DIR:-$APP_HOME/var}"
BACKUP_DIR="${ROBOT_BACKUP_DIR:-$APP_HOME/backups}"

ENTRY_PORT="${ENTRY_PORT:-8001}"
BACKEND_PORT="${BACKEND_PORT:-8081}"

# 输出统一前缀，方便日志机检与人工 grep。
log()  { printf '[ops] %s\n' "$*"; }
ok()   { printf '[ops] PASS  %s\n' "$*"; }
warn() { printf '[ops] WARN  %s\n' "$*"; }
err()  { printf '[ops] FAIL  %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

usage_tail() {
  cat <<'EOF'
公共参数：
  --help          显示本脚本用法
  --dry-run       只打印将要执行的动作（安装/卸载/备份/迁移的默认值）
  APP_HOME        安装根目录，默认取脚本所在交付包根目录
  ROBOT_ENV_FILE  凭据文件，默认 ~/.config/robot-autotest/env

安全约定：脚本不打印 DB_PASSWORD，不把口令写进任何日志或 MANIFEST。
EOF
}

need_arg() {
  [[ $# -ge 2 ]] || die "参数 $1 缺少取值"
}

# 只加载 KEY=VALUE 形式的行；不 source 任意 shell 代码，避免环境文件被执行。
load_env() {
  if [[ ! -f "$ENV_FILE" ]]; then
    warn "凭据文件不存在：$ENV_FILE（只做只读检查时可忽略）"
    return 1
  fi
  while IFS='=' read -r key value; do
    case "$key" in
      ''|'#'*) continue ;;
    esac
    key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${value%$'\r'}"
    export "$key=$value"
  done < "$ENV_FILE"
  return 0
}

has_cmd() { command -v "$1" >/dev/null 2>&1; }

port_open() {
  local port="$1"
  has_cmd ss || return 2
  ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .
}

http_code() {
  local url="$1"
  has_cmd curl || return 2
  curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 3 "$url" 2>/dev/null || true
}

jar_path() {
  local candidate=""
  for candidate in "$JAR_DIR"/*.jar; do
    [[ -f "$candidate" ]] || continue
    case "$candidate" in
      *.original) continue ;;
    esac
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

version_of() {
  if [[ -f "$ROOT/VERSION" ]]; then
    head -1 "$ROOT/VERSION" | tr -d '\r' | tr -d '[:space:]'
  else
    printf 'unknown\n'
  fi
}

sha_of() {
  if has_cmd sha256sum && [[ -f "$1" ]]; then
    sha256sum "$1" | cut -d' ' -f1
  else
    printf 'unknown\n'
  fi
}
