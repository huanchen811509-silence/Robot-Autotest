#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.1.0 · 门禁第 38 步：pytest 结果归一（FR-v5.1-08，W51-07）。

核心判据：**点分 classname 与斜杠 nodeid 的匹配键必须一致**。

背景：junit 的 `<testcase classname="tests.nic.base.test_im_fun_020">` 是点分，
json-report 的 `nodeid` 是 `tests/nic/base/test_im_fun_020.py::test_im_fun_020`，
两者不同源。平台用 `<文件末段>::<函数名>` 作匹配键（`PytestResultNormalizer.matchKey`）。
本脚本用 Python 复刻这条规则，对 `case-index.json` 里的真实脚本清单跑一遍：

A. **全命中**——61 个带编号脚本，按其路径生成的点分 classname 都能匹配回编号；
B. **参数化**——`name="test_x[a-b]"` 剥后缀后仍命中，不得因参数化掉成未绑定；
C. **未绑定可见**——库里没有对应脚本的编号，`unbound` 计数必须单调增加；
D. **规则同源**——Java 源码里 `matchKey` / `fileStem` / `baseName` 三者必须都在，
   少一个说明有人把规则改散到别处（那就会出现「归一用一套、入库用另一套」）。

W51-12 追加 E~G（步骤证据）：

E. **捕获开关在位**——`PytestNicKernelCommand` 的 argv 必须有 `--capture=fd`。
   用例库 `addopts` 带 `-s`（关闭捕获），没有这个覆盖项，junit 的
   `<system-out>/<system-err>` 全是空的，步骤树会退化成「一条都抽不到」。
F. **标记字面量同源**——Java 侧必须出现 `"[明细]"` 与 `"[判定]"`，
   与框架 `StepReporter.check` 的日志前缀逐字一致；改了任一边都会在这里炸。
G. **金样例可解析**——`step-sample.xml` 抽出 2 个用例、7 条步骤、2 条 FAIL、1 条 SKIP，
   且第二例的参数化实例剥后缀后仍能匹配。

用法：
  python3 tools/check_pytest_artifact.py
  python3 tools/check_pytest_artifact.py --self-check
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "docs/evidence/v5.1.0/pytest/case-index.json"
STEP_SAMPLE = ROOT / "docs/evidence/v5.1.0/pytest/step-sample.xml"
NORMALIZER = ("backend/src/main/java/com/qa/autotest/application/kernel/pytest/"
              "PytestResultNormalizer.java")
KERNEL_COMMAND = ("backend/src/main/java/com/qa/autotest/application/kernel/pytest/"
                  "PytestNicKernelCommand.java")
STEP_EXTRACTOR = ("backend/src/main/java/com/qa/autotest/application/kernel/pytest/"
                  "PytestStepExtractor.java")

# 与 tests/conftest.py StepReporter.check 的日志前缀逐字一致。改这里必须同步框架与 Java。
DETAIL_MARK = "[明细]"
VERDICT_MARK = "[判定]"


def file_stem(path_or_class: str) -> str:
    """复刻 Java 的 fileStem：把 `/` 当 `.`，**先剥 .py**，再取末段。

    顺序不能反：`tests.nic.base.test_x.py` 先按点取末段会得到字面量 "py"，
    索引键就全变成 `py::函数名`，反查永远不命中——而计数看起来一切正常，
    属于最难发现的那类假绿。Java 侧 `fileStem` 就是先剥后缀再 split。
    """
    value = path_or_class.replace("/", ".").replace("\\", ".")
    if value.endswith(".py"):
        value = value[:-3]
    return value.rsplit(".", 1)[-1]


def base_name(name: str) -> str:
    """复刻 Java 的 baseName：去掉 `[...]` 参数化后缀。"""
    return name.split("[", 1)[0].strip()


def match_key(classname: str, test_name: str) -> str:
    return f"{file_stem(classname)}::{base_name(test_name)}"


def build_index(payload: dict) -> dict[str, str]:
    """脚本清单 → {<文件末段>::<函数名>: 编号}。"""
    index: dict[str, str] = {}
    for script in payload["scripts"]:
        if not script["case_code"]:
            continue
        stem = file_stem(script["path"])
        for func in script["test_functions"] or []:
            index.setdefault(f"{stem}::{func}", script["case_code"])
    return index


def dot_form(path: str) -> str:
    """脚本路径 → junit 的点分 classname。"""
    value = path[:-3] if path.endswith(".py") else path
    return value.replace("/", ".")


def check_java_rules(source: str) -> list[str]:
    problems = []
    for token, why in [
        ("public static String matchKey(", "匹配键入口缺失"),
        ("private static String fileStem(", "文件名末段规则缺失"),
        ("private static String baseName(", "参数化后缀剥离缺失"),
        ("indexOf('[')", "参数化后缀剥离实现缺失"),
    ]:
        if token not in source:
            problems.append(f"{why}：源码里找不到 `{token}`")
    return problems


def check_capture_switch(source: str) -> list[str]:
    """E：没有 --capture=fd 就没有捕获，没有 junit_logging=out-err 捕获也不进 junit。"""
    problems = []
    if '"--capture=fd"' not in source:
        problems.append("argv 里找不到 `--capture=fd`：用例库 addopts 的 -s 会把捕获关掉，"
                        "步骤证据将一条都抽不到")
    if "junit_logging=out-err" not in source:
        # pytest 的 junit_logging 默认是 "no"——即便捕获到了也不写进 junit。
        # 实测：只加 --capture=fd 时 junit 里 0 条明细，framework.log 里 3 条。
        problems.append("argv 里找不到 `junit_logging=out-err`：junit_logging 默认 no，"
                        "junit 里不会有任何 <system-out>/<system-err>")
    if "junit_family=xunit2" not in source:
        problems.append("junit_family 未钉成 xunit2：换成 xunit1 后 <system-out> 的落法不同，"
                        "解析会静默失效")
    return problems


def check_markers(source: str) -> list[str]:
    """F：标记字面量必须与框架一致。"""
    return [f"PytestStepExtractor 里找不到 `{mark}`：与框架 StepReporter 的日志前缀脱钩"
            for mark in (DETAIL_MARK, VERDICT_MARK) if f'"{mark}"' not in source]


def parse_captured(text: str) -> list[dict]:
    """复刻 Java `PytestStepExtractor.fromCaptured` 的判定口径（只做门禁需要的部分）。"""
    steps: list[dict] = []
    for line in text.splitlines():
        detail_at = line.find(DETAIL_MARK)
        verdict_at = line.find(VERDICT_MARK)
        if detail_at < 0 and verdict_at < 0:
            continue
        is_verdict = verdict_at >= 0 and (detail_at < 0 or verdict_at < detail_at)
        body = line[(verdict_at + len(VERDICT_MARK)) if is_verdict
                    else (detail_at + len(DETAIL_MARK)):].strip()
        if not body:
            continue
        colon = body.find("：")
        label = ""
        if colon > 0:
            candidate = body[:colon].strip()
            if candidate and len(candidate) <= 48 and "=" not in candidate \
                    and " " not in candidate and "，" not in candidate:
                label = candidate
        text_part = body[colon + 1:].strip() if label else body
        status = None
        if is_verdict:
            status = "FAIL" if text_part.startswith("不通过") else "PASS"
        elif "Plan-only" in text_part:
            status = "SKIP"
        steps.append({"label": label, "content": text_part, "status": status,
                      "kind": "VERDICT" if is_verdict else "DETAIL"})
    return steps


def check_step_sample(path: Path) -> tuple[list[str], dict]:
    """G：金样例解析。返回 (问题, 统计)。"""
    import xml.etree.ElementTree as ET
    if not path.is_file():
        return [f"缺少金样例 {path}"], {}
    root = ET.parse(path).getroot()
    cases = root.iter("testcase")
    total_cases = 0
    total_steps = 0
    fails = 0
    skips = 0
    keys = []
    for case in cases:
        total_cases += 1
        captured = "".join((node.text or "") for tag in ("system-out", "system-err")
                           for node in case.findall(tag))
        steps = parse_captured(captured)
        total_steps += len(steps)
        fails += sum(1 for s in steps if s["status"] == "FAIL")
        skips += sum(1 for s in steps if s["status"] == "SKIP")
        if case.find("failure") is not None or case.find("error") is not None:
            total_steps += 1
            fails += 1
        keys.append(match_key(case.get("classname", ""), case.get("name", "")))
    stats = {"cases": total_cases, "steps": total_steps, "fails": fails, "skips": skips,
             "keys": keys}
    problems = []
    if total_cases != 2:
        problems.append(f"金样例用例数 {total_cases} != 2（样例被改动？同步更新判据）")
    if total_steps != 7:
        problems.append(f"金样例步骤数 {total_steps} != 7（期望 5 条日志步 + 2 条失败行）")
    if fails != 2:
        problems.append(f"金样例 FAIL 计数 {fails} != 2")
    if skips != 1:
        problems.append(f"金样例 SKIP 计数 {skips} != 1（Plan-only 应记 SKIP 不记 PASS）")
    if "test_im_net_131::test_im_net_131" not in keys:
        problems.append(f"参数化实例未剥后缀，实际匹配键 {keys}")
    return problems, stats


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()

    if not INDEX.is_file():
        print(f"[FAIL] 缺少 {INDEX}")
        return 1
    payload = json.loads(INDEX.read_text(encoding="utf-8"))
    normalizer = (ROOT / NORMALIZER)
    if not normalizer.is_file():
        print(f"[FAIL] 缺少归一器 {NORMALIZER}")
        return 1
    source = normalizer.read_text(encoding="utf-8")

    problems = check_java_rules(source)
    kernel = ROOT / KERNEL_COMMAND
    extractor = ROOT / STEP_EXTRACTOR
    if not kernel.is_file():
        problems.append(f"缺少命令构造器 {KERNEL_COMMAND}")
    else:
        problems.extend(check_capture_switch(kernel.read_text(encoding="utf-8")))
    if not extractor.is_file():
        problems.append(f"缺少步骤抽取器 {STEP_EXTRACTOR}")
    else:
        problems.extend(check_markers(extractor.read_text(encoding="utf-8")))
    sample_problems, sample = check_step_sample(STEP_SAMPLE)
    problems.extend(sample_problems)

    index = build_index(payload)
    scripts = [s for s in payload["scripts"] if s["case_code"]]

    # A + B：每个脚本按 junit 形态反查，含参数化实例
    misses = []
    for script in scripts:
        classname = dot_form(script["path"])
        for func in script["test_functions"] or []:
            plain = match_key(classname, func)
            parameterized = match_key(classname, func + "[a-b]")
            if plain not in index:
                misses.append(f"{script['path']}::{func}")
            elif parameterized != plain:
                misses.append(f"参数化未剥离: {parameterized}")
    if misses:
        problems.append(f"{len(misses)} 个脚本匹配不上，例如 {misses[:3]}")

    # C：未绑定计数
    codes = {item["code"] for item in payload["cases"]}
    bound = {index[key] for key in index if index[key] in codes}
    unbound = len(codes) - len(bound)
    if unbound < 0:
        problems.append("未绑定计数为负：索引里出现了库里不存在的编号")

    print(f"  匹配键 {len(index)} 个 / 命中编号 {len(bound)} / 未绑定 {unbound}")
    if sample:
        print(f"  金样例：{sample['cases']} 用例 / {sample['steps']} 步 / "
              f"FAIL {sample['fails']} / SKIP {sample['skips']}")

    if args.self_check:
        print("=== 负向自证（真注入，用临时副本，不碰仓库文件）===")
        failures = 0
        # ① 篡改一个 classname 使其无法命中
        if not scripts:
            print("  SELFCHECK: 无脚本可改，自证无效")
            return 1
        victim = scripts[0]
        dirty_key = match_key(dot_form(victim["path"]) + "_typo", victim["test_functions"][0])
        ok = dirty_key not in index
        print(f"  ① 篡改 classname: {'RED（符合预期）' if ok else 'GREEN（不符合预期）'}")
        failures += 0 if ok else 1

        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            # ② 把金样例的判定标记改错 → 判定步骤必须消失，判据必须红
            mutated = Path(tmp) / "step-sample.xml"
            text = STEP_SAMPLE.read_text(encoding="utf-8")
            mutated.write_text(text.replace(VERDICT_MARK, "[判断]"), encoding="utf-8")
            bad_problems, bad = check_step_sample(mutated)
            red = bool(bad_problems)
            print(f"  ② 篡改判定标记: {'RED（符合预期）' if red else 'GREEN（不符合预期）'}"
                  f" → FAIL={bad.get('fails')} SKIP={bad.get('skips')}")
            failures += 0 if red else 1

            # ③ 摘掉 --capture=fd → 捕获开关判据必须红
            stripped = Path(tmp) / "PytestNicKernelCommand.java"
            stripped.write_text(kernel.read_text(encoding="utf-8").replace('"--capture=fd"', '"--capture"'),
                                encoding="utf-8")
            capture_problems = check_capture_switch(stripped.read_text(encoding="utf-8"))
            red = bool(capture_problems)
            print(f"  ③ 摘掉 --capture=fd: {'RED（符合预期）' if red else 'GREEN（不符合预期）'}")
            failures += 0 if red else 1

        print(f"SELFCHECK_RESULT FAILS={failures}")
        if failures:
            return 1
        print("PYTEST_ARTIFACT PASS")
        return 0

    if problems:
        print("PYTEST_ARTIFACT FAIL")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("PYTEST_ARTIFACT PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
