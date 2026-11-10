#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.11.0 · 门禁第 29 步：主机清单 10 类 + 只读命令白名单（FR-v3.11-01）。

三步判据，缺一不可：

A. **静态事实**——`HostInventoryCollector` 里的 `COMMANDS` 是命令的唯一事实来源。
   本脚本直接解析那份 Java 源码（不另存一份命令清单，否则必然漂移）：
   ① 分类必须正好 10 个，且顺序与 `HostInventory.CATEGORIES` 逐字一致；
   ② 每条命令非空、互不重复、长度可控（命令短才能被人逐条复核）；
   ③ 命令不得含任何写操作（重定向写文件 / rm / mv / dd / systemctl / ip ... set ...），
     且每个可执行名必须在只读白名单内（白名单在下面列全，新增命令要一起改这里）。

B. **接口自述一致**——`docs/evidence/v3.11.0/EW311-01/commands.json` 是
   `GET /api/asset-host-inventory/commands` 的真实响应。它必须与 A 解析出的命令**
   逐字相同**：接口报的命令和真跑的命令不一致，等于证据失效。

C. **真实采集结果**——`docs/evidence/v3.11.0/EW311-01/host-inventory.json` 是
   `POST /api/asset-host-inventory/collect` 采到的本机清单（`spec_json` 原样导出）：
   ① 10 类齐全、顺序正确、无重复；
   ② 每项都带 `command` 与 `collectedAt`（判据②：看得见数是怎么来的）；
   ③ `value` 为空时**必须**带非空 `reason`（判据③：不填假值，也不留空白）；
   ④ `value` 非空时不许是 `-` / `未知` / `0` 这类占位串冒充结果；
   ⑤ `status` 必须与「有值项数」对得上（全采到 OK、一条没有 FAILED、其余 PARTIAL）。

D. **远程采集硬约束**（FR-v4.2-03）——远程是本门禁唯一会「在别人机器上起进程」的路径，
   源码里必须逐条写有：`StrictHostKeyChecking=yes` / `BatchMode=yes` / 拒绝 sudo·su·doas /
   输出上限 / 超时杀进程 / 口令不进 argv / 二次确认 / 凭据走 CREDENTIAL:USE / 审计主机掩码。
   删掉任何一条都判红；`--self-check` 会逐条删除并验证「删哪条红哪条」。

证据缺失判红（不静默放行）：没有一次真实采集，就没有「10 类齐全」这回事。

用法：
  python3 tools/check_host_inventory.py
  python3 tools/check_host_inventory.py --self-check   # 负向自证：脏数据必须判红
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE_DIR = ROOT / "docs/evidence/v3.11.0/EW311-01"
COMMANDS_JSON = EVIDENCE_DIR / "commands.json"
INVENTORY_JSON = EVIDENCE_DIR / "host-inventory.json"

COLLECTOR_JAVA = "backend/src/main/java/com/qa/autotest/application/host/HostInventoryCollector.java"
DOMAIN_JAVA = "backend/src/main/java/com/qa/autotest/domain/host/HostInventory.java"
SERVICE_JAVA = "backend/src/main/java/com/qa/autotest/application/host/HostInventoryService.java"
CONTROLLER_JAVA = "backend/src/main/java/com/qa/autotest/api/host/HostInventoryController.java"
MIGRATION_SQL = "db/migration/V11_26__v3_11_host_inventory.sql"

# 与 HostInventory.CATEGORIES 必须一致；这里写死是为了「两边不一致就报错」，
# 而不是跟着源码一起动（那样就变成了自己给自己判卷）。
CATEGORIES = ["OS", "KERNEL", "CPU", "MEMORY", "DISK", "GPU", "NIC", "IP", "ROCE", "ROUTE"]

# 命令里允许出现的可执行名（只读）。新增采集项必须同步这里，改不动就是设计意图：
# 想加一条会写盘或会装包的命令，得先改这份白名单，而改白名单是要被 review 的。
READONLY_BINARIES = {
    "grep", "uname", "nproc", "lscpu", "free", "df", "lsblk", "nvidia-smi", "ip",
    "cat", "printf", "basename", "head", "tail", "echo", "awk", "cut", "sort", "wc",
    # shell 控制结构（循环体本身仍会被逐段拆分检查）
    "for", "do", "done", "if", "then", "fi",
}

# 写操作黑名单：(正则, 说明)。命中即红——只列出「一定不是读」的写法，避免误伤。
WRITE_PATTERNS = [
    (r"(?<!\d)>{1,2}(?!&)", "重定向写入（>/>>）"),
    (r"\b(rm|rmdir|shred)\b", "删除文件"),
    (r"\b(mv|cp|ln|install)\b", "改动文件系统"),
    (r"\bdd\b", "裸写块设备"),
    (r"\bmkfs(\.\w+)?\b", "格式化"),
    (r"\b(chmod|chown|chgrp|setfacl)\b", "改权限/属主"),
    (r"\b(systemctl|service|journalctl\s+--vacuum)\b", "改动服务状态"),
    (r"\b(reboot|shutdown|halt|poweroff|init\s+[06])\b", "重启/关机"),
    (r"\b(kill|killall|pkill)\b", "杀进程"),
    (r"\b(mount|umount)\b", "挂载/卸载"),
    (r"\b(tee|truncate|touch|mkfifo)\b", "写文件"),
    (r"\b(curl|wget|nc|ncat|telnet|ssh|scp|rsync|socat)\b", "外联/传输"),
    (r"\b(iptables|nft|ufw|ifconfig)\b", "改网络配置"),
    (r"\b(apt|apt-get|yum|dnf|pacman|zypper|pip3?|npm|yarn|snap)\b", "装包"),
    (r"\b(useradd|userdel|usermod|passwd|visudo)\b", "改账号"),
    (r"\bcrontab\b", "改计划任务"),
    (r"\bsed\s+-i", "就地改文件"),
    (r"\bip\s+(link|addr|address|route|neigh)\s+(set|add|del|delete|change|replace|flush)\b",
     "用 ip 改网络"),
]

# value 里出现这些字符串说明在用占位值冒充采集结果（判据③明确禁止）。
PLACEHOLDER_VALUES = {"", "-", "--", "n/a", "na", "none", "null", "nil", "未知", "无", "0"}


# --------------------------------------------------------------------- 解析 Java 源码

_JAVA_PUT_RE = re.compile(
    r'map\.put\(\s*"([A-Z]+)"\s*,\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)\s*;')
_JAVA_STR_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def java_unescape(raw: str) -> str:
    """按 Java 字符串字面量规则还原内容。

    只处理本文件会出现的转义：`\\"` -> `"`、`\\\\` -> `\\`。
    注意 `printf '%s %s\\n'` 在源码里是 `\\\\n`，还原后应是「反斜杠 + n」两个字面字符
    （那是给 printf 的格式串，不是换行），所以这里不把 `\\n` 变成真换行。
    """
    out, i = [], 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            if nxt == "n":
                out.append("\n")
            elif nxt == "t":
                out.append("\t")
            elif nxt == "r":
                out.append("\r")
            else:
                out.append(nxt)
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def parse_java_commands(text: str) -> list[tuple[str, str]]:
    """从 orderedCommands() 里抽出 (分类, 命令)，保序。

    只取那个方法体：`LABELS` 也是 `Map.of(...)`，整文件乱搜会把标签当命令。
    """
    start = text.find("orderedCommands()")
    if start < 0:
        return []
    end = text.find("return java.util.Collections.unmodifiableMap", start)
    body = text[start:end] if end > 0 else text[start:]
    # 跨行拼接的字符串常量先合成一行：`"a"\n + "b"` -> `"ab"` 的等价形态
    body = re.sub(r'"\s*\n\s*\+\s*"', "", body)
    result = []
    for category, expr in _JAVA_PUT_RE.findall(body):
        pieces = _JAVA_STR_RE.findall(expr)
        result.append((category, java_unescape("".join(pieces))))
    return result


def parse_domain_categories(text: str) -> list[str]:
    start = text.find("CATEGORIES = List.of(")
    if start < 0:
        return []
    end = text.find(")", start)
    return re.findall(r'"([A-Z]+)"', text[start:end])


# --------------------------------------------------------------------- 判据 A：命令本身

def check_commands(pairs: list[tuple[str, str]]) -> list[str]:
    problems: list[str] = []
    cats = [c for c, _ in pairs]
    if len(pairs) != 10:
        problems.append(f"采集命令只有 {len(pairs)} 类（应为 10 类：{'/'.join(CATEGORIES)}）")
    if cats != CATEGORIES:
        problems.append(f"分类顺序与 CATEGORIES 不一致：源码={cats} 期望={CATEGORIES}")
    if len(set(cats)) != len(cats):
        problems.append(f"分类有重复：{cats}")

    seen = {}
    for category, command in pairs:
        cmd = command.strip()
        if not cmd:
            problems.append(f"{category} 的命令为空")
            continue
        if len(cmd) > 500:
            problems.append(f"{category} 的命令长达 {len(cmd)} 字符（>500，没人能逐条复核）")
        if cmd in seen:
            problems.append(f"{category} 与 {seen[cmd]} 用了同一条命令（分类不同命令必须不同）")
        seen[cmd] = category
        problems.extend(f"{category}: {p}" for p in command_problems(cmd))
    return problems


def command_problems(command: str) -> list[str]:
    """单条命令是否只读。返回问题列表，空表示通过。"""
    problems = []
    # `2>/dev/null` 是「丢弃 stderr」，只是把输出扔掉，不写任何文件；先摘掉再判重定向。
    probe = re.sub(r"\d?>\s*/dev/null", "", command)
    for pattern, reason in WRITE_PATTERNS:
        hit = re.search(pattern, probe)
        if hit:
            problems.append(f"命令含写操作（{reason}）：{hit.group(0)!r}")
    for token in _binaries(command):
        if token not in READONLY_BINARIES:
            problems.append(f"命令里出现非只读/未登记的可执行名 {token!r}（如确为只读，请补进白名单）")
    return problems


def _binaries(command: str) -> list[str]:
    """按 `;` / `|` / `$(` / 反引号切段，取每段首个可执行名。"""
    tokens = []
    for match in re.finditer(r"(?:^|[;|]|\$\(|`|&&|\|\|)\s*([A-Za-z0-9_.\-]+)", command):
        token = match.group(1)
        if token and token not in tokens:
            tokens.append(token)
    return tokens


# --------------------------------------------------------------------- 判据 B / C：证据

def _parse_time(value) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    text = value.strip().replace("Z", "+00:00")
    try:
        datetime.fromisoformat(text)
        return True
    except ValueError:
        return False


def _is_blank(value) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def _is_placeholder(value) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return value.strip().lower() in PLACEHOLDER_VALUES
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        # 数值 0 单独判：速率/端口数这类「有就是正数」，0 只能是占位
        return value == 0
    return False


def check_api_commands(api_commands: dict, java_commands: dict[str, str]) -> list[str]:
    problems = []
    if not isinstance(api_commands, dict) or not api_commands:
        return ["commands.json 不是非空对象（接口应返回 {分类: 命令}）"]
    if list(api_commands.keys()) != CATEGORIES:
        problems.append(f"接口返回的分类/顺序与判据不一致：{list(api_commands.keys())}")
    for category in CATEGORIES:
        api_cmd = (api_commands.get(category) or "").strip()
        java_cmd = (java_commands.get(category) or "").strip()
        if not api_cmd:
            problems.append(f"接口未返回 {category} 的命令")
        elif api_cmd != java_cmd:
            problems.append(
                f"{category} 接口命令与源码不一致（接口报的和真跑的不是同一条）\n"
                f"      接口: {api_cmd}\n      源码: {java_cmd}")
    return problems


def check_inventory(payload: dict, java_commands: dict[str, str]) -> list[str]:
    """判据 C。payload 是 HostInventory 的 JSON（spec_json 原样导出）。"""
    problems: list[str] = []
    if not isinstance(payload, dict):
        return ["host-inventory.json 不是对象"]

    if _is_blank(payload.get("host")):
        problems.append("清单没有 host（不知道是哪台机器采的）")
    if not _parse_time(payload.get("collectedAt")):
        problems.append(f"清单的 collectedAt 不是合法时间：{payload.get('collectedAt')!r}")

    items = payload.get("items")
    if not isinstance(items, list):
        return problems + ["清单里没有 items 数组"]
    if len(items) != 10:
        problems.append(f"清单只有 {len(items)} 类（应为 10 类）")

    cats = [str(item.get("category", "")) for item in items if isinstance(item, dict)]
    if cats != CATEGORIES:
        problems.append(f"清单分类顺序与 CATEGORIES 不一致：{cats}")
    if len(set(cats)) != len(cats):
        problems.append(f"清单分类有重复：{cats}")

    collected = 0
    for item in items:
        if not isinstance(item, dict):
            problems.append(f"清单项不是对象：{item!r}")
            continue
        category = str(item.get("category", "?"))
        label = item.get("label")
        command = (item.get("command") or "").strip()
        value = item.get("value")
        reason = item.get("reason")

        if _is_blank(label):
            problems.append(f"{category} 缺 label（界面与导出件要显示分类名）")
        # 判据②：每项都要能自证「怎么采的、什么时候采的」
        if not command:
            problems.append(f"{category} 没记 command（判据②：看得见命令）")
        elif command != (java_commands.get(category) or "").strip():
            problems.append(f"{category} 记录的命令与源码白名单不一致（证据漂移）")
        if not _parse_time(item.get("collectedAt")):
            problems.append(f"{category} 的 collectedAt 不是合法时间：{item.get('collectedAt')!r}")

        # 判据③：没采到必须给原因；两个都空 = 空白占位，是最容易混过去的一种
        if _is_blank(value):
            collected += 0
            if _is_blank(reason):
                problems.append(f"{category} 既没有 value 也没有 reason（空白占位不算采集结果）")
        else:
            collected += 1
            if _is_placeholder(value):
                problems.append(f"{category} 的 value 是占位值 {value!r}（判据③：不填假值）")
            if not _is_blank(reason):
                problems.append(f"{category} 有 value 却又带 reason（{reason!r}），语义矛盾")

    status = str(payload.get("status") or "")
    expected = "FAILED" if collected == 0 else ("OK" if collected == len(items) else "PARTIAL")
    if status != expected:
        problems.append(f"status={status!r} 与「{collected}/{len(items)} 项有值」推出的 {expected!r} 不符")
    return problems


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except OSError:
        return None
    except json.JSONDecodeError:
        return None


# --------------------------------------------------------------------- 静态契约（其余文件）

STATIC_CHECKS = [
    (MIGRATION_SQL, ["device_host_spec", "spec_json", "spec_hash", "roce_max_gbps"],
     "迁移建表与摘要列"),
    (DOMAIN_JAVA, ["CATEGORIES", "STATUS_OK", "STATUS_PARTIAL", "STATUS_FAILED"], "10 类与三态"),
    (SERVICE_JAVA, ["sha256", "countOf", "maxRoceGbps"], "入库摘要列与哈希"),
    # `{id:\d+}` 不是洁癖：写成 `{id}` 时 `GET /collect` 会被详情路由吃掉，
    # 回一个「参数类型不正确：id」的 400，而正确答复是 405（2026-11-09 真实抓到）。
    # 用 raw 字符串写针：Java 源码里注解是 `"/{id:\\d+}"`，字面就是两个反斜杠。
    (CONTROLLER_JAVA, ["/collect", "/commands", "requireProjectAccess", r"{id:\\d+}"],
     "显式采集 / 命令公开 / 数字 id 路由"),
    ("backend/src/main/java/com/qa/autotest/infrastructure/HostInventoryRepositoryJdbc.java",
     ["SUMMARY_COLUMNS", 'withJson ? rs.getString("spec_json") : null'],
     "列表不取整份清单（给 null 而不是空串）"),
    ("backend/src/main/java/com/qa/autotest/domain/host/HostInventoryItem.java",
     ["collected()", "reason"], "采集与否的唯一判定入口"),
    ("backend/src/main/java/com/qa/autotest/common/tenant/TenantGuard.java",
     ["device_host_spec"], "多租户表登记（漏登记 = 跨项目可读）"),
]

# --------------------------------------------------------------- W42-03：远程采集硬约束
#
# 远程采集是本门禁唯一会「在别人的机器上起进程」的路径。下面每一条都是**安全边界**，
# 不是代码风格：从源码里删掉一条，本脚本必须判红（负向自证见 self_check）。
# 判据不是「有没有 SSH 类」，而是「这几条边界是不是真的写在代码里」。
SSH_JAVA = "backend/src/main/java/com/qa/autotest/application/host/SshHostCommandRunner.java"
ROUTER_JAVA = "backend/src/main/java/com/qa/autotest/application/host/HostCommandRunnerRouter.java"
ACCESS_JAVA = "backend/src/main/java/com/qa/autotest/application/host/HostAccess.java"
CREDENTIAL_JAVA = "backend/src/main/java/com/qa/autotest/application/asset/CredentialService.java"

# 每条 (文件, 针, 说明)。针取源码里的字面，不取「类名」——类名在，门可能已经没了。
REMOTE_GUARDS = [
    (SSH_JAVA, '"StrictHostKeyChecking=yes"', "远程 SSH 必须校验主机指纹（不能静默接受新主机）"),
    (SSH_JAVA, '"BatchMode=yes"', "禁止交互式提问（无人值守下不能挂起等输入）"),
    (SSH_JAVA, '(sudo|su|doas)', "提权词必须写在拒绝正则里"),
    (SSH_JAVA, "PRIVILEGE.matcher(command).find()", "命令含 sudo/su/doas 必须直接拒绝"),
    (SSH_JAVA, '"SSHPASS"', "口令只走环境变量（ps 看不到 argv）"),
    (SSH_JAVA, "MAX_OUTPUT_BYTES", "单条输出必须有上限"),
    (SSH_JAVA, "已截断", "超上限要显式标注截断（不假装采全）"),
    (SSH_JAVA, "destroyForcibly", "超时必须真的杀进程"),
    (SSH_JAVA, "StrictHostKeyChecking=yes，known_hosts=", "指纹失败要把原因透给用户"),
    (ROUTER_JAVA, "@Primary", "本机/远程由同一个路由 Bean 决定，避免两套通道各写一半"),
    (ROUTER_JAVA, "LocalHostCommandRunner.isLocal(host)", "本机判定只有一个口径"),
    (SERVICE_JAVA, "需要二次确认：请求需带 confirm=true", "远程采集必须二次确认（不误触生产机）"),
    (SERVICE_JAVA, "需要传 credentialId", "远程采集必须显式选凭据"),
    (SERVICE_JAVA, "maskHost(target)", "审计里的主机必须掩码"),
    (SERVICE_JAVA, "audit.recordFailure", "采集失败也要落审计"),
    (CONTROLLER_JAVA, "request.confirm()", "接口要把二次确认标记透传下来"),
    (CREDENTIAL_JAVA, "resolveMaterial", "凭据材料解析必须经 CREDENTIAL:USE"),
    (ACCESS_JAVA, "secret=***", "访问材料不得被日志打出口令"),
]


def remote_problems(overrides: dict[str, str] | None = None) -> list[str]:
    """W42-03 硬约束逐条判红。overrides 供负向自证用（按相对路径替换文件内容）。"""
    problems = []
    for rel, needle, label in REMOTE_GUARDS:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"缺少文件：{rel}（{label}）")
            continue
        text = (overrides or {}).get(rel)
        if text is None:
            text = path.read_text(encoding="utf-8", errors="replace")
        if needle not in text:
            problems.append(f"{rel} 缺少 {needle!r}（{label}）")
    return problems


def check_static() -> list[str]:
    problems = []
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


# --------------------------------------------------------------------- 负向自证

def _item(category, value="x", reason=None, command="uname -r", collected_at="2026-11-09T15:20:00Z"):
    return {"category": category, "label": category, "command": command,
            "collectedAt": collected_at, "value": value, "reason": reason}


def self_check() -> int:
    java_text = (ROOT / COLLECTOR_JAVA).read_text(encoding="utf-8")
    java_commands = dict(parse_java_commands(java_text))
    if len(java_commands) != 10:
        print(f"  SELFCHECK 前置失败：源码里只解析出 {len(java_commands)} 条命令，判据无法自证")
        return 1
    api_ok = dict(java_commands)

    def payload(items, status=None, host="localhost"):
        collected = sum(1 for i in items if i.get("value") is not None)
        if status is None:
            status = "FAILED" if collected == 0 else (
                "OK" if collected == len(items) else "PARTIAL")
        return {"host": host, "deviceId": None, "collectedAt": "2026-11-09T15:20:00Z",
                "status": status, "items": items}

    def items_all(overrides=None):
        overrides = overrides or {}
        out = []
        for category in CATEGORIES:
            base = _item(category, command=java_commands[category])
            base.update(overrides.get(category, {}))
            out.append(base)
        return out

    clean_partial = items_all({"GPU": {"value": None, "reason": "未采集到 GPU 信息：命令不存在"}})
    clean_all = items_all()

    cases = [
        ("干净数据（10 类，GPU 未采到但给了原因）", payload(clean_partial), True),
        ("10 类全采到（status=OK）", payload(clean_all), True),
        ("少一类（9 类）", payload(clean_all[:9]), False),
        ("顺序错（GPU 与 DISK 对调）", payload(
            items_all()[:4] + [items_all()[5], items_all()[4]] + items_all()[6:]), False),
        ("未采集却没有原因", payload(items_all({"GPU": {"value": None, "reason": None}})), False),
        ("用 '-' 冒充采集结果", payload(items_all({"GPU": {"value": "-"}})), False),
        ("status 与项数不符", payload(clean_partial, status="OK"), False),
        ("记录的命令与源码不符", payload(items_all({"OS": {"command": "cat /etc/os-release"}})), False),
        ("缺 collectedAt", payload(items_all({"CPU": {"collectedAt": ""}})), False),
        ("有值却又带 reason（语义矛盾）",
         payload(items_all({"NIC": {"reason": "其实没采到"}})), False),
    ]

    fails = 0
    for label, data, expect_pass in cases:
        problems = check_inventory(data, java_commands)
        ok = not problems
        if ok == expect_pass:
            print(f"  SELFCHECK {label}: {'PASS' if ok else 'RED'}（符合预期）")
        else:
            print(f"  SELFCHECK {label}: 不符合预期 problems={problems}")
            fails += 1

    # 命令白名单的负向自证：写操作必须被判红，正常只读命令必须判绿。
    cmd_cases = [
        ("只读命令 `ip -br addr`", "ip -br addr", True),
        ("带 2>/dev/null 的只读命令", "cat /sys/class/net/eth0/speed 2>/dev/null", True),
        ("重定向写文件", "uname -r > /tmp/x", False),
        ("rm -rf", "rm -rf /var/log/*", False),
        ("就地改文件 sed -i", "sed -i 's/a/b/' /etc/hosts", False),
        ("用 ip 改地址", "ip addr add 10.0.0.1/24 dev eth0", False),
        ("装包", "apt-get install -y htop", False),
        ("未登记的可执行名", "htop -b", False),
    ]
    for label, command, expect_pass in cmd_cases:
        problems = command_problems(command)
        ok = not problems
        if ok == expect_pass:
            print(f"  SELFCHECK {label}: {'PASS' if ok else 'RED'}（符合预期）")
        else:
            print(f"  SELFCHECK {label}: 不符合预期 problems={problems}")
            fails += 1

    # 接口自述与源码不一致必须判红（这是「两份命令」漂移的现场）
    drifted = dict(api_ok)
    drifted["GPU"] = "nvidia-smi -L"
    if check_api_commands(drifted, java_commands):
        print("  SELFCHECK 接口命令与源码漂移: RED（符合预期）")
    else:
        print("  SELFCHECK 接口命令与源码漂移: 不符合预期（漂移没被发现）")
        fails += 1
    if check_api_commands(api_ok, java_commands):
        print("  SELFCHECK 接口命令与源码一致: 不符合预期（干净数据被判红）")
        fails += 1
    else:
        print("  SELFCHECK 接口命令与源码一致: PASS（符合预期）")

    # W42-03 的负向自证：从真实源码里**逐条删掉**安全边界，删哪条就得判红哪条。
    # 只判「删除后是否被判红」不做别的加工：这样证明的是门禁本身有效，不是数据造得对。
    ssh_text = (ROOT / SSH_JAVA).read_text(encoding="utf-8") if (ROOT / SSH_JAVA).is_file() else ""
    if not ssh_text:
        print(f"  SELFCHECK W42-03 前置失败：缺少 {SSH_JAVA}")
        fails += 1
    else:
        detected = 0
        ssh_guards = [(n, lb) for rel, n, lb in REMOTE_GUARDS if rel == SSH_JAVA]
        for needle, label in ssh_guards:
            if needle not in ssh_text:
                print(f"  SELFCHECK W42-03 源码本就没有 {needle!r}（{label}）: 不符合预期")
                fails += 1
                continue
            # 全部删掉：像 MAX_OUTPUT_BYTES 这种常量会出现在多行，只删一处等于没删
            broken = ssh_text.replace(needle, "")
            if any(label in p for p in remote_problems({SSH_JAVA: broken})):
                detected += 1
            else:
                print(f"  SELFCHECK W42-03 删掉 {label}: 不符合预期（门禁没判红）")
                fails += 1
        print(f"  SELFCHECK W42-03 远程采集硬约束（删一条即判红）: {detected}/{len(ssh_guards)} 条符合预期")
        if remote_problems():
            print(f"  SELFCHECK W42-03 干净源码被判红: 不符合预期 problems={remote_problems()}")
            fails += 1
        else:
            print("  SELFCHECK W42-03 干净源码: PASS（符合预期）")

    print(f"SELFCHECK_RESULT FAILS={fails}")
    return 1 if fails else 0


# --------------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description="主机清单 10 类门禁（第 29 步）")
    parser.add_argument("--self-check", action="store_true", help="只做判据负向自证")
    parser.add_argument("--evidence-dir", default=str(EVIDENCE_DIR))
    args = parser.parse_args()

    if args.self_check:
        return self_check()

    evidence_dir = Path(args.evidence_dir)
    problems: list[str] = list(check_static()) + remote_problems()

    collector = ROOT / COLLECTOR_JAVA
    if not collector.is_file():
        print("HOST_INVENTORY FAIL")
        print(f"  - 缺少 {COLLECTOR_JAVA}")
        return 1
    pairs = parse_java_commands(collector.read_text(encoding="utf-8"))
    java_commands = dict(pairs)
    problems.extend(check_commands(pairs))

    declared = parse_domain_categories((ROOT / DOMAIN_JAVA).read_text(encoding="utf-8"))
    if declared != CATEGORIES:
        problems.append(f"{DOMAIN_JAVA} 的 CATEGORIES 与本判据不一致：{declared}")

    api_commands = load_json(evidence_dir / COMMANDS_JSON.name)
    if api_commands is None:
        problems.append(f"证据缺失或不可解析：{evidence_dir / COMMANDS_JSON.name}"
                        "（先跑 bash scripts/host-inventory-contract.sh 取真实接口响应）")
    else:
        problems.extend(check_api_commands(api_commands, java_commands))

    inventory = load_json(evidence_dir / INVENTORY_JSON.name)
    if inventory is None:
        problems.append(f"证据缺失或不可解析：{evidence_dir / INVENTORY_JSON.name}"
                        "（需要一次真实 POST /api/asset-host-inventory/collect）")
    else:
        problems.extend(check_inventory(inventory, java_commands))

    if problems:
        print("HOST_INVENTORY FAIL")
        for item in problems:
            print(f"  - {item}")
        return 1

    items = inventory["items"]
    collected = sum(1 for i in items if not _is_blank(i.get("value")))
    print("HOST_INVENTORY PASS")
    print(f"  命令白名单 {len(pairs)} 类，全部只读；接口自述与源码逐字一致")
    print(f"  远程采集硬约束 {len(REMOTE_GUARDS)} 条齐全（指纹/BatchMode/拒绝提权/输出上限/二次确认）")
    print(f"  真实采集 {inventory.get('host')} status={inventory.get('status')} "
          f"{collected}/{len(items)} 项有值")
    for item in items:
        mark = "有值" if not _is_blank(item.get("value")) else "未采集到"
        note = "" if mark == "有值" else f" | {item.get('reason')}"
        print(f"    {item.get('category'):<7} {mark}{note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
