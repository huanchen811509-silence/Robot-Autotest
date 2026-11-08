#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CLOS-02 · Robot Framework output.xml metadata 六必填机检（纯标准库）。

用法：
    python3 tools/check_output_metadata.py               # 扫描当前版本样本 + 强制判据自校验
    python3 tools/check_output_metadata.py --self-check  # 只跑判据自校验（负向实证）

扫描范围（硬边界，不放宽）：
    docs/evidence/v3.0.1/**/output.xml  +  fixtures/**/output.xml
历史冻结证据目录（v2.0 及更早、v2.1、v1.0、var 运行产物）显式排除并打印理由：
它们字节冻结、没有 metadata，既不计为合规，也不因缺项判红。

判据（永久有效）：
    六必填 CASE_ID / RUN_ID / QA_PROJECT / DUT / LINK / SW_BUILD 必须齐全；
    键名全大写、RUN_ID 键名不可改（不接受大小写变体或别名）；
    值 <= 40 字符；值内不得出现 password/token/secret/key= 等凭据明文特征；
    推荐 4 项 BASELINE / OPERATOR / STARTED_AT / CASE_KIND 缺失只提示、不判红。
凭据规则只匹配 item 的「值」，不匹配键名，故 CRED_MASTER_KEY 这类键名本身不会被误判。

退出码：0 = 样本全部合规且判据自校验通过；1 = 样本不合规或自校验失灵；2 = 用法错误。
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

REQUIRED = ("CASE_ID", "RUN_ID", "QA_PROJECT", "DUT", "LINK", "SW_BUILD")
RECOMMENDED = ("BASELINE", "OPERATOR", "STARTED_AT", "CASE_KIND")
VALUE_MAX = 40

KEY_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
# 只扫值：赋值形态的明文凭据 + Bearer 令牌。键名不参与匹配。
CRED_RE = re.compile(
    r"(?i)(?:\b(?:password|passwd|pwd|token|secret|api[_-]?key|key)\s*[:=]\s*\S+"
    r"|Bearer\s+\S{6,})"
)

INCLUDE_ROOTS = ("docs/evidence/v3.0.1", "fixtures")

# 显式排除清单：目录 -> 理由。统计数由脚本实测，不写死。
EXCLUDED_DIRS = [
    ("docs/evidence/v2.0/a3-rf-kernel", "v2.0 RF 内核历史冻结证据，无 metadata、字节冻结，不得改"),
    ("docs/evidence/v2.0/a8-report-chain", "v2.0 报告链历史冻结证据（含 deleted-run 原始产物），无 metadata、字节冻结"),
    ("docs/evidence/v2.1", "v2.1 历史示例证据（SMPL-01），无 metadata、字节冻结"),
    ("docs/evidence/v1.0", "v1.0 历史证据（HRD02B / RPT05B / RPT0607B 原始产物），无 metadata、字节冻结"),
    ("var/artifacts", "运行时产物，非仓库版本证据，不纳入本版判据"),
]
EXCLUDE_TAIL = "以上目录一律：不计为合规、不因缺项判红、一个字节都不改。"


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_metadata(path: pathlib.Path) -> dict:
    """解析 <suite><metadata><item name="KEY">VALUE</item>...（顶层 suite 的直接子节点）。"""
    root = ET.parse(str(path)).getroot()
    top_suite = next((e for e in root.iter() if _local(e.tag) == "suite"), None)
    container = top_suite if top_suite is not None else root
    meta = next((c for c in container if _local(c.tag) == "metadata"), None)
    items = {}
    if meta is not None:
        for it in meta:
            if _local(it.tag) == "item":
                items[it.get("name", "")] = it.text or ""
    return items


def validate(items: dict):
    """返回 (problems, infos)：problems 非空即判红。凭据规则只作用于值。"""
    problems, infos = [], []
    missing = [k for k in REQUIRED if k not in items]
    if missing:
        problems.append("缺必填项: " + ", ".join(missing))
    if "RUN_ID" not in items:
        aliases = [k for k in items if k != "RUN_ID" and k.upper() == "RUN_ID"]
        if aliases:
            problems.append("RUN_ID 键名不可改（发现别名: " + ", ".join(aliases) + "）")
    for k, v in items.items():
        if not KEY_RE.match(k):
            problems.append("键名未全大写: " + repr(k))
        if len(v) > VALUE_MAX:
            problems.append("值超 %d 字符: %s (%d)" % (VALUE_MAX, k, len(v)))
        if CRED_RE.search(v):
            problems.append("值含凭据明文特征: " + k)
    rec_missing = [k for k in RECOMMENDED if k not in items]
    if rec_missing:
        infos.append("推荐项未写（不判红）: " + ", ".join(rec_missing))
    return problems, infos


def _under(p: pathlib.Path, base: pathlib.Path) -> bool:
    return p == base or base in p.parents


def scan_samples(root: pathlib.Path):
    found = []
    for rel in INCLUDE_ROOTS:
        base = root / rel
        if base.is_dir():
            found.extend(p for p in base.glob("**/output.xml") if p.is_file())
    return sorted({p.relative_to(root).as_posix() for p in found})


def excluded_report(root: pathlib.Path):
    """返回 (按显式清单分组的计数, 未归类列表, 全仓 output.xml 总数)。"""
    all_xml = [p for p in root.glob("**/output.xml") if p.is_file()]
    in_scope = {root / rel for rel in INCLUDE_ROOTS}
    counts, other = {}, []
    for p in all_xml:
        if any(_under(p, b) for b in in_scope):
            continue
        rel = p.relative_to(root).as_posix()
        hit = next((d for d, _ in EXCLUDED_DIRS if rel == d or rel.startswith(d + "/")), None)
        if hit:
            counts[hit] = counts.get(hit, 0) + 1
        else:
            other.append(rel)
    return counts, sorted(other), len(all_xml)


GOOD_ITEMS = {
    "CASE_ID": "TC-v3.0.1-001",
    "RUN_ID": "RUN-20261108-0001",
    "QA_PROJECT": "robot-it",
    "DUT": "DUT-WSL-01",
    "LINK": "docs/evidence/v3.0.1/v22/CLOS-02",
    "SW_BUILD": "v3.0.1+20261108",
}


def make_xml(items: dict) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<robot generator="Robot 7.0" generated="20261108 00:00:00.000" rpa="false" schemaversion="5">',
        '<suite id="s1" name="CLOS-02 SelfCheck" source="/tmp/clos02-selfcheck.robot">',
        '<metadata>',
    ]
    for k, v in items.items():
        lines.append('    <item name="%s">%s</item>' % (escape(k), escape(v)))
    lines += [
        '</metadata>',
        '<test id="t1" name="noop"><kw name="No Operation"/><status status="PASS"/></test>',
        '<status status="PASS"/>',
        '</suite>',
        '</robot>',
    ]
    return "\n".join(lines)


def self_check() -> int:
    """负向实证：临时目录生成 XML，断言判据该拒的拒、该收的收。"""
    long_value = dict(GOOD_ITEMS, SW_BUILD="X" * (VALUE_MAX + 1))
    lower_key = {("run_id" if k == "RUN_ID" else k): v for k, v in GOOD_ITEMS.items()}
    cred_value = dict(GOOD_ITEMS, LINK="https://host/report?token=abcdefgh123")
    key_name = dict(GOOD_ITEMS)
    key_name["CRED_MASTER_KEY"] = "v3.0.1-placeholder"  # 键名含 KEY、值无明文 -> 不得误判
    cases = [
        ("齐全样本（6/6）", GOOD_ITEMS, "accept"),
        ("缺 1 项样本（缺 SW_BUILD）", {k: v for k, v in GOOD_ITEMS.items() if k != "SW_BUILD"}, "reject"),
        ("值超 40 字符样本", long_value, "reject"),
        ("RUN_ID 改小写别名样本", lower_key, "reject"),
        ("值含 token= 明文样本", cred_value, "reject"),
        ("键名含 KEY 但值无凭据样本", key_name, "accept"),
    ]
    print("[判据自校验] 临时目录生成 6 组 XML，逐组走 parse_metadata + validate：")
    ok = True
    with tempfile.TemporaryDirectory(prefix="clos02-meta-") as td:
        tmp = pathlib.Path(td)
        for i, (label, items, expect) in enumerate(cases, 1):
            p = tmp / ("sample-%d.xml" % i)
            p.write_text(make_xml(items), encoding="utf-8")
            try:
                problems, _ = validate(parse_metadata(p))
            except ET.ParseError as e:
                problems = ["XML 解析失败: %s" % e]
            rejected = bool(problems)
            hit = rejected if expect == "reject" else not rejected
            tag = "PASS" if hit else "FAIL"
            verdict = ("判据结果 FAIL（预期 FAIL，判据已拒绝）" if expect == "reject"
                       else "判据结果 PASS（预期 PASS，判据已接受）")
            detail = "; ".join(problems[:2]) if problems else "0 问题"
            print("  [%s] %s -> %s | %s" % (tag, label, verdict, detail))
            if not hit:
                ok = False
    if ok:
        print("PASS  判据自校验：6 组正/负样本全部符合预期（缺项被拒、齐全通过、键名不误判）")
    else:
        print("FAIL  判据自校验：存在样本未按预期判定，判据失灵")
    return 0 if ok else 1


def main(argv) -> int:
    self_only = any(a in ("--self-check", "--selfcheck") for a in argv)
    print("=" * 78)
    print("CLOS-02 · output.xml metadata 六必填机检（FR-v2.2-25）")
    print("=" * 78)
    if self_only:
        return self_check()

    root = pathlib.Path.cwd()
    if not (root / "scripts" / "gates.sh").is_file():
        print("FAIL  请在仓库根目录运行（找不到 scripts/gates.sh）")
        return 2

    counts, other, total_all = excluded_report(root)
    print("[扫描范围] " + " + ".join(r + "/**/output.xml" for r in INCLUDE_ROOTS))
    print("[排除清单] 以下历史冻结目录显式排除（既不计合规，也不判红）：")
    for d, reason in EXCLUDED_DIRS:
        if counts.get(d):
            print("  - %s : %d 份 | %s" % (d, counts[d], reason))
    if other:
        print("  - 其它未归类历史/运行产物 : %d 份 | 同上原则处理" % len(other))
        for r in other[:8]:
            print("      " + r)
    print("  %s 全仓 output.xml 实测 %d 份，其中排除 %d 份。" %
          (EXCLUDE_TAIL, total_all, sum(counts.values()) + len(other)))

    samples = scan_samples(root)
    print("[样本] 当前版本样本数 = %d" % len(samples))
    sample_fail = 0
    for s in samples:
        try:
            problems, infos = validate(parse_metadata(root / s))
        except ET.ParseError as e:
            problems, infos = ["XML 解析失败: %s" % e], []
        if problems:
            sample_fail += 1
            print("  FAIL %s : %s" % (s, "; ".join(problems)))
        else:
            note = ("；" + "；".join(infos)) if infos else ""
            print("  PASS %s : 六必填齐全、键名大写、值合规、无凭据%s" % (s, note))

    if not samples:
        print("INFO  当前版本样本数 = 0：不得静默放行，强制运行判据自校验（负向实证）")
    print()
    sc = self_check()
    print()
    if sample_fail:
        print("结论 FAIL：%d 份样本中 %d 份不合规" % (len(samples), sample_fail))
        return 1
    if sc != 0:
        print("结论 FAIL：判据自校验失灵（负向实证未通过），门禁不得放行")
        return 1
    if not samples:
        print("结论 PASS：当前版本样本数 = 0（这不是合规证明）；判据自校验负向实证通过，"
              "据此放行，历史冻结证据一个字节未改、也未假装合规")
    else:
        print("结论 PASS：%d 份当前版本样本全部合规，判据自校验通过" % len(samples))
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main(sys.argv[1:]))
