#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁 52 / EHT-11,12 / FR-v5.5-12,13：八页必须全接真端点，不许 mock 顶替。

守什么
------
1. 八张 EHT 页面各自 .vue + .spec.ts 都在，且每页都调用 ehtViewsApi；
2. 每页代码里不得出现 Promise.resolve / setTimeout / vi.mock 等本地兜底；
3. ehtViewsApi 每个方法体必须含 request 或 casesPage（真实 HTTP），
   且不得返回本地常量；方法数不低于 10；
4. 路由登记八条 /eht/* 且组件指向对应页面；双语包八节齐；
5. docs/evidence/v5.5.0/eht/raw/eht-endpoint-probe.json 记录 12 个端点且全 200；
6. scripts/gates.sh 登记步骤 52（只增不删）。

用法
----
    python3 tools/check_eht_pages_live.py
    python3 tools/check_eht_pages_live.py --self-test  # 真注入把端点改成本地 mock

退出码：0 通过 / 1 违规 / 2 扫描失败。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIEWS = ROOT / "frontend/src/views/eht"
API = ROOT / "frontend/src/api/ehtViews.ts"
ROUTER = ROOT / "frontend/src/router/index.ts"
ZH = ROOT / "frontend/src/locales/zh-CN.ts"
EN = ROOT / "frontend/src/locales/en-US.ts"
GATES = ROOT / "scripts/gates.sh"
EVIDENCE = ROOT / "docs/evidence/v5.5.0/eht/raw/eht-endpoint-probe.json"

ROUTES = {
    "EhtCaseLibraryView": "/eht/cases",
    "EhtQuickActionsView": "/eht/quick-actions",
    "EhtDurationBaselineView": "/eht/duration-baseline",
    "EhtDispositionView": "/eht/disposition",
    "EhtEnvironmentRadarView": "/eht/environment-radar",
    "EhtKernelUnifiedView": "/eht/kernel-unified",
    "EhtManualExecutionView": "/eht/manual-execution",
    "EhtResultSplitView": "/eht/result-split",
}
SECTIONS = [
    "caseLibrary", "quickActions", "durationBaseline", "disposition",
    "environmentRadar", "kernelUnified", "manualExecution", "resultSplit",
]
MIN_METHODS = 10
MIN_ENDPOINTS = 12
LOCAL_MARKERS = ("Promise.resolve(", "setTimeout(", "vi.mock(", "ref<EhtRecord[]>([{")


def ok(msg: str) -> None:
    print("  OK     " + msg)


def fail(problems: list[str], msg: str) -> None:
    problems.append(msg)
    print("  FAIL   " + msg)


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def check(problems: list[str], api_path: Path = API, view_dir: Path = VIEWS,
          overrides: dict[str, Path] | None = None, router: Path = ROUTER,
          gates: Path = GATES, evidence: Path = EVIDENCE,
          locales: tuple[Path, Path] = (ZH, EN)) -> None:
    overrides = overrides or {}
    for stem in ROUTES:
        vue = overrides.get(stem, view_dir / (stem + ".vue"))
        spec = view_dir / (stem + ".spec.ts")
        if not vue.exists():
            fail(problems, "缺页面 %s.vue" % stem)
            continue
        if not spec.exists():
            fail(problems, "缺页面 spec %s.spec.ts" % stem)
        clean = strip_comments(vue.read_text(encoding="utf-8"))
        if "ehtViewsApi." not in clean:
            fail(problems, "%s 未调用 ehtViewsApi（页面没接真端点）" % stem)
        for marker in LOCAL_MARKERS:
            if marker in clean:
                fail(problems, "%s 出现本地兜底/mock：%s" % (stem, marker))
    ok("八页逐页检查 .vue / .spec.ts / 真端点调用 / 无本地 mock")

    api_text = strip_comments(api_path.read_text(encoding="utf-8"))
    if "const base = " not in api_text or "/projects/" not in api_text:
        fail(problems, "ehtViews.ts 缺 /projects/{id} 真实端点前缀")
    else:
        ok("ehtViews.ts 端点前缀为 /projects/{id}")
    block = re.search(r"export const ehtViewsApi = \{(.*?)\n\}", api_text, re.S)
    if not block:
        fail(problems, "找不到 ehtViewsApi 定义块（结构变了，机检要同步）")
    else:
        segments = [s for s in re.split(r"\n(?=  (?:async )?[A-Za-z_]\w*\()", block.group(1))
                    if re.match(r"\s{2}(?:async )?[A-Za-z_]\w*\(", s)]
        names = []
        for seg in segments:
            name = re.match(r"\s{2}(?:async )?([A-Za-z_]\w*)\(", seg).group(1)
            names.append(name)
            if "request" not in seg and "casesPage" not in seg:
                fail(problems, "ehtViewsApi.%s 方法体没有 request/casesPage（改成本地 mock）" % name)
            if "Promise.resolve(" in seg:
                fail(problems, "ehtViewsApi.%s 返回本地常量（mock）" % name)
        if len(names) < MIN_METHODS:
            fail(problems, "ehtViewsApi 方法数 %d < %d（漏了真端点封装）" % (len(names), MIN_METHODS))
        else:
            ok("ehtViewsApi %d 个方法全部走 request/casesPage" % len(names))

    rt = router.read_text(encoding="utf-8")
    for stem, path in ROUTES.items():
        if "path: '%s'" % path not in rt or "@/views/eht/%s.vue" % stem not in rt:
            fail(problems, "路由未登记 %s -> %s" % (path, stem))
    ok("八条 /eht/* 路由登记齐备")

    for lang in locales:
        lang_text = lang.read_text(encoding="utf-8")
        for section in SECTIONS:
            if not re.search(r"\b" + section + r"\s*:\s*\{", lang_text):
                fail(problems, "%s 缺 i18n 节 eht.%s" % (lang.name, section))
    ok("双语包八节齐备")

    if not evidence.exists():
        fail(problems, "缺端点响应证据 %s" % evidence.relative_to(ROOT).as_posix())
    else:
        data = json.loads(evidence.read_text(encoding="utf-8"))
        eps = data.get("endpoints", {})
        if len(eps) < MIN_ENDPOINTS:
            fail(problems, "端点证据只有 %d 个（应 >= %d）" % (len(eps), MIN_ENDPOINTS))
        bad = [k for k, v in eps.items() if v.get("status") != 200]
        if bad:
            fail(problems, "端点证据里有非 200：%s" % bad)
        else:
            ok("端点证据 %d 个全部 200（project_id=%s）" % (len(eps), data.get("project_id")))

    if not re.search(r"^run 52 eht_pages_live ", gates.read_text(encoding="utf-8"), re.M):
        fail(problems, "gates.sh 未登记步骤 52")
    else:
        ok("gates.sh 登记步骤 52")


def run() -> int:
    print("check_eht_pages_live: EHT 八页真实端点契约")
    problems: list[str] = []
    check(problems)
    if problems:
        print("check_eht_pages_live: FAIL（%d 项）" % len(problems))
        return 1
    print("check_eht_pages_live: PASS")
    return 0


def self_test() -> int:
    """真注入：把一个端点封装 / 一张页面改成本地 mock，两次都必须判红。"""
    api_original = API.read_text(encoding="utf-8")
    api_old = "    return request<EhtRecord[]>(`${base(projectId)}/duration-baselines`)"
    if api_old not in api_original:
        print("  FAIL   ehtViews.ts 注入点没找到")
        return 2
    page_path = VIEWS / "EhtDurationBaselineView.vue"
    page_original = page_path.read_text(encoding="utf-8")
    page_old = "    payload.value = await ehtViewsApi.durationBaselines(projectId.value)"
    if page_old not in page_original:
        print("  FAIL   页面注入点没找到")
        return 2
    tmp_api = tmp_page = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".ts", delete=False, encoding="utf-8") as h:
            h.write(api_original.replace(api_old, "    return Promise.resolve([])", 1))
            tmp_api = Path(h.name)
        with tempfile.NamedTemporaryFile("w", suffix=".vue", delete=False, encoding="utf-8") as h:
            h.write(page_original.replace(page_old, "    payload.value = await Promise.resolve([])", 1))
            tmp_page = Path(h.name)
        injections = [
            ("把 durationBaselines 端点封装改成本地 mock",
             {"api_path": tmp_api}),
            ("把时长基线页改成本地 mock",
             {"overrides": {"EhtDurationBaselineView": tmp_page}}),
        ]
        for label, kwargs in injections:
            problems: list[str] = []
            check(problems, **kwargs)
            if not problems:
                print("  FAIL   %s 后机检仍判绿（假绿灯）" % label)
                return 1
            print("  OK     %s -> 判红（%d 项）" % (label, len(problems)))
    finally:
        for tmp in (tmp_api, tmp_page):
            if tmp is not None:
                tmp.unlink(missing_ok=True)
    print("--- 还原后应判绿 ---")
    problems = []
    check(problems)
    if problems:
        print("  FAIL   还原后仍判红：%s" % problems)
        return 1
    print("check_eht_pages_live: SELF-TEST PASS")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="EHT 八页真实端点机检")
    p.add_argument("--self-test", action="store_true", help="破坏性自检")
    a = p.parse_args()
    return self_test() if a.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
