#!/usr/bin/env python3
"""v5.22.0 / W72-01 + W72-02 验收探针：对外视图字段集（真实链路）。

**为什么不能只断言「report 22 有 22 键」**：对外视图的键集 = 快照内模板字段 ∩ 白名单，
模板是冻结时的用户选择。report 22 的模板冻结在旧 21 字段版本，删掉 4 个假值后只剩 17 项，
这是「冻结」语义的正确结果，不是缺陷。所以本探针分两段：

A. 全选模板 → 新建报告 → 必须恰好 22 键、顺序与 C5 文档 §2 一致（判据 01-1 / 02-1）；
B. 存量报告 22 → 不报错、四个假值键已消失、projectName 是真名（判据 02-2 / 02-3）。

会临时建一份「W72-FULL-ACCEPT」模板并设为默认，跑完还原原默认模板并删除临时模板。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081"
OUT = "/tmp/w522_w1_fields.json"

PROJECT_ID = 95
RUN_PYTEST = "EHT-FAKE-1794805219801"
EXISTING_REPORT = 22
TEMP_TEMPLATE_NAME = "W72-FULL-ACCEPT"
FAKE_KEYS = ["caseCovered", "coverageRate", "failureDistribution", "trend"]
EXPECTED_ORDER = [
    "reportId", "title", "version", "frozenAt", "conclusion",
    "passCount", "failCount", "skipCount", "passRate", "caseTotal",
    "projectName", "swBuild", "caseKind",
    "qaProject", "dut", "link", "caseId", "runId",
    "startedAt", "finishedAt", "disclaimer", "watermark",
]


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except Exception:
            return e.code, {"raw": raw[:300].decode("utf-8", "replace")}


def login():
    user = os.environ.get("QA_E2E_ADMIN") or "qa-e2e-admin"
    pwd = os.environ.get("QA_E2E_PASSWORD", "")
    status, body = req("POST", "/api/auth/login", {"username": user, "password": pwd})
    token = (body.get("data") or {}).get("token") or (body.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"登录失败 {status} {body}")
    return token


def external_view(token, report_id):
    status, body = req("GET", f"/api/test-reports/{report_id}?external=true", token=token)
    return status, (body.get("data") or {})


def main():
    token = login()
    result = {"projectId": PROJECT_ID, "runId": RUN_PYTEST}

    # —— 0) 可选字段清单本身（判据 01-1 的前半：清单必须是 22 项）
    st, body = req("GET", "/api/report-templates/fields", token=token)
    fields = body.get("data") or []
    result["selectableFields"] = {
        "status": st, "count": len(fields), "fields": fields,
        "matchesDoc": fields == EXPECTED_ORDER,
    }

    # —— 记录原默认模板，便于还原
    st, body = req("GET", f"/api/report-templates?projectId={PROJECT_ID}", token=token)
    templates = body.get("data") or []
    original_default = next((t["id"] for t in templates if t.get("isDefault")), None)
    result["originalDefaultTemplateId"] = original_default

    # —— A) 建全选模板并设为默认
    payload = {
        "projectId": PROJECT_ID,
        "name": TEMP_TEMPLATE_NAME,
        "description": "v5.22.0 W72 验收临时模板，探针跑完即删",
        "fields": [{"fieldKey": key, "titleOverride": None} for key in EXPECTED_ORDER],
        "kernel": "pytest",
    }
    st, body = req("POST", "/api/report-templates", payload, token=token)
    if st != 200:
        result["tempTemplateError"] = {"status": st, "body": body}
        print(json.dumps(result, ensure_ascii=False, indent=2))
        sys.exit(1)
    temp_id = (body.get("data") or {}).get("id")
    result["tempTemplateId"] = temp_id
    st_def, _ = req("PUT", f"/api/report-templates/{temp_id}/default?projectId={PROJECT_ID}",
                    None, token=token)
    result["setDefaultStatus"] = st_def

    try:
        st, body = req("POST", "/api/test-reports",
                       {"projectId": PROJECT_ID, "runId": RUN_PYTEST,
                        "title": "W72 字段收口验收（全选模板）"}, token=token)
        if st != 200:
            result["createError"] = {"status": st, "body": body}
            print(json.dumps(result, ensure_ascii=False, indent=2))
            sys.exit(1)
        new_id = (body.get("data") or {}).get("id")
        result["newReportId"] = new_id

        st, view = external_view(token, new_id)
        keys = list(view.keys())
        result["newReport"] = {
            "status": st,
            "keyCount": len(keys),
            "keys": keys,
            "orderMatchesDoc": keys == EXPECTED_ORDER,
            "fakeKeysPresent": [k for k in FAKE_KEYS if k in view],
            "projectName": view.get("projectName"),
            "qaProject": view.get("qaProject"),
            "dut": view.get("dut"),
            "link": view.get("link"),
            "caseId": view.get("caseId"),
            "runId": view.get("runId"),
            "swBuild": view.get("swBuild"),
            "caseKind": view.get("caseKind"),
            "missingExpected": [k for k in EXPECTED_ORDER if k not in view],
        }
    finally:
        if original_default:
            req("PUT", f"/api/report-templates/{original_default}/default?projectId={PROJECT_ID}",
                None, token=token)
            result["restoredDefault"] = original_default
        st_del, _ = req("DELETE", f"/api/report-templates/{temp_id}?projectId={PROJECT_ID}",
                        None, token=token)
        result["deleteTempStatus"] = st_del

    # —— B) 存量报告：冻结模板裁剪后仍可用
    st2, old = external_view(token, EXISTING_REPORT)
    old_keys = list(old.keys())
    result["existingReport"] = {
        "id": EXISTING_REPORT,
        "status": st2,
        "keyCount": len(old_keys),
        "keys": old_keys,
        "fakeKeysPresent": [k for k in FAKE_KEYS if k in old],
        "projectName": old.get("projectName"),
        "qaProject": old.get("qaProject"),
        "dut": old.get("dut"),
        "link": old.get("link"),
        "caseId": old.get("caseId"),
        "runId": old.get("runId"),
    }

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)

    sf = result["selectableFields"]
    n = result["newReport"]
    e = result["existingReport"]
    print(f"[清单] /fields = {sf['count']} 项，与 C5 文档顺序一致={sf['matchesDoc']}")
    print(f"[新报告 {result['newReportId']}] {n['keyCount']} 键 / 顺序符合文档={n['orderMatchesDoc']} / "
          f"假值残留={n['fakeKeysPresent'] or '无'}")
    print(f"        projectName={n['projectName']!r} qaProject={n['qaProject']!r} "
          f"dut={n['dut']!r} link={n['link']!r}")
    print(f"        caseId={n['caseId']!r} runId={n['runId']!r} 缺失={n['missingExpected'] or '无'}")
    print(f"[存量 {EXISTING_REPORT}] {e['keyCount']} 键（冻结模板裁剪）/ "
          f"假值残留={e['fakeKeysPresent'] or '无'} / projectName={e['projectName']!r}")
    print(f"[还原] 默认模板 -> {result.get('restoredDefault')} / 删临时模板={result.get('deleteTempStatus')}")
    print(f"JSON: {OUT}")


if __name__ == "__main__":
    main()
