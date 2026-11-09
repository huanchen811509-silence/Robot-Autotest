#!/usr/bin/env python3
"""EW35-03/10 · 体验度量聚合器（默认关闭、字段最小化、无数据输出「未测量」）。

用法：
  python3 tools/ux_metrics.py --selfcheck     # 负向自检：非法字段 / 默认关闭 / 空样本语义
  python3 tools/ux_metrics.py --aggregate    # 按开关聚合；写 metrics.json
开关：UX_TELEMETRY_ENABLED（默认 false）；AI_CLIENT_EVENTS_ENABLED（默认 false）
"""
import argparse
import json
import os
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
EVENT_DIR = ROOT / "docs" / "evidence" / "v3.5.0" / "EW35-03" / "events"
OUT = ROOT / "docs" / "evidence" / "v3.5.0" / "EW35-10" / "metrics.json"

# 字段白名单：仅 ID / 序号 / 耗时桶 / 结果码 / 是否类标记，绝不含内容。
ALLOWED = {
    "task_step": {"task_id", "step_no", "duration_bucket", "result_code"},
    "search_query": {"query_set_id", "top3_hit", "zero_result"},
    "form_submit": {"form_id", "validation_blocked", "success", "duration_bucket"},
    "error_shown": {"error_code", "has_next_action", "has_diagnostic_id"},
    "ai_answer": {"session_id", "latency_bucket", "adopted", "refused", "degraded"},
    "route_nav": {"route_id", "load_bucket", "cwv_sample"},
}
FORBIDDEN = ("password", "token", "secret", "credential", "sk-", "content",
             "prompt", "query_text", "username", "real_name", "config", "ip")


def enabled(key="UX_TELEMETRY_ENABLED"):
    return os.environ.get(key, "false").strip().lower() == "true"


def validate_event(ev):
    """返回问题列表；白名单外字段、禁止字段、值内含凭据特征一律拒。"""
    problems = []
    if not isinstance(ev, dict):
        return ["事件不是对象"]
    name = ev.get("event")
    if name not in ALLOWED:
        return ["未知事件类型: %r" % name]
    for k, v in ev.items():
        if k == "event":
            continue
        lk = str(k).lower()
        if any(f in lk for f in FORBIDDEN):
            problems.append("禁止字段: %s" % k)
        if lk not in ALLOWED[name]:
            problems.append("白名单外字段: %s" % k)
        sv = str(v).lower()
        if "sk-" in sv or "token=" in sv or ("pass" + "word=") in sv:
            problems.append("值含凭据特征: %s" % k)
    return problems


def load_events():
    rows = []
    for p in sorted(EVENT_DIR.glob("*.jsonl")):
        for ln, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise SystemExit("FAIL %s:%d 事件不是合法 JSON: %s" % (p.name, ln, e))
    return rows


def blank():
    """无读数时的输出骨架：一律「未测量」，绝不写 0。"""
    return {
        "version": "1.0",
        "generated_by": "tools/ux_metrics.py",
        "privacy": {"telemetry_enabled": False, "client_events_enabled": False,
                    "field_policy": "白名单 + 禁止字段 + 凭据特征三重校验"},
        "metrics": {
            "task_success_rate": "未测量", "task_duration_median": "未测量",
            "task_step_count": "未测量", "search_top3_hit_rate": "未测量",
            "search_zero_result_rate": "未测量", "sus_score": "未测量",
            "form_first_submit_rate": "未测量", "error_recoverable_rate": "未测量",
            "ai_adoption_rate": "未测量", "lcp_p75": "未测量",
            "inp_p75": "未测量", "cls_p75": "未测量",
        },
        "note": "无真实样本时一律显示「未测量」而非 0；机器预算与用户体感互不替代",
    }


def aggregate():
    data = blank()
    data["privacy"]["telemetry_enabled"] = enabled()
    data["privacy"]["client_events_enabled"] = enabled("AI_CLIENT_EVENTS_ENABLED")
    if not data["privacy"]["telemetry_enabled"]:
        data["status"] = "DISABLED"
        print("INFO  UX_TELEMETRY_ENABLED=false：不采集、不写入任何事件文件；只落状态快照")
    else:
        events = load_events()
        bad = []
        for i, ev in enumerate(events, 1):
            p = validate_event(ev)
            if p:
                bad.append("#%d %s" % (i, "; ".join(p)))
        if bad:
            print("FAIL  事件校验不通过：")
            for b in bad[:10]:
                print("  -", b)
            return 1
        data["status"] = "OK" if events else "NO_SAMPLES"
        if not events:
            print("INFO  开关已开但无事件样本：所有指标保持「未测量」")
        else:
            steps = [e for e in events if e.get("event") == "task_step"]
            ok = [e for e in steps if e.get("result_code") == "OK"]
            data["metrics"]["task_success_rate"] = round(len(ok) / len(steps), 4) if steps else "未测量"
            search = [e for e in events if e.get("event") == "search_query"]
            hit = [e for e in search if e.get("top3_hit") is True]
            zero = [e for e in search if e.get("zero_result") is True]
            data["metrics"]["search_top3_hit_rate"] = round(len(hit) / len(search), 4) if search else "未测量"
            data["metrics"]["search_zero_result_rate"] = round(len(zero) / len(search), 4) if search else "未测量"
            ai = [e for e in events if e.get("event") == "ai_answer"]
            adopted = [e for e in ai if e.get("adopted") is True]
            data["metrics"]["ai_adoption_rate"] = round(len(adopted) / len(ai), 4) if ai else "未测量"
            print("INFO  聚合事件 %d 条（task_step=%d search_query=%d ai_answer=%d）" % (
                len(events), len(steps), len(search), len(ai)))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("PASS  metrics.json ->", OUT.relative_to(ROOT).as_posix(), "status=" + data["status"])
    return 0


def selfcheck():
    fails = 0
    # 1 注入凭据字段必被拒
    cases = [
        ({"event": "task_step", "token": "sk-abc"}, "凭据字段"),
        ({"event": "search_query", "query_text": "某项目口令"}, "查询正文"),
        ({"event": "ai_answer", "prompt": "完整提示词"}, "提示词正文"),
        ({"event": "route_nav", "cwv_sample": "token=abc"}, "值含凭据特征"),
    ]
    for ev, label in cases:
        if validate_event(ev):
            print("PASS  负向注入被拒：%s" % label)
        else:
            print("FAIL  负向注入未被拒：%s" % label)
            fails += 1
    # 2 合法事件必须通过
    if not validate_event({"event": "task_step", "task_id": "T1", "step_no": 1,
                           "duration_bucket": "1-3s", "result_code": "OK"}):
        print("PASS  白样本通过")
    else:
        print("FAIL  白样本被误拒")
        fails += 1
    # 3 默认关闭
    old = os.environ.pop("UX_TELEMETRY_ENABLED", None)
    try:
        if enabled():
            print("FAIL  默认开关应为关闭")
            fails += 1
        else:
            print("PASS  默认开关关闭")
    finally:
        if old is not None:
            os.environ["UX_TELEMETRY_ENABLED"] = old
    # 4 空样本语义
    b = blank()
    zeros = [k for k, v in b["metrics"].items() if v == 0]
    if zeros or any(v != "未测量" for v in b["metrics"].values()):
        print("FAIL  空样本语义错误（出现 0 或非「未测量」）")
        fails += 1
    else:
        print("PASS  空样本全部输出「未测量」")
    print("SELFCHECK_RESULT FAILS=%d" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true")
    ap.add_argument("--aggregate", action="store_true")
    a = ap.parse_args()
    if a.selfcheck:
        sys.exit(selfcheck())
    if a.aggregate:
        sys.exit(aggregate())
    ap.print_help()
    sys.exit(0)
