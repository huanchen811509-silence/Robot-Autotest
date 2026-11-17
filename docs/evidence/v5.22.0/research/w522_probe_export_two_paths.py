#!/usr/bin/env python3
"""v5.22 调研 D 组：两条导出链路 + 撤回闸。

D1 run 级导出（POST /api/reports/{runId}/export，走 ExportService，带 detailRows）
D2 报告级导出（GET /api/test-reports/{id}/export，走模板白名单）
D3 撤回闸：report 状态 REVOKED 时外部导出是否被拒

会写导出记录与落盘文件（测试环境可接受），不改业务数据。
"""
import io
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import zipfile

BASE = "http://127.0.0.1:8081"
OUT = "/tmp/w522_d.json"
RUN_70 = "EHT-FAKE-1794805219801"   # project 95 / pytest / 明细 5 PASS 4 FAIL 61 SKIP
RUN_REVOKED = "w56-24-retry-2"      # 三份报告 20 DRAFT / 21,22 REVOKED


def req(method, path, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"} if data else {}
    if token:
        h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=180) as resp:
            b = resp.read()
            if raw:
                return resp.status, b, dict(resp.headers)
            return resp.status, json.loads(b or b"{}")
    except urllib.error.HTTPError as e:
        b = e.read()
        if raw:
            return e.code, b, dict(e.headers)
        try:
            return e.code, json.loads(b or b"{}")
        except Exception:
            return e.code, {"raw": b[:300].decode("utf-8", "replace")}


def login():
    u = os.environ.get("QA_E2E_ADMIN") or "qa-e2e-admin"
    p = os.environ.get("QA_E2E_PASSWORD", "")
    st, js = req("POST", "/api/auth/login", {"username": u, "password": p})
    d = js.get("data") or {}
    t = d.get("token") or d.get("accessToken")
    if not t:
        raise SystemExit(f"登录失败 {st}")
    return t


def xlsx_probe(blob):
    """xlsx 结构：sheet 名、每 sheet 行数、关键串。"""
    info = {"bytes": len(blob)}
    try:
        z = zipfile.ZipFile(io.BytesIO(blob))
        names = z.namelist()
        shared = z.read("xl/sharedStrings.xml").decode("utf-8", "ignore") if "xl/sharedStrings.xml" in names else ""
        wb = z.read("xl/workbook.xml").decode("utf-8", "ignore") if "xl/workbook.xml" in names else ""
        import re
        info["sheets"] = re.findall(r'<sheet name="([^"]+)"', wb)
        rows = {}
        for n in sorted(x for x in names if x.startswith("xl/worksheets/sheet")):
            xml = z.read(n).decode("utf-8", "ignore")
            rows[n.split("/")[-1]] = xml.count("<row ")
        info["rowCounts"] = rows
        info["sharedStringsLen"] = len(shared)
        info["hasSample"] = shared[:400].replace("\n", " ")
        # 明细判定：sheet 里出现「用例/编号/nodeId」等
        for kw in ("用例明细", "用例编号", "CASE", "nodeId", "失败原因"):
            info[f"has_{kw}"] = kw in shared or kw in wb
    except Exception as e:
        info["unzipError"] = str(e)
    return info


def pdf_probe(blob):
    t = blob.decode("latin-1", "ignore")
    return {"bytes": len(blob),
            "has_CASE_ID": "CASE_ID" in t,
            "has_RUN_ID": "RUN_ID" in t,
            "has_SW_BUILD": "SW_BUILD" in t,
            "has_DUT": "DUT" in t,
            "head": t[:120].replace("\n", " ")}


def main():
    token = login()
    out = {}

    # D1 run 级导出（xlsx / pdf）
    for fmt in ("xlsx", "pdf"):
        st, resp = req("POST",
                       f"/api/reports/{urllib.parse.quote(RUN_70)}/export?projectId=95&mode=external&format={fmt}",
                       token=token)
        e = {"status": st, "body": resp if st != 200 else None}
        if st == 200:
            e["result"] = {k: resp.get("data", {}).get(k) for k in
                           ("exportId", "format", "mode", "bytes", "fileName", "qualityGate")}
            dl = (resp.get("data") or {}).get("downloadUrl")
            e["downloadUrl"] = dl
            if dl:
                st2, blob, hdr = req("GET", dl, token=token, raw=True)
                e["downloadStatus"] = st2
                if st2 == 200:
                    e["probe"] = xlsx_probe(blob) if fmt == "xlsx" else pdf_probe(blob)
        out[f"D1_run_{fmt}"] = e

    # D2 报告级导出（report 22 REVOKED 仍可 GET 导出？）
    for rid in (22, 20):
        for fmt in ("xlsx", "pdf"):
            st, blob, hdr = req("GET", f"/api/test-reports/{rid}/export?format={fmt}", token=token, raw=True)
            e = {"status": st, "bytes": len(blob)}
            if st == 200:
                e["probe"] = xlsx_probe(blob) if fmt == "xlsx" else pdf_probe(blob)
            else:
                e["body"] = blob[:200].decode("utf-8", "replace")
            out[f"D2_report{rid}_{fmt}"] = e

    # D3 撤回闸：run 级 external 导出对 REVOKED 报告所在的 run
    for mode in ("external", "internal"):
        st, resp = req("POST",
                       f"/api/reports/{urllib.parse.quote(RUN_REVOKED)}/export?projectId=95&mode={mode}&format=pdf",
                       token=token)
        out[f"D3_{mode}"] = {"status": st,
                             "body": resp if st != 200 else {"bytes": (resp.get("data") or {}).get("bytes")}}

    with open(OUT, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=2, default=str)

    for k, v in out.items():
        print(f"\n=== {k} ===")
        print(json.dumps(v, ensure_ascii=False)[:900])
    print("\nfull ->", OUT)


if __name__ == "__main__":
    main()
