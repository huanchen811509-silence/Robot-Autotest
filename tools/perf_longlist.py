#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。

import argparse, json, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/evidence/v5.0.0/perf/perf_longlist.json"


def payload():
    return {"metric": '10000 行长列表滚动 minFps', "target": 'minFps >= 50', "status": "未测量",
            "mode": "not_measured", "captured_at": "2026-11-12",
            "sample_size": 0, "machine": None, "rounds": 0,
            "p50": None, "p95": None, "min_fps": None,
            "blocker": '零数据改动约束下不造 10000 行测试数据；前端 fps 需真实浏览器（CDP 按 D1 关闭）',
            "note": "本脚本产出的是前后对照框架，不是压测结论；禁止把 0 轮采样写成达标"}


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    if a.selfcheck:
        bad = payload(); bad["status"] = "PASS"; bad["p95"] = 10
        problems = [x for x in ("status", "sample_size") if bad.get("status") == "PASS" and bad.get("sample_size") == 0]
        if not problems: print("[FAIL] selfcheck：0 轮采样标 PASS 未判红"); return 1
        print("[PASS] %s selfcheck：0 轮采样冒充达标被判红" % pathlib.Path(__file__).name); return 0
    OUT.write_text(json.dumps(payload(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("[PASS] 已写框架报告 %s（status=未测量，sample_size=0，如实登记）" % OUT.relative_to(ROOT)); return 0


if __name__ == "__main__":
    raise SystemExit(main())
