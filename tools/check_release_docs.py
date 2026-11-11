#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
版本开发流程机检脚本（对应 docs/process/版本开发流程.md §8）

用法：
    python tools/check_release_docs.py                # 检查全部版本
    python tools/check_release_docs.py v0.4-execution # 只检查一个

退出码：0 = 全通过；1 = 有 FAIL 项

检查项（§8）：
    1. 每版目录六份产物齐全
    2. PRD 的每条 FR 在 TEST-PLAN 有对应覆盖
    3. TEST-PLAN 的每条 TC 在 TEST-REPORT 有结果
    4. TEST-REPORT 无未写明原因的 BLOCKED
    5. 评估报告含「不做」与「前置条件」
    6. 评估报告有修订记录段

说明：判据 2 采用「TEST-PLAN 中出现该 FR 编号」的宽松匹配（能抓孤儿 FR，
不校验映射表结构）；判据 3 同理。宁可宽松也不误报，结构校验交给人工评审。
"""

import io
import os
import re
import sys

REQUIRED = {
    "00-RESEARCH.md": "① 需求调研",
    "01-ASSESSMENT.md": "② 评估报告",
    "PRD.md": "③ 需求文档",
    "DEV-PLAN.md": "④ 开发计划",
    "TEST-PLAN.md": "⑤ 测试计划",
    "05-TEST-REPORT.md": "⑥ 测试报告",
}

RELEASE_DIR = os.path.join("docs", "release")


def read(path):
    if not os.path.exists(path):
        return None
    return io.open(path, encoding="utf-8", errors="replace").read()


def detect_prefix(text, base):
    """从文档里推断带版本号的编号前缀，如 "FR" -> "FR-v0.4"。

    版本号不硬编码，避免目录改名后脚本失效。
    """
    hits = re.findall(re.escape(base) + r"-(v[\d.]+)-\d+", text)
    if not hits:
        return None
    most = max(set(hits), key=hits.count)
    return "{0}-{1}".format(base, most)


def id_sort_key(s):
    m = re.search(r"-(\d+)$", s)
    return (int(m.group(1)) if m else 0, s)


def extract_ids(text, prefix):
    """提取编号，支持区间写法。

    能识别：
        FR-v0.4-03              单条
        FR-v0.4-01 ~ 34         带空格的范围（第二个数字省略前缀）
        FR-v0.4-01~08           紧凑范围
        FR-v0.4-01-08           连字符范围

    返回去重后的编号集合。区间写法在测试计划里很常用（「对应 PRD FR-v0.4-01 ~ 34」），
    按单条匹配会误报成孤儿，因此必须先展开。
    """
    found = set()

    # 区间：prefix-NN [~/-] M[、, ｜] ...
    for m in re.finditer(
        re.escape(prefix) + r"-(\d+)\s*[~～]\s*(\d+)|"
        + re.escape(prefix) + r"-(\d+)-(\d+)",
        text,
    ):
        a = m.group(1) or m.group(3)
        b = m.group(2) or m.group(4)
        try:
            a, b = int(a), int(b)
        except (TypeError, ValueError):
            continue
        if 0 < a <= b and b - a <= 200:  # 防误把日期当区间
            width = max(len(str(a)), len(str(b)))
            for i in range(a, b + 1):
                found.add("{0}-{1:0{2}d}".format(prefix, i, width))

    # 单条
    for m in re.finditer(re.escape(prefix) + r"-(\d+)", text):
        n = m.group(1)
        found.add("{0}-{1}".format(prefix, n))

    return found


def check_version(vdir):
    """返回 (version_name, fails[list], warns[list], stats[dict])"""
    name = os.path.basename(vdir.rstrip("/\\"))
    fails, warns, stats = [], [], {}

    # ---- 0. 是否已启动流程 ----
    # 判据：出现了 00-RESEARCH 或 01-ASSESSMENT 才算"开始走七道"。
    # 二者全无的版本视为「未启动」，只报 INFO，不判 FAIL ——
    # 否则脚本永远红着，红久了对告警免疫，流程反而失效。
    started = (
        os.path.exists(os.path.join(vdir, "00-RESEARCH.md"))
        or os.path.exists(os.path.join(vdir, "01-ASSESSMENT.md"))
    )

    # ---- 1. 产物齐全 ----
    missing = [n for n in REQUIRED if not os.path.exists(os.path.join(vdir, n))]
    if missing and started:
        fails.append("缺产物: " + ", ".join(f"{n}（{REQUIRED[n]}）" for n in missing))
    elif missing:
        stats["未启动缺项"] = len(missing)

    prd = read(os.path.join(vdir, "PRD.md")) or ""
    dev = read(os.path.join(vdir, "DEV-PLAN.md")) or ""
    tplan = read(os.path.join(vdir, "TEST-PLAN.md")) or ""
    treport = read(os.path.join(vdir, "05-TEST-REPORT.md")) or ""
    assess = read(os.path.join(vdir, "01-ASSESSMENT.md")) or ""
    research = read(os.path.join(vdir, "00-RESEARCH.md")) or ""

    # ---- 2. FR 覆盖 ----
    fr_prefix = detect_prefix(prd, "FR")
    frs = sorted(extract_ids(prd, fr_prefix), key=id_sort_key) if fr_prefix else []
    stats["FR"] = len(frs)
    if frs and tplan:
        covered = extract_ids(tplan, detect_prefix(tplan, "FR") or fr_prefix)
        orphan = [f for f in frs if f not in covered]
        if orphan:
            fails.append(
                "孤儿 FR（{0}/{1} 条未在 TEST-PLAN 覆盖）: {2}{3}".format(
                    len(orphan), len(frs), ", ".join(orphan[:6]),
                    " ..." if len(orphan) > 6 else ""
                )
            )
    elif frs and not tplan:
        fails.append("有 {0} 条 FR 但无 TEST-PLAN，无法核对覆盖".format(len(frs)))

    # ---- 3. TC 有结果 ----
    tc_prefix = detect_prefix(tplan, "TC")
    tcs = sorted(extract_ids(tplan, tc_prefix), key=id_sort_key) if tc_prefix else []
    stats["TC"] = len(tcs)
    if tcs and not treport:
        if started:
            warns.append("有 {0} 条 TC 但无测试报告，尚未执行".format(len(tcs)))
    elif tcs and treport:
        done = extract_ids(treport, detect_prefix(treport, "TC") or tc_prefix)
        nores = [t for t in tcs if t not in done]
        if nores:
            fails.append(
                "未执行 TC（{0}/{1} 条在 TEST-REPORT 无结果）: {2}{3}".format(
                    len(nores), len(tcs), ", ".join(nores[:6]),
                    " ..." if len(nores) > 6 else ""
                )
            )

    # ---- 4. BLOCKED 必须有原因 ----
    if treport:
        blocked_lines = [
            ln for ln in treport.splitlines()
            if "BLOCKED" in ln.upper() and ln.strip().startswith("|")
        ]
        # 表格行里 BLOCKED 之后应还有内容（原因列）
        bad = [ln for ln in blocked_lines if len(re.sub(r"BLOCKED", "", ln, flags=re.I).strip()) < 12]
        if bad:
            fails.append("BLOCKED 未写明原因: {0} 行".format(len(bad)))

    # ---- 5. 评估报告必备章节 ----
    if assess:
        if not re.search(r"不做|不做的事|不做什么|范围外", assess):
            fails.append("评估报告缺「不做什么」的明确表述")
        if not re.search(r"前置条件|前提条件", assess):
            fails.append("评估报告缺「前置条件」清单")
        if not re.search(r"\[已核实\]|已核实|证据", assess):
            warns.append("评估报告未见证据分级或证据附录")
    if research:
        if not re.search(r"\[已核实\]|\[推断\]|\[假设\]", research):
            warns.append("调研文档未见 [已核实]/[推断]/[假设] 三档标注")

    # ---- 6. 评估报告修订记录 ----
    if assess and not re.search(r"修订记录|变更记录|版本记录", assess):
        fails.append("评估报告缺修订记录段（⑦ 回写未做）")

    # ---- 附加统计 ----
    stats["WBS"] = len(set(re.findall(
        r"\b(?:WS|IAM|AST|CAS|EDT|EXE|RPT|PLN|HRD|DOC)-\d{2}\b", dev)))

    return name, fails, warns, stats


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None

    if not os.path.isdir(RELEASE_DIR):
        print("找不到 {0}，请在仓库根目录运行".format(RELEASE_DIR))
        return 1

    # 约定：`_` 前缀目录是**草案/暂存区**，不是版本目录。
    # 例：`docs/release/_draft/v4.11.0/` 放「已写 ① 调研②评估③ PRD、尚未进 ④」的版本，
    # 若按版本扫描会因「已启动但六份不齐」判 FAIL，等于逼着团队提前编造测试报告。
    # 这里显式跳过并打印，保持「六份齐全」这条对**真实版本**的约束不放宽。
    skipped = sorted(
        d for d in os.listdir(RELEASE_DIR)
        if d.startswith("_") and os.path.isdir(os.path.join(RELEASE_DIR, d))
    )
    vdirs = sorted(
        os.path.join(RELEASE_DIR, d) for d in os.listdir(RELEASE_DIR)
        if os.path.isdir(os.path.join(RELEASE_DIR, d)) and not d.startswith("_")
    )
    if only:
        vdirs = [d for d in vdirs if os.path.basename(d) == only]
        if not vdirs:
            print("没有名为 {0} 的版本目录".format(only))
            return 1

    total_fail = total_warn = total_idle = 0
    print("=" * 78)
    print("版本开发流程机检（docs/process/版本开发流程.md §8）")
    print("=" * 78)
    if skipped:
        print("INFO  跳过草案区（`_` 前缀，非版本目录）：" + ", ".join(skipped))

    for vdir in vdirs:
        name, fails, warns, stats = check_version(vdir)
        idle = stats.get("未启动缺项") is not None and not fails and not warns
        total_fail += len(fails)
        total_warn += len(warns)
        total_idle += 1 if idle else 0
        if fails:
            icon = "FAIL"
        elif warns:
            icon = "WARN"
        elif idle:
            icon = "IDLE"
        else:
            icon = "PASS"
        print("\n[{0}] {1}   FR={2} TC={3} WBS={4}".format(
            icon, name, stats.get("FR", 0), stats.get("TC", 0), stats.get("WBS", 0)))
        if idle:
            print("   INFO  未启动流程（缺 {0} 项，含 00-RESEARCH / 01-ASSESSMENT），"
                  "按 §9 不计为违规".format(stats["未启动缺项"]))
        for f in fails:
            print("   FAIL  " + f)
        for w in warns:
            print("   WARN  " + w)

    print("\n" + "-" * 78)
    print("合计：{0} 个版本  |  FAIL {1} 项  |  WARN {2} 项  |  未启动 {3} 个".format(
        len(vdirs), total_fail, total_warn, total_idle))
    if total_fail:
        print("\n按流程 §9：存在 FAIL 的版本不得判定完成，不得作为下一版基线。")
    if total_idle:
        print("未启动的版本：正式开工前须先走 ① 调研 → ② 评估，再写 PRD。")
    print("=" * 78)
    return 1 if total_fail else 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
