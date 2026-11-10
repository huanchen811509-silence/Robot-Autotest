#!/usr/bin/env bash
# 可回滚升级（FR-v4.0-05）。
#
#   ./bin/update.sh --package /tmp/robot-autotest-4.0.0                 # dry-run（默认）
#   ./bin/update.sh --package /tmp/robot-autotest-4.0.0 --apply         # 二次确认后执行
#   ./bin/update.sh --package /tmp/robot-autotest-4.0.0 --apply --yes   # 非交互（演练用）
#
# 安全默认态（与 PRD §3.4 一致，任何一条都不许放松）：
#   - 默认 dry-run；只有 --apply + 交互输入 UPDATE 才写盘；
#   - 同版本必须 --force；降级必须 --force 且 --restore-db；
#   - IT 库（库名以 _it 结尾）必须显式 --allow-it，避免把测试库当生产升级；
#   - --apply 之前必须完成 DB 全量备份，备份失败立即中止；
#   - 仅当新迁移全部为 expand（无 drop/modify/rename/truncate）才允许 --schema=keep，
#     否则回滚一律恢复 DB 备份（RPO = 0）；
#   - 任一步失败自动回滚；回滚失败打印 docs/ROLLBACK.md 的人工恢复步骤并退出码 2。
#
# 十步编排：版本比对 → 包校验 → 锁与磁盘 → doctor/迁移清单 → DB 备份+归档当前包
#          → 停/覆盖/起 → 健康抽样 → 审计与 UPDATE-HISTORY → status 结论 → 保留 3 版
set -Eeuo pipefail
# shellcheck source=/dev/null
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PKG=""
TARGET_PREFIX="$APP_HOME"
APPLY=0
YES=0
FORCE=0
RESTORE_DB=0
ALLOW_IT=0
SCHEMA="auto"          # auto | keep | restore
SIG_FILE=""
KEEP=3
HEALTH_WAIT="${HEALTH_WAIT:-180}"
DRILL_MODE="${UPDATE_DRILL:-0}"   # 1 = 编排演练：不启停真实服务，只校验编排与回滚

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) need_arg "$@"; PKG="$2"; shift 2 ;;
    --prefix) need_arg "$@"; TARGET_PREFIX="$2"; shift 2 ;;
    --schema) need_arg "$@"; SCHEMA="$2"; shift 2 ;;
    --schema=*) SCHEMA="${1#*=}"; shift ;;
    --signature) need_arg "$@"; SIG_FILE="$2"; shift 2 ;;
    --keep) need_arg "$@"; KEEP="$2"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    --dry-run) APPLY=0; shift ;;
    --yes) YES=1; shift ;;
    --force) FORCE=1; shift ;;
    --restore-db) RESTORE_DB=1; shift ;;
    --allow-it) ALLOW_IT=1; shift ;;
    --help|-h)
      cat <<'EOF'
用法: update.sh --package DIR [--prefix DIR] [--apply] [--yes] [--force]
                 [--restore-db] [--schema=auto|keep|restore] [--signature FILE]
                 [--keep N] [--allow-it]

  --package DIR   新交付包（已解压，含 MANIFEST.json / VERSION / app/）
  --prefix DIR    被升级的安装根，默认取脚本所在交付包根目录
  --apply         真执行；缺省 dry-run
  --yes           非交互确认（演练/自动化用）
  --force         同版本或降级必需
  --restore-db    降级时必需；回滚时恢复 --apply 前生成的 DB 备份
  --schema=keep   仅 expand 迁移允许；否则拒绝
  --keep N        保留最近 N 份归档包，默认 3
EOF
      usage_tail; exit 0 ;;
    *) die "未知参数 $1" ;;
  esac
done

[[ -n "$PKG" ]] || die '必须给 --package（拒绝就地猜测新包位置）'
case "$SCHEMA" in
  auto|keep|restore) ;;
  *) die "--schema 只接受 auto|keep|restore，收到：$SCHEMA" ;;
esac
[[ -d "$PKG" ]] || die "新包目录不存在：$PKG"
[[ -f "$PKG/MANIFEST.json" ]] || die "新包缺少 MANIFEST.json：$PKG"
[[ -f "$PKG/VERSION" ]] || die "新包缺少 VERSION：$PKG"
[[ -d "$TARGET_PREFIX" ]] || die "安装根不存在：$TARGET_PREFIX"
[[ -f "$TARGET_PREFIX/MANIFEST.json" ]] || die "安装根没有 MANIFEST.json，不认定为本产品：$TARGET_PREFIX"

STAMP="$(date '+%Y%m%d-%H%M%S')"
CUR_VER="$(head -1 "$TARGET_PREFIX/VERSION" | tr -d '\r' | tr -d '[:space:]')"
NEW_VER="$(head -1 "$PKG/VERSION" | tr -d '\r' | tr -d '[:space:]')"
STATE_DIR="$TARGET_PREFIX/var/update"
STATE_FILE="$STATE_DIR/state"
ARTIFACT_DIR="$BACKUP_DIR/artifacts"
HISTORY_FILE="$TARGET_PREFIX/UPDATE-HISTORY.md"
LOG_FILE="$STATE_DIR/update-$STAMP.log"
LOCK_FILE="$TARGET_PREFIX/var/update.lock"

mkdir -p "$STATE_DIR" "$ARTIFACT_DIR" "$LOG_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1

DB_BACKUP=""
PKG_ARCHIVE=""
SCHEMA_MODE="restore"
ROLLING_BACK=0
STEP="初始化"

on_err() {
  local code=$?
  [[ "$ROLLING_BACK" == "1" ]] && return 0
  [[ "$APPLY" == "1" ]] || exit "$code"
  ROLLING_BACK=1
  err "第 ${STEP} 步失败（exit=$code），开始自动回滚"
  rollback "step=${STEP} exit=${code}"
  exit 2
}
trap on_err ERR

state_get() {
  local key="$1" default="${2:-}"
  [[ -f "$STATE_FILE" ]] || { printf '%s\n' "$default"; return 0; }
  local value
  value="$(awk -F= -v k="$key" '$1==k {print substr($0, index($0,"=")+1)}' "$STATE_FILE" | tail -1)"
  [[ -n "$value" ]] && printf '%s\n' "$value" || printf '%s\n' "$default"
}

state_write() {
  local last_result="$1" last_good="$2" note="$3" current="${4:-$NEW_VER}"
  local tmp="$STATE_FILE.tmp"
  {
    printf 'current_version=%s\n' "$current"
    printf 'last_good_version=%s\n' "$last_good"
    printf 'last_target_version=%s\n' "$NEW_VER"
    printf 'last_result=%s\n' "$last_result"
    printf 'last_result_version=%s\n' "$CUR_VER"
    printf 'last_update_at=%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
    printf 'last_note=%s\n' "$note"
    printf 'rto_seconds=%s\n' "${ELAPSED:-0}"
  } > "$tmp"
  mv "$tmp" "$STATE_FILE"
}

history_append() {
  local result="$1" note="$2"
  [[ -f "$HISTORY_FILE" ]] || {
    printf '# 升级历史（update.sh 自动追加）\n\n' > "$HISTORY_FILE"
    printf '| 时间 | 原版本 | 目标版本 | 结论 | 备注 |\n|---|---|---|---|---|\n' >> "$HISTORY_FILE"
  }
  printf '| %s | %s | %s | %s | %s |\n' \
    "$(date '+%Y-%m-%d %H:%M:%S')" "$CUR_VER" "$NEW_VER" "$result" "$note" >> "$HISTORY_FILE"
}

cmp_lt() { [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" && "$1" != "$2" ]]; }
cmp_gt() { cmp_lt "$2" "$1"; }

check_versions() {
  if [[ "$NEW_VER" == "$CUR_VER" ]]; then
    [[ "$FORCE" == "1" ]] || die "目标版本与当前相同（$CUR_VER），同版本升级必须 --force"
    warn "同版本覆盖升级：$CUR_VER → $NEW_VER（--force）"
  elif cmp_lt "$NEW_VER" "$CUR_VER"; then
    [[ "$FORCE" == "1" && "$RESTORE_DB" == "1" ]] \
      || die "降级 $CUR_VER → $NEW_VER 必须同时给 --force 与 --restore-db（否则 RPO 不为 0）"
    warn "降级升级：$CUR_VER → $NEW_VER，回滚依赖升级前 DB 备份"
  else
    log "版本升级：$CUR_VER → $NEW_VER"
  fi
}

# IT 库守卫（FR-v4.0-05）：库名以 _it 结尾说明是集成测试库，禁止当成生产库升级。
check_it_guard() {
  if load_env 2>/dev/null; then
    local db="${DB_NAME:-}"
    if [[ "$db" == *_it ]]; then
      [[ "$ALLOW_IT" == "1" ]] \
        || die "目标库 $db 以 _it 结尾（集成测试库），升级必须显式 --allow-it"
      warn "已确认升级集成测试库：$db（--allow-it）"
      return 0
    fi
    log "目标库 ${db:-<未配置>} 非 _it 测试库，守卫通过"
  else
    warn "凭据文件缺失，跳过 _it 守卫（后续 doctor / 备份阶段会再校验）"
  fi
}

list_new_migrations() {
  local f name
  for f in "$PKG/db/migration"/*.sql; do
    [[ -f "$f" ]] || continue
    name="$(basename "$f")"
    [[ -f "$TARGET_PREFIX/db/migration/$name" ]] || printf '%s\n' "$name"
  done
}

is_destructive() {
  local file
  for file in "$@"; do
    if grep -Eiq 'drop[[:space:]]+(column|table|index|constraint)|modify[[:space:]]+column|change[[:space:]]+column|rename[[:space:]]+(column|table)|truncate' \
      "$PKG/db/migration/$file"; then
      return 0
    fi
  done
  return 1
}

verify_package() {
  local declared
  declared="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(d.get("version",""))' "$PKG/MANIFEST.json")"
  [[ "$declared" == "$NEW_VER" ]] || die "MANIFEST.version=$declared 与 VERSION=$NEW_VER 不一致"

  # 逐文件 sha256：MANIFEST.files 是构建时算的，这里重算一遍。少文件、改内容都要红。
  python3 - "$PKG" <<'PY' || die 'MANIFEST 逐文件 sha256 校验失败（新包不完整或被改动）'
import hashlib, json, os, sys
root = sys.argv[1]
m = json.load(open(os.path.join(root, 'MANIFEST.json'), encoding='utf-8'))
bad = []
for f in m.get('files', []):
    p = os.path.join(root, f['path'])
    if not os.path.isfile(p):
        bad.append('missing:' + f['path'])
        continue
    if hashlib.sha256(open(p, 'rb').read()).hexdigest() != f['sha256']:
        bad.append('sha256:' + f['path'])
if bad:
    print('MISMATCH ' + str(len(bad)))
    for item in bad[:10]:
        print('  ' + item)
    sys.exit(1)
print('FILES_OK ' + str(len(m.get('files', []))))
PY
  ok "逐文件 sha256 校验通过（$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))["files"]))' "$PKG/MANIFEST.json") 个文件）"

  local needed
  for needed in app/backend app/frontend entry db/migration; do
    [[ -e "$PKG/$needed" ]] || die "新包缺少必需目录：$needed"
  done
  if [[ -n "$SIG_FILE" ]]; then
    [[ -f "$SIG_FILE" ]] || die "签名文件不存在：$SIG_FILE"
    if has_cmd minisign; then
      minisign -Vm "$PKG/MANIFEST.json" -x "$SIG_FILE" >/dev/null 2>&1 \
        || die '签名校验失败（minisign）'
      ok "签名校验通过（minisign）"
    elif has_cmd gpg; then
      gpg --verify "$SIG_FILE" "$PKG/MANIFEST.json" >/dev/null 2>&1 \
        || die '签名校验失败（gpg）'
      ok "签名校验通过（gpg）"
    else
      die '给了 --signature 但机器上没有 minisign/gpg，拒绝跳过校验'
    fi
  else
    warn '未提供 --signature：当前发布链未签名，本次只做 MANIFEST 自洽 + 目录完整性校验'
  fi
  actual="$(sha_of "$PKG/MANIFEST.json")"
  ok "MANIFEST sha256=$actual"
}

take_lock() {
  if has_cmd flock; then
    exec 9>"$LOCK_FILE"
    flock -n 9 || die "已有升级在跑（锁：$LOCK_FILE）"
  else
    mkdir "$LOCK_FILE.d" 2>/dev/null || die "已有升级在跑（锁目录：$LOCK_FILE.d）"
  fi
}

release_lock() {
  if has_cmd flock; then
    flock -u 9 2>/dev/null || true
  else
    rmdir "$LOCK_FILE.d" 2>/dev/null || true
  fi
}

check_disk() {
  local need_kb avail_kb
  need_kb=$(( $(du -sk "$PKG" | cut -f1) * 2 + 102400 ))
  avail_kb="$(df -Pk "$TARGET_PREFIX" | awk 'NR==2 {print $4}')"
  (( avail_kb >= need_kb )) || die "磁盘不足：需要 ${need_kb}KB，可用 ${avail_kb}KB"
  ok "磁盘余量 ${avail_kb}KB ≥ 需要 ${need_kb}KB"
}

run_doctor() {
  local out rc=0
  out="$("$OPS_DIR/doctor.sh" 2>&1)" || rc=$?
  printf '%s\n' "$out" | grep -q 'DOCTOR FAIL' && die 'doctor 未通过，拒绝升级'
  if [[ "$rc" != "0" ]]; then
    die "doctor 退出码 $rc，拒绝升级"
  fi
  ok 'doctor 通过'
}

show_migrations() {
  local list="$1"
  if [[ -z "$list" ]]; then
    log '迁移清单：无新增迁移'
    return 0
  fi
  log '迁移清单（正序，dry-run 阶段只读列出）：'
  printf '%s\n' "$list" | sort -V | sed 's/^/[ops]   - /'
}

backup_db() {
  local out
  out="$("$OPS_DIR/backup.sh" --apply 2>&1)" || die 'DB 全量备份失败，拒绝继续'
  printf '%s\n' "$out"
  DB_BACKUP="$(printf '%s\n' "$out" | awk '/BACKUP PASS file=/ {sub("BACKUP PASS file=",""); print}' | tail -1)"
  [[ -n "$DB_BACKUP" && -s "$DB_BACKUP" ]] || die '未拿到有效的 DB 备份文件'
  ok "DB 全量备份：$DB_BACKUP"
}

archive_current() {
  PKG_ARCHIVE="$ARTIFACT_DIR/pre-${CUR_VER}-${STAMP}.tar.gz"
  local items=(MANIFEST.json VERSION CHANGELOG.md README.md install.sh uninstall.sh bin app entry db deploy docs)
  local existing=()
  local item
  for item in "${items[@]}"; do
    [[ -e "$TARGET_PREFIX/$item" ]] && existing+=("$item")
  done
  tar -czf "$PKG_ARCHIVE" -C "$TARGET_PREFIX" "${existing[@]}"
  [[ -s "$PKG_ARCHIVE" ]] || die '当前包归档为空，拒绝继续'
  ok "当前包已归档：$PKG_ARCHIVE"
}

swap_program() {
  local items=(MANIFEST.json VERSION CHANGELOG.md README.md install.sh uninstall.sh bin app entry db deploy docs)
  local item
  for item in "${items[@]}"; do
    rm -rf -- "$TARGET_PREFIX/$item"
  done
  for item in "${items[@]}"; do
    [[ -e "$PKG/$item" ]] || continue
    cp -a "$PKG/$item" "$TARGET_PREFIX/$item"
  done
  ok '程序文件已覆盖（logs / var / backups / workspace 未动）'
}

restore_program() {
  local items=(MANIFEST.json VERSION CHANGELOG.md README.md install.sh uninstall.sh bin app entry db deploy docs)
  local item
  for item in "${items[@]}"; do
    rm -rf -- "$TARGET_PREFIX/$item"
  done
  tar -xzf "$PKG_ARCHIVE" -C "$TARGET_PREFIX"
  ok "已从归档恢复原程序包：$PKG_ARCHIVE"
}

restore_db() {
  local client=""
  if has_cmd mysql; then client=mysql; elif has_cmd mariadb; then client=mariadb; fi
  [[ -n "$client" ]] || die '没有 mysql/mariadb 客户端，无法恢复 DB 备份（按 docs/ROLLBACK.md 人工恢复）'
  load_env || die '缺少凭据文件，无法恢复 DB'
  local db="${DB_NAME:?}"
  gunzip -c "$DB_BACKUP" | MYSQL_PWD="${DB_PASSWORD:?}" "$client" \
    -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"${DB_USERNAME:?}" "$db" \
    || die 'DB 备份恢复失败'
  ok "DB 已恢复到升级前状态：$DB_BACKUP"
}

wait_health() {
  local waited=0 code entry
  while (( waited < HEALTH_WAIT )); do
    # 就绪判据以后端 actuator 为准：入口 /healthz 只是 node 自回 liveness，
    # 后端没起来时它照样 200（DELIVERY-GUIDE §4.6 / 计划 §3 就绪探针）。
    code="$(http_code "http://127.0.0.1:$BACKEND_PORT/actuator/health")"
    entry="$(http_code "http://127.0.0.1:$ENTRY_PORT/healthz")"
    if [[ "$code" == "200" && "$entry" == "200" ]]; then
      ok "健康检查通过（backend+entry，${waited}s）"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

sample_health() {
  if [[ "$DRILL_MODE" == "1" ]]; then
    # 演练钩子：注入健康失败，验证「失败 → 自动回滚」这条路径真的会走。
    [[ "${UPDATE_DRILL_FAIL_HEALTH:-0}" == "1" ]] && { warn '演练注入：健康抽样失败'; return 1; }
    log '演练模式：跳过真实健康抽样（只校验编排与回滚）'
    return 0
  fi
  "$OPS_DIR/ctl.sh" start || return 1
  wait_health || return 1
  if load_env 2>/dev/null && [[ -n "${QA_E2E_PASSWORD:-}" ]]; then
    local token
    token="$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$ENTRY_PORT/api/auth/login" \
      -H 'Content-Type: application/json' \
      -d "{\"username\":\"${QA_E2E_USER:-admin}\",\"password\":\"$QA_E2E_PASSWORD\"}" \
      | python3 -c 'import json,sys;print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
    if [[ -n "$token" ]]; then
      local reports
      reports="$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' \
        -H "Authorization: Bearer $token" "http://127.0.0.1:$ENTRY_PORT/api/reports?page=1&size=1")"
      [[ "$reports" == "200" ]] || return 1
      ok '登录 + 报告列表抽样通过'
    else
      warn '登录未拿到 token：降级为仅健康抽样'
    fi
  else
    warn '未配置 QA_E2E_PASSWORD：无 DUT 环境下降级为仅健康抽样'
  fi
}

rollback() {
  local reason="$1"
  set +e
  STEP='回滚-停服'
  [[ "$DRILL_MODE" == "1" ]] || "$OPS_DIR/ctl.sh" stop
  STEP='回滚-恢复程序包'
  restore_program
  if [[ "$SCHEMA_MODE" != "keep" && -n "$DB_BACKUP" ]]; then
    STEP='回滚-恢复DB'
    restore_db
  fi
  STEP='回滚-起服与健康'
  # 演练注入只针对「升级前进」的那一次健康抽样；回滚后的复检必须按真实结果判定。
  UPDATE_DRILL_FAIL_HEALTH=0
  if sample_health; then
    state_write ROLLED_BACK "$CUR_VER" "自动回滚成功：$reason" "$CUR_VER"
    history_append ROLLED_BACK "自动回滚成功：$reason"
    err "回滚成功，已回到 $CUR_VER；原因：$reason"
    release_lock
    exit 1
  fi
  state_write ROLLBACK_FAILED "$CUR_VER" "回滚后健康失败：$reason" "$CUR_VER"
  history_append ROLLBACK_FAILED "回滚后健康失败：$reason"
  err '回滚失败。按 docs/ROLLBACK.md 人工恢复（RTO ≤ 10 分钟，RPO = 0）'
  err "DB 备份：${DB_BACKUP:-<无>}"
  err "原程序包：${PKG_ARCHIVE:-<无>}"
  release_lock
  exit 2
}

prune_archives() {
  local count
  count="$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name 'pre-*.tar.gz' | wc -l | tr -d ' ')"
  while (( count > KEEP )); do
    local oldest
    oldest="$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name 'pre-*.tar.gz' -printf '%T@ %p\n' \
      | sort -n | head -1 | cut -d' ' -f2-)"
    [[ -n "$oldest" ]] || break
    log "保留最近 ${KEEP} 版，清理：$oldest"
    rm -f -- "$oldest"
    count=$((count - 1))
  done
}

main() {
  local started_at
  started_at="$(date +%s)"
  STEP='版本比对'
  check_versions
  STEP='IT 库守卫'
  check_it_guard
  STEP='包校验'
  verify_package
  STEP='锁与磁盘'
  take_lock
  check_disk
  STEP='doctor 与迁移清单'
  run_doctor
  local migrations
  migrations="$(list_new_migrations)"
  show_migrations "$migrations"
  if [[ "$SCHEMA" == "keep" ]]; then
    if [[ -n "$migrations" ]] && is_destructive $migrations; then
      die '新迁移含破坏性语句（drop/modify/rename/truncate），不允许 --schema=keep'
    fi
    SCHEMA_MODE="keep"
    ok '迁移均为 expand，可保持现有 schema（回滚不恢复 DB）'
  elif [[ "$SCHEMA" == "auto" ]]; then
    if [[ -n "$migrations" ]] && ! is_destructive $migrations; then
      SCHEMA_MODE="keep"
      ok '自动判定：expand 迁移，--schema=keep 生效'
    else
      SCHEMA_MODE="restore"
      log '自动判定：破坏性迁移或未给迁移 → 回滚将恢复 DB 备份'
    fi
  else
    SCHEMA_MODE="restore"
  fi

  if [[ "$APPLY" == "0" ]]; then
    log 'dry-run：未备份、未停服、未覆盖、未迁移。确认后加 --apply。'
    release_lock
    exit 0
  fi

  if [[ "$YES" == "0" ]]; then
    [[ -t 0 ]] || die '非交互环境必须显式给 --yes'
    printf '[ops] 目标：%s → %s（schema=%s）。输入 UPDATE 确认：' "$CUR_VER" "$NEW_VER" "$SCHEMA_MODE"
    read -r answer
    [[ "$answer" == "UPDATE" ]] || die '已取消'
  fi

  STEP='DB 备份与归档当前包'
  backup_db
  archive_current

  STEP='停服与覆盖'
  [[ "$DRILL_MODE" == "1" ]] || "$OPS_DIR/ctl.sh" stop
  swap_program

  STEP='起服与正序迁移'
  if [[ "$DRILL_MODE" == "1" ]]; then
    log '演练模式：跳过启动（迁移由应用启动时 Flyway 正序执行）'
  else
    "$OPS_DIR/ctl.sh" start
  fi

  STEP='健康抽样'
  if ! sample_health; then
    err '健康抽样未通过'
    rollback 'step=健康抽样'
    exit 2
  fi

  STEP='审计与 UPDATE-HISTORY'
  ELAPSED=$(( $(date +%s) - started_at ))
  (( ELAPSED <= 600 )) || die "RTO 超标：${ELAPSED}s > 600s"
  state_write PASS "$NEW_VER" "升级成功（schema=$SCHEMA_MODE）"
  history_append PASS "schema=$SCHEMA_MODE migrations=${migrations//$'\n'/,}"
  ok "升级完成，用时 ${ELAPSED}s（RTO ≤ 600s）"

  STEP='状态结论'
  [[ "$DRILL_MODE" == "1" ]] || "$OPS_DIR/status.sh" || true

  STEP='保留最近 N 版'
  prune_archives
  release_lock
  printf 'UPDATE PASS from=%s to=%s schema=%s elapsed=%ss\n' "$CUR_VER" "$NEW_VER" "$SCHEMA_MODE" "$ELAPSED"
}

main
