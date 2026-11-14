#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W58-11 RF 判据载体 spike：扫真实 output.xml，判断有没有稳定的「判据载体」。

对每份样本回答三件事：
  1. `<test>` 里有没有 `<doc>`（作者声明的判据/预期）；
  2. 失败用例的 `<msg level="FAIL">` 里有没有可解析的「期望 vs 实际」结构；
  3. 关键字调用里能不能拿到断言原文（`Should Be Equal` 等的参数）。

结论口径（DEV-PLAN 出口判据）：≥3 份样本能稳定提取同一形状 → 判「有载体」，否则「无载体」。
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SAMPLES = [line.strip() for line in Path(sys.argv[1]).read_text().splitlines() if line.strip()]
OUT = Path(sys.argv[2])

# RF 断言关键字：参数里天然带着「期望 / 实际」
ASSERT_KW = (
    "should be equal",
    "should not be equal",
    "should contain",
    "should not contain",
    "should be true",
    "should match",
    "should be empty",
)

EXPECTED_HINT = re.compile(r"(expected|期望|应为|应为空|不等于|等于)", re.IGNORECASE)


def texts(node):
    return " ".join(t.strip() for t in node.itertext() if t.strip())


def analyze(path):
    row = {
        "file": str(path),
        "tests": 0,
        "failed": 0,
        "with_doc": 0,
        "assert_kw": 0,
        "fail_msg_structured": 0,
        "sample_assert": "",
        "sample_doc": "",
        "error": "",
    }
    try:
        root = ET.parse(path).getroot()
    except Exception as exc:  # noqa: BLE001 - 坏文件如实记录，不中断整批
        row["error"] = type(exc).__name__
        return row

    for test in root.iter("test"):
        row["tests"] += 1
        doc = test.find("doc")
        if doc is not None and (doc.text or "").strip():
            row["with_doc"] += 1
            if not row["sample_doc"]:
                row["sample_doc"] = (doc.text or "").strip().replace("\n", " ")[:120]

        status = test.find("status")
        failed = status is not None and status.get("status") == "FAIL"
        if failed:
            row["failed"] += 1

        for kw in test.iter("kw"):
            name = (kw.get("name") or "").strip().lower()
            if name in ASSERT_KW:
                row["assert_kw"] += 1
                if not row["sample_assert"]:
                    args = [a.text.strip() for a in kw.findall("arg") if a.text]
                    row["sample_assert"] = ("%s | %s" % (kw.get("name"), " / ".join(args)))[:160]

        if failed:
            for msg in test.iter("msg"):
                if msg.get("level") != "FAIL":
                    continue
                text = (msg.text or "").strip()
                # 结构化：能分出「期望/实际」两截，或来自断言关键字
                if EXPECTED_HINT.search(text) or "!=" in text:
                    row["fail_msg_structured"] += 1
                    break
    return row


rows = [analyze(Path(p)) for p in SAMPLES]
ok = [r for r in rows if not r["error"]]
extractable = [r for r in ok if r["assert_kw"] > 0 or r["with_doc"] > 0]
structured_fail = [r for r in ok if r["fail_msg_structured"] > 0]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.with_suffix(".json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n",
                                    encoding="utf-8")

print("samples=%d parsed=%d" % (len(rows), len(ok)))
print("with_assert_kw_or_doc=%d" % len(extractable))
print("failed_msgs_structured=%d" % len(structured_fail))
for r in rows:
    print("%-72s tests=%-4d fail=%-4d doc=%-4d assert=%-4d struct=%d %s"
          % (r["file"][-72:], r["tests"], r["failed"], r["with_doc"], r["assert_kw"],
             r["fail_msg_structured"], r["error"]))
