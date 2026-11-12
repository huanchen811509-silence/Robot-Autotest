#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

# 静态契约组：把 v5.0 所有辅助机检一次性接进门禁 35 步（用户硬规矩：机检必须进门禁）。
import argparse, subprocess, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[1]
MEMBERS = ["check_v50_trace.py", "check_doc_endpoints.py", "check_v50_flags.py",
           "check_v50_adr.py", "check_v50_scenarios.py", "check_v50_mode.py",
           "check_v50_metric_schema.py", "check_v50_slo.py", "check_v50_states.py",
           "check_v50_contrast_extra.py", "v50_dora_trend.py"]


def run(script, flag=False):
    cmd = [sys.executable, "tools/" + script] + (["--selfcheck"] if flag else [])
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    return r.returncode, (r.stdout.strip().splitlines() or [""])[-1]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    # 产出型脚本先生成证据，检查型脚本再校验
    for g in ("perf_longlist.py", "perf_runchain.py"):
        if not a.selfcheck:
            rc, msg = run(g)
            if rc: print("[FAIL] %s %s" % (g, msg)); return 1
    bad = []
    for m in MEMBERS:
        flag = bool(a.selfcheck)
        # 产出型脚本没有 --selfcheck 语义时仍调用其自带自检
        rc, msg = run(m, flag)
        if rc:
            bad.append("%s: %s" % (m, msg))
        else:
            print("[OK] %-32s %s" % (m, msg[:70]))
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] v5.0 静态契约组 %d/%d 全绿%s" % (len(MEMBERS), len(MEMBERS),
          "（含负向自检）" if a.selfcheck else "")); return 0


if __name__ == "__main__":
    raise SystemExit(main())
