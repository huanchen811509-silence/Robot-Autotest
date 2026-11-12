#!/usr/bin/env python3
# RA-5.0.X 机检：退出码 0=PASS 1=判红 2=读不到证据。--selfcheck 必须实证「注入反例必判红」。
# 规划文档里的 /api 端点必须精确命中 docs/contracts/openapi.json 的路径（模板参数归一化）。
# 不设白名单：契约已有 293 条路径，任何未收录的端点都判孤儿。
import argparse, json, pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
P = ROOT / "docs/plan"
DOCS = ["v5.0.X存量打磨-产品PRD-20261112.md",
        "v5.0.X存量打磨-开发计划-20261112.md",
        "v5.0.X存量打磨-测试计划-20261112.md"]
CONTRACT = ROOT / "docs/contracts/openapi.json"


# 规划文档会引用「曾经写错、根本不存在」的端点作为历史漂移反例；这类行不是声明，跳过。
# 判据只豁免「同一行带明确反例标记」的引用，不豁免任何普通端点。
EXEMPT = ("根本不存在", "计划与实现漂移", "不存在的端点", "反例", "孤儿端点")


def endpoints(text):
    out = set()
    for line in text.splitlines():
        if any(x in line for x in EXEMPT):
            continue
        for raw in re.findall(r"`(/api/[^`\s]+)`", line):
            p = raw.split("?", 1)[0].rstrip("/")
            p = re.sub(r"/\d+(?=/|$)", "/{id}", p)
            out.add(p)
    return out


def norm(p):
    return re.sub(r"\{[^}]*\}", "{}", p.rstrip("/"))


def check(text, paths):
    cset = {norm(c) for c in paths}
    return ["孤儿端点: " + e for e in sorted(endpoints(text)) if norm(e) not in cset]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if not CONTRACT.exists():
        print("[FAIL] 读不到 openapi.json"); return 2
    paths = list(json.loads(CONTRACT.read_text(encoding="utf-8")).get("paths", {}))
    text = "\n".join((P / d).read_text(encoding="utf-8") for d in DOCS)
    if a.selfcheck:
        if not check(text + "\n`/api/__v50_orphan__/x`\n", paths):
            print("[FAIL] selfcheck：注入孤儿端点未判红"); return 1
        if check(text, paths):
            print("[FAIL] selfcheck：干净文档不应判红"); return 1
        print("[PASS] endpoints selfcheck：孤儿判红 / 干净判绿"); return 0
    bad = check(text, paths)
    if bad:
        for x in bad: print("[FAIL] " + x)
        return 1
    print("[PASS] 规划文档 %d 个 /api 端点精确命中 %d 条契约路径，零孤儿"
          % (len(endpoints(text)), len(paths))); return 0


if __name__ == "__main__":
    raise SystemExit(main())
