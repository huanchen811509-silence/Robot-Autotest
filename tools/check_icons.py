#!/usr/bin/env python3
"""UX-04 步骤 1：图标名静态对账（`iconMap.ts` ↔ 各处传的字面量）。

为什么需要这条守门
------------------
`AppIcon` 的 `name` 是 `IconName` 联合类型，但项目没开 `vueCompilerOptions.strictTemplates`，
**模板里的 prop 类型不被检查**。于是 `icon="shield"` 这种拼错（`shield` 不在表里）
`vue-tsc` 一声不响，运行时 `<component :is="undefined">` 直接渲染空白——
页面上只是少个图标，没有报错、没有样式异常，review 也看不出来。
本轮就真踩了一次（`icon="shield"`，表里只有 `audit`），所以补这条静态检查。

守什么
------
1. `iconMap.ts` 里 `const ICONS = { ... } as const` 必须能解析出 ≥ MIN_KEYS 个名字
   （解析失败/表被清空时不能「零命中 = 通过」）；
2. `frontend/src/**/*.{vue,ts}` 里**静态**写的图标名都必须在表里：
   - `icon="name"` / `:icon="'name'"`（AppIcon、EmptyState、StatusTag 等）
   - `<AppIcon ... name="name">` / `<AppIcon :name="'name'">`
   动态表达式（`:name="someVar"`）无法静态判定，跳过且计数上报，不假装检查过。

用法
----
    python3 tools/check_icons.py
    python3 tools/check_icons.py --self-test    # 注入未定义名 / 清空表，都必须被拦

退出码：0 = 全部名字有定义；1 = 有未定义名或表过短；2 = 扫描本身失败。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend" / "src"
ICON_MAP = SRC / "components" / "iconMap.ts"

MIN_KEYS = 20  # 当前 49 个；低于 20 说明解析失败或表被误改，不能当「通过」

# `const ICONS = {` 到 `} as const`，键是 `name: Component,` 形式
ICONS_BLOCK_RE = re.compile(r"const ICONS = \{(.*?)\}\s*as const", re.S)
KEY_RE = re.compile(r"^\s*([A-Za-z][\w]*)\s*:", re.M)

# icon="x" / :icon="'x'" —— 只认小写开头的裸标识符，避免把表达式当名字。
# 后顾断言 `[:\w-]` 两条都不能少：
#   - `:` -> `:icon="emptyIcon"` 是动态绑定，不是静态名字（UX-05 表格壳踩到）；
#   - `-` / 词字符 -> `empty-icon="emptyIcon"`、`data-icon="x"` 是**别的属性**，
#     不能因为 `-` 是非单词字符就把中间的 `icon` 拆出来当独立属性（W62-08 工作台踩到）。
# 少任何一条都会把正确的 Vue 写法判成「未定义图标名」，逼人改坏代码。
ICON_ATTR_RE = re.compile(r"""(?<![:\w-])icon\s*=\s*["']([a-z][\w]*)["']""")
ICON_BIND_RE = re.compile(r""":icon\s*=\s*["']'([a-z][\w]*)'["']""")
# 动态绑定（`:icon="expr"`）：无法静态判定，只计数上报，不假装检查过
ICON_EXPR_RE = re.compile(r""":icon\s*=\s*"([^"]*)\"""")
APPICON_TAG_RE = re.compile(r"<AppIcon\b[^>]*>", re.S)
APPICON_NAME_RE = re.compile(r"""\sname\s*=\s*["']([a-z][\w]*)["']""")
APPICON_BIND_RE = re.compile(r"""\s:name\s*=\s*["']'([a-z][\w]*)'["']""")


@dataclass
class Result:
    keys: set[str] = field(default_factory=set)
    hits: int = 0
    dynamic: int = 0
    violations: list[str] = field(default_factory=list)
    infos: list[str] = field(default_factory=list)


def parse_keys(icon_map: Path) -> tuple[set[str], str | None]:
    if not icon_map.is_file():
        return set(), f"未找到图标映射表：{icon_map}"
    text = icon_map.read_text(encoding="utf-8")
    block = ICONS_BLOCK_RE.search(text)
    if not block:
        return set(), f"{icon_map.name} 里找不到 `const ICONS = {{...}} as const` 块"
    return set(KEY_RE.findall(block.group(1))), None


def scan(src: Path, keys: set[str]) -> Result:
    r = Result(keys=keys)
    files = sorted(p for p in src.rglob("*") if p.suffix in {".vue", ".ts"})
    if not files:
        r.violations.append(f"{src} 下没有 .vue/.ts 文件，扫描范围可疑")
        return r

    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        rel = path.relative_to(src.parent.parent).as_posix()
        names: list[tuple[str, int]] = []
        for m in ICON_ATTR_RE.finditer(text):
            names.append((m.group(1), text.count("\n", 0, m.start()) + 1))
        for m in ICON_BIND_RE.finditer(text):
            names.append((m.group(1), text.count("\n", 0, m.start()) + 1))
        for m in ICON_EXPR_RE.finditer(text):
            inner = m.group(1).strip()
            if not (inner.startswith("'") and inner.endswith("'")):
                r.dynamic += 1
        for tag in APPICON_TAG_RE.finditer(text):
            line = text.count("\n", 0, tag.start()) + 1
            for m in APPICON_NAME_RE.finditer(tag.group(0)):
                names.append((m.group(1), line))
            for m in APPICON_BIND_RE.finditer(tag.group(0)):
                names.append((m.group(1), line))
            # 动态 name / :name="expr" 无法静态判定：只计数，不判过
            if re.search(r""":name\s*=\s*["'][^'"]*[^'"]\s*["']""", tag.group(0)) and not \
                    APPICON_BIND_RE.search(tag.group(0)):
                r.dynamic += 1

        for name, line in names:
            r.hits += 1
            if name not in keys:
                r.violations.append(
                    f"{rel}:{line} 使用了未定义的图标名 `{name}`"
                    f"（AppIcon 会静默渲染空白；请补 iconMap.ts 或改用已有名字）"
                )
    return r


def report(r: Result) -> None:
    print(f"图标映射表：{len(r.keys)} 个名字 - {', '.join(sorted(r.keys))}")
    print(f"静态引用：{r.hits} 处；动态表达式（跳过）：{r.dynamic} 处")
    for info in r.infos:
        print(f"[INFO] {info}")


def evaluate(icon_map: Path, src: Path) -> Result:
    keys, err = parse_keys(icon_map)
    if err:
        r = Result()
        r.violations.append(err)
        return r
    if len(keys) < MIN_KEYS:
        r = Result(keys=keys)
        r.violations.append(
            f"图标表只解析出 {len(keys)} 个名字（下限 {MIN_KEYS}），"
            "疑似正则失配或表被误改 —— 不能当通过"
        )
        return r
    r = scan(src, keys)
    r.keys = keys
    if r.hits == 0:
        r.violations.append(
            "没有扫描到任何静态图标名引用：可能模板写法变了（如改用变量），"
            "本守门已失去判定力，请同步调整正则"
        )
    return r


def self_test() -> int:
    """破坏性自检：临时树里注入三类问题，都必须被拦住。"""
    print("[SELFTEST] 开始：临时目录内构造最小树，不触碰真实 frontend/src")
    ok = True

    def build(tmp: Path, keys_block: str, vue_body: str) -> tuple[Path, Path]:
        icon_map = tmp / "frontend" / "src" / "components" / "iconMap.ts"
        icon_map.parent.mkdir(parents=True, exist_ok=True)
        icon_map.write_text(
            "const ICONS = {\n" + keys_block + "} as const\nexport type IconName = keyof typeof ICONS\n",
            encoding="utf-8",
        )
        view = tmp / "frontend" / "src" / "views" / "Fake.vue"
        view.parent.mkdir(parents=True, exist_ok=True)
        view.write_text(vue_body, encoding="utf-8")
        return icon_map, tmp / "frontend" / "src"

    good_keys = "\n".join(f"  {n}: Comp,\n" for n in
                          ["audit", "users", "inbox", "help", "box", "case", "run",
                           "report", "admin", "device", "link", "cred", "sun", "moon",
                           "system", "palette", "language", "logout", "check", "close"])
    good_vue = '<template><AppIcon name="audit" /><EmptyState icon="inbox" /></template>\n'

    # 1) 干净基线必须 PASS
    with tempfile.TemporaryDirectory() as d:
        icon_map, src = build(Path(d), good_keys, good_vue)
        r = evaluate(icon_map, src)
        if r.violations:
            ok = False
            print(f"[SELFTEST] FAIL 干净基线被误判：{r.violations}")
        else:
            print(f"[SELFTEST] 通过：干净基线不假红（{r.hits} 处引用全部有定义）")

    # 2) 注入未定义名必须 FAIL
    with tempfile.TemporaryDirectory() as d:
        bad_vue = '<template><AppIcon name="shield" /><EmptyState icon="inbox" /></template>\n'
        icon_map, src = build(Path(d), good_keys, bad_vue)
        r = evaluate(icon_map, src)
        if any("shield" in v for v in r.violations):
            print("[SELFTEST] 通过：注入未定义名 `shield` 后被拦截")
        else:
            ok = False
            print(f"[SELFTEST] FAIL 未定义名没被拦：{r.violations}")

    # 3) 表被清空/解析失败必须 FAIL（不能「零命中 = 通过」）
    with tempfile.TemporaryDirectory() as d:
        icon_map, src = build(Path(d), "", good_vue)
        r = evaluate(icon_map, src)
        if r.violations and any("下限" in v or "解析" in v for v in r.violations):
            print("[SELFTEST] 通过：图标表为空时按「解析失败」报错，不当通过")
        else:
            ok = False
            print(f"[SELFTEST] FAIL 空表没被拦：{r.violations}")

    # 4) 动态绑定不得被当成图标名（`:icon="emptyIcon"` 是变量）
    #    这是 UX-05 表格壳第一次用动态 icon 时的真实误报：`\b` 在 `:` 与 `i` 之间成立，
    #    `emptyIcon` 就被当成未定义图标名。误报的代价是「守门一响就得改对的代码」，
    #    所以给它一条用例，防止后顾断言被谁顺手删掉。
    with tempfile.TemporaryDirectory() as d:
        dyn_vue = (
            '<template><AppIcon name="audit" />'
            '<EmptyState :icon="emptyIcon" :description="t(\'x\')" />'
            '<StatusTag :icon="row.icon" /></template>\n'
        )
        icon_map, src = build(Path(d), good_keys, dyn_vue)
        r = evaluate(icon_map, src)
        if not r.violations and r.dynamic >= 1:
            print(f"[SELFTEST] 通过：动态绑定不误报为图标名（跳过 {r.dynamic} 处）")
        else:
            ok = False
            print(f"[SELFTEST] FAIL 动态绑定被误判：{r.violations}")

    # 5) 复合属性名不得被拆出 `icon`：W62-08 的 AssetWorkspace 用
    #    `empty-icon="emptyIcon"` 传图标变量，旧正则把它当成静态名 `emptyIcon` 判红。
    #    同一条用例里再验证「真正的静态 icon="shield" 仍被拦」，防止把正则改松到失去判定力。
    with tempfile.TemporaryDirectory() as d:
        compound_vue = (
            '<template><AppIcon name="audit" />'
            '<DataTable empty-icon="emptyIcon" :empty-icon="emptyIcon" /></template>\n'
        )
        icon_map, src = build(Path(d), good_keys, compound_vue)
        r = evaluate(icon_map, src)
        if not r.violations and r.hits >= 1:
            print("[SELFTEST] 通过：复合属性 empty-icon 不误报（静态引用 %d 处）" % r.hits)
        else:
            ok = False
            print(f"[SELFTEST] FAIL 复合属性被误判：{r.violations}")

    with tempfile.TemporaryDirectory() as d:
        static_bad = '<template><AppIcon name="audit" /><EmptyState icon="shield" /></template>\n'
        icon_map, src = build(Path(d), good_keys, static_bad)
        r = evaluate(icon_map, src)
        if any("shield" in v for v in r.violations):
            print('[SELFTEST] 通过：真实的静态 icon="shield" 仍被拦')
        else:
            ok = False
            print(f"[SELFTEST] FAIL 静态未定义名没被拦：{r.violations}")

    print("[SELFTEST] " + ("通过：六类场景均符合预期" if ok else "存在未拦截场景"))
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="UX-04 图标名静态对账")
    parser.add_argument("--self-test", action="store_true", help="破坏性自检")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    try:
        r = evaluate(ICON_MAP, SRC)
    except OSError as exc:
        print(f"[FAIL] 扫描失败：{exc}", file=sys.stderr)
        return 2

    report(r)
    if r.violations:
        for v in r.violations:
            print(f"[FAIL] {v}", file=sys.stderr)
        print(f"\n[FAIL] 共 {len(r.violations)} 项图标名问题", file=sys.stderr)
        return 1
    print(f"\n[PASS] 图标名对账通过：{r.hits} 处静态引用全部在 {len(r.keys)} 个定义内")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
