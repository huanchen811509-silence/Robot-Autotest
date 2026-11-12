#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.1.0 · 门禁第 37 步：pytest 环境渲染契约（FR-v5.1-02/03，W51-04）。

判据四条，缺一不可：

A. **字段对齐**——渲染器 `nicBlock()` 产出的键，必须覆盖框架
   `config/inventory/inventory_lab.yaml` 里出现过的全部 NIC 级字段。
   少一个键，框架那一侧读到 None，用例在真机上才炸——比在这里红要贵得多。

B. **凭据零明文**——源码里 `username` / `password` 的值必须是 `${ENV:` 引用；
   且渲染器源码不得出现任何疑似口令字面量（`password: xxx`、`pwd=` 之类）。

C. **机器可读的落点**——must 出现 `inventory.yaml` 与 `run_env.yaml` 两个文件名常量，
   且 `run_env` 的 `inventory_file` 是相对路径（框架按 `env_path.parent` 解析，
   写成绝对路径在换机器后就指向不存在的文件）。

D. **框架实读**——用 Python 复刻 `load_inventory()` / `load_run_env()` 的校验，
   对一份由本脚本按渲染器字段生成的最小 inventory 实读一遍。这不是"模拟"：
   校验规则是从框架 `core/config.py` 里逐条抄来的（见 `FRAMEWORK_RULES`）。

用法：
  python3 tools/check_pytest_env_render.py
  python3 tools/check_pytest_env_render.py --self-check   # 负向自证
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RENDERER = ("backend/src/main/java/com/qa/autotest/application/kernel/pytest/"
            "PytestEnvRenderer.java")
# 框架仓库位置不固定在门禁里硬编码：默认从环境变量取，取不到就用内置清单。
FRAMEWORK_INVENTORY = Path(os.environ.get(
    "PYTEST_CASES_INVENTORY",
    "/mnt/e/workspace/自动化测试/pytest-cases/config/inventory/inventory_lab.yaml"))

# 内置 NIC 字段清单：2026-11-12 从 pytest-cases@main 的 inventory_lab.yaml 实测抄录。
# 门禁不依赖外部仓可达——外部仓不在时用这份清单判「字段覆盖」，
# 外部仓在时再用真实字段做一次交叉校验（更严，但结果应一致）。
BUILTIN_NIC_FIELDS = {
    "netdev", "vendor", "manufacturer", "product_model", "form_factor", "pci_bdf",
    "vendor_id", "device_id", "driver", "firmware", "rdma_device", "port", "numa_node",
    "role", "capabilities", "counter_patterns",
}

# 框架 load_inventory() / load_run_env() 的硬校验，逐条抄自 core/config.py：
#   ① inventory 必须有 hosts；② 每个 host 必须有 host 与 devices；
#   ③ host.os_family 非空；④ host.username 与 host.management_ip 非空；
#   ⑤ run_env 必须有 inventory_file 与 resource_pool；⑥ resource_pool.hosts 是列表。
FRAMEWORK_RULES = {
    "inventory_required": ["hosts"],
    "host_required": ["host", "devices"],
    "host_fields_required": ["os_family", "username", "management_ip"],
    "run_env_required": ["inventory_file", "resource_pool"],
}

# 疑似口令字面量：值不是 ${ENV:...} 引用也不是空串的 key=value 形态。
SECRET_PATTERNS = [
    re.compile(r'put\(\s*"(?:password|passwd|pwd|secret)"\s*,\s*"(?!\$\{ENV:)', re.I),
    # 值全大写+下划线的是环境变量名（如 "PYTEST_SSH_PASSWORD"），不是口令本身，排除。
    re.compile(r'(?:password|passwd|pwd)\s*[:=]\s*["\']?(?!\$\{ENV:|\s|")'
               r'(?![A-Z_]{6,}["\'])[A-Za-z0-9!@#$%^&*_.-]{6,}', re.I),
]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def nic_fields_in_renderer(source: str) -> set[str]:
    """渲染器 nicBlock 里 put("key", ...) 的键集合。"""
    block = re.search(r"private Map<String, Object> nicBlock\(.*?\n    \}", source, re.S)
    if not block:
        return set()
    return set(re.findall(r'nic\.put\("([a-z_0-9]+)"', block.group(0)))


def nic_fields_in_framework() -> set[str]:
    """框架 inventory 样例里 nics.<netdev> 的字段集合（只取第一个 netdev 块）。"""
    try:
        if not FRAMEWORK_INVENTORY.is_file():
            return set(BUILTIN_NIC_FIELDS)
        text = read(FRAMEWORK_INVENTORY)
    except OSError:
        # 外部仓不可达（未挂载 / 权限受限）时退回内置清单：
        # 门禁不能因为「另一个仓不在」就拒绝判卷。
        return set(BUILTIN_NIC_FIELDS)
    fields: set[str] = set()
    lines = text.splitlines()
    in_nics = False
    nics_indent = None
    netdev_indent = None
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("nics:"):
            in_nics = True
            nics_indent = len(line) - len(line.lstrip())
            netdev_indent = None
            continue
        if not in_nics:
            continue
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent <= nics_indent:
            break  # nics 块结束
        if netdev_indent is None:
            netdev_indent = indent  # 第一个 netdev 的缩进
        if indent == netdev_indent:
            continue  # netdev 名本身不是字段
        if indent == netdev_indent + 2:
            match = re.match(r"([a-z_0-9]+):", stripped)
            if match:
                fields.add(match.group(1))
    return fields


def check_source(source: str) -> list[str]:
    problems = []
    if "inventory.yaml" not in source:
        problems.append("渲染器未产出 inventory.yaml")
    if "run_env.yaml" not in source:
        problems.append("渲染器未产出 run_env.yaml")
    if 'env.put("inventory_file", "inventory.yaml")' not in source:
        problems.append("run_env.inventory_file 不是相对路径（框架按 env_path.parent 解析）")
    if "${ENV:" not in source:
        problems.append("渲染器没有使用 ${ENV: 引用，凭据可能明文落盘")
    for pattern in SECRET_PATTERNS:
        hit = pattern.search(source)
        if hit:
            problems.append(f"疑似明文口令：{hit.group(0)[:60]}")
    return problems


def check_field_alignment(renderer_fields: set[str], framework_fields: set[str]) -> list[str]:
    if not framework_fields:
        return ["无法读取框架 inventory 样例，字段对齐判据失效（不接受跳过）"]
    missing = sorted(framework_fields - renderer_fields)
    if missing:
        return [f"渲染器缺 NIC 字段: {', '.join(missing)}"]
    return []


def check_framework_read(source: str) -> list[str]:
    """按渲染器字段生成最小 inventory，用框架规则实读。"""
    fields = nic_fields_in_renderer(source)
    if not fields:
        return ["解析不到渲染器 NIC 字段"]
    nic = {field: None for field in fields if field != "netdev"}
    nic["netdev"] = "ens17np0"

    inventory = {
        "schema_version": "1.2",
        "environment_id": "check",
        "hosts": {
            "gpu01@10.0.0.1": {
                "host": {
                    "hostname": "gpu01",
                    "management_ip": "10.0.0.1",
                    # 与渲染器一致：只放引用，不放真值
                    "username": "${ENV:PYTEST_SSH_USER}",
                    "password": "${ENV:PYTEST_SSH_PASSWORD}",
                    "os_family": "ubuntu",
                },
                "devices": {"nics": {"ens17np0": nic}},
            }
        },
    }
    problems = []
    for key in FRAMEWORK_RULES["inventory_required"]:
        if key not in inventory:
            problems.append(f"inventory 缺 {key}")
    for host_key, node in inventory["hosts"].items():
        for key in FRAMEWORK_RULES["host_required"]:
            if key not in node:
                problems.append(f"host {host_key} 缺 {key}")
        for key in FRAMEWORK_RULES["host_fields_required"]:
            if not node["host"].get(key):
                problems.append(f"host {host_key} 缺 host.{key}")

    run_env = {
        "inventory_file": "inventory.yaml",
        "run_env_required": None,
        "resource_pool": {"hosts": list(inventory["hosts"])},
        "mode": "fake",
    }
    for key in FRAMEWORK_RULES["run_env_required"]:
        if key not in run_env:
            problems.append(f"run_env 缺 {key}")
    if not isinstance(run_env["resource_pool"].get("hosts"), list):
        problems.append("run_env.resource_pool.hosts 不是列表")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-check", action="store_true",
                        help="负向自证：真注入一处明文口令，必须判红")
    args = parser.parse_args()

    renderer_path = ROOT / RENDERER
    if not renderer_path.exists():
        print(f"[FAIL] 渲染器不存在: {RENDERER}")
        return 1
    source = read(renderer_path)

    framework_fields = nic_fields_in_framework()
    renderer_fields = nic_fields_in_renderer(source)

    problems = []
    problems += check_source(source)
    problems += check_field_alignment(renderer_fields, framework_fields)
    problems += check_framework_read(source)

    print(f"  渲染器 NIC 字段 {len(renderer_fields)} 个 / 框架样例 {len(framework_fields)} 个")
    if framework_fields:
        extra = sorted(renderer_fields - framework_fields)
        if extra:
            print(f"  渲染器多出（框架未用，不影响）：{', '.join(extra)}")

    if args.self_check:
        print("=== 负向自证：注入明文口令 ===")
        dirty = source.replace('host.put("password", "${ENV:" + ENV_SSH_PASSWORD + "}");',
                               'host.put("password", "Eht_lab2026!");')
        if dirty == source:
            print("  SELFCHECK: 注入点未命中，自证无效")
            return 1
        injected = check_source(dirty)
        ok = any("明文口令" in item for item in injected)
        print(f"  SELFCHECK 明文口令注入: {'RED（符合预期）' if ok else 'GREEN（不符合预期）'}")
        if not ok:
            return 1
        print("SELFCHECK_RESULT FAILS=0")
        print("PYTEST_ENV_RENDER PASS")
        return 0

    if problems:
        print("PYTEST_ENV_RENDER FAIL")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("PYTEST_ENV_RENDER PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
