#!/usr/bin/env bash
# 升级演练（FR-v4.0-05 / TC-v4.0-05）。
#
# 从真实交付包（releases/*.tar.gz）复制两份：一份当「现网前缀」，一份当「新包」，
# 新包只做两件合法改动（VERSION +1 段、追加一条 expand-only 迁移），再重算 MANIFEST，
# 然后跑 scripts/ops/update.sh 的四个阶段：
#
#   A dry-run       只列迁移清单，不写盘
#   B 包校验负向    改一个字节 → 必须拒绝（sha256 闸门真的会红）
#   C 正序升级      --apply --yes --schema=keep → UPDATE PASS，版本/状态/归档/历史齐
#   D 失败回滚      注入健康失败 → 自动回滚，版本回到原值，state=ROLLED_BACK
#
# 说明：D 阶段用 --schema=keep，回滚只恢复程序文件，不动库 —— 演练不碰真实库数据。
# 真迁移与真实启停在 stage C 需要构建产物 + 可启动环境，本脚本不伪称做过。
set -Eeuo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/update-drill.XXXXXX")
KEEP_WORK=0
TARBALL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tarball) TARBALL="$2"; shift 2 ;;
    --keep-work) KEEP_WORK=1; shift ;;
    --help|-h) printf '用法: update-drill.sh [--tarball FILE] [--keep-work]\n'; exit 0 ;;
    *) printf '[drill] FAIL 未知参数 %s\n' "$1" >&2; exit 1 ;;
  esac
done

cleanup() {
  if [[ "$KEEP_WORK" == "1" ]]; then
    printf '[drill] 工作目录保留：%s\n' "$WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

if [[ -z "$TARBALL" ]]; then
  TARBALL="$(find "$ROOT/releases" -maxdepth 1 -type f -name '*.tar.gz' -printf '%T@ %p\n' \
    | sort -n | tail -1 | cut -d' ' -f2-)"
fi
[[ -n "$TARBALL" && -f "$TARBALL" ]] || { printf '[drill] FAIL 找不到交付包（releases/*.tar.gz）\n' >&2; exit 1; }
printf '[drill] 基线包：%s\n' "$TARBALL"

SRC="$WORK/src"
mkdir -p "$SRC"
tar -xzf "$TARBALL" -C "$SRC"
BASE="$(find "$SRC" -mindepth 1 -maxdepth 1 -type d | head -1)"
[[ -n "$BASE" ]] || { printf '[drill] FAIL 交付包结构异常\n' >&2; exit 1; }

OLD_VER="$(head -1 "$BASE/VERSION" | tr -d '[:space:]')"
NEW_VER="$(python3 -c 'import sys; p=sys.argv[1].split("."); p[-1]=str(int(p[-1])+1); print(".".join(p))' "$OLD_VER")"

PREFIX="$WORK/prefix"
PKG="$WORK/pkg"
cp -a "$BASE" "$PREFIX"
cp -a "$BASE" "$PKG"

# 两边都换成当前仓库的运维脚本：基线包早于 v4.0，bin/ 里没有升级脚本，
# doctor/backup 也需与新 update.sh 同一口径，否则演练会测到旧行为。
for script in update.sh common.sh doctor.sh backup.sh ctl.sh status.sh; do
  cp "$ROOT/scripts/ops/$script" "$PREFIX/bin/$script"
  cp "$ROOT/scripts/ops/$script" "$PKG/bin/$script"
done
chmod +x "$PREFIX/bin/"*.sh "$PKG/bin/"*.sh
# 复位基线必须在脚本注入之后快照，否则 reset_prefix 会把新脚本又冲掉。
cp -a "$PREFIX" "$WORK/prefix-base"

printf '%s\n' "$NEW_VER" > "$PKG/VERSION"
cat > "$PKG/db/migration/V90__drill_expand.sql" <<'SQL'
-- 演练专用：expand-only（只加表，不改旧结构），用于验证 --schema=keep 判定。
CREATE TABLE IF NOT EXISTS drill_marker (
  id BIGINT PRIMARY KEY,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL

# 重算 MANIFEST（与 scripts/package-release.sh 同一口径：先扫文件，最后写 MANIFEST）。
python3 - "$PKG" "$NEW_VER" "$OLD_VER" <<'PY'
import hashlib, json, pathlib, sys
from datetime import datetime, timezone
bundle, new_ver, old_ver = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
files = []
total = 0
for p in sorted(bundle.rglob('*')):
    if not p.is_file() or p.name == 'MANIFEST.json':
        continue
    data = p.read_bytes()
    files.append({'path': p.relative_to(bundle).as_posix(), 'bytes': len(data),
                  'sha256': hashlib.sha256(data).hexdigest()})
    total += len(data)
manifest = {
    'product': 'AIDC AutoTest', 'name': f'drill-{new_ver}', 'version': new_ver,
    'commit': 'drill', 'buildTime': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'platform': 'drill', 'layout': 'bundle', 'secrets': 'none',
    'entrypoints': {'start': 'bin/ctl.sh start', 'backup': 'bin/backup.sh --apply'},
    'prerequisites': [], 'fileCount': len(files), 'totalBytes': total,
    'files': files, 'drillFrom': old_ver,
}
(bundle / 'MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n',
                                      encoding='utf-8')
print(f'MANIFEST files={len(files)} bytes={total}')
PY

FAILS=0
pass_case() { printf '[drill] PASS  %s\n' "$1"; }
fail_case() { printf '[drill] FAIL  %s\n' "$1" >&2; FAILS=$((FAILS + 1)); }

reset_prefix() {
  rm -rf "$PREFIX"
  cp -a "$WORK/prefix-base" "$PREFIX"
  # 工作区标记：升级必须原样保留（KEEP 区），丢失即说明覆盖写越界。
  mkdir -p "$PREFIX/workspace"
  printf 'keep-%s\n' "$OLD_VER" > "$PREFIX/workspace/.keep"
}

run_update() {
  local name="$1"
  shift
  local log="$WORK/$name.log"
  # 显式传环境：函数内的临时赋值不一定导出给子进程，演练钩子必须确实生效。
  UPDATE_DRILL=1 UPDATE_DRILL_FAIL_HEALTH="${UPDATE_DRILL_FAIL_HEALTH:-0}" \
    "$PREFIX/bin/update.sh" --package "$PKG" --prefix "$PREFIX" "$@" > "$log" 2>&1
}

# ---- A  dry-run ----
if run_update dry-run --schema=keep; then
  grep -q 'dry-run' "$WORK/dry-run.log" \
    && pass_case 'A dry-run 只读列出，不写盘' || fail_case 'A dry-run 未打印 dry-run 结论'
else
  fail_case "A dry-run 退出码非 0（见 $WORK/dry-run.log）"
fi

# ---- B  包校验负向 ----
printf 'tampered\n' >> "$PKG/app/frontend/index.html"
if run_update sha-negative --schema=keep; then
  fail_case 'B 包被改动却通过了 sha256 校验'
else
  grep -q 'sha256 校验失败' "$WORK/sha-negative.log" \
    && pass_case 'B 改一字节即红（MANIFEST sha256 闸门有效）' \
    || fail_case 'B 失败原因不是 sha256（闸门失效原因不对）'
fi
# 用原始字节恢复被改的文件
cp -a "$BASE/app/frontend/index.html" "$PKG/app/frontend/index.html"
python3 - "$PKG" <<'PY'
import hashlib, json, pathlib, sys
bundle = pathlib.Path(sys.argv[1])
m = json.load(open(bundle / 'MANIFEST.json', encoding='utf-8'))
for f in m['files']:
    p = bundle / f['path']
    f['sha256'] = hashlib.sha256(p.read_bytes()).hexdigest()
    f['bytes'] = p.stat().st_size
(bundle / 'MANIFEST.json').write_text(json.dumps(m, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
PY

# ---- C  正序升级 ----
reset_prefix
if run_update apply --schema=keep --apply --yes; then
  [[ "$(head -1 "$PREFIX/VERSION" | tr -d '[:space:]')" == "$NEW_VER" ]] \
    && pass_case "C 版本已到 $NEW_VER" || fail_case 'C 版本未更新'
  grep -q 'UPDATE PASS' "$WORK/apply.log" \
    && pass_case 'C 输出 UPDATE PASS' || fail_case 'C 缺 UPDATE PASS'
  grep -q 'last_result=PASS' "$PREFIX/var/update/state" \
    && pass_case 'C state=last_result=PASS' || fail_case 'C state 未记录 PASS'
  ls "$PREFIX"/backups/artifacts/pre-*.tar.gz >/dev/null 2>&1 \
    && pass_case 'C 原包已归档（可回滚）' || fail_case 'C 没有归档包'
  grep -q '^| ' "$PREFIX/UPDATE-HISTORY.md" \
    && pass_case 'C UPDATE-HISTORY 已追加' || fail_case 'C 历史未追加'
  [[ -f "$PREFIX/workspace/.keep" ]] && grep -q "keep-$OLD_VER" "$PREFIX/workspace/.keep" \
    && pass_case 'C workspace 原样保留（KEEP 区未动）' || fail_case 'C workspace 被覆盖或丢失'
else
  fail_case "C 正序升级失败（见 $WORK/apply.log）"
fi

# ---- D  失败回滚 ----
reset_prefix
if UPDATE_DRILL_FAIL_HEALTH=1 run_update rollback --schema=keep --apply --yes; then
  fail_case 'D 注入失败却返回成功'
else
  [[ "$(head -1 "$PREFIX/VERSION" | tr -d '[:space:]')" == "$OLD_VER" ]] \
    && pass_case "D 回滚后版本回到 $OLD_VER" || fail_case 'D 回滚后版本不对'
  grep -q 'last_result=ROLLED_BACK' "$PREFIX/var/update/state" \
    && pass_case 'D state=ROLLED_BACK' || fail_case 'D state 未记录回滚'
  grep -q 'ROLLED_BACK' "$PREFIX/UPDATE-HISTORY.md" \
    && pass_case 'D 历史记录回滚' || fail_case 'D 历史未记录回滚'
fi

printf 'UPDATE_DRILL_RESULT FAILS=%d baseline=%s target=%s\n' "$FAILS" "$OLD_VER" "$NEW_VER"
[[ "$FAILS" -eq 0 ]]
