#!/usr/bin/env python3
"""构建产物体积守门（UX-04 美术 S1 / 《美术体系-开发与测试计划-20261104.md》§2.2、§4）。

为什么需要这个工具
------------------
UX-04 S1 会给主仓引入 Naive UI、lucide-vue-next、@fontsource/noto-sans-sc、
@fontsource/jetbrains-mono，并新增一个**仅 DEV 挂载**的 /dev/styleguide 路由页。
这几样东西都会实打实地改变 frontend/dist 的体积和内容，但「页面看起来没问题」
完全掩盖得了体积超标和 DEV-only 页面泄漏。本脚本直接扫 Vite 产物，把计划里的
判据做成 CI 守门（由 scripts/lint.sh 接线）。

判据来源（数字照抄计划与实测，不另行发明）
--------------------------------------------
- §2.2 步骤 1：图标 chunk ≤ 15 KB（TC-S1-01「图标 chunk gzip ≤ 15 KB」）。
- §2.2 步骤 2：首屏字体增量目标 ≤ 800 KB gzip；font-display: swap 不阻塞首帧
  （TC-S1-02）。Vite 打包后 CSS 里应有 font-display: swap 声明。
- §2.2 步骤 8：组件库 chunk 预算在试点实测后定，> 预算报警（TC-S1-09）。
- §4：styleguide 泄漏到生产 —— 路由挂载条件 `import.meta.env.DEV || 显式开关`，
  **构建产物 grep 验证**。

两页试点实测（docs/evidence/v0.3/ux04-pilot-measure-20261104.tsv）：
  - Naive UI 2.45.3 独立试点：JS gzip 153,376 B，合计 gzip 154,779 B；
  - Element Plus 2.14.7：合计 174,484 B。
  注意：这两个数**含 vue runtime**，是「独立入口完整产物」，
  **不是接入主仓后的净增量**。

v3.2.0 EW32-01 追加两档（FR-3.2-06 前端预算）：
- 主入口闭包（index.html 引用的入口脚本 + 其静态 import 图）：300 KiB = 307200 B（gzip）；
- 单个路由块（`*View-*.js`）：150 KiB = 153600 B（gzip）。
  路由块按「每个文件」判，不按合计：一页过大就会拖慢该页自身首屏，
  合计口径会把问题摊平到看不见。

三档预算（统一按 1 KiB = 1024 B 换算，与既定口径「200 KiB = 204800 B」一致）：
- 图标 chunk：15 KiB = 15360 B（gzip）。
- 组件库 vendor chunk：200 KiB = 204800 B（gzip）。
  定档理由：试点 Naive UI 合计 154,779 B 含 vue runtime；主仓本就复用同一个
  runtime，接入后的组件库净增量应明显小于该独立入口完整产物。取整到 200 KiB
  既高于实测值、又留出余量，避免把「复用 runtime 后本应更小」的产物误判超标。
  （Element Plus 对照 174,484 B 同样落在该预算内。）
- 字体：800 KiB = 819200 B（gzip），对应步骤 2 的「≤ 800 KB gzip」。

gzip 数值由本脚本对产物文件即时计算（gzip.compress level=9、mtime=0 固定，
保证可复现），与试点 TSV 的「gzip 后字节」口径一致；Vite 默认不产出 .gz 文件，
所以不依赖 .gz 是否存在。

styleguide 标记约定（§4「构建产物 grep 验证」）
------------------------------------------------
用户会在 styleguide 组件里放一个稳定字符串 __STYLEGUIDE_DEV_ONLY__（本脚本
只认这一个常量）。/dev/styleguide 仅 DEV 挂载，生产构建里**不应**出现它：
- 命中   → 违规（DEV 页面泄漏到生产）；
- 找不到 → 不能假绿：脚本不假设该标记一定存在（组件还没写、字符串被换名、
  或产物被整体裁剪都可能），此时显式打印「未找到标记」并计入「未触发项」，
  让守门结果如实反映「这一项本轮其实没被验证到」，而不是闷头 PASS。

关于「当前产物还没有新资产」的处理
------------------------------------
本轮改造落地前，dist 里既没有 lucide / naive-ui 标识，也没有 woff2 / 标记。
判据是**上限**，用量为 0 天然不违规，因此这些情况一律不判 FAIL（不假红）；
但每一项都显式 [INFO] 报告「未检测到目标 / 检查未触发」，并在末尾 [PASS] 里
标出未触发项数（不假绿）。依赖与 styleguide 落地后，各项自动转为实测判定。

用法
----
    python3 tools/check_bundle_budget.py                  # 扫 frontend/dist
    python3 tools/check_bundle_budget.py --dist PATH      # 扫指定产物目录
    python3 tools/check_bundle_budget.py --self-test      # 破坏性自检（不碰 frontend/dist）

退出码（与其它 tools/check_*.py 一致）：
    0 = 通过（含 dist 缺失 SKIP、含未触发项如实报告）；
    1 = 有违规（自检模式下 = 自检失败）；
    2 = 扫描本身失败（dist 损坏 / 计数下限不满足 / 读文件出错，疑似假绿）。
"""

from __future__ import annotations

import argparse
import gzip
import os
import posixpath
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DIST = ROOT / "frontend" / "dist"

# ---- 预算：统一按 1 KiB = 1024 B 换算（与既定口径「200 KiB = 204800 B」一致） ----
ICON_BUDGET = 15 * 1024        # 15360 B —— §2.2 步骤 1「图标 chunk ≤ 15 KB」gzip
VENDOR_BUDGET = 200 * 1024     # 204800 B —— §2.2 步骤 8，试点实测后定
FONT_BUDGET = 800 * 1024       # 819200 B —— §2.2 步骤 2「首屏字体增量 ≤ 800 KB gzip」
# 懒加载共享分包（非入口 / 非路由视图）合计上限。
#
# 2026-11-05 定 120 KiB：引入 NDataTable 后实测 94 KiB（表格组件 92 KiB + 若干 util），
# 留约 26 KiB 余量。
#
# 2026-11-06 调到 160 KiB —— 不是体积涨了，是判据原来有洞，这次被填上后才看见。
# 起因：PLN-02 给「跳过事件」面板加日期范围筛选，PlansView 也开始用 NDatePicker，
# 于是 NDatePicker + date-fns 从「只被 AuditView 引用」变成「两页共享」，Vite 把它
# 提取成独立 `audit-*.js`（167219 B / gzip 37141 B）。此前这 37 KB 整体打在
# `AuditView-*.js` 里（171154 B / gzip 38630 B），被下面 `View-` 排除规则漏算，
# 合计只有 105540 B 看着达标。实测差值 142553 - 105540 = 37013 B，与新 chunk
# 37141 B 基本吻合，证明总量没变、只是从「不可见」变「可见」。
# 因此那时把**合计**预算上调到 160 KiB（163840 B）。
# v3.2.0 起改为按单包判（LAZY_CHUNK_BUDGET = 300 KiB）+ 单路由加载总量 300 KiB，
# 理由见下方常量注释：合计口径会把不同页面的按需包相加，既会误伤，也会掩盖
# 「某一页真的下载了 400 KiB」——后者由单路由总量这条新判据负责。

# ---- v3.2.0 EW32-01：前端双阈值（PRD §3.2「主入口 JS / 路由块」） ----
ENTRY_BUDGET = 300 * 1024      # 307200 B —— 主入口闭包 gzip
ROUTE_BUDGET = 150 * 1024      # 153600 B —— 单个 *View-*.js gzip

# Vite 产物里 index.html 的入口脚本 + ESM 静态 import（from"./x.js" / import"./x.js"）。
# 动态 import("...") 不进首屏闭包，正则用「引号紧邻」把 import( 排除掉。
ENTRY_SCRIPT_RE = re.compile(r'<script[^>]+src="([^"]+\.js)"', re.I)
STATIC_IMPORT_RE = re.compile(r'(?:from|import)\s*["\']([^"\']+\.js)["\']')
# 静态 from + 动态 import()：用于单路由可加载总量的闭包计算
ALL_IMPORT_RE = re.compile(r'(?:from\s*|import\s*\(\s*|import\s*)["\']([^"\']+\.js)["\']')

# 懒加载共享分包：v3.2.0 起按**单个 chunk** 判预算（上限与主入口同档 300 KiB），
# 不再把互不相干的按需分包相加。原因：editor / DataTable / audit 只在各自页面加载，
# 合计 400 KiB 不代表任一页面首屏要下载 400 KiB；用合计会逼着把编辑器重新塞回
# EditorView 路由块（那正是 EW32-06 要修的 247 KiB）。总量仍打印为 INFO 供趋势对比。
LAZY_CHUNK_BUDGET = ENTRY_BUDGET

# 单路由实际加载总量：路由 chunk + 它可达的懒加载分包 - 已在入口闭包里的共享代码。
# 上限沿用 300 KiB，用于证明「按需加载」没有把首屏搬到路由切换时。
ROUTE_TOTAL_BUDGET = ENTRY_BUDGET

# 标识是启发式：文件名或内容命中即认为属于该类 chunk（大小写不敏感）。
# 若 Vite 产物里的标识形态变化（例如只留 n-* 类名、不再出现包名），需同步更新
# 这里的常量；否则会如实走「未找到独立 chunk」分支并给出全量 JS gzip 数，
# 不会静默放过。
ICON_MARKER = b"lucide"                          # lucide-vue-next
VENDOR_MARKER = b"naive-ui"                      # Naive UI
STYLEGUIDE_MARKER = b"__STYLEGUIDE_DEV_ONLY__"   # 由 styleguide 组件提供，见上方约定
# 标记的正向来源：约定字符串必须真实存在于这个源文件里，否则「产物中没找到」不能
# 说明没泄漏（可能只是被改名）。这个文件由 router 的 DEV 条件挂载引用。
VERIFY_MARKER_SOURCE = ROOT / "frontend" / "src" / "views" / "StyleguideView.vue"
FONT_DISPLAY_RE = re.compile(r"font-display\s*:\s*swap", re.I)

# 计数下限：dist 存在但文件太少时，多半是产物没构建全 / 路径变了，而不是
# 「项目变干净了」。宁可报扫描失败（2），也不能让空产物一路 PASS 成假绿。
MIN_JS_FILES = 1
MIN_CSS_FILES = 1


@dataclass
class Entry:
    path: Path
    rel: str          # 相对 dist 的路径（即输出里的「文件名」）
    data: bytes
    raw: int
    gz: int
    suffix: str


@dataclass
class Bundle:
    dist: Path
    entries: list[Entry]

    @property
    def js(self) -> list[Entry]:
        return [e for e in self.entries if e.suffix == ".js"]

    @property
    def css(self) -> list[Entry]:
        return [e for e in self.entries if e.suffix == ".css"]

    @property
    def fonts(self) -> list[Entry]:
        return [e for e in self.entries if e.suffix == ".woff2"]

    @property
    def css_text(self) -> str:
        return "\n".join(e.data.decode("utf-8", "ignore") for e in self.css)


@dataclass
class Result:
    violations: list[str] = field(default_factory=list)
    infos: list[str] = field(default_factory=list)
    untriggered: list[str] = field(default_factory=list)
    scan_error: str | None = None


def gzip_len(data: bytes) -> int:
    # mtime=0 让结果可复现（gzip 头的时间戳不参与压缩体大小，但固定它更干净）。
    return len(gzip.compress(data, compresslevel=9, mtime=0))


def read_bundle(dist: Path) -> Bundle:
    """读取整个 dist；OSError 交给 main 转成退出码 2（扫描本身失败）。"""
    entries: list[Entry] = []
    for path in sorted(dist.rglob("*")):
        if not path.is_file():
            continue
        data = path.read_bytes()
        entries.append(Entry(
            path=path,
            # 统一用正斜杠：下面的判据正则按 `assets/index-*.js` 写，Windows 的
            # `assets\index-*.js` 会漏匹配，把入口与 vue/lucide 误算进懒加载预算。
            rel=path.relative_to(dist).as_posix(),
            data=data,
            raw=len(data),
            gz=gzip_len(data),
            suffix=path.suffix.lower(),
        ))
    return Bundle(dist=dist, entries=entries)


def has_marker(e: Entry, marker: bytes) -> bool:
    """文件名或内容含标识（大小写不敏感）。"""
    return marker in e.path.name.lower().encode() or marker in e.data.lower()


def _normalize_rel(spec: str) -> str:
    clean = spec.split("?", 1)[0].split("#", 1)[0]
    if clean.startswith("/"):
        clean = clean[1:]
    return posixpath.normpath(clean)


def entry_scripts(bundle: Bundle) -> list[str]:
    """从 index.html 取入口脚本的 dist 相对路径（保持声明顺序）。"""
    html = next((e for e in bundle.entries if e.rel == "index.html"), None)
    if html is None:
        return []
    text = html.data.decode("utf-8", "ignore")
    return [m.group(1) for m in ENTRY_SCRIPT_RE.finditer(text)]


def entry_closure(bundle: Bundle, starts: list[str]) -> list[Entry]:
    """静态 import 闭包：入口脚本 + 它（递归）`from "./x.js"` 引用的 chunk。

    只跟静态 import：动态 `import("...")` 是路由块，不属于首屏闭包。
    找不到的 spec（例如外部 URL、非 .js 资产）直接跳过，不猜。
    """
    by_rel = {e.rel: e for e in bundle.js}
    queue = [_normalize_rel(s) for s in starts]
    seen: set[str] = set()
    result: list[Entry] = []
    while queue:
        rel = queue.pop(0)
        if rel in seen:
            continue
        entry = by_rel.get(rel)
        if entry is None:
            continue
        seen.add(rel)
        result.append(entry)
        text = entry.data.decode("utf-8", "ignore")
        for match in STATIC_IMPORT_RE.finditer(text):
            spec = match.group(1)
            if spec.startswith("http://") or spec.startswith("https://"):
                continue
            if spec.startswith("/"):
                target = _normalize_rel(spec)
            else:
                base = entry.rel.rsplit("/", 1)[0] if "/" in entry.rel else ""
                target = _normalize_rel(f"{base}/{spec}" if base else spec)
            queue.append(target)
    return result


def module_closure(bundle: Bundle, start_rel: str,
                   stop_rels: set[str] | None = None) -> list[Entry]:
    """跟随静态 `from` 与动态 `import()` 的模块闭包，用于单路由加载总量。

    与 `entry_closure` 的区别：这里要算「进入某页后还会按需拉什么」，
    所以动态 import 也跟进；入口闭包只算首屏静态依赖。

    `stop_rels` 里的文件（已在主入口闭包中）计入体积但不继续展开：否则路由块
    引用共享 vendor 时会走回入口 chunk，再把入口里**全部路由的懒加载表**算进来，
    导致每个页面都显示「加载了所有页面」。
    """
    by_rel = {e.rel: e for e in bundle.js}
    stop = stop_rels or set()
    queue = [_normalize_rel(start_rel)]
    seen: set[str] = set()
    result: list[Entry] = []
    while queue:
        rel = queue.pop(0)
        if rel in seen:
            continue
        entry = by_rel.get(rel)
        if entry is None:
            continue
        seen.add(rel)
        result.append(entry)
        if rel in stop:
            continue
        text = entry.data.decode("utf-8", "ignore")
        for match in ALL_IMPORT_RE.finditer(text):
            spec = match.group(1)
            if spec.startswith("http://") or spec.startswith("https://"):
                continue
            if spec.startswith("/"):
                target = _normalize_rel(spec)
            else:
                base = entry.rel.rsplit("/", 1)[0] if "/" in entry.rel else ""
                target = _normalize_rel(f"{base}/{spec}" if base else spec)
            queue.append(target)
    return result


def evaluate(bundle: Bundle) -> Result:
    r = Result()

    # ---- 自检 0：计数下限（防「dist 存在但几乎没文件」的假绿） ----
    if len(bundle.js) < MIN_JS_FILES or len(bundle.css) < MIN_CSS_FILES:
        r.scan_error = (
            f"dist 存在但文件数过少：JS {len(bundle.js)} 个（需 ≥{MIN_JS_FILES}）、"
            f"CSS {len(bundle.css)} 个（需 ≥{MIN_CSS_FILES}）；共 {len(bundle.entries)} 个文件，"
            f"疑似产物未完整生成或目录结构变更"
        )
        return r

    # ---- (a) 图标 chunk ≤ 15 KiB gzip（§2.2 步骤 1 / TC-S1-01） ----
    # 只按**文件名**认定独立图标 chunk。曾经按「文件内容含 lucide 标识」认定，
    # 结果把含 vue runtime + naive-ui 的整个主包（138 KB gzip）当成图标 chunk 判超预算 ——
    # 守门自己造假红。现在：内容里有 lucide 但文件名没有 → 说明图标没独立分包，
    # 明确报「无法按 chunk 判预算」（vite.config.ts 的 manualChunks 负责拆出来）。
    icon_named = [e for e in bundle.js if "lucide" in e.path.name.lower()]
    icon_any = [e for e in bundle.js if has_marker(e, ICON_MARKER)]
    if not icon_any:
        r.untriggered.append("图标 chunk（lucide 标识）")
        r.infos.append(
            "未检测到 lucide 图标 chunk（文件名/内容均无 lucide 标识）；"
            "图标 ≤ 15 KiB 检查未触发"
        )
    elif not icon_named:
        r.violations.append(
            "图标未独立分包：lucide 代码混在 "
            + ", ".join(e.rel for e in icon_any)
            + " 中，无法按 chunk 判预算（§2.2 步骤 1 要求独立图标 chunk）；"
            "请在 vite.config.ts 的 manualChunks 拆出 lucide"
        )
    else:
        total_gz = sum(e.gz for e in icon_named)
        over = [e for e in icon_named if e.gz > ICON_BUDGET]
        for e in over:
            r.violations.append(
                f"图标 chunk 超预算：{e.rel} gzip {e.gz} B > {ICON_BUDGET} B（15 KiB）"
            )
        if not over:
            r.infos.append(
                f"图标 chunk {len(icon_named)} 个，gzip 合计 {total_gz} B ≤ "
                f"{ICON_BUDGET} B（15 KiB），达标"
            )

    # ---- (b) 组件库 vendor chunk ≤ 200 KiB gzip（§2.2 步骤 8 / TC-S1-09） ----
    vendor_chunks = [e for e in bundle.js if has_marker(e, VENDOR_MARKER)]
    if not vendor_chunks:
        all_js_gz = sum(e.gz for e in bundle.js)
        all_js_raw = sum(e.raw for e in bundle.js)
        r.untriggered.append("组件库 vendor chunk（naive-ui 标识）")
        r.infos.append(
            "未找到独立的 naive-ui vendor chunk（文件名/内容均无 naive-ui 标识），"
            "无法单独衡量组件库体积；200 KiB 判据本轮不适用。"
            f"全量 JS gzip 合计 {all_js_gz} B（原始 {all_js_raw} B，{len(bundle.js)} 个 .js 文件），"
            "仅作参考、不参与判据"
        )
    else:
        total_gz = sum(e.gz for e in vendor_chunks)
        over = [e for e in vendor_chunks if e.gz > VENDOR_BUDGET]
        for e in over:
            r.violations.append(
                f"组件库 vendor chunk 超预算：{e.rel} gzip {e.gz} B > "
                f"{VENDOR_BUDGET} B（200 KiB；试点实测 154,779 B 取整定档）"
            )
        if not over:
            r.infos.append(
                f"组件库 vendor chunk {len(vendor_chunks)} 个，gzip 合计 {total_gz} B ≤ "
                f"{VENDOR_BUDGET} B（200 KiB），达标"
            )

    # ---- (b2) 懒加载第三方分包 ≤ LAZY_BUDGET gzip（2026-11-05 新增） ----
    #
    # 起因：UX-04b 把审计/报告两页的表格换成 `NDataTable` 后，Vite 把表格组件单独拆成
    # `DataTable-*.js`（gzip 92 KiB）。这个 chunk **不含** "naive-ui" 字样，于是 (b) 的
    # vendor 判据只看到 104 KiB 就报达标 —— 凭空多出 92 KiB 而预算毫无反应，这正是
    # 「写了守门但守不住」的典型。这里补一条覆盖「非入口、非路由视图」的共享分包。
    #
    # 识别依赖项目约定：路由视图一律命名 `XxxView.vue`（Vite 会产出 `XxxView-*.js`），
    # 入口固定 `index-*.js`，vue runtime 与图标 chunk 另有名字。新增一类共享分包时
    # 无需改这里；但如果哪天出现不叫 `*View` 的页面组件，需要同步本判据。
    lazy_shared = [
        e
        for e in bundle.js
        if not re.search(r"(^|/)(index|vue|lucide)-[^/]*\.js$", e.rel)
        and not re.search(r"View-[^/]*\.js$", e.rel)
    ]
    if lazy_shared:
        lazy_gz = sum(e.gz for e in lazy_shared)
        biggest = max(lazy_shared, key=lambda e: e.gz)
        over = [e for e in lazy_shared if e.gz > LAZY_CHUNK_BUDGET]
        for e in over:
            r.violations.append(
                f"懒加载分包超预算：{e.rel} gzip {e.gz} B > "
                f"{LAZY_CHUNK_BUDGET} B（{LAZY_CHUNK_BUDGET // 1024} KiB）"
            )
        if not over:
            r.infos.append(
                f"懒加载共享分包 {len(lazy_shared)} 个，单包最大 {biggest.rel} "
                f"{biggest.gz} B ≤ {LAZY_CHUNK_BUDGET} B（{LAZY_CHUNK_BUDGET // 1024} KiB），达标"
                f"；按需总量 gzip 合计 {lazy_gz} B（仅趋势参考，不跨页相加判违规）"
            )

    # ---- (c) 字体 ≤ 800 KiB + font-display: swap（§2.2 步骤 2 / TC-S1-02） ----
    # 以 dist 内全部 woff2 之和作首屏增量的保守上界（不细分 unicode-range 分包，
    # 避免漏报）；原始总和与 gzip 后总和都报告，取较大者与预算比较。
    fonts = bundle.fonts
    raw_total = sum(e.raw for e in fonts)
    gz_total = sum(e.gz for e in fonts)
    if not fonts:
        r.untriggered.append("字体资产（woff2 总和 / font-display: swap）")
        r.infos.append("未检测到 *.woff2 字体资产；字体 ≤ 800 KiB 检查未触发")
        if FONT_DISPLAY_RE.search(bundle.css_text):
            r.infos.append("CSS 中存在 font-display: swap 声明（但当前未引入 woff2 字体资产，该项未触发判定）")
        else:
            r.infos.append("CSS 中未找到 font-display: swap 声明（当前产物未引入打包字体，该项未触发）")
    else:
        worst = max(raw_total, gz_total)
        if worst > FONT_BUDGET:
            r.violations.append(
                f"字体超预算：{len(fonts)} 个 woff2 原始合计 {raw_total} B、"
                f"gzip 合计 {gz_total} B，较大者 {worst} B > {FONT_BUDGET} B（800 KiB）"
            )
        else:
            r.infos.append(
                f"字体 {len(fonts)} 个 woff2：原始合计 {raw_total} B、gzip 合计 "
                f"{gz_total} B ≤ {FONT_BUDGET} B（800 KiB），达标"
            )
        # 有字体资产时才强制要求 swap：font-display 缺失会阻塞首帧。
        if FONT_DISPLAY_RE.search(bundle.css_text):
            r.infos.append("CSS 中存在 font-display: swap，首帧不被字体阻塞，达标")
        else:
            r.violations.append(
                "检测到 woff2 字体资产，但 CSS 中未找到 font-display: swap 声明"
                "（会阻塞首帧，违反 §2.2 步骤 2）"
            )

    # ---- (d) styleguide 标记不得出现在生产产物（§4 / 构建产物 grep 验证） ----
    # 先做**正向**校验：约定字符串必须还在源码里被真实使用（`StyleguideView.vue` 的
    # `:data-marker="MARKER"`）。没有这一步，「产物里没找到标记」就有第二种解释——
    # 有人把标记改名或删了，于是这项检查永远绿。守门必须能区分「没泄漏」和「根本没测」。
    marker_src = VERIFY_MARKER_SOURCE
    if marker_src.is_file():
        src_text = marker_src.read_text(encoding="utf-8", errors="replace")
        if STYLEGUIDE_MARKER.decode() not in src_text:
            r.violations.append(
                f"styleguide 标记约定失效：{marker_src} 里找不到 "
                f"{STYLEGUIDE_MARKER.decode()}。约定字符串被改名/删除，"
                "「产物无泄漏」的检查将失去意义（改回该字符串，或同步改本脚本）"
            )
        else:
            r.infos.append(
                f"styleguide 标记在源码中存在（{marker_src.name}），"
                "产物侧 grep 因此具备判定力"
            )
    else:
        # 源码路径都不在了，说明目录结构变了：不静默跳过，计入未触发并说明原因。
        r.untriggered.append("styleguide 源码标记（源码文件不存在）")
        r.infos.append(
            f"[INFO] 未找到 {marker_src}，无法做标记正向校验；"
            "产物侧仍继续 grep，但结论强度下降"
        )

    # 对全部产物文件（含 .html 等）做字节级 grep，等价于 grep -r。
    hits = [e for e in bundle.entries if STYLEGUIDE_MARKER in e.data]
    if hits:
        for e in hits:
            r.violations.append(
                f"styleguide 标记泄漏到生产：{e.rel} 含 __STYLEGUIDE_DEV_ONLY__"
                "（§4 要求构建产物 grep 为空）"
            )
    elif marker_src.is_file() and STYLEGUIDE_MARKER.decode() in marker_src.read_text(
            encoding="utf-8", errors="replace"):
        # 正向校验通过 + 产物侧 0 命中 = 真的没泄漏，不再挂「未触发」。
        r.infos.append(
            "生产产物中未找到 __STYLEGUIDE_DEV_ONLY__ 标记，且源码侧标记确认存在"
            "（DEV 条件挂载 + tree-shaking 生效），本项已被反向验证"
        )
    else:
        r.untriggered.append("styleguide 标记 grep（未检出标记）")
        r.infos.append(
            "生产产物中未找到 __STYLEGUIDE_DEV_ONLY__ 标记（无泄漏）；但源码侧标记缺失，"
            "无法排除「字符串被换名」，故如实计入『未触发』，不假绿"
        )

    # ---- (e) v3.2.0 前端双阈值：主入口闭包 ≤300 KiB / 单路由块 ≤150 KiB ----
    starts = entry_scripts(bundle)
    if not starts:
        r.untriggered.append("主入口闭包（index.html 未声明 <script src>）")
        r.infos.append(
            "index.html 中未找到入口脚本；主入口 ≤300 KiB 检查未触发"
            "（若产物结构变化，请同步 ENTRY_SCRIPT_RE）"
        )
    else:
        closure = entry_closure(bundle, starts)
        missing = [s for s in starts if _normalize_rel(s) not in {e.rel for e in closure}]
        entry_gz = sum(e.gz for e in closure)
        if not closure:
            r.untriggered.append("主入口闭包（入口脚本不在产物内）")
            r.infos.append(
                f"index.html 声明的入口 {starts} 在产物中找不到同名 .js；"
                "主入口预算无法判定（不假绿）"
            )
        else:
            biggest = max(closure, key=lambda e: e.gz)
            if entry_gz > ENTRY_BUDGET:
                r.violations.append(
                    f"主入口闭包 gzip {entry_gz} B > {ENTRY_BUDGET} B（300 KiB）："
                    f"{len(closure)} 个文件，最大 {biggest.rel} {biggest.gz} B"
                )
            else:
                r.infos.append(
                    f"主入口闭包 gzip {entry_gz} B ≤ {ENTRY_BUDGET} B（300 KiB），"
                    f"含 {len(closure)} 个文件（最大 {biggest.rel} {biggest.gz} B）"
                )
        if missing:
            r.infos.append(
                "入口声明里有未解析到文件的 src：" + ", ".join(missing)
                + "（可能被 HTML 改写或产物不完整）"
            )

    routes = [
        e for e in bundle.js
        if re.search(r"(^|/)[^/]*View-[^/]*\.js$", e.rel)
    ]
    if not routes:
        r.untriggered.append("路由块（未找到 *View-*.js）")
        r.infos.append("未检测到路由视图 chunk；单路由块 ≤150 KiB 检查未触发")
    else:
        over = [e for e in routes if e.gz > ROUTE_BUDGET]
        biggest = max(routes, key=lambda e: e.gz)
        for e in over:
            r.violations.append(
                f"路由块超预算：{e.rel} gzip {e.gz} B > {ROUTE_BUDGET} B（150 KiB）"
            )
        if not over:
            r.infos.append(
                f"路由块 {len(routes)} 个，单块最大 {biggest.rel} {biggest.gz} B ≤ "
                f"{ROUTE_BUDGET} B（150 KiB），达标"
            )

        # (e2) 单路由实际加载总量：路由块 + 其可达的按需分包，扣掉已在入口闭包里的共享代码。
        # 这条防止「把大块挪进懒加载分包，让路由块数字变好看，但切页仍下载 250 KiB」。
        entry_rels = set()
        if starts:
            entry_rels = {e.rel for e in entry_closure(bundle, starts)}
        worst_rel = ""
        worst_total = 0
        for route in routes:
            incremental = [e for e in module_closure(bundle, route.rel, entry_rels)
                           if e.rel not in entry_rels]
            total = sum(e.gz for e in incremental)
            if total > worst_total:
                worst_total = total
                worst_rel = route.rel
            if total > ROUTE_TOTAL_BUDGET:
                r.violations.append(
                    f"单路由加载总量超预算：{route.rel} 及其按需分包 gzip {total} B > "
                    f"{ROUTE_TOTAL_BUDGET} B（{ROUTE_TOTAL_BUDGET // 1024} KiB）"
                )
        if worst_total <= ROUTE_TOTAL_BUDGET:
            r.infos.append(
                f"单路由加载总量（路由块 + 按需分包 - 入口已下载）最大 {worst_rel} "
                f"{worst_total} B ≤ {ROUTE_TOTAL_BUDGET} B（{ROUTE_TOTAL_BUDGET // 1024} KiB），达标"
            )

    return r


def report(bundle: Bundle, r: Result) -> None:
    js, css, fonts = bundle.js, bundle.css, bundle.fonts
    other = len(bundle.entries) - len(js) - len(css) - len(fonts)
    print(f"扫描产物目录：{bundle.dist}")
    print(
        f"产物概览：共 {len(bundle.entries)} 个文件"
        f"（JS {len(js)} / CSS {len(css)} / woff2 {len(fonts)} / 其它 {other}）"
    )
    print(
        f"全量 JS ：原始 {sum(e.raw for e in js):>9} B / gzip "
        f"{sum(e.gz for e in js):>9} B（{len(js)} 个文件）"
    )
    print(
        f"全量 CSS：原始 {sum(e.raw for e in css):>9} B / gzip "
        f"{sum(e.gz for e in css):>9} B（{len(css)} 个文件）"
    )

    print("\n=== 产物文件清单（原始字节 / gzip 字节 / 文件名）===")
    for e in bundle.entries:
        if e.suffix in (".js", ".css", ".woff2"):
            print(f"  {e.raw:>9} B / gzip {e.gz:>9} B  {e.rel}")

    print("\n=== 各项判定 ===")
    for v in r.violations:
        print(f"[FAIL] {v}")
    for i in r.infos:
        print(f"[INFO] {i}")


# ---- 破坏性自检用的夹具：全部写在临时目录，不触碰 frontend/dist ----

def _make_baseline(dist: Path) -> Path:
    """构造满足计数下限、不含任何违规的最小 Vite 产物（含入口 script 引用）。"""
    assets = dist / "assets"
    assets.mkdir(parents=True)
    (dist / "index.html").write_text(
        '<!doctype html><html><body><script type="module" src="./assets/index-AAAA.js">'
        "</script></body></html>", encoding="utf-8")
    (assets / "index-AAAA.js").write_bytes(b"export const ok = 1;\n")
    # CSS 里放 swap：注入「字体超预算」用例时不会混入「缺 font-display」的额外违规
    (assets / "index-BBBB.css").write_bytes(b"body{color:#333;font-display:swap}\n")
    return dist


def _inject_icon_over(dist: Path) -> None:
    # 文件名 + 内容都带 lucide 标识；不可压缩随机字节确保 gzip 后 > 15 KiB
    payload = b"/* lucide icon chunk */\n" + os.urandom(20000)
    (dist / "assets" / "lucide-CCCC.js").write_bytes(payload)


def _inject_font_over(dist: Path) -> None:
    # 随机字节 gzip 后几乎不缩，稳超 800 KiB
    (dist / "assets" / "noto-sans-sc-DDDD.woff2").write_bytes(
        b"wOF2" + os.urandom(820000)
    )


def _inject_styleguide_leak(dist: Path) -> None:
    (dist / "assets" / "styleguide-EEEE.js").write_bytes(
        b"export const devOnly = '__STYLEGUIDE_DEV_ONLY__';\n"
    )


def _inject_entry_over(dist: Path) -> None:
    # 入口脚本换成一个不可压缩的大文件，gzip 后稳超 300 KiB
    (dist / "assets" / "index-OVER.js").write_bytes(
        b"/* entry */\n" + os.urandom(340_000)
    )
    (dist / "index.html").write_text(
        '<!doctype html><html><body><script type="module" src="./assets/index-OVER.js">'
        "</script></body></html>", encoding="utf-8")


def _inject_route_over(dist: Path) -> None:
    # 单个路由块不可压缩，gzip 后稳超 150 KiB
    (dist / "assets" / "CasesView-OVER.js").write_bytes(
        b"/* route */\n" + os.urandom(170_000)
    )


def self_test() -> int:
    """破坏性自检：在临时目录伪造 dist，逐类注入违规，确认 evaluate 真能拦截。

    不污染 frontend/dist：每个用例一个 TemporaryDirectory，dist 通过参数传给
    read_bundle/evaluate；不改任何全局常量（无需 monkeypatch），上下文退出即
    删除临时目录 —— 自检结束天然还原。
    """
    failures = 0

    # 用例 0：干净基线必须 PASS（验证「没引入新资产时不假红」）
    with tempfile.TemporaryDirectory(prefix="bundle-budget-selftest-") as tmp:
        r = evaluate(read_bundle(_make_baseline(Path(tmp))))
        if r.scan_error or r.violations:
            print(
                f"[FAIL] 自检基线：干净产物未被判通过"
                f"（scan_error={r.scan_error}, violations={r.violations}）",
                file=sys.stderr,
            )
            failures += 1
        else:
            print("[SELFTEST] 通过：干净基线产物不假红（应返回 PASS）")

    cases = [
        ("图标超预算", _inject_icon_over, "图标"),
        ("字体超预算", _inject_font_over, "字体"),
        ("styleguide 标记泄漏", _inject_styleguide_leak, "styleguide"),
        ("主入口超预算", _inject_entry_over, "主入口"),
        ("单路由块超预算", _inject_route_over, "路由块"),
    ]
    for label, inject, keyword in cases:
        with tempfile.TemporaryDirectory(prefix="bundle-budget-selftest-") as tmp:
            dist = _make_baseline(Path(tmp))
            inject(dist)
            r = evaluate(read_bundle(dist))
            matched = [v for v in r.violations if keyword in v]
            if matched:
                print(f"[SELFTEST] 通过：注入「{label}」后按预期报 FAIL —— {matched[0]}")
            else:
                print(
                    f"[FAIL] 自检：「{label}」未被拦截"
                    f"（scan_error={r.scan_error}, violations={r.violations}）",
                    file=sys.stderr,
                )
                failures += 1

    if failures:
        print(f"[FAIL] 自检失败：{failures} 个用例未按预期工作", file=sys.stderr)
        return 1
    print(
        "[SELFTEST] 通过：图标 / 字体 / styleguide / 主入口 / 单路由块五类违规均可被拦截；"
        "临时目录已随上下文销毁，frontend/dist 未被触碰"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="UX-04 S1 构建产物体积 / styleguide 泄漏守门")
    parser.add_argument(
        "--dist", type=Path, default=DEFAULT_DIST,
        help="Vite 产物目录（默认 frontend/dist）",
    )
    parser.add_argument(
        "--self-test", action="store_true",
        help="破坏性自检：注入三类违规验证拦截，不碰 frontend/dist",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    dist: Path = args.dist
    if not dist.exists():
        print("[SKIP] 未找到构建产物，请先 npm run build")
        return 0
    if not dist.is_dir():
        print(f"[FAIL] 扫描失败：{dist} 存在但不是目录", file=sys.stderr)
        return 2

    try:
        bundle = read_bundle(dist)
    except OSError as exc:
        print(f"[FAIL] 扫描失败：读取产物出错：{exc}", file=sys.stderr)
        return 2

    r = evaluate(bundle)
    if r.scan_error:
        print(f"[FAIL] 扫描失败：{r.scan_error}", file=sys.stderr)
        return 2

    report(bundle, r)
    if r.violations:
        print(f"\n[FAIL] 共 {len(r.violations)} 项违规（预算超标 / 标记泄漏）", file=sys.stderr)
        return 1

    extra = (
        f"；6 项守门中 {len(r.untriggered)} 项因产物未检出目标资产/标记而未被实际触发验证"
        "（详见上方 [INFO]）"
        if r.untriggered else ""
    )
    print(
        "\n[PASS] 构建产物体积守门通过：图标 ≤15 KiB / 组件库 ≤200 KiB / "
        f"字体 ≤800 KiB / 无 styleguide 标记泄漏 / 主入口 ≤300 KiB / 单路由块 ≤150 KiB{extra}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
