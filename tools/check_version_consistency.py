#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""版本一致性门禁（v3.2.0 收口建议，第 23 步）。

为什么需要它：v3.2.0 收口时「根 VERSION 已升 3.2.0、关于页仍显示 3.0.1」真实发生过——
版本号有四个副本（VERSION / 后端构建产物 / openapi 快照 / 前端 public 副本），
任何一个没刷新，页面就会对外说错版本，而既有 22 步门禁全绿。

检查项（任一不符即退出 1）：
    1. VERSION 与 backend/src/main/resources/build-info.properties 的 app.version 一致
    2. CHANGELOG.md 首条版本 = VERSION，且带 <!-- current -->
    3. frontend/public/CHANGELOG.md 首条版本 = VERSION（部署静态副本已刷新）
    4. docs/contracts/openapi.json 的 info.version = VERSION
    5. 运行期：登录后 /api/system/build-info 的 version = VERSION（后端已按新版本重启）
    6. 运行期：GET {entry}/CHANGELOG.md 首条版本 = VERSION（入口已托管新前端 dist）

运行期检查默认开启（门禁 13/17 本就要求 :8001 可用）；仅做静态检查时传 --static。
"""

import io
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def read(path):
    full = os.path.join(ROOT, path)
    if not os.path.exists(full):
        return None
    return io.open(full, encoding="utf-8", errors="replace").read()


def first_version(text):
    """返回不带 `v` 前缀的版本号（与根 VERSION 文件同口径）。"""
    if not text:
        return None
    m = re.search(r"^##\s+v?([\d.]+)", text, re.M)
    return m.group(1) if m else None


def release_section(text, version):
    """取出指定版本的 `## vX.Y.Z` 小节正文（到下一个 `## ` 前）。

    规范化：去掉 `<!-- current -->` 标记、去行尾空白、丢弃空行，
    只比「这一版到底写了什么」，不因排版差异误报。
    """
    if not text:
        return None
    lines = text.split("\n")
    start = None
    for i, line in enumerate(lines):
        m = re.match(r"^##\s+v?([\d.]+)", line)
        if m and m.group(1) == version:
            start = i + 1
            break
    if start is None:
        return None
    end = len(lines)
    for j in range(start, len(lines)):
        if re.match(r"^##\s+", lines[j]):
            end = j
            break
    kept = []
    for line in lines[start:end]:
        # 只摘掉 current 标记；`<!-- added -->` 这类小节锚点必须留着，
        # 后面的 section_items 靠它定位，一起删掉会导致「四类小节全缺」的假红。
        cleaned = re.sub(r"<!--\s*current\s*-->", "", line).rstrip()
        if cleaned.strip():
            kept.append(cleaned)
    return "\n".join(kept)


def section_items(section, key):
    """取某一类小节（`### ... <!-- added -->`）下的 `- ` 条目。

    小节不存在返回 None，存在但没条目返回 []——两者语义不同：
    「漏写小节」和「写了但空着」都要报，但提示语不一样。
    """
    if not section:
        return None
    marker = "<!-- %s -->" % key
    lines = section.split("\n")
    start = None
    for i, line in enumerate(lines):
        if line.startswith("### ") and marker in line:
            start = i + 1
            break
    if start is None:
        return None
    items = []
    for line in lines[start:]:
        if line.startswith("### "):
            break
        if line.strip().startswith("- "):
            items.append(line.strip())
    return items


def main():
    args = set(sys.argv[1:])
    errors = []
    warns = []

    version = (read("VERSION") or "").strip()
    if not version:
        print("FAIL  VERSION 缺失或为空")
        return 1

    # 1. 后端构建产物
    props = read("backend/src/main/resources/build-info.properties") or ""
    m = re.search(r"^app\.version=(\S+)", props, re.M)
    built = m.group(1) if m else None
    print("INFO  VERSION=%s  build-info=%s" % (version, built or "缺失"))
    if built != version:
        errors.append("build-info app.version=%s 与 VERSION=%s 不一致（先跑 scripts/gen_build_info.py）"
                      % (built, version))

    # 2/3. CHANGELOG 两份副本
    for path in ("CHANGELOG.md", "frontend/public/CHANGELOG.md"):
        text = read(path)
        first = first_version(text)
        if first != version:
            errors.append("%s 首条版本=%s，应=%s" % (path, first, version))
        elif path == "CHANGELOG.md" and "<!-- current -->" not in text.split("\n## ", 1)[1][:200]:
            errors.append("CHANGELOG.md 的 %s 条目缺 <!-- current --> 标记" % version)

    # 3.1 版本迭代页内容一致性（3.9.0 / FR-v3.9-17 加固）
    # 为什么只比「首条版本小节」而不是全文：CHANGELOG 尾部允许留未发版工作记录（引用块），
    # 那部分本来就不该进对外页面。旧逻辑只比首条**版本号**，于是出现过
    # 「版本小节改了、public 副本没同步，页面还是旧内容，门禁却全绿」——
    # 版本迭代页正是读 public 副本渲染的，必须按小节内容比对。
    root_sec = release_section(read("CHANGELOG.md") or "", version)
    pub_sec = release_section(read("frontend/public/CHANGELOG.md") or "", version)
    if root_sec is None:
        errors.append("CHANGELOG.md 找不到 v%s 的版本小节" % version)
    else:
        if pub_sec is None:
            errors.append("frontend/public/CHANGELOG.md 找不到 v%s 的版本小节（版本迭代页会缺这一版）"
                          % version)
        elif pub_sec != root_sec:
            errors.append("frontend/public/CHANGELOG.md 的 v%s 小节与根 CHANGELOG.md 不一致"
                          "（版本迭代页显示旧内容；先跑 scripts/build.sh 或手工同步）" % version)

        # 3.2 版本小节必须写得出内容：四类小节齐全且「新增/变更/修复」各有条目。
        # 空小节 = 关于页与版本迭代页对这一版说不出任何东西，等于没更新。
        for key in ("added", "changed", "fixed", "breaking"):
            body = section_items(root_sec, key)
            if body is None:
                errors.append("CHANGELOG.md 的 v%s 缺 <!-- %s --> 小节" % (version, key))
            elif key != "breaking" and not body:
                errors.append("CHANGELOG.md 的 v%s 的 %s 小节没有任何条目" % (version, key))

    # 4. OpenAPI 快照
    raw = read("docs/contracts/openapi.json")
    try:
        api_version = json.loads(raw or "{}").get("info", {}).get("version")
    except ValueError:
        api_version = None
    if api_version != version:
        errors.append("docs/contracts/openapi.json info.version=%s，应=%s" % (api_version, version))

    # 5/6. 运行期（入口 :8001）
    if "--static" not in args:
        base = os.environ.get("ENTRY_BASE", "http://127.0.0.1:8001")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def call(path, data=None, token=None):
            req = urllib.request.Request(
                base + path,
                data=json.dumps(data).encode() if data else None,
                method="POST" if data else "GET",
            )
            req.add_header("Content-Type", "application/json")
            if token:
                req.add_header("Authorization", "Bearer " + token)
            return opener.open(req, timeout=10).read().decode("utf-8")

        password = os.environ.get("QA_E2E_PASSWORD", "")
        if not password:
            errors.append("缺 QA_E2E_PASSWORD，无法验证运行期版本（静态检查可用 --static）")
        else:
            try:
                login = json.loads(call("/api/auth/login",
                                       {"username": "qa-e2e-admin", "password": password}))
                data = login.get("data") or {}
                token = data.get("token") or data.get("accessToken")
                if not token:
                    errors.append("登录响应无 token，无法验证 /api/system/build-info")
                else:
                    info = json.loads(call("/api/system/build-info", token=token)).get("data") or {}
                    runtime = info.get("version")
                    print("INFO  运行期 build-info=%s (source=%s)" %
                          (runtime, info.get("versionSource")))
                    if runtime != version:
                        errors.append("运行期 /api/system/build-info=%s，应=%s（后端未按新版本重启）"
                                      % (runtime, version))
            except Exception as exc:  # noqa: BLE001 - 门禁要把网络错误转成红
                errors.append("运行期检查失败: %s" % exc)

        try:
            text = call("/CHANGELOG.md")
            served = first_version(text)
            print("INFO  入口托管 CHANGELOG=%s" % served)
            if served != version:
                errors.append("入口 %s/CHANGELOG.md 首条=%s，应=%s（前端 dist 未重建/未部署）"
                              % (base, served, version))
        except Exception as exc:  # noqa: BLE001
            errors.append("入口静态文件读取失败: %s" % exc)

    for w in warns:
        print("WARN  " + w)
    for e in errors:
        print("FAIL  " + e)
    print("VERSION_CONSISTENCY %s (%d 项)" % ("FAIL" if errors else "PASS", len(errors)))
    return 1 if errors else 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
