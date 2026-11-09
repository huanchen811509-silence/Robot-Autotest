#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.3.0 · AI 黄金集离线评测（门禁第 24 步，FR-3.3-08）。

为什么要有它：AI 回答「听起来对」不等于「有据可依」。本评测直接调用后端真实端点，
用证据装配器的输出做事实性检查——正文里的数字必须能在报告证据里找到、引用必须真实存在、
没有证据时必须明确拒答、流式首字时延必须达标。默认不走真实模型 CLI（fake CLI 契约），
因此结果可重复、不依赖外部账号。

检查项：
  1. 分诊（triage）   ：事实标注 / 证据引用 / 事实一致性报告通过 / 无未支撑数字
  2. 报告摘要（narrative）：结论段 / 数字可追溯 / 校验通过
  3. 日志解释（chat） ：带真实运行编号提问，必须给出带引用的回答
  4. 拒答（chat）     ：不存在的运行编号，必须明确说「没有可用证据」
  5. 用例草稿         ：必须是 DRAFT 且写明「人工审阅」；reviewed=false 必须被拒
  6. 流式             ：事件序列 meta→citations→delta*→done，首字 ≤ 3000ms

输出：docs/evidence/v3.3.0/ai-eval.json（含逐项检查、评分与结论）。
退出码：0 = 全达标；1 = 有检查不达标；2 = 环境/端点不可用。
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_FILE = Path(os.environ.get("AI_EVAL_OUT", ROOT / "docs/evidence/v3.3.0/ai-eval.json"))
BASE = os.environ.get("ENTRY_BASE", "http://127.0.0.1:8001").rstrip("/")
PROJECT_ID = int(os.environ.get("AI_EVAL_PROJECT_ID", os.environ.get("PERF_PROJECT_ID", "29")))
USERNAME = os.environ.get("AI_EVAL_USER", "qa-e2e-admin")
FIRST_TOKEN_BUDGET_MS = int(os.environ.get("AI_EVAL_FIRST_TOKEN_MS", "3000"))
FACT_TARGET_PCT = float(os.environ.get("AI_EVAL_FACT_TARGET", "95"))

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TOKEN: str | None = None


def env_value(key: str) -> str | None:
    if os.environ.get(key):
        return os.environ[key]
    path = Path.home() / ".config" / "robot-autotest" / "env"
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            name, value = stripped.split("=", 1)
            if name.strip() == key:
                return value.strip()
    except OSError:
        return None
    return None


def call(path: str, payload: dict | None = None, timeout: float = 20.0) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(BASE + path, data=data,
                                     method="POST" if data else "GET")
    request.add_header("Content-Type", "application/json")
    if TOKEN:
        request.add_header("Authorization", "Bearer " + TOKEN)
    with _OPENER.open(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def api(path: str, payload: dict | None = None, timeout: float = 20.0):
    envelope = call(path, payload, timeout)
    if envelope.get("code") != 0:
        raise RuntimeError("%s -> %s %s" % (path, envelope.get("codeName"),
                                            envelope.get("message")))
    return envelope.get("data")


def api_error(path: str, payload: dict) -> tuple[int, str]:
    try:
        envelope = call(path, payload)
        return int(envelope.get("code") or 0), str(envelope.get("message") or "")
    except urllib.error.HTTPError as http:
        body = http.read().decode("utf-8", "replace")
        try:
            envelope = json.loads(body)
            return int(envelope.get("code") or http.code), str(envelope.get("message") or body)
        except ValueError:
            return http.code, body[:200]


def login() -> None:
    global TOKEN
    password = env_value("QA_E2E_PASSWORD")
    if not password:
        raise RuntimeError("缺 QA_E2E_PASSWORD（~/.config/robot-autotest/env）")
    data = api("/api/auth/login", {"username": USERNAME, "password": password})
    TOKEN = data.get("token") or data.get("accessToken")
    if not TOKEN:
        raise RuntimeError("登录响应里没有 token")


def pick_run() -> str:
    for state in ("failed", "finished", ""):
        query = "?projectId=%d&page=1&size=10" % PROJECT_ID
        if state:
            query += "&state=" + state
        page = api("/api/execution/runs" + query) or {}
        items = page.get("items") or []
        for item in items:
            run_id = item.get("runId") or item.get("id")
            if run_id:
                return str(run_id)
    raise RuntimeError("项目 %d 没有可评测的运行" % PROJECT_ID)


def check(checks: list, name: str, passed: bool, detail: str = "") -> None:
    checks.append({"name": name, "pass": bool(passed), "detail": detail})


def answer_of(result: dict) -> dict:
    return result.get("answer") or {}


def verify_of(result: dict) -> dict:
    return result.get("verification") or {}


def numeric_checks(prefix: str, result: dict, checks: list) -> list:
    answer = answer_of(result)
    verification = verify_of(result)
    citations = answer.get("citations") or []
    check(checks, prefix + ".verification", bool(verification.get("pass")),
          "unsupported=%s missing=%s" % (verification.get("unsupportedNumbers"),
                                         verification.get("missingCitations")))
    check(checks, prefix + ".citations", len(citations) > 0,
          "citationCount=%d" % len(citations))
    check(checks, prefix + ".no_hallucinated_number",
          not (verification.get("unsupportedNumbers") or []),
          str(verification.get("unsupportedNumbers") or []))
    return citations


def stream_case(run_id: str) -> dict:
    """真实读取 SSE：首字时延与事件序列都在服务端实测，不由客户端伪造。"""
    stream_id = None
    events: list[str] = []
    first_delta_ms: int | None = None
    deltas = 0
    done: dict | None = None
    started = time.monotonic()
    request = urllib.request.Request(
        BASE + "/api/ai/workbench/chat/stream",
        data=json.dumps({"projectId": PROJECT_ID, "sessionId": 0,
                         "prompt": "请分诊运行 %s" % run_id}).encode(),
        method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "text/event-stream")
    if TOKEN:
        request.add_header("Authorization", "Bearer " + TOKEN)
    with _OPENER.open(request, timeout=60) as response:
        stream_id = response.headers.get("X-AI-Stream-Id")
        event_name = None
        deadline = started + 60
        for raw in response:
            if time.monotonic() > deadline:
                break
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith("event:"):
                event_name = line[6:].strip()
                events.append(event_name)
            elif line.startswith("data:") and event_name == "delta" and first_delta_ms is None:
                first_delta_ms = int((time.monotonic() - started) * 1000)
                deltas += 1
            elif line.startswith("data:") and event_name == "delta":
                deltas += 1
            elif line.startswith("data:") and event_name == "done":
                try:
                    done = json.loads(line[5:].strip())
                except ValueError:
                    done = {"raw": line[5:].strip()}
    elapsed_ms = int((time.monotonic() - started) * 1000)
    checks: list = []
    check(checks, "stream.sequence",
          bool(events) and events[0] == "meta" and events[-1] in ("done", "cancelled", "error"),
          ",".join(events[:3]) + "..." + ",".join(events[-2:]))
    check(checks, "stream.first_token",
          first_delta_ms is not None and first_delta_ms <= FIRST_TOKEN_BUDGET_MS,
          "firstDeltaMs=%s budget=%d" % (first_delta_ms, FIRST_TOKEN_BUDGET_MS))
    check(checks, "stream.done", done is not None and "elapsedMs" in done,
          json.dumps(done, ensure_ascii=False)[:160])
    check(checks, "stream.deltas", deltas > 0, "deltas=%d" % deltas)
    return {"case": "stream", "streamId": stream_id, "elapsedMs": elapsed_ms,
            "firstTokenMs": first_delta_ms, "events": events, "done": done,
            "checks": checks, "pass": all(item["pass"] for item in checks)}


def main() -> int:
    generated = datetime.now(timezone.utc).isoformat()
    try:
        login()
        run_id = pick_run()
    except Exception as exc:  # noqa: BLE001 - 端点不可用属门禁红，需可读原因
        print("AI_EVAL_ERROR 端点不可用或登录失败：%s" % exc, file=sys.stderr)
        return 2

    cases: list = []

    triage = api("/api/ai/workbench/triage",
                 {"projectId": PROJECT_ID, "runId": run_id, "sessionId": 0})
    checks: list = []
    answer = answer_of(triage)
    check(checks, "triage.mode", answer.get("mode") == "GROUNDED", str(answer.get("mode")))
    check(checks, "triage.facts_label", "事实" in (answer.get("text") or ""))
    numeric_checks("triage", triage, checks)
    cases.append({"case": "triage", "elapsedMs": triage.get("elapsedMs"),
                  "checks": checks, "pass": all(item["pass"] for item in checks)})

    narrative = api("/api/ai/workbench/narrative",
                    {"projectId": PROJECT_ID, "runId": run_id, "sessionId": 0})
    checks = []
    answer = answer_of(narrative)
    check(checks, "narrative.conclusion", "结论" in (answer.get("text") or ""))
    numeric_checks("narrative", narrative, checks)
    cases.append({"case": "narrative", "elapsedMs": narrative.get("elapsedMs"),
                  "checks": checks, "pass": all(item["pass"] for item in checks)})

    explain = api("/api/ai/workbench/chat",
                  {"projectId": PROJECT_ID, "sessionId": 0,
                   "prompt": "请解释运行 %s 的日志与失败原因" % run_id})
    checks = []
    answer = answer_of(explain)
    check(checks, "log_explain.mode", answer.get("mode") == "GROUNDED",
          str(answer.get("mode")))
    check(checks, "log_explain.citations",
          len(answer.get("citations") or []) > 0,
          "citationCount=%d" % len(answer.get("citations") or []))
    cases.append({"case": "log_explain", "elapsedMs": explain.get("elapsedMs"),
                  "checks": checks, "pass": all(item["pass"] for item in checks)})

    refusal = api("/api/ai/workbench/chat",
                  {"projectId": PROJECT_ID, "sessionId": 0,
                   "prompt": "请解释运行 RUN-EVAL-NOT-EXIST 的日志"})
    checks = []
    answer = answer_of(refusal)
    text = answer.get("text") or ""
    check(checks, "refusal.mode", answer.get("mode") == "NO_EVIDENCE",
          str(answer.get("mode")))
    check(checks, "refusal.explicit", "没有可用证据" in text or "无可用证据" in text, text[:80])
    cases.append({"case": "refusal", "elapsedMs": refusal.get("elapsedMs"),
                  "checks": checks, "pass": all(item["pass"] for item in checks)})

    draft = api("/api/ai/workbench/case-draft",
                {"projectId": PROJECT_ID, "requirement": "评测黄金集：需求片段",
                 "module": "AI 评测", "count": 3, "sessionId": 0})
    checks = []
    answer = answer_of(draft)
    check(checks, "case_draft.mode", answer.get("mode") == "DRAFT", str(answer.get("mode")))
    check(checks, "case_draft.review_notice", "人工审阅" in (answer.get("text") or ""))
    code, message = api_error("/api/ai/workbench/case-draft/apply",
                              {"projectId": PROJECT_ID, "reviewed": False,
                               "drafts": [{"title": "评测草稿", "precondition": "-",
                                           "steps": "-", "expected": "-", "source": "E1"}]})
    check(checks, "case_draft.requires_review", code != 0, "%s %s" % (code, message))
    cases.append({"case": "case_draft", "elapsedMs": draft.get("elapsedMs"),
                  "checks": checks, "pass": all(item["pass"] for item in checks)})

    try:
        cases.append(stream_case(run_id))
    except Exception as exc:  # noqa: BLE001
        cases.append({"case": "stream", "checks": [
            {"name": "stream.available", "pass": False, "detail": str(exc)}], "pass": False})

    flat = [item for case in cases for item in case["checks"]]
    passed = sum(1 for item in flat if item["pass"])
    fact_flat = [item for item in flat if "verification" in item["name"]
                 or "hallucinated" in item["name"] or "citations" in item["name"]]
    fact_pass = sum(1 for item in fact_flat if item["pass"])
    fact_pct = round(100.0 * fact_pass / len(fact_flat), 1) if fact_flat else 0.0
    hallucinations = sum(len((item.get("detail") or "").split(",")) for item in flat
                         if item["name"].endswith("no_hallucinated_number") and not item["pass"])
    first_tokens = [case.get("firstTokenMs") for case in cases
                    if isinstance(case.get("firstTokenMs"), int)]
    latency = min(first_tokens) if first_tokens else None
    verdict = ("PASS" if all(case["pass"] for case in cases)
               and fact_pct >= FACT_TARGET_PCT and latency is not None
               and latency <= FIRST_TOKEN_BUDGET_MS else "FAIL")

    report = {
        "generatedAt": generated,
        "engine": "grounded-answer-factory",
        "realCli": False,
        "fakeCliContract": True,
        "entry": BASE,
        "projectId": PROJECT_ID,
        "runId": run_id,
        "checksPassed": passed,
        "checksTotal": len(flat),
        "scores": {
            "factConsistencyPct": fact_pct,
            "factTargetPct": FACT_TARGET_PCT,
            "severeHallucinations": hallucinations,
            "citationCoveragePct": round(100.0 * sum(1 for item in fact_flat
                                                     if "citations" in item["name"] and item["pass"])
                                         / max(1, sum(1 for item in fact_flat
                                                     if "citations" in item["name"]), ), 1),
            "firstTokenMs": latency,
            "firstTokenBudgetMs": FIRST_TOKEN_BUDGET_MS,
        },
        "cases": cases,
        "verdict": verdict,
    }
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    for case in cases:
        print("  %-14s %s" % (case["case"], "PASS" if case["pass"] else "FAIL"))
        for item in case["checks"]:
            if not item["pass"]:
                print("    FAIL %s %s" % (item["name"], item.get("detail") or ""))
    print("AI_EVAL %s checks=%d/%d fact=%.1f%% firstToken=%sms -> %s"
          % (verdict, passed, len(flat), fact_pct, latency, OUT_FILE))
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
