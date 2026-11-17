#!/usr/bin/env python3
"""v5.22.0 / W2 验收探针：导出能力（明细 / PDF 中文 / 撤回闸）。

判据（PRD FR-02 / FR-03 / FR-04）：
- 02-1 新报告 xlsx 双 sheet，明细行数 = 明细条数 + 1（表头）
- 02-3 存量报告（无 cases）xlsx 明细 sheet 只有 1 行说明，且 200
- 03-1 PDF 提取文本含中文项目名与标题，字节数 ≥ 100KB，字体不是 Helvetica
- 04-1 REVOKED 四格式全部 409，错误体带 state
- 04-2 DRAFT 且 exportable=false：external 400（带 missing），internal 200

会临时建一份全选模板（跑完删）并新建一份报告；不改既有报告状态。
"""
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

BASE = "http://127.0.0.1:8081"
OUT = "/tmp/w522_w2.json"
PROJECT_ID = 95
RUN_PYTEST = "EHT-FAKE-1794805219801"
REVOKED_REPORT = 22
PARTIAL_REPORT = 20          # DRAFT + exportable=false（缺 STARTED_AT）
LEGACY_FROZEN_REPORT = 33    # DRAFT + exportable=true，快照无 cases（W72-03 之前创建）
TEMP_TEMPLATE = "W72-FULL-ACCEPT-W2"
KEYS = [
    "reportId", "title", "version", "frozenAt", "conclusion",
    "passCount", "failCount", "skipCount", "passRate", "caseTotal",
    "projectName", "swBuild", "caseKind",
    "qaProject", "dut", "link", "caseId", "runId",
    "startedAt", "finishedAt", "disclaimer", "watermark",
]


def req(method, path, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=300) as resp:
            blob = resp.read()
            if raw:
                return resp.status, blob, dict(resp.headers)
            return resp.status, json.loads(blob or b"{}")
    except urllib.error.HTTPError as e:
        blob = e.read()
        if raw:
            return e.code, blob, dict(e.headers)
        try:
            return e.code, json.loads(blob or b"{}")
        except Exception:
            return e.code, {"raw": blob[:300].decode("utf-8", "replace")}


def login():
    user = os.environ.get("QA_E2E_ADMIN") or "qa-e2e-admin"
    pwd = os.environ.get("QA_E2E_PASSWORD", "")
    status, body = req("POST", "/api/auth/login", {"username": user, "password": pwd})
    token = (body.get("data") or {}).get("token") or (body.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"登录失败 {status} {body}")
    return token


def export(token, report_id, fmt, mode="external"):
    path = f"/api/test-reports/{report_id}/export?format={fmt}"
    if mode != "external":
        path += f"&mode={mode}"
    return req("GET", path, token=token, raw=True)


def xlsx_shape(blob):
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        wb = z.read("xl/workbook.xml").decode("utf-8", "replace")
        sheets = [chunk.split('name="')[1].split('"')[0]
                  for chunk in wb.split("<sheet ") if "name=" in chunk]
        rows = {}
        for name in sorted(z.namelist()):
            if name.startswith("xl/worksheets/sheet") and name.endswith(".xml"):
                data = z.read(name).decode("utf-8", "replace")
                rows[name.rsplit("/", 1)[-1]] = data.count("<row ")
        return {"bytes": len(blob), "sheets": sheets, "worksheetRows": rows}


def pdf_shape(blob):
    info = {"bytes": len(blob)}
    try:
        import unicodedata
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(blob))
        info["pages"] = len(reader.pages)
        text = "\n".join((p.extract_text() or "") for p in reader.pages)
        info["textHead"] = text[:300]
        # 中文字体（Noto CJK）会把部分字符映射成康熙部首/兼容汉字（如 ⽂ / ⼀），
        # 直接子串比会假阴性；先 NFKC 归一化再去空格。
        norm = unicodedata.normalize("NFKC", text).replace(" ", "")
        info["hasChinese"] = any("\u4e00" <= ch <= "\u9fff" for ch in norm)
        for label, probe in (("hasProjectName", "EHTpytest用例库工程"),
                             ("hasQaProject", "EHT-PYTEST-LIB"),
                             ("hasDut", "EHT-TESTBED-01"),
                             ("hasLink", "NIC-RDMA-P0")):
            info[label] = probe in norm
    except Exception as e:  # pypdf 缺失或 PDF 损坏
        info["error"] = f"{type(e).__name__}: {e}"
    return info


def setup_full_template(token):
    st, body = req("GET", f"/api/report-templates?projectId={PROJECT_ID}", token=token)
    templates = body.get("data") or []
    original_default = next((t["id"] for t in templates if t.get("isDefault")), None)
    payload = {
        "projectId": PROJECT_ID, "name": TEMP_TEMPLATE,
        "description": "v5.22.0 W2 验收临时模板，探针跑完即删",
        "fields": [{"fieldKey": k, "titleOverride": None} for k in KEYS],
        "kernel": "pytest",
    }
    st, body = req("POST", "/api/report-templates", payload, token=token)
    if st != 200:
        raise SystemExit(f"建临时模板失败 {st} {body}")
    temp_id = (body.get("data") or {}).get("id")
    req("PUT", f"/api/report-templates/{temp_id}/default?projectId={PROJECT_ID}", None, token=token)
    return temp_id, original_default


def main():
    token = login()
    result = {"projectId": PROJECT_ID, "runId": RUN_PYTEST}

    temp_id, original_default = setup_full_template(token)
    result["tempTemplateId"] = temp_id
    result["originalDefaultTemplateId"] = original_default
    try:
        st, body = req("POST", "/api/test-reports",
                       {"projectId": PROJECT_ID, "runId": RUN_PYTEST,
                        "title": "W72 导出能力验收"}, token=token)
        if st != 200:
            raise SystemExit(f"建报告失败 {st} {body}")
        new_id = (body.get("data") or {}).get("id")
        result["newReportId"] = new_id

        st, view_body = req("GET", f"/api/test-reports/{new_id}?external=true", token=token)
        view = view_body.get("data") or {}
        result["newReportView"] = {"status": st, "keyCount": len(view),
                                   "orderOk": list(view.keys()) == KEYS}

        st, blob, _ = export(token, new_id, "xlsx")
        result["xlsx"] = {"status": st, **xlsx_shape(blob)} if st == 200 else {"status": st,
                                                                             "raw": blob[:200].decode("utf-8", "replace")}
        st, blob, _ = export(token, new_id, "pdf")
        result["pdf"] = {"status": st, **pdf_shape(blob)} if st == 200 else {
            "status": st, "raw": blob[:200].decode("utf-8", "replace")}
    finally:
        if original_default:
            req("PUT", f"/api/report-templates/{original_default}/default?projectId={PROJECT_ID}",
                None, token=token)
        req("DELETE", f"/api/report-templates/{temp_id}?projectId={PROJECT_ID}", None, token=token)

    # —— 撤回闸：REVOKED 四格式全 409
    result["revoked"] = {}
    for fmt in ("pdf", "html", "docx", "xlsx"):
        st, blob, _ = export(token, REVOKED_REPORT, fmt)
        item = {"status": st}
        if st != 200:
            try:
                item["body"] = json.loads(blob)
            except Exception:
                item["raw"] = blob[:120].decode("utf-8", "replace")
        else:
            item["bytes"] = len(blob)
        result["revoked"][fmt] = item

    # —— metadata 缺项：external 400 / internal 200
    st_ext, blob_ext, _ = export(token, PARTIAL_REPORT, "xlsx", "external")
    ext = {"status": st_ext}
    try:
        ext["body"] = json.loads(blob_ext)
    except Exception:
        ext["bytes"] = len(blob_ext)
    st_int, blob_int, _ = export(token, PARTIAL_REPORT, "xlsx", "internal")
    result["metadataGate"] = {
        "external": ext,
        "internal": {"status": st_int, "bytes": len(blob_int),
                     **({"shape": xlsx_shape(blob_int)} if st_int == 200 else {})},
    }

    # —— 存量报告（无 cases）：明细 sheet 仍出，且有说明行
    st, blob, _ = export(token, LEGACY_FROZEN_REPORT, "xlsx")
    legacy = {"status": st}
    if st == 200:
        legacy.update(xlsx_shape(blob))
    else:
        legacy["raw"] = blob[:200].decode("utf-8", "replace")
    result["legacyNoCases"] = legacy

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)

    n = result["newReportView"]
    print(f"[新报告 {result['newReportId']}] external {n['keyCount']} 键 / 顺序={n['orderOk']}")
    print(f"[xlsx] {result['xlsx']}")
    p = result.get("pdf", {})
    print(f"[pdf] status={p.get('status')} bytes={p.get('bytes')} pages={p.get('pages')} "
          f"中文={p.get('hasChinese')} 项目名={p.get('hasProjectName')} "
          f"QA项目={p.get('hasQaProject')} DUT={p.get('hasDut')} LINK={p.get('hasLink')}")
    if p.get("textHead"):
        print("  text:", p["textHead"][:120].replace("\n", " / "))
    print("[revoked]", {k: v.get("status") for k, v in result["revoked"].items()})
    print(f"[metadata] external={result['metadataGate']['external'].get('status')} "
          f"internal={result['metadataGate']['internal'].get('status')}")
    print(f"[存量无 cases] {result['legacyNoCases']}")
    print(f"JSON: {OUT}")


if __name__ == "__main__":
    main()
