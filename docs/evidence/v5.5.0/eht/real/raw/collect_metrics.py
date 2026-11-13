#!/usr/bin/env python3
"""汇总本轮真实执行：逐用例结论、耗时分布、失败归因，输出评估用指标 JSON。"""
import json
import pathlib
import statistics
import xml.etree.ElementTree as ET

RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')
RUN_DIRS = sorted(pathlib.Path('/tmp/eht-real-runs').glob('EHT-REAL-*'))

rows = []
for run_dir in RUN_DIRS:
    junit = run_dir / 'junit.xml'
    if not junit.is_file():
        continue
    tree = ET.parse(junit)
    for tc in tree.iter('testcase'):
        classname = tc.get('classname') or ''
        name = tc.get('name') or ''
        node = (classname + '::' + name) if classname else name
        verdict = 'PASS'
        msg = ''
        for child in tc:
            if child.tag in ('failure', 'error', 'skipped'):
                verdict = {'failure': 'FAIL', 'error': 'ERROR', 'skipped': 'SKIP'}[child.tag]
                msg = (child.get('message') or '').strip()[:200]
                break
        rows.append({
            'run': run_dir.name,
            'nodeId': node,
            'script': classname.replace('.', '/') + '.py',
            'verdict': verdict,
            'durationSec': float(tc.get('time') or 0),
            'message': msg,
        })

# 只保留最终一轮（同一用例多次执行时取最新 run）
latest = {}
for row in sorted(rows, key=lambda r: r['run']):
    latest[row['nodeId']] = row
final = list(latest.values())

verdicts = {}
for row in final:
    verdicts[row['verdict']] = verdicts.get(row['verdict'], 0) + 1
durations = sorted(r['durationSec'] for r in final)

summary = {
    'generatedAt': '2026-11-13',
    'totalCases': len(final),
    'verdicts': verdicts,
    'durationSec': {
        'sum': round(sum(durations), 1),
        'median': round(statistics.median(durations), 1) if durations else 0,
        'p90': round(durations[int(len(durations) * 0.9) - 1], 1) if durations else 0,
        'max': round(max(durations), 1) if durations else 0,
        'maxCase': max(final, key=lambda r: r['durationSec'])['nodeId'] if final else None,
    },
    'nonPass': [{'nodeId': r['nodeId'], 'verdict': r['verdict'], 'message': r['message']}
                for r in final if r['verdict'] != 'PASS'],
    'topSlow': [{'nodeId': r['nodeId'], 'durationSec': r['durationSec']}
                for r in sorted(final, key=lambda r: -r['durationSec'])[:8]],
    'cases': sorted(final, key=lambda r: r['nodeId']),
}
out = RAW / 'eht-real-metrics.json'
out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: summary[k] for k in ('totalCases', 'verdicts', 'durationSec', 'topSlow')},
                 ensure_ascii=False, indent=2))
print('non-pass:', json.dumps(summary['nonPass'], ensure_ascii=False, indent=2)[:600])
print('证据', out)
