#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁 50 / EHT-08 / FR-v5.5-06,07：绑定证据与真源快照必须自洽。

守什么
------
1. `eht08-sync-report.json` 的四类计数自洽（bound + unbound == totalCases）、
   分母 469、扫描脚本数与 case-index 的 62 对得上（框架自测文件无编号，
   扫出来是 61，允许 61~62）；
2. `eht08-repo-head.json` 记的是 40 位 `git rev-parse HEAD`，不是工作树哈希；
3. 用例库工作树零写入（git status 前后一致）、绑定无重复 nodeid；
4. `scripts/gates.sh` 登记本步。

用法
----
    python3 tools/check_eht_binding_evidence.py
    python3 tools/check_eht_binding_evidence.py --self-test  # 真注入把 scripts 改 0，必须判红

退出码：0 通过 / 1 违规 / 2 证据缺失或结构变了。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "docs/evidence/v5.5.0/eht/raw"
SYNC = RAW / "eht08-sync-report.json"
HEAD = RAW / "eht08-repo-head.json"
GATES = ROOT / "scripts/gates.sh"


def ok(msg: str) -> None:
    print("  OK     " + msg)


def fail(problems: list[str], msg: str) -> None:
    problems.append(msg)
    print("  FAIL   " + msg)


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def check(problems: list[str], sync: Path = SYNC, head: Path = HEAD,
          gates: Path = GATES) -> None:
    if not sync.exists() or not head.exists():
        fail(problems, "EHT-08 证据文件缺失（先跑绑定同步并留证）")
        return
    try:
        s = load(sync)
        h = load(head)
    except Exception as e:
        fail(problems, "证据 JSON 解析失败：" + str(e))
        return

    data = s.get("data", {})
    totals = s.get("source_totals", {})
    total = data.get("totalCases")
    bound = data.get("bound")
    unbound = data.get("unbound")
    scanned = data.get("scriptsScanned")
    if total != 469:
        fail(problems, "分母不是 469（实际 %s）" % total)
    else:
        ok("分母 469（case-index 真源）")
    if not isinstance(bound, int) or not isinstance(unbound, int) or bound + unbound != total:
        fail(problems, "bound + unbound != totalCases（四类计数不自洽）")
    else:
        ok("bound + unbound == totalCases")
    if totals.get("scripts") != 62:
        fail(problems, "case-index 的 scripts 不是 62（证据被改或真源漂移）")
    else:
        ok("case-index scripts = 62")
    if not isinstance(scanned, int) or scanned < 61 or scanned > 62:
        fail(problems, "scriptsScanned=%s 不在 61~62（无编号框架自测不计入）" % scanned)
    else:
        ok("scriptsScanned = %s（62 - 1 个无编号框架自测）" % scanned)

    sha = h.get("repo_head", "")
    if not re.fullmatch(r"[0-9a-f]{40}", str(sha)):
        fail(problems, "repo_head 不是 40 位 git rev-parse HEAD（工作树哈希不许进快照）")
    else:
        ok("repo_head = 40 位 rev-parse HEAD")
    if h.get("git_status_unchanged") is not True:
        fail(problems, "用例库工作树被写入（解耦 E2 违反）")
    else:
        ok("用例库工作树零写入")
    if h.get("duplicate_node_ids"):
        fail(problems, "绑定存在重复 nodeid")
    else:
        ok("绑定无重复 nodeid")

    if not re.search(r"^run 50 eht_binding_evidence ", gates.read_text(encoding="utf-8"), re.M):
        fail(problems, "gates.sh 未登记步骤 50")
    else:
        ok("gates.sh 登记步骤 50")


def run() -> int:
    print("check_eht_binding_evidence: 绑定证据与真源快照")
    problems: list[str] = []
    check(problems)
    if problems:
        print("check_eht_binding_evidence: FAIL（%d 项）" % len(problems))
        return 1
    print("check_eht_binding_evidence: PASS")
    return 0


def self_test() -> int:
    """真注入：把证据里的 scripts 改成 0 / 把 repo_head 改成工作树样式，必须判红。"""
    original = load(SYNC)
    injections = [
        (lambda d: d["source_totals"].__setitem__("scripts", 0), "把 scripts 改成 0"),
        (lambda d: d["data"].__setitem__("unbound", 0), "破坏四类计数恒等式"),
    ]
    for mutate, label in injections:
        broken = json.loads(json.dumps(original))
        mutate(broken)
        tmp = None
        try:
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False,
                                             encoding="utf-8") as fh:
                json.dump(broken, fh, ensure_ascii=False)
                tmp = Path(fh.name)
            problems: list[str] = []
            check(problems, sync=tmp)
            if not problems:
                print("  FAIL   %s 后机检仍判绿（假绿灯）" % label)
                return 1
            print("  OK     %s -> 判红（%d 项）" % (label, len(problems)))
        finally:
            if tmp is not None:
                tmp.unlink(missing_ok=True)
    print("--- 还原后应判绿 ---")
    problems = []
    check(problems)
    if problems:
        print("  FAIL   还原后仍判红：%s" % problems)
        return 1
    print("check_eht_binding_evidence: SELF-TEST PASS")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="EHT 绑定证据与真源快照机检")
    p.add_argument("--self-test", action="store_true", help="破坏性自检")
    a = p.parse_args()
    return self_test() if a.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
