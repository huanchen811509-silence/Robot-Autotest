#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CLOS-02 · 三处单测回归基线机检（纯标准库，FR-v2.2-28）。

用法：
    python3 tools/check_test_baseline.py               # 读三份真实门禁日志 + 判据自校验
    python3 tools/check_test_baseline.py --self-check  # 只跑判据自校验（负向实证）

判据：
    frontend / backend / editor 三处 failures 与 errors 必须均为 0；
    tests 必须 >= test-baseline.json 的 baseline.tests（只增不减）；
    baseline.tests 不得低于 floor（防止偷偷调低基线放过倒退）；
    日志缺失 / 解析不出 -> 明确报错且退出非 0，绝不静默 PASS。

退出码：0 = 三处达标且自校验通过；1 = 倒退/失败/日志异常/自校验失灵；2 = 基线文件异常。
"""
from __future__ import annotations

import copy
import json
import pathlib
import re
import sys
import tempfile

BASELINE_REL = "docs/evidence/v3.0.1/test-baseline.json"
LOG_SPEC = {
    "frontend": ("docs/evidence/gates/logs/02-frontend_test.log", "vitest"),
    "backend": ("docs/evidence/gates/logs/05-backend_test.log", "maven"),
    "editor": ("docs/evidence/gates/logs/11-editor_test.log", "vitest"),
}
ORDER = ("frontend", "backend", "editor")

VITEST_RE = re.compile(r"Tests\s+(\d+)\s+passed\s*\((\d+)\)")
FAILED_RE = re.compile(r"(?i)(\d+)\s+failed")
ERRORS_RE = re.compile(r"(?i)(\d+)\s+errors?\b")
MAVEN_RE = re.compile(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)")


def parse_log(kind: str, text: str):
    if kind == "maven":
        hits = list(MAVEN_RE.finditer(text))
        if not hits:
            raise ValueError("未解析出 'Tests run: N, Failures: F, Errors: E'")
        # 汇总行是 N 最大的一条（per-class 行 N 小且带 '-- in ...'）。
        m = max(hits, key=lambda x: int(x.group(1)))
        if "BUILD SUCCESS" not in text:
            raise ValueError("日志缺 'BUILD SUCCESS'（构建失败或日志被截断）")
        return {
            "tests": int(m.group(1)),
            "failures": int(m.group(2)),
            "errors": int(m.group(3)),
            "detail": "汇总行 Tests run: %s（共 %d 条读数，取 N 最大者）" % (m.group(1), len(hits)),
        }
    m = VITEST_RE.search(text)
    if not m:
        raise ValueError("未解析出 'Tests N passed (N)'")
    passed, total = int(m.group(1)), int(m.group(2))
    failures = sum(int(x) for x in FAILED_RE.findall(text))
    errors = sum(int(x) for x in ERRORS_RE.findall(text))
    if passed != total:
        failures = max(failures, total - passed)
    return {
        "tests": passed,
        "failures": failures,
        "errors": errors,
        "detail": "Tests %d passed (%d)" % (passed, total),
    }


def load_baseline(root: pathlib.Path):
    p = root / BASELINE_REL
    if not p.is_file():
        raise FileNotFoundError("基线文件缺失: " + BASELINE_REL)
    data = json.loads(p.read_text(encoding="utf-8"))
    for k in ORDER:
        if k not in data.get("baseline", {}) or k not in data.get("floor", {}):
            raise ValueError("基线文件缺 %s 的 baseline / floor 读数" % k)
    return data


def collect_records(root: pathlib.Path):
    records, errs = {}, []
    for k in ORDER:
        rel, kind = LOG_SPEC[k]
        p = root / rel
        if not p.is_file():
            errs.append("%s: 日志缺失 %s" % (k, rel))
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        try:
            records[k] = parse_log(kind, text)
        except ValueError as e:
            errs.append("%s: 解析失败 %s -> %s" % (k, rel, e))
    return records, errs


def evaluate(records: dict, base: dict):
    problems = []
    for k in ORDER:
        if k not in records:
            problems.append("%s: 无有效读数（日志缺失或解析失败）" % k)
            continue
        r = records[k]
        b = base["baseline"][k]
        floor = int(base["floor"][k])
        base_tests = int(b["tests"])
        if base_tests < floor:
            problems.append("%s: 基线被下调 baseline.tests=%d < floor=%d（拒绝放过倒退）" %
                           (k, base_tests, floor))
        if r["failures"] != 0 or r["errors"] != 0:
            problems.append("%s: failures=%d errors=%d，必须为 0" %
                           (k, r["failures"], r["errors"]))
        if r["tests"] < base_tests:
            problems.append("%s: tests=%d < 基线 %d（只增不减）" %
                           (k, r["tests"], base_tests))
    return problems


GOOD_LOG = {
    "frontend": " Test Files  49 passed (49)\n      Tests  325 passed (325)\n",
    "backend": "[INFO] Tests run: 1016, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n",
    "editor": " Test Files  24 passed (24)\n      Tests  219 passed (219)\n",
}
BAD_BACKEND = "[INFO] Tests run: 1016, Failures: 1, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n"


def self_check(root: pathlib.Path) -> int:
    try:
        base = load_baseline(root)
    except (OSError, ValueError) as e:
        print("FAIL  判据自校验无法加载基线: %s" % e)
        return 1
    print("[判据自校验] 临时目录构造假日志，逐组走 parse_log + evaluate：")
    state = {"ok": True}

    def run_case(label, logs, baseline, expect_red):
        records, errs = {}, []
        with tempfile.TemporaryDirectory(prefix="clos02-base-") as td:
            tmp = pathlib.Path(td)
            for k in ORDER:
                name = pathlib.Path(LOG_SPEC[k][0]).name
                (tmp / name).write_text(logs[k], encoding="utf-8")
                try:
                    records[k] = parse_log(LOG_SPEC[k][1], logs[k])
                except ValueError as e:
                    errs.append("%s: 解析失败 -> %s" % (k, e))
            problems = evaluate(records, baseline) + errs
        judged_red = bool(problems)
        hit = judged_red == expect_red
        tag = "PASS" if hit else "FAIL"
        verdict = "判据判红（预期判红）" if expect_red else "判据接受（预期接受）"
        detail = "; ".join(problems[:2]) if problems else "0 问题"
        print("  [%s] %s -> %s | %s" % (tag, label, verdict, detail))
        if not hit:
            state["ok"] = False

    run_case("正常三处读数 + 正常基线", GOOD_LOG, base, False)
    run_case("backend failures=1 假日志 + 正常基线", dict(GOOD_LOG, backend=BAD_BACKEND), base, True)
    tampered = copy.deepcopy(base)
    tampered["baseline"]["backend"]["tests"] = 1
    run_case("正常三处读数 + 基线被下调到 1", GOOD_LOG, tampered, True)
    run_case("backend 日志乱码（解析不出） + 正常基线",
             dict(GOOD_LOG, backend="NOT A TEST LOG\n"), base, True)
    with tempfile.TemporaryDirectory(prefix="clos02-missing-") as td:
        missing_records, missing_errs = collect_records(pathlib.Path(td))
        judged_red = bool(missing_errs) and bool(evaluate(missing_records, base))
        tag = "PASS" if judged_red else "FAIL"
        print("  [%s] 三份日志全缺失 -> 判据判红（预期判红） | %s" %
              (tag, "; ".join(missing_errs[:2])))
        if not judged_red:
            state["ok"] = False

    if state["ok"]:
        print("PASS  判据自校验：5 组样本全部符合预期（正常放行、failures=1 判红、下调基线判红、乱码日志判红、日志缺失判红）")
    else:
        print("FAIL  判据自校验：存在样本未按预期判定，判据失灵")
    return 0 if state["ok"] else 1


def main(argv) -> int:
    self_only = any(a in ("--self-check", "--selfcheck") for a in argv)
    print("=" * 78)
    print("CLOS-02 · 三处单测回归基线机检（FR-v2.2-28）")
    print("=" * 78)
    root = pathlib.Path.cwd()
    if self_only:
        return self_check(root)
    if not (root / "scripts" / "gates.sh").is_file():
        print("FAIL  请在仓库根目录运行（找不到 scripts/gates.sh）")
        return 2
    try:
        base = load_baseline(root)
    except (OSError, ValueError) as e:
        print("FAIL  基线文件异常: %s" % e)
        return 2
    print("[基线] %s | capturedAt=%s" % (BASELINE_REL, base.get("capturedAt", "?")))
    print("       floor=%s（防下调）  rule=%s" % (base["floor"], base.get("rule", "")))
    records, errs = collect_records(root)
    for k in ORDER:
        rel = LOG_SPEC[k][0]
        if k in records:
            r = records[k]
            b = base["baseline"][k]
            print("  [%s] %s -> %s | tests=%d failures=%d errors=%d | 基线 tests=%d" %
                  (k, pathlib.Path(rel).name, r["detail"], r["tests"], r["failures"],
                   r["errors"], int(b["tests"])))
    problems = evaluate(records, base) + errs
    print()
    sc = self_check(root)
    print()
    if problems:
        for p in problems:
            print("  FAIL " + p)
        print("结论 FAIL：三处基线判据未达标（日志缺失/解析失败/倒退/failures 非 0 均不放行）")
        return 1
    if sc != 0:
        print("结论 FAIL：判据自校验失灵（负向实证未通过），门禁不得放行")
        return 1
    print("结论 PASS：frontend / backend / editor 三处 failures=0、errors=0，"
          "tests 均不低于基线（只增不减），判据自校验通过")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main(sys.argv[1:]))
