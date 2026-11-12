#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
F = ROOT / "docs/plan/v5.0-质量属性场景.md"
TRAITS = ["Functional suitability", "Performance efficiency", "Compatibility",
          "Interaction capability", "Reliability", "Security",
          "Maintainability", "Flexibility", "User engagement"]


def check(text):
    bad = []
    scenes = re.findall(r"^\| (SC-\d{2}) \|", text, re.M)
    if len(scenes) < 9: bad.append("场景数 %d < 9" % len(scenes))
    if len(set(scenes)) != len(scenes): bad.append("场景编号重复")
    missing = [t for t in TRAITS if t not in text]
    if missing: bad.append("缺特性 %s" % missing)
    if "排除理由" not in text or "Safety" not in text: bad.append("缺 Safety 排除理由（D4）")
    for line in text.splitlines():
        if line.startswith("| SC-"):
            if "FR-v5.0-" not in line or "TC-v5.0-" not in line:
                bad.append("场景未绑定 FR/TC: " + line[:40])
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not F.exists(): print("[FAIL] 读不到场景文件"); return 2
    t = F.read_text(encoding="utf-8")
    if a.selfcheck:
        broken = re.sub(r"^\| SC-09 \|.*$", "", t, count=1, flags=re.M)
        if not check(broken): print("[FAIL] selfcheck：删场景未判红"); return 1
        if check(t): print("[FAIL] selfcheck：完整文件不应判红"); return 1
        print("[PASS] scenarios selfcheck：删场景判红 / 完整判绿"); return 0
    bad = check(t)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 质量属性场景 >=9 条、八特性齐、Safety 排除理由成文"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
