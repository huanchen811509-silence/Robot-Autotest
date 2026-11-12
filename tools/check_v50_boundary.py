#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, hashlib, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/evidence/v5.0.0/baseline/exec-chain.sha256"


def check(text):
    problems = []
    entries = [ln.split(None, 1) for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
    if not entries:
        return ["清单为空"]
    for parts in entries:
        if len(parts) != 2:
            problems.append("清单行格式错: %r" % parts)
            continue
        sha, rel = parts
        p = ROOT / rel.strip()
        if not p.exists():
            problems.append("文件缺失: " + rel)
            continue
        cur = hashlib.sha256(p.read_bytes()).hexdigest()
        if cur != sha.strip():
            problems.append("执行链被改动: " + rel)
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not MANIFEST.exists():
        print("[FAIL] 读不到 sha256 清单"); return 2
    text = MANIFEST.read_text(encoding="utf-8")
    if a.selfcheck:
        lines = [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
        bad = "\n".join(["0" * 64 + "  " + lines[0].split(None, 1)[1]] + lines[1:])
        if not check(bad):
            print("[FAIL] selfcheck：篡改 sha 未判红"); return 1
        if check(text):
            print("[FAIL] selfcheck：干净清单不应判红"); return 1
        print("[PASS] boundary selfcheck：篡改判红 / 干净判绿"); return 0
    p = check(text)
    if p:
        for x in p: print("[FAIL] " + x)
        return 1
    n = len([ln for ln in text.splitlines() if ln.strip() and not ln.startswith("#")])
    print("[PASS] 边界：%d 个双内核执行链文件零改动" % n); return 0


if __name__ == "__main__":
    raise SystemExit(main())
