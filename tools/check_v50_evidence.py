#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, pathlib, re, subprocess, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
EV = ROOT / "docs/evidence/v5.0.0"
IP = re.compile(r"\b(?:10|172|192\.168|169\.254)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b")
SECRET = re.compile(r"(password\s*=\s*\S|LAB_PWD\s*=\s*\S|passwd\s*=\s*\S)", re.I)
BADREF = "docs/evidence/gates/logs/"


def check(evroot, skip=None):
    bad = []
    files = [p for p in evroot.rglob("*") if p.is_file() and p != skip]
    for p in files:
        try: t = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError): continue
        if IP.search(t): bad.append("内网 IP: " + str(p.relative_to(ROOT)))
        if SECRET.search(t): bad.append("口令字面量: " + str(p.relative_to(ROOT)))
        if BADREF in t and p.name.endswith((".md", ".json")):
            bad.append("引用被忽略目录: " + str(p.relative_to(ROOT)))
        if "\ufffd" in t: bad.append("U+FFFD: " + str(p.relative_to(ROOT)))
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not EV.exists(): print("[FAIL] 读不到证据目录"); return 2
    if a.selfcheck:
        tmp = ROOT / "docs/evidence/v5.0.0/raw/_selfcheck_inject.md"
        tmp.write_text("password = should_fail\n" + BADREF + "\n", encoding="utf-8")
        try:
            bad = check(EV)
            if not bad: print("[FAIL] selfcheck：注入违规未判红"); return 1
            tmp.unlink()
            if check(EV): print("[FAIL] selfcheck：干净目录不应判红"); return 1
        finally:
            if tmp.exists(): tmp.unlink()
        print("[PASS] evidence selfcheck：注入判红 / 还原判绿"); return 0
    bad = check(EV)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    r = subprocess.run(["git", "check-ignore", "-q", "docs/evidence/v5.0.0/baseline/exec-chain.sha256"],
                       cwd=ROOT, capture_output=True)
    if r.returncode == 0:
        print("[FAIL] 证据目录被 .gitignore 忽略"); return 1
    print("[PASS] v5.0.0 证据目录：无内网 IP / 口令 / 被忽略引用，且未被 git 忽略"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
