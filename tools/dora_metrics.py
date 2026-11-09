#!/usr/bin/env python3
"""EW37-04 · DORA 五项读数（近似口径，只建读数不设考核）。

真实数据源：git log、CHANGELOG、门禁同批次汇总、flaky 台账、发布记录。
口径限制写进输出 JSON：本工具给的是「可复现的近似读数」，不是标准 DORA 定义值，
不设目标、不用于考核、不因读数好看合并批次或跳过回归。
"""
import json
import pathlib
import re
import subprocess
from datetime import datetime

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "evidence" / "v3.7.0" / "EW37-04" / "dora-metrics.json"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def main():
    log = git("log", "--format=%h|%aI|%s", "-n", "200")
    rows = [ln.split("|", 2) for ln in log.splitlines() if ln.count("|") >= 2]
    stamps = []
    for r in rows:
        try:
            stamps.append(datetime.fromisoformat(r[1]))
        except ValueError:
            pass
    span_days = max(1, (max(stamps) - min(stamps)).days) if stamps else 1
    commits = len(rows)
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    versions = re.findall(r"^##\s+v?\d+\.\d+(?:\.\d+)?", changelog, flags=re.M)
    gate_log = ROOT / "docs" / "evidence" / "gates" / "logs" / "00-full-run.log"
    gate_text = gate_log.read_text(encoding="utf-8") if gate_log.exists() else ""
    m = re.search(r"GATES_RESULT\s+FAILS=(\d+)", gate_text)
    gate_fails = int(m.group(1)) if m else None
    pass_steps = len(re.findall(r"^\s+PASS\s+\d\d-", gate_text, flags=re.M))
    fail_steps = len(re.findall(r"^\s+FAIL\s+\d\d-", gate_text, flags=re.M))
    ledger = ROOT / "docs" / "evidence" / "v3.2.0" / "flaky-ledger.md"
    flaky_rows = len(re.findall(r"^\|\s*2026-", ledger.read_text(encoding="utf-8"), flags=re.M)) if ledger.exists() else 0
    steps = pass_steps + fail_steps
    data = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "scope": "本仓 git 历史 + 同批次门禁汇总 + flaky 台账",
        "caveat": "近似口径，非标准 DORA 定义值；只建读数、不设目标、不用于考核",
        "metrics": {
            "deployment_frequency": {
                "value": round(len(versions) / span_days, 4),
                "unit": "版本条目/天（CHANGELOG 版本数 / 提交时间跨度）",
                "basis": "CHANGELOG 版本条目 %d 条，历史跨度 %d 天" % (len(versions), span_days),
            },
            "lead_time_for_changes": {
                "value": round(span_days / max(1, commits), 4),
                "unit": "天/提交（提交间隔均值，近似）",
                "basis": "%d 个提交 / %d 天" % (commits, span_days),
            },
            "change_failure_rate": {
                "value": None,
                "unit": "需发布失败记录，当前无发布",
                "basis": "本轮不产生发布；门禁失败不冒充部署失败",
                "status": "未测量",
            },
            "time_to_restore": {
                "value": None,
                "unit": "需真实故障与恢复事件",
                "basis": "本轮 B1 的 02/19 连锁失败已修复，但不是生产故障恢复事件",
                "status": "未测量",
            },
            "reliability": {
                "value": round(pass_steps / steps, 4) if steps else "未测量",
                "unit": "门禁步骤通过率（同批次汇总）",
                "basis": "PASS=%d FAIL=%d FAILS=%s" % (pass_steps, fail_steps, gate_fails),
            },
        },
        "release_record": {
            "baseline_sha": git("rev-parse", "HEAD"),
            "version": (ROOT / "VERSION").read_text(encoding="utf-8").strip(),
            "rollback_point": "上一提交 " + (git("rev-parse", "HEAD~1") if commits > 1 else "无"),
            "note": "版本戳 / 迁移版本 / 回滚点齐；本轮未发版、未申请 G 门",
        },
        "flaky_ledger_rows": flaky_rows,
        "conclusion": "PARTIAL：五项读数中 3 项建立近似值、2 项如实标「未测量」；灰度与回滚演练未执行",
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("DORA_RESULT", data["conclusion"])
    print("  reliability=%s  deployment_frequency=%s  lead_time=%s" % (
        data["metrics"]["reliability"]["value"],
        data["metrics"]["deployment_frequency"]["value"],
        data["metrics"]["lead_time_for_changes"]["value"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
