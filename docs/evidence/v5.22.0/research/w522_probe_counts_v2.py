#!/usr/bin/env python3
"""v5.22 调研 B 组（补测 v2）：同一 run 的计数在四处是否一致。

四处口径：
  R1 运行记录接口  GET /api/execution/runs/{runId}?projectId=X
  R2 报告渲染      GET /api/test-reports/{id}?external=true
  R3 报告快照      GET /api/test-reports/{id}/snapshot
  R4 报告行        GET /api/test-reports?projectId=X

只读，不写库。
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8081"
OUT = "/tmp/w522_c.json"
COUNT_KEYS = ("total", "passed", "failed", "skipped", "error", "errors",
              "totalCount", "passedCount", "failedCount", "skippedCount",
              "caseTotal", "caseCovered", "coverageRate", "durationMs",
              "passedTests", "failedTests", "totalTests",
              "passCount", "failCount", "skipCount", "passRate",
              "conclusion", "projectName", "swBuild", "caseKind")


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"} if data else {}
    if token:
        h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=90) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def login():
    u = os.environ.get("QA_E2E_ADMIN") or "qa-e2e-admin"
    p = os.environ.get("QA_E2E_PASSWORD", "")
    st, js = req("POST", "/api/auth/login", {"username": u, "password": p})
    d = js.get("data") or {}
    t = d.get("token") or d.get("accessToken")
    if not t:
        raise SystemExit(f"登录失败 {st} {str(js)[:200]}")
    return t


def counts(d):
    if not isinstance(d, dict):
        return {}
    return {k: d[k] for k in COUNT_KEYS if k in d}


def deep_counts(obj, prefix=""):
    """在嵌套结构里找 counts / stats 子对象。"""
    found = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k.lower() in ("counts", "stats", "summary", "result", "results") and isinstance(v, dict):
                found[prefix + k] = counts(v)
            elif isinstance(v, (dict, list)):
                found.update(deep_counts(v, prefix + k + "."))
    elif isinstance(obj, list) and obj:
        found.update(deep_counts(obj[0], prefix + "[0]."))
    return found


def main():
    token = login()
    out = {}

    # 取最近报告（跨项目），优先含 pytest 的
    st, js = req("GET", "/api/projects", token=token)
    pdata = js.get("data")
    projects = pdata.get("items") if isinstance(pdata, dict) else pdata
    projects = projects or []
    out["projects"] = [(p.get("id"), p.get("name")) for p in projects]

    rows = []
    for p in projects:
        pid = p.get("id")
        st, js2 = req("GET", f"/api/test-reports?projectId={pid}&page=1&size=5", token=token)
        for r in (js2.get("data") or []):
            r["_pid"] = pid
            rows.append(r)
    out["reportCount"] = len(rows)

    cases = []
    for r in rows[:6]:
        rid, run_id, pid = r.get("id"), r.get("runId"), r.get("_pid")
        e = {"reportId": rid, "runId": run_id, "projectId": pid, "rowKeys": sorted(r.keys())}
        e["R4_row"] = counts(r)
        # R1
        st, js = req("GET", f"/api/execution/runs/{urllib.parse.quote(str(run_id))}?projectId={pid}", token=token)
        e["R1_status"] = st
        d = js.get("data") if isinstance(js, dict) else None
        e["R1_counts"] = counts(d) or deep_counts(d)
        e["R1_keys"] = sorted(d.keys()) if isinstance(d, dict) else []
        if st == 200 and isinstance(d, dict):
            e["R1_raw_subset"] = {k: v for k, v in d.items()
                                  if isinstance(v, (int, float, str, bool)) and k not in ("logTail",)}
        # R2
        st, js = req("GET", f"/api/test-reports/{rid}?external=true", token=token)
        e["R2_status"] = st
        v = js.get("data") if isinstance(js, dict) else None
        e["R2_counts"] = counts(v) or deep_counts(v)
        e["R2_keys"] = sorted(v.keys()) if isinstance(v, dict) else []
        # R3
        st, js = req("GET", f"/api/test-reports/{rid}/snapshot", token=token)
        e["R3_status"] = st
        sv = js.get("data") if isinstance(js, dict) else None
        if isinstance(sv, dict):
            e["R3_keys"] = sorted(sv.keys())
            sj = sv.get("snapshotJson") or sv.get("json")
            if isinstance(sj, str):
                try:
                    sj = json.loads(sj)
                except Exception:
                    sj = None
            if isinstance(sj, dict):
                e["R3_counts"] = counts(sj) or deep_counts(sj)
                e["R3_topKeys"] = sorted(sj.keys())
        cases.append(e)
    out["cases"] = cases

    with open(OUT, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=2, default=str)

    print("projects:", out["projects"][:8])
    print("report rows:", out["reportCount"])
    for e in cases:
        print(f"\n--- report={e['reportId']} run={e['runId']} pid={e['projectId']} ---")
        print("  R4 row    :", json.dumps(e["R4_row"], ensure_ascii=False))
        print("  R1 runs   :", json.dumps(e["R1_counts"], ensure_ascii=False), "st=", e["R1_status"])
        print("  R1 keys   :", e["R1_keys"][:24])
        print("  R2 render :", json.dumps(e["R2_counts"], ensure_ascii=False), "st=", e["R2_status"])
        print("  R3 snap   :", json.dumps(e.get("R3_counts"), ensure_ascii=False), "st=", e["R3_status"],
              "top=", e.get("R3_topKeys"))
        if e.get("R1_raw_subset"):
            print("  R1 scalars:", json.dumps(e["R1_raw_subset"], ensure_ascii=False)[:300])
    print("\nfull ->", OUT)


if __name__ == "__main__":
    main()
