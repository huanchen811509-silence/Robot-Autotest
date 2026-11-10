#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.9.0 · 门禁第 28 步：用例级命令契约（FR-v3.9-02）。

两层判据，缺一不可：

A. **静态契约**——四层都有这三列，且口径只有一个来源：
   迁移 V11_24（列）→ CaseCommandExtractor（白名单 + 来源枚举）→
   读取链（RunCaseRow / TreeCase / ReportReadRepositoryJdbc）→
   前端（ReportCaseNode / SuiteTree 三态文案）→ 导出（DocxExporter 命令列）。
   任何一层漏改，都会表现为「SQL 有值、界面没有」或「界面有、导出没有」，
   静态检查就是把这种漏改钉在提交前。

B. **真实覆盖率**——docs/evidence/v3.9.0/EW39-02/case-command-coverage.json
   由 tools/case_command_report.py 从库里真实 run 取数。要求：
   ① RF 与 pytest 各至少一条 run；② 每条 run 的覆盖率 100%
   （命令原文 + 显式 NONE 都算覆盖）；③ 来源值都在枚举内；
   ④ 抽样命令非空；⑤ 不允许用「历史 NULL 行」凑覆盖率。

证据缺失直接判红（2），不静默放行：没有真实 run 的「覆盖率 100%」不算数。

用法：
  python3 tools/check_case_command.py
  python3 tools/check_case_command.py --self-check   # 负向自证：脏数据必须判红
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/evidence/v3.9.0/EW39-02/case-command-coverage.json"

ALLOWED_SOURCES = ["RF_KEYWORD", "PYTEST_MARK", "STDOUT_EXTRACT", "NONE"]
KERNELS = ["RF", "PYTEST"]

# (相对路径, 必须出现的片段, 说明)
STATIC_CHECKS = [
    ("db/migration/V11_24__v3_9_case_command.sql",
     ["command_text", "command_source", "command_exit_code"], "迁移三列"),
    ("backend/src/main/java/com/qa/autotest/application/report/CaseCommandExtractor.java",
     ["RF_KEYWORD", "PYTEST_MARK", "STDOUT_EXTRACT", "SOURCE_NONE", "MAX_COMMAND_LENGTH"],
     "提取器来源枚举与上限"),
    ("backend/src/main/java/com/qa/autotest/domain/report/CaseNode.java",
     ["commandText", "commandSource", "commandExitCode", "NO_COMMAND"], "领域节点三列"),
    ("backend/src/main/java/com/qa/autotest/domain/kernel/NormalizedCaseResult.java",
     ["commandText", "commandSource", "commandExitCode"], "双内核归一结果三列"),
    ("backend/src/main/java/com/qa/autotest/domain/report/RunCaseRow.java",
     ["commandText", "commandSource", "commandExitCode"], "读取链扁平行三列"),
    ("backend/src/main/java/com/qa/autotest/infrastructure/ReportReadRepositoryJdbc.java",
     ["t.command_text", "t.command_source", "t.command_exit_code"], "读取 SQL 三列"),
    ("backend/src/main/java/com/qa/autotest/application/report/ReportTreeService.java",
     ["commandText", "commandSource", "commandExitCode"], "树节点透出三列"),
    ("backend/src/main/java/com/qa/autotest/application/report/DocxExporter.java",
     ["下发命令", "NO_COMMAND_TEXT"], "导出件命令列"),
    ("backend/src/main/java/com/qa/autotest/application/report/OutputXmlParser.java",
     ["CaseCommandExtractor"], "RF 提取接入"),
    ("backend/src/main/java/com/qa/autotest/application/kernel/PytestKernel.java",
     ["CaseCommandExtractor"], "pytest 提取接入"),
    ("frontend/src/api/reports.ts",
     ["commandText", "commandSource", "commandExitCode"], "前端类型三列"),
    ("frontend/src/components/report/SuiteTree.vue",
     ["command.notCollected", "command.none", "command.expand", "command.copy"],
     "前端三态与折叠/复制"),
    ("frontend/src/views/ReportPrintView.vue",
     ["commandText", "reports.command.label"], "打印页命令列"),
    ("frontend/src/locales/zh-CN.ts", ["notCollected", "本用例未下发外部命令"], "中文文案"),
    ("frontend/src/locales/en-US.ts", ["notCollected", "No external command"], "英文文案"),
    # v4.0：全表面脱敏（FR-v4.0-01）。规则只允许一处，四个写入面只做接入。
    ("backend/src/main/java/com/qa/autotest/common/security/SecretRedactor.java",
     ["MASK", "redact(", "hasLeak(", "assertClean("], "唯一脱敏规则源"),
    ("backend/src/main/java/com/qa/autotest/application/execution/EvidenceLogWriter.java",
     ["SecretRedactor"], "运行日志脱敏接入"),
    ("backend/src/main/java/com/qa/autotest/application/report/ReportViewRenderer.java",
     ["SecretRedactor"], "报告视图脱敏接入"),
    ("backend/src/main/java/com/qa/autotest/application/report/ReportExportService.java",
     ["SecretRedactor", "assertNoSecrets"], "导出前兜底阻断"),
    ("backend/src/main/java/com/qa/autotest/application/iam/AuditService.java",
     ["SecretRedactor"], "审计脱敏接入"),
]

# 全表面脱敏的证据（FR-v4.0-01 / TC-v4.0-03）。三类写真 + 不误伤样本由
# SecretRedactorEvidenceTest 产出，scripts/redaction-evidence.sh 拷进 docs。
V40_EVIDENCE = ROOT / "docs/evidence/v4.0.0/EW40-01/redaction-cases.json"
REDACTION_CLASSES = ["kv", "bearer", "url", "cli"]
SECRET_WORDS = ("password", "passwd", "bearer", "token", "secret")
SECRET_SOURCE = "backend/src/main/java/com/qa/autotest/common/security/SecretRedactor.java"


def check_single_source() -> list[str]:
    """规则只能有一份：任何其他 Java 源再写凭据正则，迟早与 SecretRedactor 漂移。

    判据用「Pattern.compile 行里出现凭据关键词」这条窄规则，宁窄不宽：
    扫全量源码找 `password` 会命中字段名、注释与 SQL，恒红反而没人看。
    """
    problems: list[str] = []
    base = ROOT / "backend/src/main/java"
    for path in base.rglob("*.java"):
        rel = path.relative_to(ROOT).as_posix()
        if rel == SECRET_SOURCE:
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for no, line in enumerate(lines, 1):
            if "Pattern.compile" not in line:
                continue
            low = line.lower()
            if any(word in low for word in SECRET_WORDS):
                problems.append(f"{rel}:{no} 绕过 SecretRedactor 自写凭据正则：{line.strip()[:80]}")
    return problems


def check_redaction(payload: dict) -> list[str]:
    """全表面脱敏证据（TC-v4.0-03）：三类写真必脱敏、不误伤样本必原样保留。"""
    problems: list[str] = []
    if payload.get("maskedToken") != "<redacted>":
        problems.append(f"证据里的占位符与实现不一致：{payload.get('maskedToken')!r}")

    cases = payload.get("cases") or []
    if not cases:
        problems.append("脱敏证据里没有任何样本")
        return problems

    classes = {case.get("class") for case in cases}
    for need in REDACTION_CLASSES:
        if need not in classes:
            problems.append(f"缺少 {need} 类脱敏样本（三类写法 + 命令行都要有）")

    innocents = [case for case in cases if case.get("innocent")]
    if len(innocents) < 2:
        problems.append(f"不误伤样本 {len(innocents)} 条 < 2（正常命令被误脱敏无法被发现）")

    for case in cases:
        cid = case.get("id") or "<无 id>"
        inp = case.get("input") or ""
        masked = case.get("masked") or ""
        if not inp:
            problems.append(f"{cid} 没有原文样本")
            continue
        if case.get("innocent"):
            if case.get("leakedBefore") or case.get("leakedAfter"):
                problems.append(f"{cid} 正常命令被判为含凭据（误伤）")
            if masked != inp:
                problems.append(f"{cid} 正常命令被改动：{masked!r}")
            continue
        secret = case.get("secret") or ""
        if not secret:
            problems.append(f"{cid} 未给出明文口令，无法证明真的脱敏")
        if not case.get("leakedBefore"):
            problems.append(f"{cid} 原文未被判为含凭据（样本本身无效）")
        if case.get("leakedAfter"):
            problems.append(f"{cid} 脱敏后仍被判为含凭据")
        if secret and secret in masked:
            problems.append(f"{cid} 脱敏结果仍含口令原文")
        if masked == inp:
            problems.append(f"{cid} 脱敏前后无变化")
        if not masked.count("<redacted>"):
            problems.append(f"{cid} 没有使用统一占位符 <redacted>")
    return problems


def check_static() -> list[str]:
    problems: list[str] = []
    for rel, needles, label in STATIC_CHECKS:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"缺少文件：{rel}（{label}）")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for needle in needles:
            if needle not in text:
                problems.append(f"{rel} 缺少 {needle!r}（{label}）")
    return problems


def check_evidence(payload: dict) -> list[str]:
    """判据 B。payload 由 case_command_report.py 产出，也用于负向自证。"""
    problems: list[str] = []

    allowed = payload.get("allowedSources") or []
    if sorted(allowed) != sorted(ALLOWED_SOURCES):
        problems.append(f"证据里的来源枚举与判据不一致：{allowed}")

    runs = payload.get("runs") or []
    if not runs:
        problems.append("证据里没有任何 run")
        return problems

    kernels = {str(run.get("kernel", "")).upper() for run in runs}
    for kernel in KERNELS:
        if kernel not in kernels:
            problems.append(f"缺少 {kernel} 内核的真实 run（两个内核都要各跑一次）")

    for run in runs:
        rid = run.get("runId", "<无 RUN_ID>")
        kernel = run.get("kernel", "?")
        total = int(run.get("total") or 0)
        if total <= 0:
            problems.append(f"{kernel}/{rid} 没有用例行")
            continue
        coverage = float(run.get("coveragePct") or 0.0)
        if round(coverage, 2) < 100.0:
            problems.append(
                f"{kernel}/{rid} 覆盖率 {coverage}% < 100%"
                f"（with={run.get('withCommand')} none={run.get('explicitNone')} total={total}）")
        if int(run.get("nullLegacy") or 0) > 0:
            problems.append(
                f"{kernel}/{rid} 有 {run.get('nullLegacy')} 条历史 NULL 行（V11_24 之前入库），"
                "必须重跑一次真实 run 后重新取证")
        for source, count in (run.get("sources") or {}).items():
            if source not in ALLOWED_SOURCES:
                problems.append(f"{kernel}/{rid} 出现非法命令来源 {source!r}（{count} 条）")

    samples = [s for run in runs for s in (run.get("samples") or [])]
    if not samples:
        problems.append("证据里没有抽样，无法核对命令原文")
    for sample in samples:
        text = (sample.get("commandText") or "").strip()
        if not text:
            problems.append(f"抽样 {sample.get('caseKey')} 的命令为空（应为原文或 NONE）")
        elif text != "NONE" and len(text) < 2:
            problems.append(f"抽样 {sample.get('caseKey')} 的命令过短，疑似误识别：{text!r}")

    return problems


def load_evidence(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def self_check() -> int:
    """负向自证：把三种典型脏数据喂给判据，必须都判红。

    只证明「能判红」不够，还要证明「干净数据判绿」，否则判据可能恒定报错。
    """
    good = {
        "allowedSources": ALLOWED_SOURCES,
        "runs": [
            {"kernel": "RF", "runId": "R-RF", "total": 10, "withCommand": 9,
             "explicitNone": 1, "nullLegacy": 0, "coveragePct": 100.0,
             "sources": {"RF_KEYWORD": 9, "NONE": 1},
             "samples": [{"caseKey": "a.robot.case-1", "commandText": "ls -l"}]},
            {"kernel": "PYTEST", "runId": "R-PY", "total": 12, "withCommand": 12,
             "explicitNone": 0, "nullLegacy": 0, "coveragePct": 100.0,
             "sources": {"STDOUT_EXTRACT": 12},
             "samples": [{"caseKey": "t.py::test_cmd", "commandText": "iperf3 -c 192.0.2.10"}]},
        ],
    }
    bad_coverage = json.loads(json.dumps(good))
    bad_coverage["runs"][0]["coveragePct"] = 92.5
    bad_coverage["runs"][0]["withCommand"] = 8
    bad_source = json.loads(json.dumps(good))
    bad_source["runs"][1]["sources"]["GUESSED"] = 3
    bad_missing_kernel = json.loads(json.dumps(good))
    bad_missing_kernel["runs"] = [bad_missing_kernel["runs"][0]]
    bad_legacy = json.loads(json.dumps(good))
    bad_legacy["runs"][1]["nullLegacy"] = 4
    bad_empty_cmd = json.loads(json.dumps(good))
    bad_empty_cmd["runs"][0]["samples"] = [{"caseKey": "a.robot.case-1", "commandText": ""}]

    cases = [
        ("干净数据", good, True),
        ("覆盖率不足", bad_coverage, False),
        ("非法来源", bad_source, False),
        ("缺一个内核", bad_missing_kernel, False),
        ("历史 NULL 行", bad_legacy, False),
        ("抽样命令为空", bad_empty_cmd, False),
    ]
    fails = 0
    for label, payload, expect_pass in cases:
        problems = check_evidence(payload)
        ok = not problems
        if ok == expect_pass:
            print(f"  SELFCHECK {label}: {'PASS' if ok else 'RED'}（符合预期）")
        else:
            print(f"  SELFCHECK {label}: 不符合预期，problems={problems}")
            fails += 1

    # 静态判据的负向自证：临时把某个必需片段从副本里抹掉，必须报缺。
    with tempfile.TemporaryDirectory() as tmp:
        rel = "frontend/src/api/reports.ts"
        target = Path(tmp) / "reports.ts"
        target.write_text(
            (ROOT / rel).read_text(encoding="utf-8").replace("commandSource", ""),
            encoding="utf-8")
        text = target.read_text(encoding="utf-8")
        if "commandSource" in text:
            print("  SELFCHECK 静态缺片段: 抹除失败，自证无效")
            fails += 1
        else:
            print("  SELFCHECK 静态缺片段: RED（符合预期）")

    # 脱敏判据负向自证（TC-v4.0-03）：干净证据必须绿，漏脱敏必须红。
    good_redaction = {
        "maskedToken": "<redacted>",
        "cases": [
            {"id": "kv", "class": "kv", "input": "password=hunter2", "secret": "hunter2",
             "masked": "password=<redacted>", "leakedBefore": True, "leakedAfter": False,
             "innocent": False},
            {"id": "bearer", "class": "bearer", "input": "Authorization: Bearer tk.abc",
             "secret": "tk.abc", "masked": "Authorization: Bearer <redacted>",
             "leakedBefore": True, "leakedAfter": False, "innocent": False},
            {"id": "url", "class": "url", "input": "mysql://root:pw@h/db", "secret": "pw",
             "masked": "mysql://root:<redacted>@h/db", "leakedBefore": True,
             "leakedAfter": False, "innocent": False},
            {"id": "cli", "class": "cli", "input": "sshpass -p pw ssh h", "secret": "pw",
             "masked": "sshpass -p <redacted> ssh h", "leakedBefore": True,
             "leakedAfter": False, "innocent": False},
            {"id": "ok-port", "class": "innocent", "input": "ssh -p 22 -u root h",
             "secret": "", "masked": "ssh -p 22 -u root h", "leakedBefore": False,
             "leakedAfter": False, "innocent": True},
            {"id": "ok-var", "class": "innocent",
             "input": "robot --variable TIMEOUT:30s a.robot", "secret": "",
             "masked": "robot --variable TIMEOUT:30s a.robot", "leakedBefore": False,
             "leakedAfter": False, "innocent": True},
        ],
    }
    bad_residual = json.loads(json.dumps(good_redaction))
    bad_residual["cases"][0]["masked"] = "password=hunter2"
    bad_residual["cases"][0]["leakedAfter"] = True
    bad_missing_class = json.loads(json.dumps(good_redaction))
    bad_missing_class["cases"] = [
        case for case in bad_missing_class["cases"] if case["class"] != "url"]
    bad_overmask = json.loads(json.dumps(good_redaction))
    bad_overmask["cases"][4]["masked"] = "ssh -p <redacted> -u root h"

    for label, payload, expect_pass in [
        ("脱敏干净证据", good_redaction, True),
        ("脱敏后残留口令", bad_residual, False),
        ("缺少 URL 类样本", bad_missing_class, False),
        ("正常命令被误脱敏", bad_overmask, False),
    ]:
        problems = check_redaction(payload)
        ok = not problems
        if ok == expect_pass:
            print(f"  SELFCHECK {label}: {'PASS' if ok else 'RED'}（符合预期）")
        else:
            print(f"  SELFCHECK {label}: 不符合预期，problems={problems}")
            fails += 1

    print(f"SELFCHECK_RESULT FAILS={fails}")
    return 1 if fails else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="用例级命令契约门禁（第 28 步）")
    parser.add_argument("--self-check", action="store_true", help="只做判据负向自证")
    parser.add_argument("--evidence", default=str(EVIDENCE))
    args = parser.parse_args()

    if args.self_check:
        return self_check()

    problems = check_static()
    problems.extend(check_single_source())

    payload = load_evidence(Path(args.evidence))
    if payload is None:
        problems.append(f"证据缺失或不可解析：{args.evidence}"
                        "（先跑 tools/case_command_report.py 取真实 run 数据）")
    else:
        problems.extend(check_evidence(payload))

    redaction = load_evidence(V40_EVIDENCE)
    if redaction is None:
        problems.append(f"脱敏证据缺失或不可解析：{V40_EVIDENCE}"
                        "（先跑 scripts/redaction-evidence.sh 重新取证）")
    else:
        problems.extend(check_redaction(redaction))

    if problems:
        print("CASE_COMMAND FAIL")
        for item in problems:
            print(f"  - {item}")
        return 1

    runs = payload["runs"]
    print("CASE_COMMAND PASS")
    for run in runs:
        print(f"  {run['kernel']:<7} {run['runId']} total={run['total']} "
              f"coverage={run['coveragePct']}% with={run['withCommand']} "
              f"none={run['explicitNone']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
