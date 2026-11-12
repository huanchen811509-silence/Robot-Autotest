#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""命令集合机检（v5.3.0 / FR-v5.3-02、W53-02）。

为什么需要这条机检
------------------
命令集合把「这个 run 发了哪些命令」变成一等数据。它有两个一旦出错就**无法事后补救**的属性：

1. **脱敏**：明文口令一旦写进 `commands.jsonl`，那份文件已经跟着证据归档、可能已经
   被打包发给外部。事后删掉记录也删不掉已经流出去的副本。所以必须在**写盘前**拦住，
   而拦住的办法就是「拿一份真实样本逐行验」，不能让 `-pXXX` 这种形态漏过去。
2. **字段契约**：字段名是 `check` 与前端、与 PRD 三方的共同约定。少一个字段，
   下游的过滤/统计会静默变成空值——空值和「本来就没有」长得一模一样，最难发现。

守什么
------
1. **字段契约**：每行必须是含齐 14 个字段的 JSON 对象（PRD R1 的 13 项 + R4 需要的
   `case_code`，偏差已记入 `docs/plan` 的偏差清单）；
2. **敏感项全掩码**：`sensitive=true` 的行里，`argv` 不得出现看起来像口令的片段；
   全文件不得出现 `-p<非数字>`、`--password=...`、`password=...` 的明文值；
3. **截断有标记**：超长参数必须以 `…` 结尾，日志行的截断必须带 `[truncated`。
4. **代码侧契约同源**：即使当前没有样本文件，也要校验 `CommandSetService.toJson`
   写出的字段集合与预期一致——否则「没有样本 ⇒ 跳过检查」会让门禁在无执行的情况下
   永远绿，等于没装。

用法
----
    python3 tools/check_command_set.py              # 常规检查
    python3 tools/check_command_set.py --self-test   # 破坏性自检：注入明文口令，必须被拦

样本从哪里来：`execution.command-set-root`（默认 `~/robot-evidence`）下的
`<runId>/commands.jsonl`。仓库里没有样本时只做代码侧契约校验，并**明确打印**
「无样本，仅校验代码契约」，不假装全验过。

退出码：0 = 通过；1 = 违规；2 = 扫描失败（找不到源码文件等，疑似假绿）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
           / "application" / "execution" / "monitor" / "CommandSetService.java")
RECORD = (ROOT / "backend" / "src" / "main" / "java" / "com" / "qa" / "autotest"
          / "application" / "execution" / "monitor" / "CommandRecord.java")

#: PRD R1 的 13 项 + R4 需要的 case_code = 14
EXPECTED_FIELDS = [
    "cmd_id", "run_id", "node_id", "case_code", "seq", "endpoint", "kind", "argv",
    "started_at", "duration_ms", "exit_code", "stdout_sha", "truncated", "sensitive",
]

KIND_VALUES = {"collect", "configure", "traffic", "verify", "cleanup", "other"}

#: 明文凭据形态。与 Java 侧 SensitiveMask 的规则保持一致（改一处必须改两处）。
PLAINTEXT_PATTERNS = [
    re.compile(r"(?<!\S)-p(?![0-9])(?!\*\*\*)(\S+)"),          # -pXXX（排除端口）
    re.compile(r"(?i)--(?:password|passwd|token|secret|community"
               r"|passphrase|api-key)(?:=|\s+)(?!\*\*\*)(\S+)"),
    re.compile(r"(?i)(?:^|\s)(?:password|passwd|pwd|secret|token|api[_-]?key"
               r"|community|passphrase)\s*=\s*(?!\*\*\*)(\S+)"),
]

#: 「后跟一个值」的凭据开关。值往往是**独立的 argv 元素**（`-p Sup3rSecret`），
#: 只对 join 后的字符串跑正则会漏掉——`-p` 与值之间的空格让 `\S+` 匹配不上。
VALUE_FLAGS = {
    "-p", "--password", "--passwd", "--token", "--secret", "--community",
    "--passphrase", "--api-key", "--api_key", "--access-key",
}

MAX_ARG_CHARS = 200


def fail(message: str) -> None:
    print(f"  FAIL   {message}")


def ok(message: str) -> None:
    print(f"  OK     {message}")


def check_source_contract() -> list[str]:
    """代码侧字段契约：toJson 里 put 的键必须恰好是 14 个且与预期同集。"""
    problems: list[str] = []
    if not SERVICE.is_file():
        return [f"找不到 {SERVICE}"]
    text = SERVICE.read_text(encoding="utf-8")
    if "static String toJson" not in text:
        return ["CommandSetService 里找不到 toJson 方法（契约位置变了，检查脚本要同步）"]
    body = text.split("static String toJson", 1)[1]
    body = body.split("static CommandRecord fromJson", 1)[0]
    written = set(re.findall(r'node\.put(?:Array)?\("([a-z_]+)"', body))
    missing = [name for name in EXPECTED_FIELDS if name not in written]
    extra = sorted(written - set(EXPECTED_FIELDS))
    if missing:
        problems.append(f"toJson 缺字段：{', '.join(missing)}")
    if extra:
        problems.append(f"toJson 多出未登记字段：{', '.join(extra)}（新增字段须先写进 PRD 与本脚本）")
    if not missing and not extra:
        ok(f"toJson 字段集合 = {len(EXPECTED_FIELDS)} 项（与 PRD R1 + R4 一致）")

    if not RECORD.is_file():
        problems.append(f"找不到 {RECORD}")
        return problems
    record = RECORD.read_text(encoding="utf-8")
    # 超过 MAX_ARG_CHARS 时必须加省略号，否则「截断」会静默成「就是这么短」
    if "MAX_ARG_CHARS" not in record:
        problems.append("CommandRecord 缺少 MAX_ARG_CHARS 上限常量")
    for token in ("truncate(", "SensitiveMask.mask", "looksSensitive"):
        if token not in record:
            problems.append(f"CommandRecord 缺少 {token}（脱敏或截断链路被切断）")
    if not problems:
        ok("CommandRecord 保留截断标记与两级掩码链路")
    return problems


def check_samples(root: Path) -> tuple[list[str], int]:
    """逐行校验真实样本。返回（问题列表, 检查过的文件数）。"""
    problems: list[str] = []
    files = sorted(root.glob("*/commands.jsonl")) if root.is_dir() else []
    if not files:
        return problems, 0
    for file in files:
        run_dir = file.parent.name
        for lineno, line in enumerate(file.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            where = f"{run_dir}/commands.jsonl:{lineno}"
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                problems.append(f"{where} 不是合法 JSON：{error.msg}")
                continue
            if not isinstance(row, dict):
                problems.append(f"{where} 行不是 JSON 对象")
                continue
            missing = [name for name in EXPECTED_FIELDS if name not in row]
            if missing:
                problems.append(f"{where} 缺字段：{', '.join(missing)}")
                continue
            if row["kind"] not in KIND_VALUES:
                problems.append(f"{where} kind={row['kind']!r} 不在 {sorted(KIND_VALUES)}")
            if not isinstance(row["argv"], list):
                problems.append(f"{where} argv 不是数组")
                continue
            problems.extend(_check_argv(where, row))
            problems.extend(_check_truncation(where, row))
    return problems, len(files)


def _check_argv(where: str, row: dict) -> list[str]:
    problems: list[str] = []
    argv = [str(x) for x in row["argv"]]
    joined = " ".join(argv)

    # ① token 级：开关在后、值在前一个元素。这是最常漏的形态。
    for index, token in enumerate(argv[:-1]):
        if token.lower() not in VALUE_FLAGS:
            continue
        value = argv[index + 1]
        if value == "***" or value.isdigit():
            # *** 已掩码；纯数字是端口（ssh -p 2222），不是口令
            continue
        problems.append(f"{where} 凭据开关 {token} 的值未掩码：{value[:40]}")

    # ② 字符串级：合写形态 -pXXX / --password=XXX / password=XXX
    for pattern in PLAINTEXT_PATTERNS:
        match = pattern.search(joined)
        if not match:
            continue
        captured = match.group(match.lastindex or 1)
        if captured in ("***", ""):
            continue
        problems.append(f"{where} 出现明文凭据形态：{match.group(0)[:40]}")

    if row.get("sensitive") is True and "***" not in joined:
        # 标了敏感却一个掩码都没有，说明标记与内容是矛盾的
        problems.append(f"{where} sensitive=true 但 argv 里没有掩码")
    return problems


def _check_truncation(where: str, row: dict) -> list[str]:
    problems: list[str] = []
    for arg in row["argv"]:
        text = str(arg)
        if len(text) > MAX_ARG_CHARS + 1 and not text.endswith("…"):
            problems.append(f"{where} 参数长度 {len(text)} 超过上限但无截断标记")
    return problems


def run(sample_root: Path) -> int:
    print(f"[check_command_set] 源码={SERVICE.relative_to(ROOT)}")
    problems = check_source_contract()
    sample_problems, file_count = check_samples(sample_root)
    problems.extend(sample_problems)
    if file_count == 0:
        print(f"  NOTE   无命令集合样本（{sample_root} 下无 */commands.jsonl），"
              "本次只校验代码侧契约")
    else:
        ok(f"校验 {file_count} 份 commands.jsonl")
    if problems:
        for message in problems:
            fail(message)
        print(f"check_command_set: FAIL（{len(problems)} 项）")
        return 1
    print("check_command_set: PASS")
    return 0


def self_test() -> int:
    """真注入：写一份带明文口令的样本，检查函数必须判红。"""
    import tempfile

    print("--- self-test：注入明文口令与缺字段样本后应判失败 ---")
    with tempfile.TemporaryDirectory() as tmp:
        run_dir = Path(tmp) / "RUN-SELFTEST"
        run_dir.mkdir(parents=True)
        good = {
            "cmd_id": "RUN-SELFTEST#1", "run_id": "RUN-SELFTEST", "node_id": None,
            "case_code": "a.py::t", "seq": 1, "endpoint": "10.0.0.1", "kind": "traffic",
            "argv": ["iperf3", "-c", "10.0.0.2"], "started_at": "2026-11-12T10:00:00Z",
            "duration_ms": 120, "exit_code": 0, "stdout_sha": "0123456789abcdef",
            "truncated": False, "sensitive": False,
        }
        bad_secret = dict(good, argv=["sshpass", "-p", "Sup3rSecret", "iperf3"])
        bad_field = dict(good)
        bad_field.pop("case_code")
        bad_trunc = dict(good, argv=["x" * (MAX_ARG_CHARS + 30)])
        lines = [json.dumps(bad_secret), json.dumps(bad_field), json.dumps(bad_trunc)]
        (run_dir / "commands.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        problems, count = check_samples(Path(tmp))
        expected_hits = ("值未掩码", "缺字段", "截断标记")
        hit = {key: any(key in p for p in problems) for key in expected_hits}
        if count != 1 or not all(hit.values()):
            print(f"  FAIL   自检未按预期判红：count={count}, hits={hit}, 问题={problems}")
            print("check_command_set: SELF-TEST FAIL")
            return 1
        print(f"  OK     三类缺陷全部被拦（{len(problems)} 项）")

    print("check_command_set: SELF-TEST PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="命令集合 schema / 脱敏 / 截断机检")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检：注入缺陷必须判红")
    parser.add_argument("--root", default=str(Path.home() / "robot-evidence"),
                        help="命令集合样本根目录（默认 ~/robot-evidence）")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(Path(args.root))


if __name__ == "__main__":
    sys.exit(main())
