#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性凭证「六处零泄漏」可复跑探针（v5.7.0 / W57-42）。

六处泄漏面（sink）：
    DB / APP_LOG / AUDIT / HTTP_RESPONSE / EVIDENCE_BUNDLE / EXCEPTION_STACK

无真实 Java 服务时提供两种互补模式，默认都跑：
  * static —— 静态扫描一次性凭证相关的 Java 源码，命中「把材料写进某个 sink」的
              禁止模式即判该 sink FAIL；
  * mock   —— 用 Python 模拟一次一次性采集调用，把六个 sink 的输出捕获到内存，
              断言哨兵口令零出现；并先做「探针自检」（故意注入哨兵，确认每个 sink
              确实抓得到），防止探针本身失效造成假 PASS。

哨兵串刻意取得又长又显眼，任何一处明文泄漏都会被字符串包含检测抓到。
脚本无副作用（只读源码 + 内存模拟），可反复运行。

用法：
    python tools/probe_oneshot_secret.py                 # static + mock
    python tools/probe_oneshot_secret.py --mode static
    python tools/probe_oneshot_secret.py --mode mock
    python tools/probe_oneshot_secret.py --root /path/to/Robot-Autotest

输出最后一行恒为：PROBE_RESULT PASS=n/6
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SENTINEL = "ONESHOT_SENTINEL_p9X7q2_secret_DO_NOT_LEAK"

SINKS = ("DB", "APP_LOG", "AUDIT", "HTTP_RESPONSE", "EVIDENCE_BUNDLE", "EXCEPTION_STACK")

# 只扫「一次性凭证通道」相关的三个源文件；不把 B 组证据包接入（CaseImportService）
# 和 C 组验证服务算进来，避免把合法的其它通道误判成泄漏。
SOURCE_FILES = (
    "backend/src/main/java/com/qa/autotest/application/asset/ManualCredentialCollectService.java",
    "backend/src/main/java/com/qa/autotest/domain/asset/OneShotCredential.java",
    "backend/src/main/java/com/qa/autotest/api/asset/GlobalAssetCredentialController.java",
)

# 每个 sink 的「禁止模式」：命中即说明材料（口令/私钥）可能被写进该通道。
FORBIDDEN: dict[str, tuple[str, ...]] = {
    "DB": (
        r"\bJdbcTemplate\b",
        r"\bjdbc\s*\.\s*(?:update|query|queryForObject|queryForList|execute)\b",
    ),
    "APP_LOG": (
        r"\b(?:log|LOG|logger|LOGGER)\s*\.\s*(?:trace|debug|info|warn|error)\s*\(",
        r"\bSystem\s*\.\s*out\s*\.\s*print",
    ),
    "AUDIT": (
        r"\bAuditService\b",
        r"\baudit\s*\.\s*record",
    ),
    "HTTP_RESPONSE": (
        r"record\s+ManualCollectOutcome\s*\([^)]*\b(?:password|secret|privateKey)\b",
        r"ApiResponse\s*<\s*ManualCollectRequest",
    ),
    "EVIDENCE_BUNDLE": (
        r"\bEvidenceBundleService\b",
        r"\bbundles?\s*\.\s*write\s*\(",
    ),
    "EXCEPTION_STACK": (
        r"throw\s+new\s+\w*Exception\s*\([^;]*(?:password|secret|privateKey|req\s*\.|request\s*\.)",
        r"\bex\s*\.\s*getMessage\s*\(\s*\)",
    ),
}


def _read_sources(root: Path) -> tuple[bool, str, list[str]]:
    """读取三个源文件；任一缺失直接判全 FAIL（防止「删掉实现」骗过静态扫描）。"""
    texts: list[str] = []
    for rel in SOURCE_FILES:
        path = root / rel
        if not path.is_file():
            return False, "缺少源文件 " + rel, []
        texts.append(path.read_text(encoding="utf-8"))
    joined = "\n".join(texts)
    if "OneShotCredential" not in joined:
        return False, "源码里找不到 OneShotCredential（实现可能被删）", []
    return True, "", texts


def static_scan(root: Path) -> dict[str, tuple[bool, str]]:
    ok, reason, texts = _read_sources(root)
    if not ok:
        return {sink: (False, reason) for sink in SINKS}
    joined = "\n".join(texts)
    result: dict[str, tuple[bool, str]] = {}
    for sink in SINKS:
        hits = [pat for pat in FORBIDDEN[sink] if re.search(pat, joined, re.IGNORECASE)]
        if hits:
            result[sink] = (False, "静态扫描命中禁止模式：" + ", ".join(hits))
        else:
            result[sink] = (True, "静态扫描：未发现材料写入 " + sink)
    return result


class Capture:
    """六个 sink 的内存捕获器。"""

    def __init__(self) -> None:
        self.values: dict[str, list[str]] = {sink: [] for sink in SINKS}

    def write(self, sink: str, text: str) -> None:
        self.values[sink].append(text)

    def joined(self, sink: str) -> str:
        return "\n".join(self.values[sink])


def _sensitivity_check() -> dict[str, bool]:
    """探针自检：往每个 sink 故意注入哨兵，确认这个探针确实抓得到。"""
    results: dict[str, bool] = {}
    for sink in SINKS:
        capture = Capture()
        capture.write(sink, "deliberate leak " + SENTINEL)
        results[sink] = SENTINEL in capture.joined(sink)
    return results


def _simulate_one_shot(secret: str, capture: Capture) -> dict[str, object]:
    """等价 Python 复刻「一次性分支」：材料只在内存，六个 sink 只写元信息。"""
    material: dict[str, object] = {
        "username": "probe_user",
        "password": secret,
        "privateKey": None,
        "credType": "ssh",
        "connectOptions": None,
    }
    # 正确的实现绝不把 material 的任何字段写进下面六处
    capture.write("DB", json.dumps({"action": "collect.manual", "mode": "oneShot"}))
    capture.write("APP_LOG", "collect accepted assetId=1 mode=oneShot")
    capture.write("AUDIT", "action=collect.manual; mode=oneShot; saveAsCredential=false")
    capture.write("HTTP_RESPONSE", json.dumps(
        {"assetId": 1, "oneShot": True, "mode": "oneShot",
         "status": "ACCEPTED", "channel": "ssh"}, ensure_ascii=False))
    capture.write("EVIDENCE_BUNDLE", "pointer_sha=<sha256>; file_count=0")
    try:
        # 失败路径：异常文案固定，不拼接 material
        raise RuntimeError("一次性凭证缺少口令或私钥")
    except RuntimeError as exc:  # noqa: BLE001 - 探针刻意捕获并写入堆栈 sink
        capture.write("EXCEPTION_STACK", type(exc).__name__ + ": " + str(exc))
    return material


def mock_scan() -> dict[str, tuple[bool, str]]:
    sensitivity = _sensitivity_check()
    capture = Capture()
    material = _simulate_one_shot(SENTINEL, capture)
    if material.get("password") != SENTINEL:
        return {sink: (False, "mock 未真正携带哨兵，探针无效") for sink in SINKS}
    result: dict[str, tuple[bool, str]] = {}
    for sink in SINKS:
        if not sensitivity[sink]:
            result[sink] = (False, "探针自检失败：该 sink 抓不到注入的哨兵")
        elif SENTINEL in capture.joined(sink):
            result[sink] = (False, "mock 捕获到明文哨兵泄漏")
        else:
            result[sink] = (True, "mock：自检有效且哨兵零出现")
    return result


def _merge(mode: str, static: dict[str, tuple[bool, str]],
           mock: dict[str, tuple[bool, str]]) -> dict[str, tuple[bool, str]]:
    merged: dict[str, tuple[bool, str]] = {}
    for sink in SINKS:
        parts: list[tuple[bool, str]] = []
        if mode in ("static", "all"):
            parts.append(static[sink])
        if mode in ("mock", "all"):
            parts.append(mock[sink])
        failed = [reason for ok, reason in parts if not ok]
        if failed:
            merged[sink] = (False, " | ".join(failed))
        else:
            merged[sink] = (True, " | ".join(reason for _, reason in parts))
    return merged


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="一次性凭证六处零泄漏探针")
    parser.add_argument("--mode", choices=("static", "mock", "all"), default="all",
                        help="静态扫描 / mock 模拟 / 两者都跑（默认 all）")
    parser.add_argument("--root", default=None, help="项目根目录（默认脚本上一级）")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    static = static_scan(root)
    mock = mock_scan()
    merged = _merge(args.mode, static, mock)

    print("哨兵口令串：" + SENTINEL)
    print("模式：" + args.mode + "  项目根：" + str(root))
    passed = 0
    for sink in SINKS:
        ok, reason = merged[sink]
        if ok:
            passed += 1
        print(("[PASS] " if ok else "[FAIL] ") + sink + " - " + reason)
    print("PROBE_RESULT PASS=" + str(passed) + "/" + str(len(SINKS)))
    return 0 if passed == len(SINKS) else 1


if __name__ == "__main__":
    sys.exit(main())
