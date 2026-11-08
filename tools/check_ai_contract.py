#!/usr/bin/env python3
"""AI 契约静态机检（FR-AI-02 / FR-AI-07 / CN4 / CN5）。

可独立运行：python3 tools/check_ai_contract.py

判据：
  1. application/ai、api/ai、domain/ai 的主源码内零 --prompt-file；
  2. 零硬编码 API key / Bearer 令牌 / sk- / AIza 密钥；
  3. 零 git commit / git push / git apply 等 L4-L5 自动落地调用；
  4. 真实调用链存在（AiProcessManager 用 ProcessBuilder，且受 FEATURE_AI_REAL_CLI 闸门）；
  5. 假 CLI 夹具 fixtures/ai/fake-cli.sh 存在且输出 FAKE_CLI_OK；
  6. V11_9 迁移 up/down 成对。

只扫描 src/main 下的 AI 源码：测试里的负向断言允许出现禁用串，
产品 argv 与生产代码才是本守门要保护的对象。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
AI_MAIN_DIRS = [
    JAVA_ROOT / "application" / "ai",
    JAVA_ROOT / "api" / "ai",
    JAVA_ROOT / "domain" / "ai",
]
PROCESS_MANAGER = AI_MAIN_DIRS[0] / "AiProcessManager.java"
FAKE_CLI = ROOT / "fixtures" / "ai" / "fake-cli.sh"
MIGRATION_UP = ROOT / "db" / "migration" / "V11_9__ai_runtime.sql"
MIGRATION_DOWN = ROOT / "db" / "down" / "V11_9__ai_runtime.down.sql"

BANNED_PROMPT_FILE = "--prompt-file"
SECRET_PATTERNS = [
    re.compile(r"Bearer\s+[A-Za-z0-9._\-]{8,}"),
    re.compile(r"(?i)\b(api[_-]?key|apikey|secret[_-]?key)\s*[:=]\s*[\"'][^\"']{8,}[\"']"),
    re.compile(r"\bsk-[A-Za-z0-9]{16,}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_\-]{20,}\b"),
]
L45_PATTERNS = [
    re.compile(r"git\s+commit"),
    re.compile(r"git\s+push"),
    re.compile(r"git\s+apply"),
    re.compile(r"git\s+checkout\s+--"),
]

failures: list[str] = []


def java_sources() -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for directory in AI_MAIN_DIRS:
        if not directory.is_dir():
            failures.append("AI 源码目录缺失：" + str(directory.relative_to(ROOT)))
            continue
        files.extend(sorted(directory.glob("*.java")))
    return files


def main() -> int:
    sources = java_sources()
    if not sources:
        failures.append("未扫描到任何 AI 主源码 .java")

    for path in sources:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT)
        for lineno, line in enumerate(text.splitlines(), 1):
            if BANNED_PROMPT_FILE in line:
                failures.append(f"{rel}:{lineno}: 出现 {BANNED_PROMPT_FILE}")
            for pattern in SECRET_PATTERNS:
                if pattern.search(line):
                    failures.append(f"{rel}:{lineno}: 疑似硬编码凭据 / API key")
            for pattern in L45_PATTERNS:
                if pattern.search(line):
                    failures.append(f"{rel}:{lineno}: 疑似 L4-L5 自动落地调用")

    if not PROCESS_MANAGER.is_file():
        failures.append("缺少 AiProcessManager.java")
    else:
        pm = PROCESS_MANAGER.read_text(encoding="utf-8")
        if "ProcessBuilder" not in pm:
            failures.append("AiProcessManager.java 未使用 ProcessBuilder（真实调用链缺失）")
        if "realCliFeatureEnabled" not in pm:
            failures.append("AiProcessManager.java 未接 FEATURE_AI_REAL_CLI 闸门")

    if not FAKE_CLI.is_file():
        failures.append("缺少 fixtures/ai/fake-cli.sh 假 CLI 夹具")
    elif "FAKE_CLI_OK" not in FAKE_CLI.read_text(encoding="utf-8"):
        failures.append("假 CLI 夹具不含固定可断言字符串 FAKE_CLI_OK")

    if not MIGRATION_UP.is_file():
        failures.append("缺少 db/migration/V11_9__ai_runtime.sql")
    if not MIGRATION_DOWN.is_file():
        failures.append("缺少 db/down/V11_9__ai_runtime.down.sql（up/down 必须成对）")

    if failures:
        print("[FAIL] AI 契约机检未通过：")
        for item in failures:
            print("  - " + item)
        return 1
    print(f"[OK] AI 契约机检通过（扫描 {len(sources)} 个 AI 主源码文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
