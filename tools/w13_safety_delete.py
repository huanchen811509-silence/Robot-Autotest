#!/usr/bin/env python3
"""v5.8.0 · W58-13 判据④⑤ 取证：安全闸四守卫 + 报告软删后判据行保留。

为什么四守卫要单独取证
---------------------
real 模式会真的 SSH 登录 DUT 并执行命令。四道守卫中任何一道 fail-open，
都意味着「拼错一个 nodeId」就能绕过 ADR-0013 直接连设备。所以这里不看代码、
不看单测，只发四条**真实 HTTP 请求**，把状态码与响应原文记下来。

为什么软删要验「test_result 仍在」
--------------------------------
报告页读的是 `test_result`，不是 junit 文件。如果软删把 `test_result` 一起清了，
「删报告」就会变成「删执行事实」——判据证据链跟着消失。判据⑤要防的就是这个。

用法
----
    python3 tools/w13_safety_delete.py \
        --project 95 --out docs/evidence/v5.8.0/criteria/raw

前置：后端 :8081 在跑；`QA_E2E_PASSWORD` 在 ~/.config/robot-autotest/env；
      DB 环境变量同 `tools/` 其他脚本（`set -a; . env`）。
退出码：0 = 全部断言通过；1 = 有断言失败；2 = 前置缺失。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE = os.environ.get("APP_BACKEND", "http://127.0.0.1:8081")
USER = os.environ.get("QA_USER", "qa-e2e-admin")

# 从 DB 查到的真实 nodeId（95 工程 EHT-PYTEST-LIB）
OK_NODE = "tests/nic/new_scripts/test_im_fun_014.py::test_im_fun_014_nic_info"
LX_NODE = "tests/nic/new_scripts/test_im_vir_001.py::test_im_vir_001_sriov_vf_create"
TYPO_NODE = "tests/nic/new_scripts/test_im_fun_014.py::test_im_fun_14_nic_info"
ABSENT_NODE = "tests/nic/new_scripts/test_not_exist_9527.py::test_nothing"


def env_value(key: str) -> str | None:
    if os.environ.get(key):
        return os.environ[key]
    path = os.path.expanduser("~/.config/robot-autotest/env")
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k.strip() == key:
                    return v.strip()
    except FileNotFoundError:
        pass
    return None


def request(method: str, path: str, token: str | None = None, body: dict | None = None,
            timeout: int = 300) -> tuple[int, dict]:
    url = f"{BASE}{path}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            return exc.code, json.loads(raw) if raw.strip() else {}
        except json.JSONDecodeError:
            return exc.code, {"raw": raw}
    except urllib.error.URLError as exc:
        return 0, {"error": str(exc)}


def db_scalar(sql: str) -> str:
    keys = ("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD")
    env = {k: env_value(k) for k in keys}
    missing = [k for k, v in env.items() if not v]
    if missing:
        raise SystemExit(f"缺少 DB 环境变量：{', '.join(missing)}")
    cmd = ["mysql", "-h", env["DB_HOST"], "-P", env["DB_PORT"], "-u", env["DB_USERNAME"],
           "-p" + env["DB_PASSWORD"], env["DB_NAME"], "-N", "-B", "-e", sql]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise SystemExit(f"mysql 失败：{res.stderr[:300]}")
    return res.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", type=int, default=95)
    ap.add_argument("--out", default="docs/evidence/v5.8.0/criteria/raw")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    password = env_value("QA_E2E_PASSWORD")
    if not password:
        print("缺少 QA_E2E_PASSWORD", file=sys.stderr)
        return 2

    checks: list[dict] = []

    def check(cid: str, ok: bool, detail: str) -> None:
        checks.append({"id": cid, "ok": bool(ok), "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'}  {cid}  {detail}")

    status, body = request("POST", "/api/auth/login",
                           body={"username": USER, "password": password})
    token = (body.get("data") or {}).get("token")
    if status != 200 or not token:
        print(f"登录失败 {status} {body}", file=sys.stderr)
        return 2
    print(f"[auth] {USER} ok")

    # ---------- ④ 安全闸四守卫 ----------
    runs_path = f"/api/projects/{args.project}/pytest/runs"
    guards = [
        ("no-confirmReal", {"mode": "real", "nodeIds": [OK_NODE]},
         "real 模式需要显式确认"),
        ("unmapped-absent", {"mode": "real", "confirmReal": True, "nodeIds": [ABSENT_NODE]},
         "未绑定用例"),
        ("unmapped-typo", {"mode": "real", "confirmReal": True, "nodeIds": [TYPO_NODE]},
         "未绑定用例"),
        ("high-risk-LX", {"mode": "real", "confirmReal": True, "nodeIds": [LX_NODE]},
         "拒跑"),
    ]
    guard_log = []
    for gid, payload, expect in guards:
        st, bd = request("POST", runs_path, token, payload, timeout=60)
        msg = (bd.get("message") or "") + " " + str(bd.get("codeName") or "")
        ok = st == 400 and expect in msg
        check(f"guard-{gid}", ok, f"HTTP {st} · {msg.strip()[:110]}")
        guard_log.append({
            "id": gid, "request": payload, "httpStatus": st,
            "code": bd.get("code"), "codeName": bd.get("codeName"),
            "message": bd.get("message"), "expectContains": expect, "passed": ok,
        })

    # ---------- ⑤ 报告软删、判据行保留 ----------
    # 用 fake 单条跑一份新报告，不动已归档的正向 real 样本
    run_id = f"EHT-FAKE-DEL-{int(time.time() * 1000)}"
    st, bd = request("POST", runs_path, token,
                     {"runId": run_id, "mode": "fake", "nodeIds": [OK_NODE],
                      "metadata": {"CASE_ID": "IM-FUN-014", "RUN_ID": run_id,
                                   "QA_PROJECT": "EHT-PYTEST-LIB", "DUT": "fake",
                                   "LINK": "loopback", "SW_BUILD": "v5.8.0-w58-13"}},
                     timeout=600)
    check("delete-prepare-run", st == 200, f"HTTP {st} runId={run_id}")

    # 轮询到终态（fake 也可能走异步）
    terminal = {"FINISHED", "FAILED", "CANCELLED", "CANCELED", "ERROR", "TIMEOUT"}
    state = ""
    for _ in range(120):
        st, bd = request("GET", f"{runs_path}/{run_id}/progress", token, timeout=30)
        state = str(((bd.get("data") or {}).get("state") or bd.get("data") or "")).upper()
        if any(t in state for t in terminal):
            break
        time.sleep(2)
    check("delete-run-finished", any(t in state for t in terminal), f"state={state}")

    rows_before = db_scalar(
        "SELECT COUNT(*) FROM test_result tr JOIN run r ON r.id = tr.run_id "
        f"WHERE r.run_id='{run_id}'")
    check("delete-rows-before", rows_before.isdigit() and int(rows_before) >= 1,
          f"test_result rows={rows_before}")

    st, bd = request("GET", f"/api/reports?projectId={args.project}&page=1&size=100", token, timeout=60)
    items = ((bd.get("data") or {}).get("items") or [])
    listed_before = any(str(it.get("runId")) == run_id for it in items)
    check("delete-listed-before", listed_before, f"报告列表可见={listed_before} 共 {len(items)} 条")

    st, bd = request("DELETE", f"/api/reports/{run_id}?projectId={args.project}", token, timeout=60)
    deleted = (bd.get("data") or {}).get("deleted")
    check("delete-http", st == 200, f"HTTP {st} deleted={deleted} resp={json.dumps(bd.get('data'), ensure_ascii=False)[:120]}")

    st, bd = request("GET", f"/api/reports?projectId={args.project}&page=1&size=100", token, timeout=60)
    items = ((bd.get("data") or {}).get("items") or [])
    listed_after = any(str(it.get("runId")) == run_id for it in items)
    check("delete-hidden-after", not listed_after, f"报告列表可见={listed_after}")

    st, bd = request("GET", f"/api/reports/{run_id}?projectId={args.project}", token, timeout=60)
    check("delete-detail-gone", st != 200, f"详情 HTTP {st}")

    rows_after = db_scalar(
        "SELECT COUNT(*) FROM test_result tr JOIN run r ON r.id = tr.run_id "
        f"WHERE r.run_id='{run_id}'")
    check("delete-rows-kept", rows_after == rows_before,
          f"test_result rows {rows_before} -> {rows_after}（软删不清执行事实）")

    crit = db_scalar(
        "SELECT IFNULL(GROUP_CONCAT(IFNULL(tr.criteria_verdict,'NULL')),'') "
        f"FROM test_result tr JOIN run r ON r.id = tr.run_id WHERE r.run_id='{run_id}'")
    check("delete-criteria-kept", crit != "", f"criteria_verdict=[{crit}]")

    payload = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "backend": BASE,
        "projectId": args.project,
        "operator": USER,
        "guards": guard_log,
        "softDelete": {
            "runId": run_id,
            "state": state,
            "rowsBefore": rows_before,
            "rowsAfter": rows_after,
            "listedBefore": listed_before,
            "listedAfter": listed_after,
            "criteriaVerdicts": crit,
        },
        "checks": checks,
    }
    path = os.path.join(args.out, "safety-gate.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    failed = [c for c in checks if not c["ok"]]
    print(f"\nW13_SAFETY_DELETE {'PASS' if not failed else f'FAIL({len(failed)})'}  out={path}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
