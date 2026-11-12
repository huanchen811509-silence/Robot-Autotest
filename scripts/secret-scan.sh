#!/usr/bin/env bash
# 秘密扫描：仓库内不得出现明文口令（FR-v0.1-07 / TC-v0.1-031）
# 命中即退出 1；排除文档与依赖目录
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "[secret-scan] 扫描明文口令赋值…"
# 只认「引号内首字符不是 $」的字面量；变量引用（"${DB_PASSWORD:-}" 与 --password="$DB_PASSWORD"）不算明文。
# 双向自检：放宽正则后必须仍能拦住真明文，且不得把变量引用误判成明文——避免「全跳过=PASS」。
#
# 2026-11-06 扩容：原来只认 `password=`，于是证据脚本里的 `QA_PASS = '...'` 拦不住
# （那两处确实是明文口令）。**但不改成裸 `pass=`** —— 实测会把
# `CHANGE_FAIL_TO_PASS = "FAIL_TO_PASS"`、`<stat pass="0" fail="0">`、`pass=" + n` 全判成明文，
# 误报会训练人忽略告警。这里只加本项目夹具的具名规则：QA_PASS / *_PASSWORD / FIXTURE_PASSWORD。
REGEX="(password|QA_PASS|FIXTURE_PASSWORD|QA_E2E_PASSWORD)[[:space:]]*=[[:space:]]*['\"][^'\"\$][^'\"]*['\"]"
# 环境变量**名**不是口令：`ENV_SSH_PASSWORD = "PYTEST_SSH_PASSWORD"` 里右侧只是
# 「渲染器要输出的 ${ENV:...} 键名」，没有任何密钥信息。只豁免「以凭据后缀结尾的
# 全大写标识符」，不放宽到「任何全大写串」——否则 `QA_PASS = "HUNTER2BIGVALUE"` 会漏。
EXEMPT="['\"][A-Z][A-Z0-9_]*_(PASSWORD|PASSWD|SECRET|TOKEN|KEY)['\"]"
if ! printf '%s
' 'password="hunter2-selftest"' | grep -Eqi "$REGEX"; then
    echo "[secret-scan] 自检 FAIL：正则已无法识别真明文" >&2
    exit 1
fi
if ! printf '%s
' 'QA_PASS = "hunter2-selftest"' | grep -Eqi "$REGEX"; then
    echo "[secret-scan] 自检 FAIL：夹具口令命名漏检（2026-11-06 扩容失效）" >&2
    exit 1
fi
if printf '%s
' 'DB_PASSWORD="${DB_PASSWORD:-}"' | grep -Eqi "$REGEX"; then
    echo "[secret-scan] 自检 FAIL：变量引用被误判为明文" >&2
    exit 1
fi
if printf '%s
' 'CHANGE_FAIL_TO_PASS = "FAIL_TO_PASS"' | grep -Eqi "$REGEX"; then
    echo "[secret-scan] 自检 FAIL：状态常量被误判为口令" >&2
    exit 1
fi
if printf '%s
' '<stat pass="0" fail="0" skip="0">All Tests</stat>' | grep -Eqi "$REGEX"; then
    echo "[secret-scan] 自检 FAIL：XML 的 pass 属性被误判为口令" >&2
    exit 1
fi
# 双向锁住豁免规则：值确实是环境变量名时不算命中；全大写但不像环境变量名的仍是明文。
if printf '%s
' 'ENV_SSH_PASSWORD = "PYTEST_SSH_PASSWORD"' | grep -vEqi "$EXEMPT"; then
    echo "[secret-scan] 自检 FAIL：环境变量名被误判为明文（豁免规则失效）" >&2
    exit 1
fi
if ! printf '%s
' 'QA_PASS = "HUNTER2BIGVALUE"' | grep -vEqi "$EXEMPT"; then
    echo "[secret-scan] 自检 FAIL：全大写真明文被豁免掉了（放宽过头）" >&2
    exit 1
fi

# 排除本脚本自身：上面两行自检样例含 password="..." 字面量，否则会扫到自己（假红）。
# 正则本身由自检双向锁住，本脚本不含任何真实凭据。
# 覆盖面修正（2026-11-06）：原来只用 `git grep`，那只扫**已跟踪**文件。
# 门禁是在提交前跑的，新写的证据脚本还没 `git add`，于是整批漏扫 ——
# 本轮 `docs/evidence/v1.0/auth-http-e2e/run.py` 与 `.../edt09-save-chain/run.py`
# 两份夹具口令就是这样过一次「PASS」、提交后才变红的。
# 改为扫「已跟踪 + 未跟踪（仍受 .gitignore 约束）」，让门禁在提交前就能判。
mapfile -d '' SCAN_FILES < <(git ls-files -z --cached --others --exclude-standard)
HITS=0
for f in "${SCAN_FILES[@]}"; do
    case "$f" in
        *.md|*.log|frontend/package-lock.json|artifacts/*|scripts/secret-scan.sh) continue ;;
    esac
    [[ -f "$f" ]] || continue
    if grep -nEi "$REGEX" -- "$f" 2>/dev/null | grep -vEi "$EXEMPT" | sed "s|^|$f:|"; then
        HITS=1
    fi
done
if [[ $HITS -ne 0 ]]; then
    echo "[secret-scan] FAIL：发现疑似明文口令（见上）" >&2
    exit 1
fi

echo "[secret-scan] PASS（无命中，双向自检已过）"
