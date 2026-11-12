#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, json, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
B = ROOT / "docs/evidence/v5.0.0/baseline"


def check(data):
    bad = []
    if "lab" not in data or "field" not in data: bad.append("缺 lab/field 两支")
    for k in ("lab", "field"):
        d = data.get(k, {})
        if d.get("mode") != k: bad.append("%s 缺 mode 字段或值错" % k)
    if data.get("status") != "未测量": bad.append("status 不是未测量")
    lab = data.get("lab", {})
    for k in ("lcp_ms", "inp_ms", "cls"):
        if lab.get(k) is not None: bad.append("lab %s 有数值，须标明非真实用户 p75" % k)
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    f = B / "cwv-evidence-index.json"
    if not f.exists(): print("[FAIL] 读不到 CWV 证据索引"); return 2
    d = json.loads(f.read_text(encoding="utf-8"))
    if a.selfcheck:
        broken = dict(d); bad = dict(d.get("lab", {})); bad.pop("mode", None); broken["lab"] = bad
        if not check(broken): print("[FAIL] selfcheck：删 mode 未判红"); return 1
        if check(d): print("[FAIL] selfcheck：干净证据不应判红"); return 1
        print("[PASS] mode selfcheck：删 mode 判红 / 干净判绿"); return 0
    bad = check(d)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] CWV 证据 lab/field 分离、mode 字段齐全、无 lab 折算达标"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
