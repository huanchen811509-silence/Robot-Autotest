#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, json, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
B = ROOT / "docs/evidence/v5.0.0/baseline"
NUMERIC = ("success_rate", "completed_steps", "duration_ms_p95", "sus_score",
           "longlist_10000_min_fps", "runchain_create_p95_ms",
           "deployment_frequency", "lead_time_for_changes",
           "change_failure_rate", "time_to_restore", "facts_consistent",
           "lcp_ms", "inp_ms", "cls")


def walk(o, path=""):
    if isinstance(o, dict):
        for k, v in o.items(): yield from walk(v, path + "/" + k)
    elif isinstance(o, list):
        for i, v in enumerate(o): yield from walk(v, path + "/%d" % i)
    else:
        yield path, o


def check(files):
    bad = []
    for name, d in files:
        if d.get("status") != "未测量" and d.get("mode") == "not_measured":
            bad.append("%s 标了 not_measured 但 status 不是未测量" % name)
        if d.get("status") == "未测量":
            for p, v in walk(d):
                key = p.rsplit("/", 1)[-1]
                if key in NUMERIC and v is not None and v != "未测量":
                    bad.append("%s %s 填了 %r（未测量必须 null）" % (name, p, v))
        if d.get("mode") not in (None, "not_measured", "lab", "field"):
            bad.append("%s mode 非法 %r" % (name, d.get("mode")))
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    files = []
    for f in sorted(B.glob("*.json")):
        if f.name == "golden-eval-v2.template.json": continue
        try: files.append((f.name, json.loads(f.read_text(encoding="utf-8"))))
        except json.JSONDecodeError as e: print("[FAIL] JSON 解析失败 %s: %s" % (f.name, e)); return 1
    if not files: print("[FAIL] 无基线 JSON"); return 2
    if a.selfcheck:
        broken = [(n, json.loads(json.dumps(d).replace('"success_rate": null', '"success_rate": 0'))) for n, d in files]
        if not check(broken): print("[FAIL] selfcheck：未测量字段填 0 未判红"); return 1
        if check(files): print("[FAIL] selfcheck：干净基线不应判红"); return 1
        print("[PASS] metric_schema selfcheck：填 0 判红 / 干净判绿"); return 0
    bad = check(files)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] %d 份基线 JSON：未测量字段保持 null，无填 0 / 估算" % len(files)); return 0


if __name__ == "__main__":
    raise SystemExit(main())
