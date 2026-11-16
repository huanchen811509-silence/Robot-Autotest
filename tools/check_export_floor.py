#!/usr/bin/env python3
"""导出物健康机检（v5.19.0 / W69-07）。

为什么需要它：对外导出的 PDF 曾恒定 1,521 字节——看起来"导出成功"，实际只有表头与水印。
用户拿到文件才发现是空壳，而这条链路上没有任何自动化会在意"文件里有没有内容"。

**本脚本守的是"确定的坏"，不守"内容多不多"**：

| 判定 | 条件 | 理由 |
|---|---|---|
| 硬失败 | 5xx / 0 字节 / PDF 不是 `%PDF-` 头 / HTML 不是 `<!doctype` 或 `<html` | 这些一定是坏产物 |
| 放行 | 4xx + 明确拒导（空快照） | 拒导是正确行为，比给空壳好 |
| 观察项 | 字节数、是否含 metadata 字段 key | 现状对外视图只有 19 个汇总字段，**不含 metadata 与用例明细**（见 L-19-05），不作判据 |

内容量不足（对外导出件没有 DUT/LINK/SW_BUILD/RUN_ID 这些"对外报告必须能说清"的字段）
登记为 **L-19-05**：要改会牵动 `ReportFieldWhitelist` + 报告模板字段体系 + 四格式一致性机检，
需独立评估，不在 v5.19 内顺手改。

用法：
    python3 tools/check_export_floor.py
    python3 tools/check_export_floor.py --self-test
    python3 tools/check_export_floor.py --project 95 --json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("API_BASE", "http://127.0.0.1:8001")
USER = os.environ.get("QA_ADMIN", "admin")
PASSWORD = os.environ.get("QA_ADMIN_PASSWORD", "Admin@2026")

FORMATS = ("html", "docx", "xlsx", "pdf")
META_KEYS = ("CASE_ID", "RUN_ID", "DUT", "LINK", "SW_BUILD")


def call(path: str, method: str = "GET", body=None, token: str | None = None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        resp = urllib.request.urlopen(req, timeout=60)
        return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def login() -> str:
    status, body = call("/api/auth/login", "POST", {"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"[ERROR] 登录失败 {status}: {body[:160]!r}")
    return json.loads(body)["data"]["token"]


def judge(fmt: str, status: int, raw: bytes) -> str | None:
    """返回失败原因；None 表示通过。"""
    if status >= 500:
        return f"HTTP {status}（服务端错误，导出链路坏了）"
    if status >= 400:
        # 4xx：空快照/无权限等，属正确拒导
        return None
    if len(raw) == 0:
        return "导出 0 字节"
    if fmt == "pdf" and not raw.startswith(b"%PDF-"):
        return "不是合法 PDF（缺 %PDF- 头）"
    if fmt == "html" and not (b"<!doctype" in raw.lower() or b"<html" in raw.lower()):
        return "不是合法 HTML"
    if fmt in ("docx", "xlsx") and not raw.startswith(b"PK"):
        return "不是合法 OOXML（缺 PK 头）"
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", type=int, default=95)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--self-test", action="store_true", help="注入一次坏产物判定，断言判红")
    args = ap.parse_args()

    token = login()
    status, body = call(f"/api/test-reports?projectId={args.project}", token=token)
    if status != 200:
        print(f"[ERROR] 取测试报告列表失败 {status}")
        return 2
    reports = json.loads(body).get("data") or []
    if not reports:
        print(f"[SKIP] 项目 {args.project} 没有测试报告，无法验证导出物")
        return 0

    if args.self_test:
        fake = judge("pdf", 200, b"not a pdf")
        if fake is None:
            print("[FAIL] --self-test：非法 PDF 未被判红，检查逻辑失效")
            return 1
        print(f"[PASS] --self-test：非法产物判红（{fake}）")
        return 0

    rows = []
    problems = []
    for rep in reports:
        rid = rep["id"]
        for fmt in FORMATS:
            st, raw = call(f"/api/test-reports/{rid}/export?format={fmt}", token=token)
            reason = judge(fmt, st, raw)
            meta_hit = [k for k in META_KEYS if k.encode() in raw]
            rows.append({"id": rid, "format": fmt, "status": st, "size": len(raw),
                         "metaKeys": meta_hit, "rejected": 400 <= st < 500})
            if reason:
                problems.append(f"报告 {rid} 的 {fmt}: {reason}")

    if args.json:
        print(json.dumps({"project": args.project, "rows": rows, "problems": problems}, ensure_ascii=False, indent=2))
    else:
        for r in rows:
            tag = "拒导" if r["rejected"] else f"{r['size']}B"
            print(f"[{r['id']}/{r['format']}] {r['status']} {tag} metadata={r['metaKeys'] or '无'}")
        if not any(r["metaKeys"] for r in rows):
            print("[WARN] 全部导出件都不含 metadata 字段（CASE_ID/RUN_ID/DUT/LINK/SW_BUILD）——见 L-19-05")

    if problems:
        print(f"\n[FAIL] {len(problems)} 条：")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"[PASS] {len(rows)} 次导出均产出了合法产物（4xx 拒导视为正确）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
