#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""进度通道机检（v5.2.2 / FR-v5.2-06 / W52-07 / W53-01）。

为什么需要这条机检
------------------
进度通道的价值全在「事件契约稳定」这一件事上：页面按 `Kind` 折叠、ETA 按
`CASE_START/CASE_END` 算、报告按 `CASE_END.detail` 取结论。任何一处不满足，
表现都不是报错，而是「进度条卡在 60%」「ETA 显示 3 分钟其实跑了 40 分钟」——
这类退化没有任何单测会红，只有契约本身能被检查。

守什么
------
1. **事件种类齐**：`MonitorEvent.Kind` 恰 7 种（PHASE / CASE_START / CASE_END /
   COMMAND / WARNING / ERROR / DEGRADED），少一种就有下游静默失效；
2. **四输入齐**：`stdout` / `stderr` / `progress` / `evidenceChanged` / `executionEvent`
   五个入口都在（前四个是 PRD 的四输入，第五个是平台事件）；
3. **R4 不向上抛**：解析逻辑必须包在 try/catch 里，监视器失败不能拖垮执行；
4. **R5 有上限且丢弃有计数**：五道上限常量 + 四个 `dropped*` 计数器；
5. **事件名不带显示装饰**：`executionEvent` 不得给 name 加前缀（会污染过滤与统计）。

用法
----
    python3 tools/check_progress_channel.py
    python3 tools/check_progress_channel.py --self-test   # 真注入：删掉 DEGRADED，必须判红

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码，疑似假绿）。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MONITOR = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
           / "application" / "execution" / "monitor" / "ExecutionMonitor.java")
EVENT = MONITOR.parent / "MonitorEvent.java"

EXPECTED_KINDS = ["PHASE", "CASE_START", "CASE_END", "COMMAND", "WARNING", "ERROR", "DEGRADED"]
INPUTS = ["stdout", "stderr", "progress", "evidenceChanged", "executionEvent"]
LIMITS = ["maxLogLines", "maxEvents", "maxCommands", "maxLineChars", "maxLinesPerSecond"]
COUNTERS = ["droppedLines", "droppedEvents", "droppedCommands", "droppedByRate"]


def ok(message: str) -> None:
    print(f"  OK     {message}")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  FAIL   {message}")


def enum_values(text: str) -> list[str]:
    match = re.search(r"enum Kind\s*\{(.*?)\n    \}", text, re.S)
    if not match:
        return []
    body = re.sub(r"/\*.*?\*/", "", match.group(1), flags=re.S)
    body = re.sub(r"//[^\n]*", "", body)
    return re.findall(r"^\s*([A-Z][A-Z_]*)\s*[,;]?\s*$", body, re.M)


def check(monitor_text: str, event_text: str, problems: list[str]) -> None:
    kinds = enum_values(event_text)
    if not kinds:
        fail(problems, "MonitorEvent 里找不到 Kind 枚举（结构变了，脚本要同步）")
    elif kinds != EXPECTED_KINDS:
        fail(problems, f"Kind 枚举与契约不一致：期望 {EXPECTED_KINDS}，实际 {kinds}")
    else:
        ok("事件种类齐（7 种，顺序与契约一致）")

    missing = [name for name in INPUTS if not re.search(rf"public\s+synchronized\s+\w+\s+{name}\(",
                                                        monitor_text)]
    if missing:
        fail(problems, f"缺少输入入口：{', '.join(missing)}（四输入不全，进度会缺一段）")
    else:
        ok("五个输入入口齐（stdout / stderr / progress / evidenceChanged / executionEvent）")

    feed_match = re.search(r"private void feed\((.*?)\n    \}", monitor_text, re.S)
    if not feed_match:
        fail(problems, "找不到 feed(...) 解析入口")
    elif "catch" not in feed_match.group(1):
        fail(problems, "feed() 里没有 try/catch（R4：监视器异常会拖垮执行）")
    else:
        ok("feed() 包了 try/catch（R4：监视失败不影响执行）")

    missing = [name for name in LIMITS if name not in monitor_text]
    if missing:
        fail(problems, f"缺少资源上限常量：{', '.join(missing)}（R5）")
    else:
        ok("五道资源上限常量齐（行数/事件/命令/单行字符/每秒速率）")

    missing = [name for name in COUNTERS if name not in monitor_text]
    if missing:
        fail(problems, f"缺少丢弃计数器：{', '.join(missing)}"
                       "（超限丢弃必须能显示「丢了 N 行」）")
    else:
        ok("四类丢弃都有计数")

    if re.search(r'"event:\s*"\s*\+|"event:"\s*\+', monitor_text):
        fail(problems, "executionEvent 给 name 加了 event: 前缀（会污染过滤与统计）")
    else:
        ok("事件名不带显示装饰")


def run() -> int:
    print(f"[check_progress_channel] 监视器={MONITOR.relative_to(ROOT)}")
    for file in (MONITOR, EVENT):
        if not file.is_file():
            print(f"check_progress_channel: FAIL（找不到 {file}）")
            return 2
    problems: list[str] = []
    check(MONITOR.read_text(encoding="utf-8"), EVENT.read_text(encoding="utf-8"), problems)
    if problems:
        print(f"check_progress_channel: FAIL（{len(problems)} 项）")
        return 1
    print("check_progress_channel: PASS")
    return 0


def self_test() -> int:
    """真注入：抹掉 DEGRADED 枚举值，种类断言必须判红。"""
    print("--- self-test：删掉 DEGRADED 事件种类后应判失败 ---")
    global EVENT
    original = EVENT
    backup = original.read_text(encoding="utf-8")
    broken = backup.replace("        DEGRADED\n", "", 1)
    if broken == backup:
        print("  FAIL   自检注入点没找到（枚举结构变了），无法自证")
        print("check_progress_channel: SELF-TEST FAIL")
        return 2
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False,
                                         encoding="utf-8") as handle:
            handle.write(broken)
            temp_path = Path(handle.name)
        EVENT = temp_path
        problems: list[str] = []
        check(MONITOR.read_text(encoding="utf-8"), EVENT.read_text(encoding="utf-8"), problems)
        if not any("Kind 枚举" in item for item in problems):
            print(f"  FAIL   自检未按预期判红：{problems}")
            print("check_progress_channel: SELF-TEST FAIL")
            return 1
        print("  OK     删掉一种事件后判红")
    finally:
        EVENT = original
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    print("check_progress_channel: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="进度通道事件契约与资源上限机检")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()
    return self_test() if args.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
