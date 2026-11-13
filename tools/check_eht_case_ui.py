#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁 51 / EHT-10 / FR-v5.5-11：用例管理页真端点契约 + 八页 i18n 齐备。

守什么
------
1. 八张 EHT 页面各自 .vue + .spec.ts 都在；
2. 页面用到的每个 t('eht.*') 键在 zh-CN / en-US 都存在，且八页各自的
   i18n 节在两种语言里都非空（少一页键就判红）；
3. 用例管理页必须走三个真实端点：分页用例 / 工程健康（双口径覆盖率）/
   优先级字典（由 /cases 分页数据汇总，不写死业务枚举字面）；
   并保留非自动化用例编辑入口；
4. 用例页模板不得出现静态中文属性值（优先级字典由真实数据生成）；
5. scripts/gates.sh 登记步骤 51（只增不删）。

用法
----
    python3 tools/check_eht_case_ui.py
    python3 tools/check_eht_case_ui.py --self-test  # 真注入删一页 i18n 键 / 去掉真端点调用

退出码：0 通过 / 1 违规 / 2 扫描失败。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIEWS = ROOT / "frontend/src/views/eht"
ZH = ROOT / "frontend/src/locales/zh-CN.ts"
EN = ROOT / "frontend/src/locales/en-US.ts"
GATES = ROOT / "scripts/gates.sh"
CASE_VIEW = VIEWS / "EhtCaseLibraryView.vue"

PAGE_STEMS = [
    "EhtCaseLibraryView", "EhtQuickActionsView",
    "EhtDurationBaselineView", "EhtDispositionView",
    "EhtEnvironmentRadarView", "EhtKernelUnifiedView",
    "EhtManualExecutionView", "EhtResultSplitView",
]
SECTIONS = [
    "caseLibrary", "quickActions", "durationBaseline", "disposition",
    "environmentRadar", "kernelUnified", "manualExecution", "resultSplit",
]
MIN_EHT_KEYS = 35


def ok(msg: str) -> None:
    print("  OK     " + msg)


def fail(problems: list[str], msg: str) -> None:
    problems.append(msg)
    print("  FAIL   " + msg)


def flat_locale(text: str) -> dict[str, str]:
    """把 export default { ... } 折成 {'eht.caseLibrary.title': ...}。

    字符级扫描，只认「标识符键 : {」与「标识符键 : '字符串'」两种形态；
    一行多键、任意层嵌套都能处理。解析不到下限由调用方判扫描失败，拒绝假绿。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    anchor = text.find("export default")
    start = text.find("{", anchor) if anchor >= 0 else text.find("{")
    if start < 0:
        return {}
    stack: list[str] = []
    out: dict[str, str] = {}
    key_re = re.compile(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*")
    i, n = start, len(text)
    while i < n:
        if text[i] == "}":
            if stack:
                stack.pop()
            i += 1
            continue
        m = key_re.match(text, i)
        if m:
            key, j = m.group(1), m.end()
            if j < n and text[j] == "{":
                stack.append(key)
                i = j + 1
                continue
            quote = text[j] if j < n else ""
            if quote in ("'", '"', "`"):
                k = j + 1
                while k < n:
                    if text[k] == "\\":
                        k += 2
                        continue
                    if text[k] == quote:
                        break
                    k += 1
                out[".".join(stack + [key])] = text[j:k + 1]
                i = k + 1
                continue
        i += 1
    return out


def check(problems: list[str], view_dir: Path = VIEWS, zh_path: Path = ZH,
          en_path: Path = EN, gates: Path = GATES,
          case_view: Path = CASE_VIEW) -> None:
    for stem in PAGE_STEMS:
        if not (view_dir / (stem + ".vue")).exists():
            fail(problems, "缺页面 %s.vue" % stem)
        if not (view_dir / (stem + ".spec.ts")).exists():
            fail(problems, "缺页面 spec %s.spec.ts" % stem)
    ok("八张 EHT 页面 .vue / .spec.ts 齐备")

    zh = flat_locale(zh_path.read_text(encoding="utf-8"))
    en = flat_locale(en_path.read_text(encoding="utf-8"))
    ezh = {k: v for k, v in zh.items() if k.startswith("eht.")}
    een = {k: v for k, v in en.items() if k.startswith("eht.")}
    if len(ezh) < MIN_EHT_KEYS or len(een) < MIN_EHT_KEYS:
        fail(problems, "语言包 eht 键解析过少（zh=%d en=%d，下限 %d）：扫描失败，拒绝假绿"
             % (len(ezh), len(een), MIN_EHT_KEYS))
        return
    ok("语言包 eht 键：zh %d / en %d" % (len(ezh), len(een)))
    if set(ezh) != set(een):
        fail(problems, "eht 键集中英不一致：zh 独有 %s / en 独有 %s"
             % (sorted(set(ezh) - set(een)), sorted(set(een) - set(ezh))))
    else:
        ok("eht 键集中英一致")

    for section in SECTIONS:
        prefix = "eht." + section + "."
        z = [k for k in ezh if k.startswith(prefix)]
        e = [k for k in een if k.startswith(prefix)]
        if not z or not e:
            fail(problems, "i18n 缺整页：eht.%s（zh %d 键 / en %d 键）" % (section, len(z), len(e)))
    ok("八页 i18n 节逐节非空")

    used: set[str] = set()
    for stem in PAGE_STEMS:
        vue = view_dir / (stem + ".vue")
        if vue.exists():
            for m in re.finditer(r"\bt\(\s*'(eht\.[\w.]+)'", vue.read_text(encoding="utf-8")):
                used.add(m.group(1))
    missing = sorted(k for k in used if k not in ezh or k not in een)
    if missing:
        fail(problems, "页面引用了不存在的 eht 键：%s" % missing)
    else:
        ok("页面引用的 %d 个 eht 键双语齐备" % len(used))

    cv = case_view.read_text(encoding="utf-8") if case_view.exists() else ""
    if not cv:
        fail(problems, "用例管理页为空或缺失")
    else:
        required = [
            ("ehtViewsApi.cases(", "分页用例走真实端点"),
            ("pytestProjectApi.health(", "双口径覆盖率走工程健康端点"),
            ("ehtViewsApi.casePriorities(", "优先级字典由真实 /cases 数据汇总"),
            ("/eht/manual-execution", "非自动化用例编辑入口"),
        ]
        gaps = [label for needle, label in required if needle not in cv]
        if gaps:
            fail(problems, "用例管理页缺契约：%s" % gaps)
        else:
            ok("用例管理页四类真端点/入口契约齐备")
        bad = re.findall(r"(?<![:@])\b[a-zA-Z-]+=\"([^\"]*[\u4e00-\u9fff][^\"]*)\"", cv)
        if bad:
            fail(problems, "用例管理页出现静态中文属性值：%s" % bad)
        else:
            ok("用例管理页零静态中文属性值")

    if not re.search(r"^run 51 eht_case_ui ", gates.read_text(encoding="utf-8"), re.M):
        fail(problems, "gates.sh 未登记步骤 51")
    else:
        ok("gates.sh 登记步骤 51")


def run() -> int:
    print("check_eht_case_ui: EHT 用例管理页与八页 i18n 契约")
    problems: list[str] = []
    check(problems)
    if problems:
        print("check_eht_case_ui: FAIL（%d 项）" % len(problems))
        return 1
    print("check_eht_case_ui: PASS")
    return 0


def self_test() -> int:
    """真注入：删掉一整页 i18n 键 / 去掉优先级真端点汇总，两次都必须判红。"""
    zh_original = ZH.read_text(encoding="utf-8")
    cv_original = CASE_VIEW.read_text(encoding="utf-8")
    zh_line = "    durationBaseline: { title: '用例时长基线' },\n"
    cv_line = "      ehtViewsApi.casePriorities(projectId.value),\n"
    if zh_line not in zh_original or cv_line not in cv_original:
        print("  FAIL   注入点没找到（语言包或用例页结构变了，机检要同步）")
        return 2
    tmp_zh = tmp_cv = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".ts", delete=False, encoding="utf-8") as h:
            h.write(zh_original.replace(zh_line, "", 1))
            tmp_zh = Path(h.name)
        with tempfile.NamedTemporaryFile("w", suffix=".vue", delete=False, encoding="utf-8") as h:
            h.write(cv_original.replace(cv_line, "", 1))
            tmp_cv = Path(h.name)
        injections = [
            ("删掉一整页 i18n 键（durationBaseline）", {"zh_path": tmp_zh}),
            ("用例页去掉优先级真端点汇总", {"case_view": tmp_cv}),
        ]
        for label, kwargs in injections:
            problems: list[str] = []
            check(problems, **kwargs)
            if not problems:
                print("  FAIL   %s 后机检仍判绿（假绿灯）" % label)
                return 1
            print("  OK     %s -> 判红（%d 项）" % (label, len(problems)))
    finally:
        for tmp in (tmp_zh, tmp_cv):
            if tmp is not None:
                tmp.unlink(missing_ok=True)
    print("--- 还原后应判绿 ---")
    problems = []
    check(problems)
    if problems:
        print("  FAIL   还原后仍判红：%s" % problems)
        return 1
    print("check_eht_case_ui: SELF-TEST PASS")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="EHT 用例管理页与八页 i18n 机检")
    p.add_argument("--self-test", action="store_true", help="破坏性自检")
    a = p.parse_args()
    return self_test() if a.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
