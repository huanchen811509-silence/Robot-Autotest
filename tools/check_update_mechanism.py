#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""更新机制静态契约机检（门禁第 31 步 / FR-v4.0-05）。

判据（缺一即红，避免「脚本存在但护栏缺失」的假绿）：

  ① `scripts/ops/update.sh`：安全默认态（dry-run / 二次确认 / 同版本与降级护栏 /
     失败自动回滚 / 先备份后写盘 / 健康抽样 / UPDATE-HISTORY 表头模板 / 保留 N 版 / RTO）；
  ② `scripts/ops/update-drill.sh`：**版本化**的演练脚本（临时 prefix、sha256 负向、
     健康失败回滚、workspace KEEP 区断言、非零退出）；
  ③ `scripts/ops/status.sh`：显示当前版本、上一可用版本与最近升级结论；
  ④ 运维文档 `docs/DELIVERY-GUIDE.md` 与 `docs/ROLLBACK.md` 已接入 `update.sh` 口径。

用法：
  python3 tools/check_update_mechanism.py
  python3 tools/check_update_mechanism.py --self-check
  python3 tools/check_update_mechanism.py --root /tmp/skeleton
退出码：0 全 PASS；1 判红；2 用法错误。
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

UPDATE_RULES = [
    ("APPLY=0", "默认必须 dry-run（APPLY=0）"),
    ("--apply)", "缺 --apply 入口"),
    ("--dry-run)", "缺 --dry-run 入口"),
    ("输入 UPDATE 确认", "缺交互二次确认（输入 UPDATE）"),
    ("rollback() {", "缺 rollback 函数"),
    ("trap on_err ERR", "失败未接入自动回滚 trap"),
    ("同版本升级必须 --force", "同版本升级护栏缺失"),
    ("--restore-db", "降级必须 --restore-db（RPO=0）"),
    ('backup.sh" --apply', "--apply 前未调用 DB 全量备份"),
    ("actuator/health", "健康抽样未打 actuator/health"),
    ("| 时间 | 原版本 | 目标版本 | 结论 | 备注 |", "UPDATE-HISTORY 表头模板缺失"),
    ("prune_archives", "未实现保留最近 N 版"),
    ("KEEP=3", "默认保留 3 版缺失"),
    ("600", "RTO ≤ 600s 判据缺失"),
]
DRILL_RULES = [
    ("mktemp", "演练未使用临时 prefix"),
    ("UPDATE_DRILL_FAIL_HEALTH", "缺健康失败注入钩子"),
    ("sha256 校验失败", "缺包 sha256 负向断言"),
    ("ROLLED_BACK", "缺回滚结论断言"),
    ("UPDATE-HISTORY", "缺历史追加断言"),
    ("workspace", "缺 workspace KEEP 区保留断言"),
    ("UPDATE_DRILL_RESULT", "缺演练汇总行"),
]
STATUS_RULES = [
    ("上一可用版本", "status 未显示上一可用版本"),
    ("最近升级", "status 未显示最近升级结论"),
    ("last_good_version", "status 未读 last_good_version"),
    ("last_result", "status 未读 last_result"),
]
DOC_RULES = {
    "docs/DELIVERY-GUIDE.md": [
        ("update.sh", "交付指南未接入 update.sh"),
        ("dry-run", "交付指南未说明默认 dry-run"),
    ],
    "docs/ROLLBACK.md": [
        ("update.sh", "回滚预案未接入 update.sh 自动回滚"),
        ("自动回滚", "回滚预案未说明自动回滚"),
    ],
}


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def index_of(text: str, needle: str) -> int:
    return text.find(needle)


def check_static(root: Path) -> list[str]:
    fails: list[str] = []
    update = root / "scripts/ops/update.sh"
    drill = root / "scripts/ops/update-drill.sh"
    status = root / "scripts/ops/status.sh"

    for p, label in ((update, "update.sh"), (drill, "update-drill.sh"), (status, "status.sh")):
        text = read(p)
        if not text:
            fails.append("缺少 scripts/ops/%s" % label)
        elif not text.startswith("#!/usr/bin/env bash"):
            fails.append("%s 缺 bash shebang" % label)
        elif "pipefail" not in text:
            fails.append("%s 缺 set -o pipefail" % label)

    ut = read(update)
    for needle, why in UPDATE_RULES:
        if needle not in ut:
            fails.append("update.sh: %s" % why)
    # 顺序：先备份、后覆盖；先停服、后覆盖。
    backup_at = index_of(ut, 'backup.sh" --apply')
    swap_at = index_of(ut, "STEP='停服与覆盖'")
    if backup_at >= 0 and swap_at >= 0 and backup_at > swap_at:
        fails.append("update.sh: DB 备份晚于覆盖写盘（必须先备份）")
    stop_at = index_of(ut, 'ctl.sh" stop')
    if backup_at >= 0 and stop_at >= 0 and stop_at < backup_at:
        fails.append("update.sh: 停服早于 DB 备份（备份失败将无法服务）")

    dt = read(drill)
    for needle, why in DRILL_RULES:
        if needle not in dt:
            fails.append("update-drill.sh: %s" % why)
    if '[[ "$FAILS" -eq 0 ]]' not in dt:
        fails.append("update-drill.sh: 失败时未返回非零退出码")

    st = read(status)
    for needle, why in STATUS_RULES:
        if needle not in st:
            fails.append("status.sh: %s" % why)

    for rel, rules in DOC_RULES.items():
        text = read(root / rel)
        if not text:
            fails.append("缺少 %s" % rel)
            continue
        for needle, why in rules:
            if needle not in text:
                fails.append("%s: %s" % (rel, why))
    return fails


# ---------------------------------------------------------------- self-check
def build_skeleton(dir: Path, src: Path) -> None:
    for rel in ("scripts/ops/update.sh", "scripts/ops/update-drill.sh", "scripts/ops/status.sh",
                "docs/DELIVERY-GUIDE.md", "docs/ROLLBACK.md"):
        dst = dir / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(src / rel, dst)


def mutate(path: Path, old: str, new: str) -> None:
    path.write_text(path.read_text(encoding="utf-8").replace(old, new), encoding="utf-8")


def self_check() -> int:
    fails = 0
    tmp = Path(tempfile.mkdtemp(prefix="updchk-"))
    try:
        good = tmp / "repo"
        build_skeleton(good, REPO)

        def expect(name, fn, clean=False):
            nonlocal fails
            errs = fn()
            ok = bool(errs) == (not clean)
            print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                               "" if ok else " -> " + "; ".join(errs)))
            if not ok:
                fails += 1

        def reset(rel: str) -> None:
            shutil.copy(REPO / rel, good / rel)

        expect("good", lambda: check_static(good), clean=True)

        def drop_dry_run_default():
            mutate(good / "scripts/ops/update.sh", "APPLY=0", "APPLY=1")
            return check_static(good)
        expect("no-dry-run-default", drop_dry_run_default)
        reset("scripts/ops/update.sh")

        def drop_confirm():
            mutate(good / "scripts/ops/update.sh", "输入 UPDATE 确认", "直接执行")
            return check_static(good)
        expect("no-confirm", drop_confirm)
        reset("scripts/ops/update.sh")

        def drop_backup():
            mutate(good / "scripts/ops/update.sh", 'backup.sh" --apply', 'backup.sh"')
            return check_static(good)
        expect("no-db-backup", drop_backup)
        reset("scripts/ops/update.sh")

        def drop_rollback_trap():
            mutate(good / "scripts/ops/update.sh", "trap on_err ERR", "trap true ERR")
            return check_static(good)
        expect("no-auto-rollback", drop_rollback_trap)
        reset("scripts/ops/update.sh")

        def drop_drill():
            (good / "scripts/ops/update-drill.sh").unlink()
            return check_static(good)
        expect("no-drill", drop_drill)
        reset("scripts/ops/update-drill.sh")

        def drill_in_tmp():
            mutate(good / "scripts/ops/update-drill.sh", "mktemp", "echo /tmp")
            return check_static(good)
        expect("drill-not-versioned", drill_in_tmp)
        reset("scripts/ops/update-drill.sh")

        def drop_status():
            mutate(good / "scripts/ops/status.sh", "上一可用版本", "版本")
            return check_static(good)
        expect("status-no-last-good", drop_status)
        reset("scripts/ops/status.sh")

        def drop_rollback_doc():
            mutate(good / "docs/ROLLBACK.md", "update.sh", "rollback.sh")
            return check_static(good)
        expect("doc-no-update", drop_rollback_doc)
        reset("docs/ROLLBACK.md")

        print("UPDATE_MECHANISM_SELFCHECK %s fails=%d"
              % ("PASS" if fails == 0 else "FAIL", fails))
        return 0 if fails == 0 else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(REPO), help="仓库根（默认本脚本上一级）")
    ap.add_argument("--self-check", action="store_true", help="负向自证")
    args = ap.parse_args()
    if args.self_check:
        return self_check()
    errs = check_static(Path(args.root))
    if errs:
        for e in errs:
            print("FAIL %s" % e)
        print("UPDATE_MECHANISM FAIL")
        return 1
    print("UPDATE_MECHANISM PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
