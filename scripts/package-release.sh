#!/usr/bin/env bash
# 交付包打包（FR-v3.11-D05）：产出 WSL2 / Ubuntu 可下载、可部署、可运维的 tar.gz。
#
#   bash scripts/package-release.sh                 # 打包到 releases/
#   bash scripts/package-release.sh --check        # 只校验构建前置，不产包
#   bash scripts/package-release.sh --out /tmp/x   # 指定输出目录
#
# 前置：先跑 scripts/build.sh（编辑器 → 前端 → 后端）。CI 的 delivery.yml 用同一序列。
# 产物：<out>/robot-autotest-<VERSION>-<SHA>-linux-x64.tar.gz + .sha256 + .manifest.json
# 内容见 MANIFEST.json；**不含任何凭据、日志、node_modules、target、.git**。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT_DIR="releases"
CHECK_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) [[ $# -ge 2 ]] || { echo 'FAIL --out 缺取值' >&2; exit 1; }; OUT_DIR="$2"; shift 2 ;;
    --check) CHECK_ONLY=1; shift ;;
    --help|-h)
      printf '用法: package-release.sh [--out DIR] [--check]\n'; exit 0 ;;
    *) printf 'FAIL 未知参数 %s\n' "$1" >&2; exit 1 ;;
  esac
done

fail=0
require() {
  if [[ -e "$1" ]]; then
    printf '[pkg] PASS  %s\n' "$1"
  else
    printf '[pkg] FAIL  %s（先跑 scripts/build.sh）\n' "$1" >&2
    fail=1
  fi
}

JAR="$(ls -1 backend/target/*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
[[ -n "$JAR" ]] || { printf '[pkg] FAIL 没找到后端 jar\n' >&2; fail=1; }
require frontend/dist/index.html
require frontend/dist/CHANGELOG.md
require scripts/serve-entry.mjs
require scripts/ops/common.sh
require scripts/ops/doctor.sh
require scripts/ops/install.sh
require scripts/ops/uninstall.sh
require scripts/ops/ctl.sh
require scripts/ops/status.sh
require scripts/ops/backup.sh
require scripts/ops/logs.sh
require scripts/ops/db-migrate.sh
require scripts/ops/README.md
require deploy/env.example
require deploy/release/robot-autotest-backend.service
require deploy/release/robot-autotest-entry.service
require docs/DELIVERY-GUIDE.md
require docs/OPS-MANUAL.md
require docs/ROLLBACK.md
require VERSION
require CHANGELOG.md
require README.md

if compgen -G 'db/migration/V*.sql' >/dev/null; then
  printf '[pkg] PASS  db/migration/V*.sql\n'
else
  printf '[pkg] FAIL db/migration/V*.sql 缺失\n' >&2; fail=1
fi
[[ "$fail" -eq 0 ]] || exit 1
if [[ "$CHECK_ONLY" == "1" ]]; then
  printf 'PACKAGE_CHECK PASS fails=0\n'
  exit 0
fi

VERSION="$(head -1 VERSION | tr -d '\r' | tr -d '[:space:]')"
SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
NAME="robot-autotest-${VERSION}-${SHA}-linux-x64"
STAGE="$(mktemp -d)"
BUNDLE="$STAGE/$NAME"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$OUT_DIR" \
  "$BUNDLE/bin" "$BUNDLE/app/backend" "$BUNDLE/app/frontend" "$BUNDLE/app/editor" \
  "$BUNDLE/entry" "$BUNDLE/db" "$BUNDLE/deploy" "$BUNDLE/docs"

# ---- 程序产物：统一命名，避免 unit 里出现带版本号的 jar 名 ----
cp "$JAR" "$BUNDLE/app/backend/robot-autotest.jar"
cp -a frontend/dist/. "$BUNDLE/app/frontend/"
if [[ -d packages/editor/dist ]]; then cp -a packages/editor/dist/. "$BUNDLE/app/editor/"; fi
cp scripts/serve-entry.mjs "$BUNDLE/entry/"

# ---- 运维脚本：bin/ 是唯一实现；顶层只放薄 wrapper ----
# 2026-11-09 解包自检实测：直接把 install.sh 复制到顶层会找不到同目录的 common.sh，
# 因为 common.sh 只在 bin/。wrapper 转调 bin/ 才是「一份实现两处入口」的正确形态。
cp scripts/ops/*.sh "$BUNDLE/bin/"
cp scripts/ops/README.md "$BUNDLE/bin/README.md"
for entry in install uninstall; do
  cat > "$BUNDLE/$entry.sh" <<EOF
#!/usr/bin/env bash
# 顶层便捷入口：实现在 bin/$entry.sh，避免两份脚本漂移。
set -euo pipefail
HERE="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
exec "\$HERE/bin/$entry.sh" "\$@"
EOF
done
chmod +x "$BUNDLE"/bin/*.sh "$BUNDLE"/install.sh "$BUNDLE"/uninstall.sh

# ---- 数据库与部署样例 ----
cp -a db/migration/. "$BUNDLE/db/migration/"
cp -a db/down/. "$BUNDLE/db/down/" 2>/dev/null || mkdir -p "$BUNDLE/db/down"
cp -a deploy/. "$BUNDLE/deploy/"
cp deploy/release/*.service "$BUNDLE/deploy/"
rm -rf "$BUNDLE/deploy/release"

# ---- 文档与版本信息 ----
for doc in DELIVERY-GUIDE.md OPS-MANUAL.md ROLLBACK.md CI接入指南.md; do
  [[ -f "docs/$doc" ]] && cp "docs/$doc" "$BUNDLE/docs/"
done
cp CHANGELOG.md README.md VERSION "$BUNDLE/"
if [[ -f backend/src/main/resources/build-info.properties ]]; then
  cp backend/src/main/resources/build-info.properties "$BUNDLE/"
fi

# ---- 清单：逐文件 sha256，供机检与离线核对 ----
python3 - "$BUNDLE" "$VERSION" "$SHA" "$NAME" <<'PY'
import hashlib, json, pathlib, sys
from datetime import datetime, timezone
bundle, version, sha, name = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
files = []
total = 0
for p in sorted(bundle.rglob('*')):
    if not p.is_file():
        continue
    rel = p.relative_to(bundle).as_posix()
    data = p.read_bytes()
    files.append({'path': rel, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    total += len(data)
manifest = {
    'product': 'AIDC AutoTest',
    'name': name,
    'version': version,
    'commit': sha,
    'buildTime': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'platform': 'linux-x64 / WSL2 Ubuntu 22.04+ / Ubuntu 24.04',
    'layout': 'bundle',
    'secrets': 'none',
    'entrypoints': {
        'install': 'install.sh --prefix <dir> --apply',
        'doctor': 'bin/doctor.sh --ci',
        'start': 'bin/ctl.sh start',
        'status': 'bin/status.sh',
        'backup': 'bin/backup.sh --apply',
        'uninstall': 'uninstall.sh --prefix <dir> --apply',
    },
    'prerequisites': ['JDK 21', 'Node.js 22+', 'MySQL 8.0', 'curl', 'tar', 'systemd --user (可选)'],
    'fileCount': len(files),
    'totalBytes': total,
    'files': files,
}
(bundle / 'MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'[pkg] MANIFEST files={len(files)} bytes={total}')
PY

# ---- 打包与校验和 ----
TARBALL="$OUT_DIR/$NAME.tar.gz"
tar -czf "$TARBALL" --sort=name --owner=0 --group=0 --numeric-owner -C "$STAGE" "$NAME"
(cd "$OUT_DIR" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")
cp "$BUNDLE/MANIFEST.json" "$OUT_DIR/$NAME.manifest.json"

# ---- 解包自检：必需路径 + 脚本可执行位 + 无凭据/日志 ----
LIST="$(tar -tzf "$TARBALL")"
for needle in "$NAME/MANIFEST.json" "$NAME/install.sh" "$NAME/uninstall.sh" \
              "$NAME/bin/doctor.sh" "$NAME/bin/ctl.sh" "$NAME/app/backend/robot-autotest.jar" \
              "$NAME/app/frontend/index.html" "$NAME/entry/serve-entry.mjs" \
              "$NAME/deploy/env.example" "$NAME/docs/DELIVERY-GUIDE.md" \
              "$NAME/db/migration/V1__" ; do
  case "$needle" in
    *'V1__'*) printf '%s\n' "$LIST" | grep -q "$NAME/db/migration/" || { echo "[pkg] FAIL 缺 $needle" >&2; exit 1; } ;;
    *) printf '%s\n' "$LIST" | grep -qx "$needle" || { echo "[pkg] FAIL 缺 $needle" >&2; exit 1; } ;;
  esac
done
if printf '%s\n' "$LIST" | grep -qE '\.log$|node_modules/|\.git/|/env$|env\.production'; then
  echo '[pkg] FAIL 包内出现日志 / 依赖 / git / 凭据文件' >&2
  exit 1
fi

printf '[pkg] tarball %s (%s bytes)\n' "$TARBALL" "$(wc -c < "$TARBALL" | tr -d ' ')"
printf '[pkg] sha256  %s\n' "$(cut -d' ' -f1 "$OUT_DIR/$NAME.tar.gz.sha256")"
printf 'PACKAGE PASS name=%s files=%s\n' "$NAME" "$(python3 -c "import json,sys;print(json.load(open('$OUT_DIR/$NAME.manifest.json',encoding='utf-8'))['fileCount'])")"
