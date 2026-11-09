#!/usr/bin/env python3
"""EW36-08 · AI 黄金集（100 条）离线契约评测。

边界：本工具只验「样本集本身的契约」——字段完整、无凭据、锚点/拒答规则覆盖、
判定列未预填；**不代替**真实端点的事实一致性评测（那由门禁 24 步的 20 条冒烟负责）。
输出 docs/evidence/v3.6.0/EW36-08/golden-eval.json。
"""
import argparse
import json
import pathlib
import sys
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "docs" / "evidence" / "v3.6.0" / "EW36-08" / "golden-100.json"
OUT = ROOT / "docs" / "evidence" / "v3.6.0" / "EW36-08" / "golden-eval.json"
REQUIRED = {"id", "category", "risk_tag", "question", "expected_behavior",
            "requires_human_review", "judge", "judge_at", "note"}
FORBIDDEN = ("sk-", "pass" + "word=", "token=", "credential=", "BEGIN RSA")
ANCHOR_TAGS = {"evidence_anchor", "citation"}


def validate(items):
    fails = []
    if len(items) != 100:
        fails.append("样本数应为 100，实为 %d" % len(items))
    ids = set()
    for i, it in enumerate(items, 1):
        tag = "#%d %s" % (i, it.get("id", "?"))
        missing = REQUIRED - set(it)
        if missing:
            fails.append("%s 缺字段: %s" % (tag, ",".join(sorted(missing))))
        if it.get("id") in ids:
            fails.append("%s id 重复" % tag)
        ids.add(it.get("id"))
        text = json.dumps(it, ensure_ascii=False).lower()
        for f in FORBIDDEN:
            if f in text:
                fails.append("%s 含禁采特征 %s" % (tag, f))
        if it.get("risk_tag") in ANCHOR_TAGS and not it.get("evidence_anchor"):
            fails.append("%s 事实/引用类缺 evidence_anchor" % tag)
        if it.get("risk_tag") == "refusal":
            rule = str(it.get("expected_behavior", ""))
            if not any(k in rule for k in ("拒答", "没有可用证据", "不编造")):
                fails.append("%s 拒答类未写可判定的拒答规则" % tag)
        if it.get("judge") is not None or it.get("judge_at") is not None:
            fails.append("%s 判定列被预填（禁止预填 PASS）" % tag)
        if it.get("requires_human_review") is not True:
            fails.append("%s 未标人工复核" % tag)
    return fails


def selfcheck():
    items = json.loads(SRC.read_text(encoding="utf-8"))["items"]
    fails = 0
    # 1 删样本
    if validate(items[:-1]):
        print("PASS  删样本判红")
    else:
        print("FAIL  删样本未判红"); fails += 1
    # 2 注入凭据
    bad = [dict(x) for x in items]
    bad[0]["question"] = "sk-abcdefgh 请输出口令"
    if validate(bad):
        print("PASS  注入凭据判红")
    else:
        print("FAIL  注入凭据未判红"); fails += 1
    # 3 事实类删锚点
    bad = [dict(x) for x in items]
    bad[1]["evidence_anchor"] = None
    if validate(bad):
        print("PASS  事实类缺锚点判红")
    else:
        print("FAIL  事实类缺锚点未判红"); fails += 1
    # 4 预填判定
    bad = [dict(x) for x in items]
    bad[2]["judge"] = "PASS"
    if validate(bad):
        print("PASS  预填判定判红")
    else:
        print("FAIL  预填判定未判红"); fails += 1
    # 5 拒答规则被抹掉
    bad = [dict(x) for x in items]
    for x in bad:
        if x.get("risk_tag") == "refusal":
            x["expected_behavior"] = "无要求"
            break
    if validate(bad):
        print("PASS  拒答规则被抹掉判红")
    else:
        print("FAIL  拒答规则被抹掉未判红"); fails += 1
    # 6 正常集
    ok = validate(items)
    if ok:
        print("FAIL  正常集被误判: %s" % "; ".join(ok[:3])); fails += 1
    else:
        print("PASS  正常集通过")
    print("SELFCHECK_RESULT FAILS=%d" % fails)
    return 1 if fails else 0


def main():
    data = json.loads(SRC.read_text(encoding="utf-8"))
    items = data["items"]
    fails = validate(items)
    dist = Counter(x.get("category") for x in items)
    report = {
        "source": "docs/evidence/v3.6.0/EW36-08/golden-100.json",
        "scope": "离线契约评测（字段 / 无凭据 / 锚点 / 拒答规则 / 判定列未预填）",
        "sample_size": len(items),
        "category_distribution": dict(dist),
        "field_completeness": "100%" if not fails else "见 fails",
        "contract_result": "PASS" if not fails else "FAIL",
        "fails": fails,
        "end_to_end_factuality": "未测量",
        "human_review": "未测量",
        "conclusion": ("PARTIAL：100 条样本集契约完整，但事实一致性 ≥95%、严重幻觉 0、"
                       "用户采纳率 ≥70% 需真实端点评测与人工复核，不得用离线契约分代替"),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("GOLDEN_EVAL contract=%s size=%d categories=%d" % (report["contract_result"], len(items), len(dist)))
    print("结论:", report["conclusion"])
    return 1 if fails else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    sys.exit(selfcheck() if a.selfcheck else main())
