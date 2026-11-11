#!/usr/bin/env bash
# 后端 lint：密文扫描 + Checkstyle（WS-02 于 2026-11-06 接入，真实阻断）+ 编译。
# 前端有真实 eslint。规则集见 backend/config/checkstyle.xml，扩展前须先清存量。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p artifacts

echo "[lint] 密文扫描"
bash scripts/secret-scan.sh

if [[ -f frontend/package.json ]]; then
    command -v npm >/dev/null 2>&1 || { echo "[lint] 缺少 npm" >&2; exit 1; }
    if [[ ! -d frontend/node_modules ]]; then
        echo "[lint] frontend/node_modules 不存在，先 npm ci"
        (cd frontend && npm ci)
    fi
    echo "[lint] frontend: npm run lint"
    (cd frontend && npm run lint)
fi

if [[ -f backend/pom.xml ]]; then
    command -v mvn >/dev/null 2>&1 || { echo "[lint] 缺少 mvn" >&2; exit 1; }
    # WS-02：Checkstyle 真实阻断（规则 backend/config/checkstyle.xml，只扫 src/main）
    echo "[lint] backend: mvn -B -q checkstyle:check"
    (cd backend && mvn -B -q checkstyle:check) 2>&1 | tee "$ROOT/artifacts/mvn-checkstyle.log"
    echo "[lint] backend: mvn -B -DskipTests compile"
    (cd backend && mvn -B -q -DskipTests compile) 2>&1 | tee "$ROOT/artifacts/mvn-compile.log"
fi

# ---- 前端静态守门（v0.2 UX-01b / CAS-03 / v0.3 EDT 的产出） ----
# 2026-11-04 按各版 DEV-PLAN「完成判定」逐项实跑时发现：这些脚本一直只写在文档里，
# lint.sh 从未调用过它们 —— 写了守门却没接线，等于 CI 三绿是假绿。此处统一接线。
echo "[lint] 前端静态守门（tools/check_*.py）"
for guard in check_colors check_contrast check_i18n check_states check_tokens; do
    echo "[lint]   - tools/${guard}.py"
    python3 "tools/${guard}.py"
done
echo "[lint]   - tools/check_import_header.py（CAS-03 台账表头对账）"
python3 tools/check_import_header.py fixtures/case-ledger/ledger-sample-226.xlsx

# UX-04 美术 S1：构建产物体积 / styleguide 泄漏守门。dist 缺失时脚本自身打印
# [SKIP] 并返回 0，不会让 lint 假红；有违规才返回非 0，由 set -e 中断。
echo "[lint]   - tools/check_bundle_budget.py（UX-04 S1 产物体积守门）"
python3 tools/check_bundle_budget.py

# UX-04 新增的三条静态守门：token 清单对账 / 六预设主色派生 / 字体子集预算。
# 与上面五条一样，写了不接线就是假绿，所以统一挂在这里。
echo "[lint]   - tools/check_styleguide_tokens.py（styleguide ↔ theme.css 对账）"
python3 tools/check_styleguide_tokens.py
echo "[lint]   - tools/gen_accent_tokens.py --check（六预设 WCAG 派生）"
python3 tools/gen_accent_tokens.py --check
echo "[lint]   - tools/check_accent_presets.py（六预设清单七处一致）"
python3 tools/check_accent_presets.py
echo "[lint]   - tools/subset_font.py --check（打包字体子集预算）"
python3 tools/subset_font.py --check

# 图标名对账：模板里的 `icon="x"` 不受 vue-tsc 检查（未开 strictTemplates），
# 写错名字只渲染空白、无任何报错。这条把字面量与 iconMap.ts 对起来。
echo "[lint]   - tools/check_icons.py（图标名 ↔ iconMap.ts 对账）"
python3 tools/check_icons.py

# 模板组件 ↔ import 对账：漏 import 的组件不会报错，只会静默渲染空白。
# 2026-11-05 三个视图的空态就这么白过一轮（构建/单测/lint 全绿）。
echo "[lint]   - tools/check_component_imports.py（模板组件 ↔ import 对账）"
python3 tools/check_component_imports.py

# WBS 进度表自洽：汇总数字必须等于逐行数出来的分布。
# 2026-11-05 真实发生过：F2 裁决改了汇总行/分组表/修订记录三处，唯独 8 个单包行
# 的状态格没改，汇总显示"待现场 0 / 已完成 13"，逐行数出来是"待现场 8 / 已完成 5"。
echo "[lint]   - tools/check_report_sensitive.py（C5 EXTERNAL 字段判据自校验）"
python3 tools/check_report_sensitive.py --self-check
echo "[lint]   - tools/check_api_prefix.py（前端 request() 双 /api 前缀守门）"
python3 tools/check_api_prefix.py
echo "[lint]   - tools/check_w7_tokens.py（W7 token / 密度 / 深浅覆盖守门）"
python3 tools/check_w7_tokens.py
echo "[lint]   - tools/check_w7_tokens.py --self-check（破坏性元验证）"
python3 tools/check_w7_tokens.py --self-check
echo "[lint]   - tools/check_wbs_consistency.py（WBS 汇总数字 ↔ 逐行状态）"
python3 tools/check_wbs_consistency.py

# 表格规范：数据网格必须走 DataTable 壳；原生 <table> 只许留在三处白名单。
# 壳是"可选"的，绕过它页面照样能跑（naive-ui 内部类名不变），所以只能静态对账。
echo "[lint]   - tools/check_tables.py（数据网格 ↔ DataTable 壳 / 原生 table 白名单）"
python3 tools/check_tables.py

# 品牌：favicon 可辨性（16px 近似机检）+ 侧栏/登录页都挂了标记 + 版本号只在「关于」。
# 这三件事都属"一次验收之后就没人再看"的类别，退化时页面完全正常。
echo "[lint]   - tools/check_brand.py（favicon 可辨 / 品牌区挂载 / 版本号归位）"
python3 tools/check_brand.py

# 菜单信息架构（v4.11.0 / W-A-04）：层级 ≤2、全局每组 ≥2、同 path 的 query 互异。
# 菜单是纯配置：某组退化成单叶、两个页签写成同一个 query，页面照样能跑、选中态却不再亮。
# 单测在 TS 运行时断言了同一批不变量；这条不依赖运行时，门禁里能先于编译拦住结构错误。
echo "[lint]   - tools/check_navigation.py（菜单层级 / 分组最小项数 / query 互异）"
python3 tools/check_navigation.py

echo "[lint] PASS"
