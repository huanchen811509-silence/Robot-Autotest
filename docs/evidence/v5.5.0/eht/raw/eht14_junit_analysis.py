#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EHT-14 独立裁判：解析归档 junit，逐条归因非 SKIP 用例。

不采信接口自报的 statusCounts；只认 junit XML 的 <testcase> 子节点。
输出 eht14-junit-analysis.json，供 EHT-15 文档回写引用。
"""
from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

RAW = Path(__file__).resolve().parent
JUNIT = RAW / 'eht14-junit.xml'
OUT = RAW / 'eht14-junit-analysis.json'

RULES = [
    ('真源用例仍写死设备名（gpu04/gpu05/switch）',
     re.compile(r'gpu0[45]@172\.30\.1\.[45]|switch:|资源定位不唯一或不存在|Host 不在本次 run_env 资源池')),
    ('平台资产台账无 GPU 实体（渲染器 gpus 恒空）',
     re.compile(r'资源池中没有可用的 GPU')),
    ('Plan-only 正常收尾', re.compile(r'Plan-only：AW 未真实执行')),
]


def classify(status: str, message: str) -> str:
    if status == 'PASS':
        return '真源用例在 Plan-only 下自身 PASS（不依赖设备，非 SKIP）'
    for label, pattern in RULES:
        if pattern.search(message):
            return label
    return '未归类（需人工复核）'


def main() -> int:
    tree = ET.parse(JUNIT)
    cases = list(tree.getroot().iter('testcase'))
    rows = []
    for case in cases:
        children = [child.tag for child in case]
        if 'skipped' in children:
            status = 'SKIP'
        elif 'failure' in children:
            status = 'FAIL'
        elif 'error' in children:
            status = 'ERROR'
        else:
            status = 'PASS'
        node = case.find('failure') if 'failure' in children else (
            case.find('error') if 'error' in children else None)
        message = (node.get('message') or '') if node is not None else ''
        name = (case.get('classname') or '') + '::' + (case.get('name') or '')
        rows.append({'nodeid': name, 'status': status,
                     'message': message[:400], 'cause': classify(status, message)})
    tally = dict(Counter(r['status'] for r in rows))
    non_skipped = [r for r in rows if r['status'] != 'SKIP']
    causes = dict(Counter(r['cause'] for r in non_skipped))
    report = {
        'source': JUNIT.name,
        'testcases': len(rows),
        'status_tally': tally,
        'non_skipped_count': len(non_skipped),
        'non_skipped_causes': causes,
        'non_skipped_cases': non_skipped,
        'conclusion': ('链路层 junit 完整产出 70 条；用例层未达「全 PLANNED」，'
                       '根因分为真源硬编码设备名、平台资产台账无 GPU 实体、'
                       '真源 Plan-only 下自身 PASS 三类，平台不得改写真源凑全 SKIP。'),
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[OK] %s testcases=%s tally=%s' % (OUT.name, len(rows), tally))
    for row in non_skipped:
        print('  [%s] %s -> %s' % (row['status'], row['nodeid'][:90], row['cause']))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
