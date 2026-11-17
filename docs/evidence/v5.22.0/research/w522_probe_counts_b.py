#!/usr/bin/env python3
"""v5.22 调研 B 组：同一 run 在「运行记录」与「报告」两处的计数是否一致；顺带 dump 报告行字段名。"""
import json
import os
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081"


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"} if data else {}
    if token:
        h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def main():
    st, js = req("POST", "/api/auth/login",
                 {"username": os.environ.get("QA_E2E_ADMIN", "qa-e2e-admin"),
                  "password": os.environ.get("QA_E2E_PASSWORD", "")})
    d = js.get("data") or {}
    token = d.get("token") or d.get("accessToken")
    if not token:
        st, js = req("POST", "/api/auth/login",
                     {"username": "admin", "password": os.environ.get("ADMIN_PASSWORD", "")})
        token = (js.get("data") or {}).get("token") or (js.get("data") or {}).get("accessToken")
    assert token, "登录失败"

    out = {}
    # 1) 报告行字段名
    st, js = req("GET", "/api/test-reports?projectId=95", token=token)
    rows = js.get("data") or []
    out["reportRows"] = rows[:2]
    out["reportRowKeys"] = sorted(rows[0].keys()) if rows else []

    # 2) 运行记录列表（同项目）
    st, js = req("GET", "/api/execution/runs?projectId=95&page=1&size=10", token=token)
    out["runsStatus"] = st
    runs = js.get("data")
    if isinstance(runs, dict):
        runs = runs.get("items") or runs.get("rows") or runs
    out["runsRows"] = runs if not isinstance(runs, list) else runs[:4]
    out["runRowKeys"] = sorted(runs[0].keys()) if isinstance(runs, list) and runs else []

    # 3) 单个 run 详情（带 projectId）
    if isinstance(runs, list):
        for r in runs:
            rid = r.get("runId") or r.get("id")
            st, js = req("GET", f"/api/execution/runs/{rid}?projectId=95", token=token)
            out.setdefault("runDetail", {})[str(rid)] = js.get("data") if st == 200 else js
            if len(out["runDetail"]) >= 2:
                break

    print(json.dumps(out, ensure_ascii=False, indent=2)[:6000])


if __name__ == "__main__":
    main()
