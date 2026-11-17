#!/usr/bin/env python3
"""v5.22 调研 A/B 组事实采集：
A 组（L-19-05）对外导出到底有什么：四格式字节数 + 字段清单 + 是否含 metadata / 用例明细
B 组（L-19-06）同一 run 的计数在「运行记录」与「报告」是否一致
只读，不改库。
"""
import json
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import io

BASE = "http://127.0.0.1:8081"
OUT = "/tmp/w522_probe.json"


def req(method, path, body=None, token=None, raw=False):
    url = path if path.startswith("http") else BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            b = resp.read()
            if raw:
                return resp.status, b, dict(resp.headers)
            return resp.status, json.loads(b.decode() or "{}"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        payload = e.read()
        if raw:
            return e.code, payload, dict(e.headers)
        try:
            return e.code, json.loads(payload.decode() or "{}"), dict(e.headers)
        except Exception:
            return e.code, {"raw": payload[:400].decode("utf-8", "replace")}, dict(e.headers)


def login(user, pw):
    st, js, _ = req("POST", "/api/auth/login", {"username": user, "password": pw})
    if st != 200 or js.get("code") != 0:
        return None, f"{user}: {st} {str(js)[:200]}"
    d = js.get("data") or {}
    return d.get("token") or d.get("accessToken"), None


def text_of(fmt, blob):
    """把导出物变成可搜索文本：pdf 直接 latin-1 扫，docx/xlsx 解 zip 里的 xml，html 原样。"""
    if fmt == "pdf":
        return blob.decode("latin-1", "ignore")
    if fmt in ("docx", "xlsx"):
        try:
            z = zipfile.ZipFile(io.BytesIO(blob))
            parts = []
            for n in z.namelist():
                if n.endswith(".xml"):
                    parts.append(z.read(n).decode("utf-8", "ignore"))
            return "\n".join(parts)
        except Exception as e:
            return f"__unzip_failed__{e}"
    return blob.decode("utf-8", "ignore")


def main():
    user = os.environ.get("QA_E2E_ADMIN") or "qa-e2e-admin"
    pw = os.environ.get("QA_E2E_PASSWORD") or ""
    token, err = login(user, pw)
    if not token:
        # 退一步用 admin
        token, err = login("admin", os.environ.get("ADMIN_PASSWORD", ""))
    if not token:
        print("LOGIN_FAIL", err)
        return

    st, js, _ = req("GET", "/api/projects", token=token)
    projects = (js.get("data") or {}).get("items") if isinstance(js.get("data"), dict) else js.get("data")
    if not isinstance(projects, list):
        print("PROJECTS_SHAPE", str(js)[:300])
        return
    print("projects:", [(p.get("id"), p.get("name")) for p in projects][:8])

    result = {"projects": [], "reports": []}
    for p in projects[:6]:
        pid = p.get("id")
        st, js, _ = req("GET", f"/api/test-reports?projectId={pid}", token=token)
        rows = js.get("data") or []
        if not isinstance(rows, list) or not rows:
            continue
        result["projects"].append({"id": pid, "name": p.get("name"), "reports": len(rows)})
        for r in rows[:2]:
            rid = r.get("id")
            entry = {
                "projectId": pid, "reportId": rid, "runId": r.get("runId"),
                "status": r.get("status"), "title": r.get("title"),
            }
            st, js2, _ = req("GET", f"/api/test-reports/{rid}?external=true", token=token)
            view = js2.get("data") if st == 200 else None
            entry["renderStatus"] = st
            if isinstance(view, dict):
                entry["viewKeys"] = sorted(view.keys())
                entry["viewKeyCount"] = len(view.keys())
                entry["hasMetadataKeys"] = [k for k in ("SW_BUILD", "QA_PROJECT", "DUT", "LINK", "CASE_ID", "RUN_ID") if k in view]
                entry["metadataSample"] = {k: view.get(k) for k in ("SW_BUILD", "QA_PROJECT", "DUT", "LINK") if k in view}
                entry["hasCaseList"] = any(k in view for k in ("cases", "caseList", "caseResults", "details"))
                entry["coverageRate"] = view.get("coverageRate")
                entry["caseCovered"] = view.get("caseCovered")
                entry["caseTotal"] = view.get("caseTotal")
                entry["failureDistribution"] = view.get("failureDistribution")
                entry["trendLen"] = len(view.get("trend") or [])
            else:
                entry["renderBody"] = str(js2)[:300]
            # 四格式导出
            entry["exports"] = {}
            for fmt in ("pdf", "html", "docx", "xlsx"):
                st3, blob, hdr = req("GET", f"/api/test-reports/{rid}/export?format={fmt}", token=token, raw=True)
                info = {"status": st3, "bytes": len(blob)}
                if st3 == 200:
                    t = text_of(fmt, blob)
                    info["has_CASE_ID"] = "CASE_ID" in t
                    info["has_RUN_ID"] = "RUN_ID" in t
                    info["has_SW_BUILD"] = "SW_BUILD" in t
                    info["has_caseCode"] = ("UEC" in t) or ("TC-" in t) or ("CASE-" in t)
                    info["textSample"] = t[:180].replace("\n", " ")
                else:
                    info["body"] = blob[:200].decode("utf-8", "replace")
                entry["exports"][fmt] = info
            # B 组：同一 run 的运行记录
            run_id = r.get("runId")
            if run_id:
                st4, js4, _ = req("GET", f"/api/execution/runs/{run_id}", token=token)
                if st4 == 200:
                    run = js4.get("data") or {}
                    entry["run"] = {k: run.get(k) for k in
                                    ("runId", "state", "totalTests", "passedTests", "failedTests",
                                     "skippedTests", "successRate") if k in run}
                else:
                    entry["run"] = {"status": st4, "body": str(js4)[:200]}
            result["reports"].append(entry)
            if len(result["reports"]) >= 5:
                break
        if len(result["reports"]) >= 5:
            break

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print("WROTE", OUT)
    for e in result["reports"]:
        print("-" * 70)
        print(f"report {e['reportId']} run {e['runId']} status={e['status']} keys={e.get('viewKeyCount')}")
        print("  metadata:", e.get("metadataSample"))
        print("  coverage:", e.get("coverageRate"), "covered/total:", e.get("caseCovered"), e.get("caseTotal"),
              "failDist:", e.get("failureDistribution"), "trend:", e.get("trendLen"))
        for fmt, inf in (e.get("exports") or {}).items():
            print(f"  {fmt}: {inf.get('status')} {inf.get('bytes')}B CASE_ID={inf.get('has_CASE_ID')} "
                  f"RUN_ID={inf.get('has_RUN_ID')} SW_BUILD={inf.get('has_SW_BUILD')} code={inf.get('has_caseCode')}")
        print("  run:", e.get("run"))


if __name__ == "__main__":
    main()
