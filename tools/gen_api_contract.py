#!/usr/bin/env python3
"""EW32-08 · 从 Spring MVC 控制器源码生成 OpenAPI 3.0 骨架，并与提交的快照做 diff。

为什么不用 springdoc：v3.2.0 的批准依赖清单里没有新增 OpenAPI 运行时依赖（见 D32-1），
所以这里用构建期静态解析：路径、方法、路径/查询参数、请求体、响应信封都能真实提取；
字段级 schema 由 8 个核心契约测试（backend .../arch 与 api 测试）兜底，不在本脚本职责内。

用法：
  python tools/gen_api_contract.py                 # 写 docs/contracts/openapi.json
  python tools/gen_api_contract.py --check         # 只比对，漂移则退出 1
  python tools/gen_api_contract.py --out X.json
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API_DIR = ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest" / "api"
SNAPSHOT = ROOT / "docs" / "contracts" / "openapi.json"
VERSION_FILE = ROOT / "VERSION"

MAPPING = {
    "GetMapping": "get",
    "PostMapping": "post",
    "PutMapping": "put",
    "DeleteMapping": "delete",
    "PatchMapping": "patch",
    "RequestMapping": None,  # 方法级 @RequestMapping 不指定方法，按 all 处理
}
HTTP_METHODS = ("get", "post", "put", "delete", "patch")


@dataclass
class Param:
    name: str
    location: str
    required: bool
    type_name: str
    default: str | None = None


@dataclass
class Endpoint:
    path: str
    method: str
    operation_id: str
    tag: str
    params: list[Param] = field(default_factory=list)
    body_type: str | None = None
    returns_page: bool = False


def strip_comments(text: str) -> str:
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"\/\*.*?\*\/", "", text, flags=re.S)
    return text


def balanced(text: str, start: int, open_ch: str = "(", close_ch: str = ")") -> int:
    """返回与 text[start] 配对的右括号下标；找不到返回 -1。"""
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i
    return -1


def split_top_level(text: str) -> list[str]:
    parts, depth, current = [], 0, []
    for ch in text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current).strip())
    return [p for p in parts if p]


def annotation_at(text: str, at: int):
    """解析 text[at] 处的 @Xxx(...)，返回 (名字, 参数字符串, 注解结束下标)。"""
    m = re.match(r"@([\w.]+)", text[at:])
    if not m:
        return None, "", at
    name = m.group(1).split(".")[-1]
    pos = at + m.end()
    while pos < len(text) and text[pos] in " \t\r\n":
        pos += 1
    args = ""
    if pos < len(text) and text[pos] == "(":
        end = balanced(text, pos)
        if end < 0:
            return name, "", pos
        args = text[pos + 1:end]
        pos = end + 1
    return name, args, pos


def literal(args: str, key: str = "value") -> str | None:
    m = re.search(rf'{key}\s*=\s*"([^"]*)"', args)
    if m:
        return m.group(1)
    m = re.match(r'\s*"([^"]*)"', args)
    return m.group(1) if m else None


def request_param(param_text: str) -> Param | None:
    """把单个方法参数解析成 Param；没有 Spring 参数注解则忽略（由 @RequestBody 分支处理）。"""
    param_text = param_text.strip()
    parts = split_top_level(param_text)
    if not parts:
        return None
    annotations = {}
    name_and_type = ""
    for part in parts:
        if part.startswith("@"):
            ann_name, ann_args, _ = annotation_at(part, 0)
            annotations[ann_name] = ann_args
        else:
            name_and_type = part
    if not name_and_type:
        return None
    m = re.match(r"(.+?)\s+(\w+)$", name_and_type, flags=re.S)
    if not m:
        return None
    type_name, name = m.group(1).strip(), m.group(2).strip()
    type_name = re.sub(r"<.*>", "", type_name).strip()
    for ann in ("PathVariable", "RequestParam", "RequestPart", "RequestHeader"):
        if ann in annotations:
            args = annotations[ann]
            if ann == "PathVariable":
                location, required = "path", True
            elif ann == "RequestParam":
                location = "query"
                required = "required = false" not in args.replace(" ", " ")
                default = None
                dm = re.search(r'defaultValue\s*=\s*"([^"]*)"', args)
                if dm:
                    default = dm.group(1)
                    required = False
                return Param(_explicit_name(args) or name, location, required, type_name, default)
            else:
                location, required = "query", True
            return Param(_explicit_name(args) or name, location, required, type_name)
    return None


def _explicit_name(args: str) -> str | None:
    m = re.search(r'name\s*=\s*"([^"]*)"', args)
    if m:
        return m.group(1)
    m = re.match(r'\s*"([^"]+)"\s*(?:,|$)', args)
    return m.group(1) if m else None


def parse_controller(path: Path) -> list[Endpoint]:
    text = strip_comments(path.read_text(encoding="utf-8"))
    m = re.search(r"@RestController", text)
    if not m:
        return []
    class_ann, class_args, _ = annotation_at(text, m.start())
    # 类级 @RequestMapping 可能在 @RestController 前或后
    class_path = ""
    for am in re.finditer(r"@RequestMapping", text):
        after = text[am.start():]
        # 类级注释的右括号后紧跟 public class
        _, args, end = annotation_at(text, am.start())
        tail = text[end:end + 80]
        if re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+class", tail):
            class_path = literal(args) or ""
            break
    class_name = re.search(r"public\s+(?:final\s+)?class\s+(\w+)", text).group(1)
    tag = path.parent.name
    endpoints: list[Endpoint] = []

    for am in re.finditer(r"@(Get|Post|Put|Delete|Patch|Request)Mapping", text):
        ann_name = am.group(0)[1:]
        if not ann_name.endswith("Mapping"):
            continue
        _, args, end = annotation_at(text, am.start())
        tail = text[end:end + 120]
        if re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+class", tail):
            continue  # 类级
        mm = re.search(
            r"\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+([\w.<>, ?\[\]]+?)\s+(\w+)\s*\(",
            text[end:],
        )
        if not mm:
            continue
        sig_start = end + mm.start()
        ret_type = mm.group(1).strip()
        method_name = mm.group(2)
        paren = text.find("(", end + mm.start(2))
        paren_end = balanced(text, paren)
        params = split_top_level(text[paren + 1:paren_end])
        sub_path = literal(args) or ""
        http_methods = []
        if ann_name == "RequestMapping":
            for hm in HTTP_METHODS:
                if re.search(rf"RequestMethod\.{hm.upper()}", args):
                    http_methods.append(hm)
            if not http_methods:
                http_methods = ["get"]
        else:
            http_methods = [MAPPING[ann_name]]

        parsed_params: list[Param] = []
        body_type = None
        for p in params:
            if "@RequestBody" in p:
                body_type = _param_type(p)
                continue
            if "@RequestPart" in p or "MultipartFile" in p:
                parsed_params.append(Param(_param_name(p), "query", True, "string"))
                continue
            parsed = request_param(p)
            if parsed:
                parsed_params.append(parsed)

        for http_method in http_methods:
            endpoints.append(Endpoint(
                path=class_path + sub_path,
                method=http_method,
                operation_id=f"{class_name}.{method_name}",
                tag=tag,
                params=parsed_params,
                body_type=body_type,
                returns_page=bool(re.search(r"PageResult<", ret_type)),
            ))
    return endpoints


def _param_type(param_text: str) -> str:
    cleaned = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", param_text).strip()
    m = re.match(r"(.+?)\s+\w+$", cleaned, flags=re.S)
    return m.group(1).strip() if m else cleaned


def _param_name(param_text: str) -> str:
    cleaned = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", param_text).strip()
    m = re.match(r"(.+?)\s+(\w+)$", cleaned, flags=re.S)
    return m.group(2) if m else cleaned


def schema_for(param: Param) -> dict:
    java_type = param.type_name
    if java_type in ("int", "Integer", "long", "Long", "short", "Short"):
        schema: dict = {"type": "integer", "format": "int64"}
    elif java_type in ("boolean", "Boolean"):
        schema = {"type": "boolean"}
    elif java_type in ("double", "float", "BigDecimal"):
        schema = {"type": "number"}
    else:
        schema = {"type": "string"}
    if param.default is not None:
        schema["default"] = int(param.default) if schema["type"] == "integer" and param.default.isdigit() else param.default
    return schema


def build_document() -> dict:
    endpoints: list[Endpoint] = []
    for file in sorted(API_DIR.rglob("*Controller.java")):
        endpoints.extend(parse_controller(file))
    endpoints.sort(key=lambda e: (e.path, HTTP_METHODS.index(e.method), e.operation_id))

    paths: dict = {}
    for ep in endpoints:
        item = paths.setdefault(ep.path, {})
        op: dict = {
            "operationId": ep.operation_id,
            "tags": [ep.tag],
            "responses": {
                "200": {
                    "description": "统一信封；业务错误码见 ErrorCode",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/ApiResponsePage"
                                if ep.returns_page else "#/components/schemas/ApiResponse"
                            }
                        }
                    },
                },
                "400": {"$ref": "#/components/responses/BusinessError"},
                "401": {"$ref": "#/components/responses/BusinessError"},
                "403": {"$ref": "#/components/responses/BusinessError"},
                "404": {"$ref": "#/components/responses/BusinessError"},
                "409": {"$ref": "#/components/responses/BusinessError"},
            },
        }
        parameters = []
        for p in ep.params:
            entry = {
                "name": p.name,
                "in": p.location,
                "required": p.required,
                "schema": schema_for(p),
            }
            parameters.append(entry)
        if parameters:
            parameters.sort(key=lambda x: (x["in"], x["name"]))
            op["parameters"] = parameters
        if ep.body_type:
            op["requestBody"] = {
                "required": True,
                "content": {"application/json": {"schema": {"type": "object", "title": ep.body_type}}},
            }
        item[ep.method] = op

    version = VERSION_FILE.read_text(encoding="utf-8").strip() if VERSION_FILE.exists() else "0.0.0"
    return {
        "openapi": "3.0.3",
        "info": {
            "title": "Robot-Autotest API（构建期静态快照）",
            "version": version,
            "description": "由 tools/gen_api_contract.py 从控制器源码生成；字段级契约由契约测试保证。",
        },
        "servers": [{"url": "http://127.0.0.1:8001"}],
        "tags": [{"name": t} for t in sorted({ep.tag for ep in endpoints})],
        "paths": paths,
        "components": {
            "schemas": {
                "ApiResponse": {
                    "type": "object",
                    "required": ["code", "codeName", "message", "data"],
                    "properties": {
                        "code": {"type": "integer", "format": "int32"},
                        "codeName": {"type": "string"},
                        "message": {"type": "string"},
                        "data": {},
                    },
                },
                "PageResult": {
                    "type": "object",
                    "required": ["items", "total", "page", "size"],
                    "properties": {
                        "items": {"type": "array", "items": {"type": "object"}},
                        "total": {"type": "integer", "format": "int64"},
                        "page": {"type": "integer"},
                        "size": {"type": "integer"},
                    },
                },
                "ApiResponsePage": {
                    "allOf": [
                        {"$ref": "#/components/schemas/ApiResponse"},
                        {
                            "type": "object",
                            "properties": {"data": {"$ref": "#/components/schemas/PageResult"}},
                        },
                    ]
                },
                "BusinessError": {
                    "type": "object",
                    "required": ["code", "codeName", "message"],
                    "properties": {
                        "code": {"type": "integer"},
                        "codeName": {"type": "string"},
                        "message": {"type": "string"},
                        "data": {"nullable": True},
                    },
                },
            },
            "responses": {
                "BusinessError": {
                    "description": "可预期业务失败（参数/权限/状态/不存在）",
                    "content": {
                        "application/json": {"schema": {"$ref": "#/components/schemas/BusinessError"}}
                    },
                }
            },
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--out", default=str(SNAPSHOT))
    args = parser.parse_args()

    doc = build_document()
    rendered = json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    endpoints = sum(len(v) for v in doc["paths"].values())
    print(f"endpoints={endpoints} paths={len(doc['paths'])}")

    if args.check:
        if not SNAPSHOT.exists():
            print(f"FAIL 快照不存在：{SNAPSHOT}", file=sys.stderr)
            return 1
        committed = SNAPSHOT.read_text(encoding="utf-8")
        if committed != rendered:
            diff = difflib.unified_diff(
                committed.splitlines(), rendered.splitlines(),
                fromfile=str(SNAPSHOT), tofile="live-controller-source", lineterm="",
            )
            print("\n".join(list(diff)[:200]))
            print("FAIL API 契约快照与源码不一致；确认后运行 python tools/gen_api_contract.py 更新", file=sys.stderr)
            return 1
        print("PASS API 契约快照与控制器源码一致")
        return 0

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(rendered, encoding="utf-8")
    print(f"WROTE {out} ({len(rendered)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
