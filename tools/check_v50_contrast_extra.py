#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

# 原 280 项由 tools/check_contrast.py + 门禁 27 步守护，本脚本只守新增的四类控件态。
import argparse, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
CSS = ROOT / "frontend/src/styles/target-size.css"
NEED = ("select", "button", "[tabindex]", "focus-visible")


def check(text):
    bad = [k for k in NEED if k not in text]
    if re.search(r"#[0-9a-fA-F]{3,8}\b", text): bad.append("出现写死 hex 色值")
    if "--v50-target-min" not in text: bad.append("未使用最小目标令牌")
    return bad


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not CSS.exists(): print("[FAIL] 读不到 target-size.css"); return 2
    t = CSS.read_text(encoding="utf-8")
    if a.selfcheck:
        if not check(t.replace("focus-visible", "focus")):
            print("[FAIL] selfcheck：删焦点环判据未判红"); return 1
        if check(t): print("[FAIL] selfcheck：完整样式不应判红"); return 1
        print("[PASS] contrast_extra selfcheck：删焦点环判红 / 完整判绿"); return 0
    bad = check(t)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 新增控件态：select / button / tabindex / focus-visible 四类齐，零写死 hex"); return 0


if __name__ == "__main__":
    raise SystemExit(main())
