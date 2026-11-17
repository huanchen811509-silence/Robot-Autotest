#!/usr/bin/env python3
"""v5.22.0 / W2 补充探针：四格式字段集一致 / PDF 嵌入字体 / PDF↔xlsx 一致 / ARCHIVED 闸。

补的是主探针 `w522_probe_export_w2.py` 没覆盖的四条判据：

- TC-22-06（PRD 01-6 延伸）四种导出格式字段集与视图一致，无第三种口径
- TC-22-13 PDF 不是 Helvetica（本机没有 pdffonts / pdfinfo，改读 PDF 字节里的 /BaseFont）
- TC-22-15 PDF 与 xlsx 的计数、metadata 完全一致
- TC-22-18 ARCHIVED 报告同样 409

副作用：把一份 DRAFT 对照样本（默认 36）归档为 ARCHIVED。归档是本探针要验的状态，
跑完不还原（库内已无其它 ARCHIVED 报告，这份正好留作长期对照）。
"""
import io
import json
import os
import re
import sys
import unicodedata
import urllib.error
import urllib.request
import zipfile

BASE = "http://127.0.0.1:8081"
OUT = "/home/evergreen/robot-autotest/docs/evidence/v5.22.0/w522_w2b.json"
TARGET_REPORT = int(os.environ.get("W522_TARGET", "37"))   # 全选模板产的 22 键对外报告
ARCHIVE_REPORT = int(os.environ.get("W522_ARCHIVE", "36"))  # 归档用的对照样本
FORMATS = ("xlsx", "pdf", "html", "docx")

# 4 个 v5.22.0 删掉的假值字段：键名与中文标签都不许再出现
FAKE_KEYS = ["coverageRate", "caseCovered", "failureDistribution", "trend"]
FAKE_LABELS = ["覆盖率", "用例覆盖", "失败分布", "趋势"]

# 长文本字段在 PDF/HTML 里会因为换行被切断，不参与"值命中"判定
LONG_FIELDS = {"disclaimer"}


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
    if not pwd:
        raise SystemExit("缺少 QA_E2E_PASSWORD")
    st, body = req("POST", "/api/auth/login", {"username": user, "password": pwd})
    token = (body.get("data") or {}).get("token") or (body.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"登录失败 {st} {body}")
    return token


def norm(s):
    """NFKC + 去所有空白：Noto CJK 会把部分汉字映射成康熙部首，直接子串比会假阴性。"""
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", s or ""))


# —— 各格式的可见文本提取 -------------------------------------------------

def text_xlsx(blob):
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".xml"))
        parts = []
        for n in names:
            if n.startswith("xl/sharedStrings") or n.startswith("xl/worksheets/"):
                parts.append(z.read(n).decode("utf-8", "replace"))
        joined = "\n".join(parts)
    # 只留标签之间的文本，去掉 XML 结构
    return re.sub(r"<[^>]+>", " ", joined)


def text_pdf(blob):
    from pypdf import PdfReader
    reader = PdfReader(io.BytesIO(blob))
    return "\n".join((p.extract_text() or "") for p in reader.pages)


def text_html(blob):
    raw = blob.decode("utf-8", "replace")
    raw = re.sub(r"(?is)<(script|style).*?</\1>", " ", raw)
    return re.sub(r"<[^>]+>", " ", raw)


def text_docx(blob):
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        xml = z.read("word/document.xml").decode("utf-8", "replace")
    return re.sub(r"<[^>]+>", " ", xml)


EXTRACTORS = {"xlsx": text_xlsx, "pdf": text_pdf, "html": text_html, "docx": text_docx}


def pdf_fonts(blob):
    """本机没有 pdffonts / pdfinfo，用 pypdf 走对象解析拿字体信息。

    两个坑（首版都踩了，记下来免得下次重犯）：

    1. 不能对 PDF 字节做正则：字体字典在 /ObjStm 压缩对象流里，latin-1 明文
       读不到 `/BaseFont`，会误判成「一个字体都没有」。
    2. **不能拿「有没有 /FontFile2」当判据**：headless Chrome 的 --print-to-pdf
       把字形摊成 **Type3**（/Subtype /Type3 + /CharProcs 矢量绘制），
       Type3 按规范就没有 /BaseFont、也没有 /FontFile*，但字是真的，
       文本照样可提取（FontFamily 记在 /FontDescriptor 里 = Noto Sans CJK SC）。
    """
    from pypdf import PdfReader
    reader = PdfReader(io.BytesIO(blob))
    found = {}
    for page in reader.pages:
        res = (page.get("/Resources") or {})
        res = res.get_object() if hasattr(res, "get_object") else res
        fonts = res.get("/Font") or {}
        fonts = fonts.get_object() if hasattr(fonts, "get_object") else fonts
        for name, ref in (fonts or {}).items():
            f = ref.get_object() if hasattr(ref, "get_object") else ref
            desc = f.get("/FontDescriptor")
            # Type0（CID）字体把描述符挂在后代字体上
            for d in (f.get("/DescendantFonts") or []):
                d = d.get_object() if hasattr(d, "get_object") else d
                if d.get("/FontDescriptor"):
                    desc = d.get("/FontDescriptor")
            dd = desc.get_object() if hasattr(desc, "get_object") else (desc or {})
            embedded = any(k in dd for k in ("/FontFile", "/FontFile2", "/FontFile3"))
            found[str(name)] = {
                "subtype": str(f.get("/Subtype") or ""),
                "baseFont": str(f.get("/BaseFont") or ""),
                "fontFamily": str(dd.get("/FontFamily") or ""),
                "embedded": embedded,
            }
    subtypes = sorted({v["subtype"] for v in found.values()})
    families = sorted({v["fontFamily"] for v in found.values() if v["fontFamily"]})
    bases = sorted({v["baseFont"] for v in found.values() if v["baseFont"]})
    blob_has_helvetica = b"Helvetica" in blob
    return {
        "fontCount": len(found),
        "subtypes": subtypes,
        "fontFamilies": families,
        "baseFonts": bases,
        "sampled": dict(list(found.items())[:3]),
        "hasHelvetica": any("Helvetica" in x for x in families + bases) or blob_has_helvetica,
        "embeddedFontFileCount": sum(1 for v in found.values() if v["embedded"]),
        "type3Count": sum(1 for v in found.values() if v["subtype"] == "/Type3"),
        "hasCjkFamily": any(("Noto" in x or "CJK" in x or "SourceHan" in x or "SimSun" in x)
                            for x in families + bases),
    }


def field_hits(fmt, text, view):
    """逐字段判「值是否出现在产物里」。短值才判，长文本与空值跳过。"""
    n = norm(text)
    hit, miss, skip = [], [], []
    for k, v in view.items():
        s = "" if v is None else str(v)
        if k in LONG_FIELDS or len(s.strip()) < 2:
            skip.append(k)
            continue
        (hit if norm(s) and norm(s) in n else miss).append(k)
    return {"hit": hit, "miss": miss, "skip": skip,
            "hitRate": f"{len(hit)}/{len(hit) + len(miss)}"}


def main():
    token = login()
    result = {"targetReport": TARGET_REPORT, "archiveReport": ARCHIVE_REPORT}

    st, body = req("GET", f"/api/test-reports/{TARGET_REPORT}?external=true", token=token)
    view = body.get("data") or {}
    result["view"] = {"status": st, "keyCount": len(view), "keys": list(view.keys())}
    if st != 200 or not view:
        raise SystemExit(f"取视图失败 {st} {body}")

    # —— 四种格式：字段集一致 + 假值缺席
    result["formats"] = {}
    texts = {}
    for fmt in FORMATS:
        st, blob, _ = req("GET", f"/api/test-reports/{TARGET_REPORT}/export?format={fmt}",
                          token=token, raw=True)
        item = {"status": st, "bytes": len(blob)}
        if st == 200:
            try:
                text = EXTRACTORS[fmt](blob)
                texts[fmt] = text
                item.update(field_hits(fmt, text, view))
                flat = norm(text)
                item["fakeKeysPresent"] = [k for k in FAKE_KEYS if k in flat]
                item["fakeLabelsPresent"] = [k for k in FAKE_LABELS if k in flat]
            except Exception as e:
                item["extractError"] = f"{type(e).__name__}: {e}"
        else:
            item["raw"] = blob[:200].decode("utf-8", "replace")
        result["formats"][fmt] = item

    # —— PDF 字体
    if "pdf" in result["formats"] and result["formats"]["pdf"]["status"] == 200:
        st, blob, _ = req("GET", f"/api/test-reports/{TARGET_REPORT}/export?format=pdf",
                          token=token, raw=True)
        result["pdfFonts"] = pdf_fonts(blob)

    # —— PDF 与 xlsx 的计数 / metadata 一致性
    if "pdf" in texts and "xlsx" in texts:
        pairs = {}
        for k in ("passCount", "failCount", "skipCount", "passRate", "caseTotal",
                  "projectName", "qaProject", "dut", "link", "caseId", "runId", "swBuild"):
            if k not in view:
                continue
            s = norm(str(view[k] or ""))
            if not s:
                continue
            pairs[k] = {"value": str(view[k]),
                        "inPdf": s in norm(texts["pdf"]),
                        "inXlsx": s in norm(texts["xlsx"])}
        result["pdfVsXlsx"] = {
            "pairs": pairs,
            "consistent": all(v["inPdf"] == v["inXlsx"] for v in pairs.values()),
            "bothHit": sum(1 for v in pairs.values() if v["inPdf"] and v["inXlsx"]),
            "total": len(pairs),
        }

    # —— ARCHIVED 闸：状态机不许 DRAFT 直跳 ARCHIVED（实测 409），必须先冻结。
    # 归档是终态、不还原，样本已归档时两个 POST 都会 409（"ARCHIVED -> ARCHIVED"），
    # 这不是失败，脚本要能重跑。判据不看这两个 POST，只看导出闸的返回。
    # 注意：GET 视图里的 `data.state` 是 **run 的执行结论**（failed/passed），
    # 不是报告状态——首版按它判「是否已归档」判错了，报告状态以 test_report.state 为准。
    st_frz, body_frz = req("POST", f"/api/test-reports/{ARCHIVE_REPORT}/freeze", None, token=token)
    result["freeze"] = {"status": st_frz, "body": body_frz}
    st_arch, body_arch = req("POST", f"/api/test-reports/{ARCHIVE_REPORT}/archive", None,
                             token=token)
    result["archive"] = {"status": st_arch, "body": body_arch}
    result["archivedExport"] = {}
    for fmt in FORMATS:
        st, blob, _ = req("GET", f"/api/test-reports/{ARCHIVE_REPORT}/export?format={fmt}",
                          token=token, raw=True)
        item = {"status": st}
        if st != 200:
            try:
                b = json.loads(blob)
                item["state"] = (b.get("data") or {}).get("state")
                item["message"] = b.get("message")
            except Exception:
                item["raw"] = blob[:160].decode("utf-8", "replace")
        result["archivedExport"][fmt] = item

    # TC-22-18 判据：四格式全 409 且 body 的 state 是 ARCHIVED
    result["archivedGateOk"] = all(
        v.get("status") == 409 and v.get("state") == "ARCHIVED"
        for v in result["archivedExport"].values()
    ) and len(result["archivedExport"]) == len(FORMATS)

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    # —— 控制台摘要
    print(f"[view] keys={result['view']['keyCount']}")
    for fmt, item in result["formats"].items():
        print(f"[{fmt}] status={item['status']} bytes={item['bytes']} "
              f"hit={item.get('hitRate')} miss={item.get('miss')} "
              f"fake={item.get('fakeKeysPresent')}/{item.get('fakeLabelsPresent')}")
    if "pdfFonts" in result:
        print(f"[pdfFonts] {result['pdfFonts']}")
    if "pdfVsXlsx" in result:
        p = result["pdfVsXlsx"]
        print(f"[pdfVsXlsx] consistent={p['consistent']} bothHit={p['bothHit']}/{p['total']} "
              f"diff={[k for k, v in p['pairs'].items() if v['inPdf'] != v['inXlsx']]}")
    print(f"[freeze] {result['freeze']['status']} "
          f"{(result['freeze']['body'] or {}).get('message', '')}")
    print(f"[archive] {result['archive']['status']} "
          f"{(result['archive']['body'] or {}).get('message', '')} "
          f"(409+ARCHIVED->ARCHIVED 表示样本已归档，可重跑)")
    print(f"[archivedExport] " + " ".join(f"{k}={v['status']}"
                                          for k, v in result["archivedExport"].items())
          + f" gateOk={result['archivedGateOk']}")
    print(f"[wr] {OUT}")


if __name__ == "__main__":
    main()
