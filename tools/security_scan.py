#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.4.0 · 安全扫描（门禁第 25 步，FR-3.4-03）。

三段扫描，全部离线、零第三方依赖（仓库已有依赖之外不再引入）：

  1. 密钥扫描：git 跟踪的源码/脚本里出现私钥块、云厂商访问键、GitHub/Slack token、
     以及长度 ≥16 的明文口令赋值，即判高危；
  2. SAST：Java 里的非预编译 Statement、Runtime.exec 拼接、printStackTrace 记为
     中/低危；只有「直接拼 SQL」才升高危；
  3. 依赖：pom.xml 命中已知危险版本denylist（log4j-core<2.17、snakeyaml<1.32、
     commons-collections 3.2.1、spring-core<5.3.18、fastjson<1.2.83）即高危；
     并校验依赖版本已固定（无 SNAPSHOT / LATEST / RELEASE 浮动版本）。

输出 docs/evidence/v3.4.0/security-scan.json。
退出码：0 = 高危 0；1 = 存在高危；2 = 扫描器自身未能完成（不可当作通过）。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_FILE = Path(os.environ.get("SECURITY_SCAN_OUT", ROOT / "docs/evidence/v3.4.0/security-scan.json"))

SCAN_DIRS = ("backend/src/main", "frontend/src", "scripts", "tools", "config", "deploy",
             "docs/plan", "examples")
ROOT_FILES = ("VERSION", "CHANGELOG.md", "README.md", "AGENTS.md", "Makefile")
TEXT_EXT = {".java", ".py", ".sh", ".mjs", ".ts", ".vue", ".xml", ".yml", ".yaml", ".json",
            ".properties", ".md", ".env", ".conf", ".sql"}
SKIP_PARTS = ("/target/", "/node_modules/", "/dist/", "/.git/", "/docs/evidence/")

HIGH_SECRETS = (
    ("private-key", re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC |PGP |DSA )?PRIVATE KEY-----")),
    ("aws-access-key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("github-token", re.compile(r"gh[pousr]_[A-Za-z0-9]{36}")),
    ("slack-token", re.compile(r"xox[abp]-[A-Za-z0-9-]{10,}")),
    ("google-api-key", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
)
PASSWORD_ASSIGN = re.compile(
    r"(?:password|passwd|secret|token)\s*[=:]\s*['\"]([A-Za-z0-9!@#$%^&*_+\-]{16,})['\"]",
    re.I)
PLACEHOLDER = re.compile(
    r"(?:changeme|example|placeholder|dummy|fake|redact|xxxx|your-|<|\\$\\{|\\$env)", re.I)
# 环境变量**名**不是口令：`ENV_SSH_PASSWORD` 赋成另一个环境变量名（如 PYTEST_SSH_PASSWORD）时，
# 值本身没有任何密钥信息，是渲染器输出 `${ENV:...}` 引用时用的键名。只豁免
# 「以凭据后缀结尾的全大写标识符」，不放宽到「任何全大写串」——否则把口令写成
# 全大写短串（QA_PASS 的常见测试写法）就会漏掉。
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]*_(?:PASSWORD|PASSWD|SECRET|TOKEN|KEY)$")
ALLOW_MARK = "security-scan: allow"

JAVA_RULES = (
    ("HIGH", "sql-string-concat",
     re.compile(r"(?:executeQuery|executeUpdate|executeBatch)\s*\([^;]*\"[^\"]*\"\s*\+")),
    ("MEDIUM", "raw-statement", re.compile(r"\bcreateStatement\s*\(")),
    ("MEDIUM", "runtime-exec-concat", re.compile(r"Runtime\.getRuntime\(\)\.exec\s*\([^)]*\+")),
    ("LOW", "print-stack-trace", re.compile(r"\.printStackTrace\s*\(")),
)
# 扫描器自身的黑样本与测试夹具会故意写「像密钥的字符串」，按文件豁免而不是放宽规则。
SELF_FIXTURES = {"secret-scan.sh", "check_report_sensitive.py"}
DEP_DENYLIST = (
    ("log4j-core", lambda v: v and _version(v) < (2, 17, 0)),
    ("snakeyaml", lambda v: v and _version(v) < (1, 32)),
    ("commons-collections", lambda v: v == "3.2.1"),
    ("spring-core", lambda v: v and _version(v) < (5, 3, 18)),
    ("fastjson", lambda v: v and _version(v) < (1, 2, 83)),
)


def _version(text: str) -> tuple:
    parts = re.findall(r"\d+", text or "")
    return tuple(int(part) for part in parts[:3]) if parts else (999,)


def tracked_files() -> list[Path]:
    try:
        listed = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True,
                                text=True, check=True).stdout.splitlines()
        files = [ROOT / item for item in listed]
    except Exception:  # noqa: BLE001 - 没有 git 时退回目录遍历
        files = []
        for directory in SCAN_DIRS:
            files.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
        files.extend(ROOT / name for name in ROOT_FILES if (ROOT / name).is_file())
    selected = []
    for path in files:
        text = str(path).replace("\\", "/")
        if any(part in text for part in SKIP_PARTS):
            continue
        # 测试夹具里的口令/令牌是刻意的假数据，纳入密钥扫描只会制造噪声；
        # 依赖与 SNAPSHOT 校验仍然覆盖整个仓库。
        if "/src/test/" in text:
            continue
        if path.suffix.lower() not in TEXT_EXT:
            continue
        if not path.exists() or path.stat().st_size > 2_000_000:
            continue
        selected.append(path)
    return selected


def scan_secrets(path: Path) -> list[dict]:
    findings = []
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return findings
    for lineno, line in enumerate(content.splitlines(), 1):
        if ALLOW_MARK in line or path.name in SELF_FIXTURES:
            continue
        for name, pattern in HIGH_SECRETS:
            if pattern.search(line):
                findings.append({"severity": "HIGH", "rule": name,
                                 "file": str(path.relative_to(ROOT)).replace("\\", "/"),
                                 "line": lineno})
        match = PASSWORD_ASSIGN.search(line)
        if match and not PLACEHOLDER.search(match.group(1)) and not ENV_NAME.match(match.group(1)):
            findings.append({"severity": "HIGH", "rule": "hardcoded-credential",
                             "file": str(path.relative_to(ROOT)).replace("\\", "/"),
                             "line": lineno})
    return findings


def scan_java(path: Path) -> list[dict]:
    findings = []
    if path.suffix.lower() != ".java":
        return findings
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return findings
    for lineno, line in enumerate(content.splitlines(), 1):
        if ALLOW_MARK in line or line.strip().startswith("//") or line.strip().startswith("*"):
            continue
        for severity, name, pattern in JAVA_RULES:
            if pattern.search(line):
                findings.append({"severity": severity, "rule": name,
                                 "file": str(path.relative_to(ROOT)).replace("\\", "/"),
                                 "line": lineno})
    return findings


def scan_dependencies() -> list[dict]:
    findings = []
    pom = ROOT / "backend/pom.xml"
    if not pom.exists():
        return [{"severity": "HIGH", "rule": "pom-missing", "file": "backend/pom.xml"}]
    content = pom.read_text(encoding="utf-8", errors="replace")
    for artifact, risky in DEP_DENYLIST:
        block = re.search(
            r"<dependency>(?:(?!</dependency>).)*?<artifactId>%s</artifactId>.*?</dependency>"
            % re.escape(artifact), content, re.S)
        if not block:
            continue
        version = re.search(r"<version>([^<]+)</version>", block.group(0))
        if version and risky(version.group(1).strip()):
            findings.append({"severity": "HIGH", "rule": "vulnerable-dependency",
                             "file": "backend/pom.xml", "detail": "%s %s"
                             % (artifact, version.group(1).strip())})
    for floating in re.finditer(r"<version>(SNAPSHOT|LATEST|RELEASE|[^<]*SNAPSHOT[^<]*)</version>",
                                content):
        findings.append({"severity": "MEDIUM", "rule": "floating-dependency",
                         "file": "backend/pom.xml", "detail": floating.group(1)})
    if not (ROOT / "frontend/package-lock.json").exists():
        findings.append({"severity": "MEDIUM", "rule": "lockfile-missing",
                         "file": "frontend/package-lock.json"})
    return findings


def main() -> int:
    if not ROOT.exists():
        print("SECURITY_SCAN_ERROR 仓库根不存在", file=sys.stderr)
        return 2
    files = tracked_files()
    if len(files) < 50:
        print("SECURITY_SCAN_ERROR 只扫到 %d 个文件，扫描器失效" % len(files), file=sys.stderr)
        return 2
    findings: list[dict] = []
    for path in files:
        findings.extend(scan_secrets(path))
        findings.extend(scan_java(path))
    findings.extend(scan_dependencies())
    counts = {level: sum(1 for item in findings if item["severity"] == level)
              for level in ("HIGH", "MEDIUM", "LOW")}
    verdict = "PASS" if counts["HIGH"] == 0 else "FAIL"
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(),
              "filesScanned": len(files), "counts": counts, "verdict": verdict,
              "findings": findings,
              "scope": ["secrets", "sast", "dependencies"]}
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    for item in findings:
        if item["severity"] == "MEDIUM":
            print("  WARN %s %s:%s" % (item["rule"], item.get("file"), item.get("line", "")))
    print("SECURITY_SCAN %s files=%d high=%d medium=%d -> %s"
          % (verdict, len(files), counts["HIGH"], counts["MEDIUM"], OUT_FILE))
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
