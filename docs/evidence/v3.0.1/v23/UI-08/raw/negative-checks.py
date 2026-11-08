# -*- coding: utf-8 -*-
"""v2.3 负向验证红/绿对照（N-01/04/06/07/09/10/11/12）。

每条：备份 → 注入缺陷 → 跑定向判据 → 必须判红 → 还原。
不用 git checkout（工作区有未提交改动）。
"""
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path.home() / "robot-autotest"
BK = pathlib.Path("/tmp/v23_neg_bk")
LOG = ROOT / "docs/evidence/v3.0.1/v23/UI-08/raw/negative-checks.log"

NAV = "frontend/src/config/navigation.ts"
HDR = "frontend/src/components/AppHeader.vue"
WB = "frontend/src/views/WorkbenchView.vue"
CSS = "frontend/src/styles/main.css"

VITEST_NAV = ["npx", "vitest", "run", "src/config/navigation.spec.ts"]
VITEST_HDR = ["npx", "vitest", "run", "src/components/AppHeader.spec.ts"]
VITEST_WB = ["npx", "vitest", "run", "src/views/WorkbenchView.spec.ts"]
CHECK_COLORS = ["python3", "tools/check_colors.py"]


def patch(path, edits):
    """edits: list of (old, new, expected_hits) 或 ('APPEND', text, 0)"""
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    for old, new, hits in edits:
        if old == "APPEND":
            text = text + new
            continue
        got = text.count(old)
        if got != hits:
            raise SystemExit(f"PATCH_MISS {path}: expect {hits} hits got {got} :: {old[:60]!r}")
        text = text.replace(old, new)
    p.write_text(text, encoding="utf-8")


def backup(path):
    src = ROOT / path
    dst = BK / path.replace("/", "__")
    BK.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    return dst


def restore(path, bak):
    shutil.copy2(bak, ROOT / path)


def run(cmd, cwd):
    r = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def tail(s, n=8):
    lines = [ln for ln in s.strip().splitlines() if ln.strip()]
    return "\n".join(lines[-n:])


CASES = [
    dict(
        cid="N-01", tc="TC-UI-01", file=NAV, cmd=VITEST_NAV,
        desc="打乱一级项顺序（模块加载后 reverse）",
        edits=[("APPEND", "\n// NEG-PROBE N-01\nNAVIGATION.reverse()\n", 0)],
        expect_red="一级项顺序断言",
    ),
    dict(
        cid="N-09", tc="TC-UI-30", file=NAV, cmd=VITEST_NAV,
        desc="给合并组加回组级 capability: execution:read（照抄原 runs 组）",
        edits=[(
            "    key: 'execution',\n    labelKey: 'menu.execution',\n    icon: 'runs',",
            "    key: 'execution',\n    labelKey: 'menu.execution',\n    icon: 'runs',\n    capability: 'execution:read',",
            1,
        )],
        expect_red="组级 capability 未定义 + viewer 角色矩阵",
    ),
    dict(
        cid="N-10", tc="TC-UI-29", file=NAV, cmd=VITEST_NAV,
        desc="把 reports 一级项加回去（合并只做一半）",
        edits=[("APPEND", (
            "\n// NEG-PROBE N-10\n"
            "NAVIGATION.push({\n"
            "  key: 'reports',\n"
            "  labelKey: 'menu.reports',\n"
            "  icon: 'reports',\n"
            "  children: [{ key: 'reports-probe', labelKey: 'menu.reports', icon: 'reports', path: '/reports' }],\n"
            "} as unknown as NavNode)\n"
        ), 0)],
        expect_red="一级项不含 reports",
    ),
    dict(
        cid="N-11", tc="TC-UI-31", file=NAV, cmd=VITEST_NAV,
        desc="把 /test-reports 入口改道（孤儿页复发）",
        edits=[("        path: '/test-reports',", "        path: '/test-reports-old',", 1)],
        expect_red="/test-reports 叶子计数",
    ),
    dict(
        cid="N-12", tc="TC-UI-32", file=NAV, cmd=VITEST_NAV,
        desc="让 section 参与选中判定（带 section 的叶子跳过 exclude）",
        edits=[(
            "  const excluded = (leaf.exclude ?? []).some((prefix) => hitsPrefix(path, prefix))",
            "  // NEG-PROBE N-12：把渲染元数据 section 误用为判定依据\n"
            "  const excluded = leaf.section ? false : (leaf.exclude ?? []).some((prefix) => hitsPrefix(path, prefix))",
            1,
        )],
        expect_red="isLeafActive 带/不带 section 结果相同",
    ),
    dict(
        cid="N-06", tc="TC-UI-15", file=HDR, cmd=VITEST_HDR,
        desc="主题快捷按钮改回三态（永远点向同一档）",
        edits=[("  const next = themeTarget.value", "  const next: ResolvedTheme = 'dark'", 1)],
        expect_red="二态翻转",
    ),
    dict(
        cid="N-07", tc="TC-UI-18", file=WB, cmd=VITEST_WB,
        desc="卡牌页加一次重复请求（N+1 回潮）",
        edits=[(
            "    cards.value = await projectCardsApi.list('7d', auth.projectId)",
            "    cards.value = await projectCardsApi.list('7d', auth.projectId)\n"
            "    await projectCardsApi.list('7d', auth.projectId)",
            1,
        )],
        expect_red="请求次数 = 1",
    ),
    dict(
        cid="N-04", tc="TC-UI-22", file=CSS, cmd=CHECK_COLORS,
        desc="滚动条样式里写一个字面色值 #888",
        edits=[("APPEND", "\n.neg-probe-scrollbar { background: #888; }\n", 0)],
        expect_red="check_colors 字面色值",
    ),
]

out = []


def emit(s=""):
    print(s)
    out.append(s)


emit("# v2.3 负向验证红/绿对照")
emit("# 执行人：学徒（自动注入缺陷后跑定向判据）")
emit("# 方法：备份原文件 → 注入单点缺陷 → 跑判据（必须判红）→ 还原 → 复跑（必须回绿）")
emit("# 注：不使用 git checkout 还原，工作区存在未提交改动")
emit()

baseline_ok = True
for c in CASES:
    bak = backup(c["file"])
    try:
        patch(c["file"], c["edits"])
        rc, log = run(c["cmd"], ROOT / "frontend" if c["cmd"][0] == "npx" else ROOT)
    finally:
        restore(c["file"], bak)
    red = rc != 0
    emit(f"## {c['cid']}（{c['tc']}）{c['desc']}")
    emit(f"- 注入位置：`{c['file']}`")
    emit(f"- 判据命令：`{' '.join(c['cmd'])}`")
    emit(f"- 期望变红：{c['expect_red']}")
    emit(f"- 实测：exit={rc} → {'RED（符合预期）' if red else 'GREEN（未判红，判据无效）'}")
    emit("- 输出尾：")
    for ln in tail(log, 8).splitlines():
        emit(f"    {ln}")
    emit()
    if not red:
        baseline_ok = False

emit("## 还原后复跑（必须回绿）")
emit()
recov = [
    ("navigation.spec.ts", VITEST_NAV, ROOT / "frontend"),
    ("AppHeader.spec.ts", VITEST_HDR, ROOT / "frontend"),
    ("WorkbenchView.spec.ts", VITEST_WB, ROOT / "frontend"),
    ("check_colors.py", CHECK_COLORS, ROOT),
]
for name, cmd, cwd in recov:
    rc, log = run(cmd, cwd)
    emit(f"- {name}: exit={rc} → {'GREEN' if rc == 0 else 'RED（还原不完整）'}")
    if rc != 0:
        baseline_ok = False
        for ln in tail(log, 8).splitlines():
            emit(f"    {ln}")
emit()
emit(f"NEG_RESULT {'ALL_RED_AS_EXPECTED' if baseline_ok else 'PROBLEM'}")

LOG.parent.mkdir(parents=True, exist_ok=True)
LOG.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"\n[written] {LOG}", file=sys.stderr)
