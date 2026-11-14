#!/usr/bin/env python3
"""W57-55 · 前后端请求体字段对账机检。

**为什么需要它**：v5.7.0 首版有 3 处前端字段名与后端 record 不一致
（``token`` / ``setIds`` / ``caseId``），后端没开严格校验就把未知字段静默忽略，
于是「界面点了没效果但也不报错」。55 步门禁的 API 契约快照只比对端点与路径，
比对不到**请求体字段名**，这三处全部逃过。

规则（只对能定位到后端端点的调用生效）：

  1. 前端 ``body: JSON.stringify({...})`` 的每个字面量键，必须出现在后端
     ``@RequestBody`` record 的组件名里；多出来的键判红（后端会忽略它，等于没传）。
  2. 后端字段比前端多**不判红**：可选字段、服务端默认值都属于正常。
  3. ``body: JSON.stringify(payload)`` 的变量入参：若该变量在**同文件**有 ``interface`` /
     ``type`` 声明，按其字段集合对账（调用方必须满足该类型，它就是事实上的契约）；
     连类型都找不到的才记 ``SKIP`` 并打印——**不静默跳过**，读数里必须看得见。
  4. 变量入参（走类型声明）只对**必填字段**判红：可选字段是「调用方可以不传」，
     强制要求后端存在会把正常代码判成错。可选字段不匹配时打 ``WARN`` 并计入读数。
  5. 找不到后端对应端点记 ``UNMATCHED``，同样打印；这类说明对账没覆盖到，
     不能当成 PASS 面积。

输出固定一行：``FE_CONTRACT_RESULT PASS=n FAIL=n SKIP=n UNMATCHED=n``

``--self-test`` 是**真注入**：在临时副本里把一个前端键改成后端不存在的名字，
必须判红；还原后必须判绿。全程不写真实源码。
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend" / "src" / "main" / "java"
# 扫描整棵前端源码，不只 api/：向导的 setIds 就写在 views/*.vue 里，
# 只扫 api/ 会漏掉整类缺陷（这也是 v5.7.0 首版漏检的原因之一）。
FRONTEND_API = ROOT / "frontend" / "src"

MAPPING_RE = re.compile(r"@(Post|Put|Patch)Mapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
CLASS_MAPPING_RE = re.compile(r"@RequestMapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
RECORD_RE = re.compile(r"\brecord\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
BODY_RE = re.compile(r"@RequestBody\b[^,)]*?\s+([A-Za-z_][A-Za-z0-9_.]*)\s+[a-zA-Z_]")
# 只定位「request<...>(url,」这一段；options 对象用括号配平取，
# 不能用 \{...\} 非贪婪——嵌套的 JSON.stringify({...}) 会在内层 } 处被截断，
# 导致键集合为空、任何错误字段都判绿（自检正是这么发现的）。
REQUEST_HEAD_RE = re.compile(
    r"request\s*(?:<[^;]*?>)?\s*\(\s*(`[^`]*`|'[^']*'|\"[^\"]*\")\s*,\s*",
    re.S,
)
METHOD_RE = re.compile(r"method\s*:\s*'([A-Z]+)'")
STRINGIFY_RE = re.compile(r"body\s*:\s*JSON\.stringify\s*\(", re.S)

# 前端路径里的已知前缀函数：base(projectId) -> /projects/{}
KNOWN_PREFIXES = {"base": "/projects/{}"}


# ---------------------------------------------------------------- 工具


def strip_comments(text: str, java: bool = False) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    if java:
        return re.sub(r"//[^\n]*", "", text)
    return re.sub(r"(?<!:)//[^\n]*", "", text)


def balanced(text: str, open_idx: int, open_ch: str = "(", close_ch: str = ")") -> int:
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return -1


def split_top_level(text: str) -> list[str]:
    parts, depth, current = [], 0, []
    for ch in text:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    tail = "".join(current)
    if tail.strip():
        parts.append(tail)
    return parts


def normalize_path(raw: str) -> str:
    """把两端路径归一成同样的形状：去 /api 前缀、变量与插值统一成 {}。"""
    path = raw.strip()
    path = path.split("?")[0]
    for fn, prefix in KNOWN_PREFIXES.items():
        path = re.sub(r"\$\{\s*%s\s*\([^)]*\)\s*\}" % fn, prefix, path)
    path = re.sub(r"\$\{[^}]*\}", "{}", path)
    path = re.sub(r"\{[^}]*\}", "{}", path)
    if path.startswith("/api/"):
        path = path[4:]
    elif path == "/api":
        path = "/"
    path = re.sub(r"/+$", "", path)
    return path or "/"


# ---------------------------------------------------------------- 后端索引


def record_components(text: str, name: str, start: int) -> list[str] | None:
    match = None
    for candidate in RECORD_RE.finditer(text, start):
        if candidate.group(1) == name:
            match = candidate
            break
    if match is None:
        return None
    open_idx = match.end() - 1
    close_idx = balanced(text, open_idx)
    if close_idx < 0:
        return None
    body = text[open_idx + 1:close_idx]
    fields = []
    for part in split_top_level(body):
        part = re.sub(r"@\w+(\([^)]*\))?", " ", part).strip()
        part = part.split("//")[0].strip()
        if not part:
            continue
        # 取最后一段标识符：`List<Long> caseIds` / `long planId` / `String name`
        found = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", part)
        if found:
            fields.append(found[-1])
    return fields


def build_backend_index() -> dict[tuple[str, str], tuple[str, list[str], str]]:
    """(method, normalized_path) -> (recordName, fields, source)"""
    index: dict[tuple[str, str], tuple[str, list[str], str]] = {}
    for java in BACKEND.rglob("*Controller.java"):
        text = strip_comments(java.read_text(encoding="utf-8"), java=True)
        class_match = CLASS_MAPPING_RE.search(text)
        prefix = class_match.group(1) if class_match else ""
        for mapping in MAPPING_RE.finditer(text):
            verb = mapping.group(1).upper()
            sub = mapping.group(2)
            # 只在**本方法签名**里找 @RequestBody：原先取 800 字符尾窗，会把下一个方法的
            # 参数当成这一个的（实测把 /notifications/read-all 认成了 PreferenceRequest）。
            body_open = text.find("{", mapping.end())
            if body_open < 0:
                continue
            signature = text[mapping.end():body_open]
            body_match = BODY_RE.search(signature)
            if body_match is None:
                continue
            type_name = body_match.group(1).split(".")[-1]
            fields = record_components(text, type_name, 0)
            if fields is None:
                # 同包其它文件里的 record
                for other in java.parent.glob("*.java"):
                    other_text = strip_comments(other.read_text(encoding="utf-8"), java=True)
                    fields = record_components(other_text, type_name, 0)
                    if fields is not None:
                        break
            if fields is None:
                continue
            full = normalize_path(prefix.rstrip("/") + "/" + sub.lstrip("/"))
            index[(verb, full)] = (type_name, fields, java.name)
    return index


# ---------------------------------------------------------------- 前端索引


def object_keys(text: str, open_idx: int) -> tuple[list[str], bool]:
    close_idx = balanced(text, open_idx, "{", "}")
    if close_idx < 0:
        return [], False
    body = text[open_idx + 1:close_idx]
    keys, has_spread = [], False
    for part in split_top_level(body):
        part = part.strip()
        if not part:
            continue
        if part.startswith("..."):
            has_spread = True
            continue
        match = re.match(r"(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_$][\w$]*))\s*(?::|$)", part)
        if match:
            keys.append(next(g for g in match.groups() if g))
        else:
            has_spread = True
    return keys, has_spread


INTERFACE_RE = re.compile(r"(?:interface|type)\s+([A-Za-z_$][\w$]*)\s*(?:=\s*)?\{")


def interface_fields(text: str, name: str) -> tuple[list[str], bool] | None:
    """取同文件里 interface / type 字面量的顶层字段。

    返回 (字段名列表, 是否有索引签名 / 交叉类型等无法穷举的写法)；
    找不到返回 None。
    """
    match = None
    for candidate in INTERFACE_RE.finditer(text):
        if candidate.group(1) == name:
            match = candidate
            break
    if match is None:
        return None
    close_idx = balanced(text, match.end() - 1, "{", "}")
    if close_idx < 0:
        return None
    body = text[match.end():close_idx]
    fields, optional, open_ended = [], [], False
    for part in split_top_level(body):
        part = part.strip()
        if not part:
            continue
        if part.startswith("["):
            open_ended = True
            continue
        found = re.match(r"([A-Za-z_$][\w$]*)(\?)?\s*:", part)
        if found:
            fields.append(found.group(1))
            if found.group(2):
                optional.append(found.group(1))
        else:
            open_ended = True
    return fields, optional, open_ended


def build_frontend_calls() -> tuple[list[dict], int]:
    """返回 (调用清单, 无法静态解析的个数)。"""
    calls: list[dict] = []
    skipped = 0
    for ts in sorted(list(FRONTEND_API.rglob("*.ts")) + list(FRONTEND_API.rglob("*.vue"))):
        if ts.name.endswith(".spec.ts") or "node_modules" in ts.parts:
            continue
        raw = ts.read_text(encoding="utf-8")
        if ts.suffix == ".vue":
            # 只取脚本块：模板里的花括号会把端点解析带偏。
            blocks = re.findall(r"<script[^>]*>(.*?)</script>", raw, re.S)
            if not blocks:
                continue
            raw = "\n".join(blocks)
        text = strip_comments(raw)
        for match in REQUEST_HEAD_RE.finditer(text):
            url_raw = match.group(1).strip("'\"`")
            brace = text.find("{", match.end())
            if brace < 0 or text[match.end():brace].strip():
                continue
            close = balanced(text, brace, "{", "}")
            if close < 0:
                continue
            options = text[brace + 1:close]
            verb_match = METHOD_RE.search(options)
            if verb_match is None:
                continue
            verb = verb_match.group(1)
            stringify = STRINGIFY_RE.search(options)
            if stringify is None:
                continue
            arg_start = stringify.end()
            while arg_start < len(options) and options[arg_start].isspace():
                arg_start += 1
            if arg_start >= len(options) or options[arg_start] != "{":
                # 变量入参：能解析出同文件的类型声明时按类型字段对账，否则如实记 SKIP。
                ident_match = re.match(r"([A-Za-z_$][\w$]*)", options[arg_start:])
                resolved = (interface_fields(text, ident_match.group(1))
                            if ident_match else None)
                if resolved is None and ident_match:
                    # 形参名 → 类型名 → 类型字段。
                    # 必须**就近**匹配：同一文件里 preview(payload: RunPreviewPayload) 与
                    # confirmAndStart(payload: RunConfirmPayload) 都叫 payload，取文件里
                    # 第一个命中会把两者的字段混在一起（实测产生过误报）。
                    window = text[max(0, match.start() - 1500):match.start()]
                    typed = None
                    for candidate in re.finditer(
                            r"\b" + re.escape(ident_match.group(1))
                            + r"\s*:\s*([A-Za-z_$][\w$]*)", window):
                        typed = candidate
                    if typed:
                        resolved = interface_fields(text, typed.group(1))
                if resolved is None or resolved[1]:
                    skipped += 1
                    calls.append({"file": str(ts.relative_to(FRONTEND_API)), "url": url_raw, "verb": verb,
                                  "keys": [], "skip": True})
                    continue
                calls.append({"file": str(ts.relative_to(FRONTEND_API)), "url": url_raw, "verb": verb,
                              "keys": resolved[0], "optional": resolved[1], "skip": False,
                              "via": ident_match.group(1)})
                continue
            keys, has_spread = object_keys(options, arg_start)
            calls.append({"file": str(ts.relative_to(FRONTEND_API)), "url": url_raw, "verb": verb,
                          "keys": keys, "skip": has_spread})
            if has_spread:
                skipped += 1
    return calls, skipped


# ---------------------------------------------------------------- 判定


def evaluate(backend_index, calls):
    passed, failed, skipped, unmatched = 0, 0, 0, 0
    details: list[str] = []
    for call in calls:
        if call.get("skip"):
            skipped += 1
            details.append(
                f"SKIP {call['file']} {call['verb']} {call['url']} —— 请求体非对象字面量，无法静态对账")
            continue
        path = normalize_path(call["url"])
        target = backend_index.get((call["verb"], path))
        if target is None:
            unmatched += 1
            details.append(
                f"UNMATCHED {call['file']} {call['verb']} {path} —— 未找到带 @RequestBody 的后端端点")
            continue
        record_name, fields, source = target
        optional = set(call.get("optional") or [])
        extra = [key for key in call["keys"] if key not in fields and key not in optional]
        warn = [key for key in (call.get("optional") or []) if key not in fields]
        via = f"（经类型 {call['via']}）" if call.get("via") else ""
        if warn:
            details.append(
                f"WARN {call['file']} {call['verb']} {path} —— 可选字段 {warn} 后端不接收"
                f"（{record_name}）{via}")
        if extra:
            failed += 1
            details.append(
                f"FAIL {call['file']} {call['verb']} {path} —— 前端字段 {extra} 不在后端 "
                f"{record_name}({', '.join(fields)}) 里（{source}）{via}")
        else:
            passed += 1
    return passed, failed, skipped, unmatched, details


def run(backend_root: Path = BACKEND, frontend_root: Path = FRONTEND_API):
    global BACKEND, FRONTEND_API
    saved = (BACKEND, FRONTEND_API)
    try:
        BACKEND, FRONTEND_API = backend_root, frontend_root
        index = build_backend_index()
        calls, _ = build_frontend_calls()
        return evaluate(index, calls)
    finally:
        BACKEND, FRONTEND_API = saved


def self_test() -> int:
    """真注入：改一个前端键为后端不存在的名字 -> 必须判红；还原 -> 必须判绿。"""
    ok = True
    tmpdir = Path(tempfile.mkdtemp(prefix="fe-contract-"))
    try:
        copied = tmpdir / "src"
        shutil.copytree(FRONTEND_API, copied)
        target = copied / "api" / "platformCaseSets.ts"
        text = target.read_text(encoding="utf-8")
        if "caseIds" not in text:
            print("[SELFTEST FAIL] 注入点缺失：platformCaseSets.ts 里找不到 caseIds")
            return 1
        target.write_text(text.replace("caseIds: [caseId]", "caseId: caseId", 1),
                          encoding="utf-8")
        injected = run(BACKEND, copied)
        if injected[1] == 0:
            print("[SELFTEST FAIL] 注入错误字段后仍判绿，说明对账没生效")
            ok = False
        else:
            print(f"[SELFTEST PASS] 注入错误字段判红（FAIL={injected[1]}）")

        clean = run(BACKEND, FRONTEND_API)
        if clean[1] != 0:
            print(f"[SELFTEST FAIL] 还原后真实源码仍判红：{clean[4][:3]}")
            ok = False
        else:
            print(f"[SELFTEST PASS] 还原后真实源码判绿（PASS={clean[0]}）")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    result = run(BACKEND, FRONTEND_API)
    print(f"FE_CONTRACT_RESULT PASS={result[0]} FAIL={result[1]} "
          f"SKIP={result[2]} UNMATCHED={result[3]}")
    print(f"SELFTEST_RESULT {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="W57-55 前后端请求体字段对账")
    parser.add_argument("--self-test", action="store_true", help="真注入负向自检")
    parser.add_argument("--verbose", action="store_true", help="打印 SKIP / UNMATCHED 明细")
    parser.add_argument("--frontend-dir", help="指定前端源码根（历史版本回放取证用）")
    parser.add_argument("--backend-dir", help="指定后端源码根（历史版本回放取证用）")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    passed, failed, skipped, unmatched, details = run(
        Path(args.backend_dir) if args.backend_dir else BACKEND,
        Path(args.frontend_dir) if args.frontend_dir else FRONTEND_API)
    for line in details:
        if line.startswith("FAIL") or args.verbose:
            print(f"  {line}")
    if not args.verbose:
        for line in details:
            if not line.startswith("FAIL"):
                print(f"  {line}")
    print(f"FE_CONTRACT_RESULT PASS={passed} FAIL={failed} SKIP={skipped} UNMATCHED={unmatched}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
