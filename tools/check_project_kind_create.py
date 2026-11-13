#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁 49 / EHT-05 / FR-v5.5-02：项目创建入口必须显式支持 kind。

守什么
------
1. `CreateProjectRequest` 有 `String kind` 组件——否则 HTTP 层根本建不出
   `pytest-suite` 工程（5 参 create 固定回落 standard，只能物理 SQL 插，绕过审计）；
2. `POST /api/projects` 调 6 参 `projects.create(...)` 且最后一项是 `request.kind()`；
3. 6 参 create 上必须有 `@Audited`——HTTP 直达后若注解还只挂在 5 参重载上，
   建工程会静默丢掉 `project:create` 审计（同类自调用不经过代理）；
4. `scripts/gates.sh` 登记本步（只增不删）。

用法
----
    python3 tools/check_project_kind_create.py
    python3 tools/check_project_kind_create.py --self-test  # 真注入删 kind 组件，必须判红

退出码：0 通过 / 1 违规 / 2 扫描失败。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CTRL = ROOT / "backend/src/main/java/com/qa/autotest/api/iam/ProjectController.java"
SVC = ROOT / "backend/src/main/java/com/qa/autotest/application/iam/ProjectService.java"
GATES = ROOT / "scripts/gates.sh"


def ok(msg: str) -> None:
    print("  OK     " + msg)


def fail(problems: list[str], msg: str) -> None:
    problems.append(msg)
    print("  FAIL   " + msg)


def check(problems: list[str], ctrl: Path = CTRL, svc: Path = SVC,
          gates: Path = GATES) -> None:
    text = ctrl.read_text(encoding="utf-8")
    rec = re.search(r"public record CreateProjectRequest\((.*?)\)\s*\{", text, re.S)
    if not rec:
        fail(problems, "找不到 CreateProjectRequest record（结构变了，机检要同步）")
        return
    if not re.search(r"\bString\s+kind\b", rec.group(1)):
        fail(problems, "CreateProjectRequest 缺 String kind 组件")
    else:
        ok("CreateProjectRequest 含 String kind")

    call = re.search(r"return ApiResponse\.ok\(projects\.create\((.*?)\)\);", text, re.S)
    if not call:
        fail(problems, "POST /api/projects 找不到 projects.create 调用")
    else:
        args = [a.strip() for a in call.group(1).split(",")]
        if len(args) != 6:
            fail(problems, "create 调用参数 %d 个（应为 6：kind 未接上）" % len(args))
        else:
            ok("create 调 6 参重载")
        if not args or "request.kind()" not in args[-1]:
            fail(problems, "create 最后一项不是 request.kind()（kind 被硬编码或丢弃）")
        else:
            ok("kind 来自请求入参，未被硬编码")

    service = svc.read_text(encoding="utf-8")
    six = re.search(
        r"(@Audited\([^\n]*\)\s*\n\s*)?@Transactional\s*\n\s*"
        r"public Project create\(String code, String name, String description, String visibility,\s*\n"
        r"\s*String testKernel, String kind\)", service)
    if not six:
        fail(problems, "找不到 6 参 create 重载")
    elif not six.group(1):
        fail(problems, "6 参 create 缺 @Audited：HTTP 直达会丢 project:create 审计")
    else:
        ok("6 参 create 保留 @Audited")

    gate_text = gates.read_text(encoding="utf-8")
    if not re.search(r"^run 49 project_kind_create ", gate_text, re.M):
        fail(problems, "gates.sh 未登记步骤 49")
    else:
        ok("gates.sh 登记步骤 49")


def run() -> int:
    print("check_project_kind_create: 项目创建入口 kind 契约")
    problems: list[str] = []
    check(problems)
    if problems:
        print("check_project_kind_create: FAIL（%d 项）" % len(problems))
        return 1
    print("check_project_kind_create: PASS")
    return 0


def self_test() -> int:
    """真注入：删掉 record 的 kind 组件 / 把 request.kind() 改成硬编码，两次都必须判红。"""
    original = CTRL.read_text(encoding="utf-8")
    injections = [
        (", String kind) {", ") {", "删掉 kind 组件"),
        ("request.kind()", '"standard"', "把 kind 改成硬编码"),
    ]
    for old, new, label in injections:
        broken = original.replace(old, new, 1)
        if broken == original:
            print("  FAIL   注入点没找到：" + label)
            return 2
        tmp = None
        try:
            with tempfile.NamedTemporaryFile("w", suffix=".java", delete=False,
                                             encoding="utf-8") as h:
                h.write(broken)
                tmp = Path(h.name)
            problems: list[str] = []
            check(problems, ctrl=tmp)
            if not problems:
                print("  FAIL   %s 后机检仍判绿（假绿灯）" % label)
                return 1
            print("  OK     %s -> 判红（%d 项）" % (label, len(problems)))
        finally:
            if tmp is not None:
                tmp.unlink(missing_ok=True)
    print("--- 还原后应判绿 ---")
    problems = []
    check(problems)
    if problems:
        print("  FAIL   还原后仍判红：%s" % problems)
        return 1
    print("check_project_kind_create: SELF-TEST PASS")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="项目创建入口 kind 契约机检")
    p.add_argument("--self-test", action="store_true", help="破坏性自检")
    a = p.parse_args()
    return self_test() if a.self_test else run()


if __name__ == "__main__":
    sys.exit(main())
