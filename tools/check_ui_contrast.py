#!/usr/bin/env python3
"""W57-44 体验机检 · 静态 token 对比度（4.5:1）与「语义色只用 token」检查。

判据分两段，都是**静态**的（不依赖浏览器，可在空库环境里跑）：

  ① 对比度：复用 ``tools/check_contrast.py`` 的 theme.css 分组解析与 WCAG 相对亮度
     算法，只取阈值 4.5:1 的正文 / 文字组合，逐 (主题 × 强调色) 分组判定；
  ② 语义色：扫 W57 新增组件的 ``<style>`` 段，颜色一律走 ``var(--token)``，
     颜色属性里出现硬编码 hex / rgb / hsl 字面量即 FAIL —— 写死颜色不随浅深主题
     切换，是「编译过、单测过、暗色下却发白」的那一类缺陷。

输出固定一行：``CONTRAST_RESULT PASS=n FAIL=n``

``--self-test`` 是**真注入**：把 theme.css 与一个组件各复制成临时文件，注入一处
「低对比度 token」与一处「硬编码颜色」，两次判据都必须判红；删掉临时文件
（还原）后，对真实文件再判一次必须判绿。全程不写真实源文件，中途失败也不会
在仓库里留下脏文件。
"""
from __future__ import annotations

import argparse
import importlib.util
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
THEME_CSS = ROOT / "frontend" / "src" / "styles" / "theme.css"
COMPONENTS_DIR = ROOT / "frontend" / "src" / "components"

# W57 新增组件：这些文件的样式必须只用语义 token（范围显式列出，避免把既有
# 页面里历史遗留的硬编码一起判红 —— 那部分由 check_css_vars.py / check_contrast.py 守）。
W57_COMPONENTS = (
    "ColumnFilter.vue",
    "ListScaffold.vue",
    "ConfirmAction.vue",
    "ReadOnlyNotice.vue",
    "CredentialPicker.vue",
)

STYLE_RE = re.compile(r"<style[^>]*>(.*?)</style>", re.S)
COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
# 颜色字面量：#hex / rgb() / rgba() / hsl() / hsla()。var(--x) 不算。
COLOR_LITERAL_RE = re.compile(
    r"#[0-9a-fA-F]{3,8}\b|\brgba?\([^)]*\)|\bhsla?\([^)]*\)"
)
# 只在颜色类属性的值里找字面量，避免 width: calc(...) 之类误判。
COLOR_DECL_RE = re.compile(
    r"(?:color|background|background-color|border|border-color|"
    r"border-left-color|border-top-color|border-bottom-color|border-right-color|"
    r"outline|outline-color|box-shadow|fill|stroke)\s*:\s*([^;]+);",
    re.I,
)


def _load_contrast_module():
    """复用 check_contrast.py 的解析与亮度算法（它有 __main__ 保护，导入无副作用）。"""
    path = ROOT / "tools" / "check_contrast.py"
    spec = importlib.util.spec_from_file_location("check_contrast_lib", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载 {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


LIB = _load_contrast_module()
# 只取 4.5:1 的组合；组合表以 check_contrast.py 的 CHECKS 为唯一真源，不另抄一份。
CHECKS_45 = [c for c in LIB.CHECKS if c[2] == 4.5]


def component_paths() -> list[Path]:
    return [COMPONENTS_DIR / name for name in W57_COMPONENTS]


def contrast_findings(theme_path: Path) -> tuple[int, int, list[str]]:
    """返回 (pass, fail, details)。解析失败按 FAIL 计，绝不静默跳过。"""
    passed = 0
    failed = 0
    details: list[str] = []
    try:
        _primitives, groups = LIB.parse_theme(theme_path)
    except Exception as exc:  # noqa: BLE001 - 解析异常必须判红而不是放过
        return 0, 1, [f"theme.css 解析失败：{exc}"]
    if not groups:
        return 0, 1, ["theme.css 未解析出任何 (主题 × 强调色) 分组"]
    for (theme, accent), variables in sorted(groups.items()):
        for fg_name, bg_name, _threshold, label in CHECKS_45:
            fg = LIB.resolve(f"--{fg_name}", variables)
            bg = LIB.resolve(f"--{bg_name}", variables)
            ratio = LIB.contrast(fg, bg)
            if ratio is None:
                # 非 hex 值（color-mix 等）由 check_contrast.py 统一跳过，这里同样不判
                continue
            if ratio >= 4.5:
                passed += 1
            else:
                failed += 1
                details.append(
                    f"{theme}/{accent} {label}：{fg} on {bg} = {ratio:.2f}:1 < 4.5"
                )
    return passed, failed, details


def semantic_findings(paths: list[Path]) -> tuple[int, int, list[str]]:
    """每个组件算 1 项：颜色属性里出现硬编码颜色字面量即 FAIL。"""
    passed = 0
    failed = 0
    details: list[str] = []
    for path in paths:
        if not path.is_file():
            failed += 1
            details.append(f"缺少组件：{path.name}")
            continue
        text = path.read_text(encoding="utf-8")
        styles = COMMENT_RE.sub("", "\n".join(STYLE_RE.findall(text)))
        offenders = [
            decl.strip()[:80]
            for decl in COLOR_DECL_RE.findall(styles)
            if COLOR_LITERAL_RE.search(decl)
        ]
        if offenders:
            failed += 1
            details.append(f"{path.name} 硬编码颜色：{'; '.join(offenders[:3])}")
        else:
            passed += 1
    return passed, failed, details


def run(theme_path: Path, paths: list[Path]) -> tuple[int, int, list[str]]:
    p1, f1, d1 = contrast_findings(theme_path)
    p2, f2, d2 = semantic_findings(paths)
    return p1 + p2, f1 + f2, d1 + d2


def self_test() -> int:
    """真注入 → 判红 → 还原 → 判绿。返回 0 = 自检通过。"""
    tmpdir = Path(tempfile.mkdtemp(prefix="ui-contrast-selftest-"))
    ok = True
    clean_pass = 0
    clean_fail = 0
    try:
        # ① 对比度：复制 theme.css，追加一个把 --text / --bg 设成同色的覆盖块。
        #    追加在文件末尾 -> parse_theme 的 defaults 会 update 到它，必然产生 1.0:1。
        dirty_theme = tmpdir / "theme.css"
        shutil.copyfile(THEME_CSS, dirty_theme)
        with dirty_theme.open("a", encoding="utf-8") as fh:
            fh.write("\n:root[data-theme='dark'] { --text: #101010; --bg: #101010; }\n")
        _p, f_bad, _d = contrast_findings(dirty_theme)
        if f_bad == 0:
            print("[SELFTEST FAIL] 注入低对比 token 后未判红")
            ok = False
        else:
            print(f"[SELFTEST PASS] 注入低对比 token 判红 {f_bad} 项")

        # ② 语义色：复制一个组件，追加一段带硬编码颜色的样式。
        dirty_component = tmpdir / "ReadOnlyNotice.vue"
        shutil.copyfile(COMPONENTS_DIR / "ReadOnlyNotice.vue", dirty_component)
        with dirty_component.open("a", encoding="utf-8") as fh:
            fh.write("\n<style scoped>\n.injected { color: #ff0000; }\n</style>\n")
        _p2, f_bad2, _d2 = semantic_findings([dirty_component])
        if f_bad2 == 0:
            print("[SELFTEST FAIL] 注入硬编码颜色后未判红")
            ok = False
        else:
            print(f"[SELFTEST PASS] 注入硬编码颜色判红 {f_bad2} 项")

        # ③ 还原（删临时文件）后对真实文件判绿
        for item in (dirty_theme, dirty_component):
            item.unlink(missing_ok=True)
        clean_pass, clean_fail, clean_detail = run(THEME_CSS, component_paths())
        if clean_fail != 0:
            print(f"[SELFTEST FAIL] 还原后真实文件仍判红：{clean_detail[:3]}")
            ok = False
        else:
            print(f"[SELFTEST PASS] 还原后真实文件判绿（PASS={clean_pass}）")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"CONTRAST_RESULT PASS={clean_pass} FAIL={clean_fail}")
    print(f"SELFTEST_RESULT {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="W57-44 静态 token 对比度与语义色检查")
    parser.add_argument("--self-test", action="store_true", help="真注入负向自检")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    if not THEME_CSS.is_file():
        print(f"[FAIL] 找不到 {THEME_CSS}", file=sys.stderr)
        print("CONTRAST_RESULT PASS=0 FAIL=1")
        return 2

    passed, failed, details = run(THEME_CSS, component_paths())
    for line in details[:20]:
        print(f"  FAIL {line}")
    print(f"CONTRAST_RESULT PASS={passed} FAIL={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
