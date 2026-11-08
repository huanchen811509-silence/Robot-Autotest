#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SCEN-04 / P-4：从 ED202 只读快照逐 sheet 提取「用例编号 / 一级分类 / 二级分类」，
产出「一级分类→场景」对照表与组织 JSON。

纯标准库（zipfile + xml.etree.ElementTree），不依赖 openpyxl。
只读快照 fixtures/ed202/ED202测试用例_V0.4.xlsx 严禁改动；本脚本只读它、只写两份产出：
  docs/plan/ED202一级分类-场景对照表-20261108.md
  docs/evidence/v3.0.1/v22/SCEN-04/ed202-organization.json
所有计数（7 sheet / 730 / 83 / 813）均由 xlsx 真实算出，不硬编码凑数。

用法（仓库根）：python3 tools/ed202_organization.py
"""
import hashlib
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / 'fixtures/ed202/ED202测试用例_V0.4.xlsx'
MD_OUT = ROOT / 'docs/plan/ED202一级分类-场景对照表-20261108.md'
JSON_OUT = ROOT / 'docs/evidence/v3.0.1/v22/SCEN-04/ed202-organization.json'

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
RNS = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'

PROJECT = 'ED202-COMMON'
DASHBOARD_SHEET = '测试用例看板'   # 汇总页，不是明细来源
ADAPTER_SHEET = 'UECGSE适配'       # 适配集
EXPECTED_SHA = '9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39'
CODE = re.compile(r'^[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+$')

CODE_HEADERS = ('用例编号', '测试编号', '测试用例编号')
PRIMARY_HEADERS = ('一级分类', '测试分类')
SECONDARY_HEADERS = ('二级分类',)


def col_idx(ref):
    s = re.match(r'([A-Z]+)', ref).group(1)
    n = 0
    for ch in s:
        n = n * 26 + ord(ch) - 64
    return n - 1


def cell_text(c, shared):
    v = c.find(f'{NS}v')
    if v is None:
        inline = c.find(f'{NS}is')
        return ''.join(t.text or '' for t in inline.iter(f'{NS}t')) if inline is not None else ''
    return shared[int(v.text)] if c.get('t') == 's' else (v.text or '')


def load_sheets():
    with zipfile.ZipFile(SNAPSHOT) as z:
        shared = []
        if 'xl/sharedStrings.xml' in z.namelist():
            for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall(f'{NS}si'):
                shared.append(''.join(t.text or '' for t in si.iter(f'{NS}t')))
        wb = ET.fromstring(z.read('xl/workbook.xml'))
        rels = ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
        targets = {r.get('Id'): r.get('Target') for r in rels}
        out = []
        for sh in wb.find(f'{NS}sheets'):
            name = sh.get('name')
            target = targets[sh.get(f'{RNS}id')]
            path = 'xl/' + target.lstrip('/') if not target.startswith('xl/') else target
            root = ET.fromstring(z.read(path))
            grid = []
            for row in root.findall(f'.//{NS}sheetData/{NS}row'):
                cells = {}
                for c in row.findall(f'{NS}c'):
                    cells[col_idx(c.get('r'))] = cell_text(c, shared).strip()
                grid.append(cells)
            out.append((name, grid))
        return out


def find_header(grid):
    """逐 sheet 找表头行：前 40 行内同时出现编号列与一级分类列的行。"""
    for i, cells in enumerate(grid[:40]):
        width = max(cells, default=-1) + 1
        vals = [cells.get(j, '') for j in range(width)]
        code_col = next((j for j, v in enumerate(vals) if v in CODE_HEADERS), None)
        primary_col = next((j for j, v in enumerate(vals) if v in PRIMARY_HEADERS), None)
        secondary_col = next((j for j, v in enumerate(vals) if v in SECONDARY_HEADERS), None)
        if code_col is not None and primary_col is not None:
            return i, code_col, primary_col, secondary_col
    return None


def parse_sheet(name, grid):
    header = find_header(grid)
    if header is None:
        raise RuntimeError(f'{name}: 找不到含「用例编号 / 一级分类」的表头行')
    hrow, code_col, primary_col, secondary_col = header
    codes = set()
    primary_counts = {}
    secondary = set()
    secondary_by_primary = {}
    data_rows = 0
    for cells in grid[hrow + 1:]:
        code = cells.get(code_col, '') if code_col is not None else ''
        if not CODE.fullmatch(code or ''):
            continue
        codes.add(code)
        data_rows += 1
        primary = (cells.get(primary_col, '') if primary_col is not None else '') or '未分类'
        primary_counts[primary] = primary_counts.get(primary, 0) + 1
        if secondary_col is not None:
            sec = cells.get(secondary_col, '')
            if sec:
                secondary.add(sec)
                secondary_by_primary.setdefault(primary, set()).add(sec)
    return {
        'sheet': name,
        'headerRow': hrow + 1,
        'codeColumn': code_col,
        'primaryColumn': primary_col,
        'secondaryColumn': secondary_col,
        'caseCount': len(codes),
        'codes': codes,
        'dataRows': data_rows,
        'primaryCounts': primary_counts,
        'secondaryCategories': sorted(secondary),
        'primarySecondary': {k: sorted(v) for k, v in secondary_by_primary.items()},
    }


def clean(value):
    """单元格文本：去掉会破坏 Markdown 表格列数的裸竖线与换行。"""
    return str(value).replace('|', '／').replace('\r', ' ').replace('\n', ' ')


def main():
    snapshot_sha = hashlib.sha256(SNAPSHOT.read_bytes()).hexdigest()
    print(f'快照: {SNAPSHOT}')
    print(f'快照 SHA-256: {snapshot_sha}')
    if snapshot_sha != EXPECTED_SHA:
        print('[WARN] 快照 SHA-256 与定案值不符，原表可能已被改动')

    sheets = load_sheets()
    print(f'实测 sheet 数: {len(sheets)}')
    parsed = []
    for name, grid in sheets:
        info = parse_sheet(name, grid)
        parsed.append(info)
        print(f"[sheet] {name}: 表头 r{info['headerRow']} "
              f"编号列={info['codeColumn']} 一级分类列={info['primaryColumn']} "
              f"二级分类列={info['secondaryColumn']} 明细行={info['dataRows']} "
              f"唯一用例编号={info['caseCount']} 一级分类数={len(info['primaryCounts'])} "
              f"二级分类数={len(info['secondaryCategories'])}")

    by_name = {p['sheet']: p for p in parsed}
    detail = [p for p in parsed if p['sheet'] not in (DASHBOARD_SHEET, ADAPTER_SHEET)]
    dashboard_codes = set()
    for p in detail:
        dashboard_codes |= p['codes']
    adapter_codes = set(by_name[ADAPTER_SHEET]['codes'])
    all_codes = set()
    for p in parsed:
        all_codes |= p['codes']

    dashboard = len(dashboard_codes)
    adapter_total = len(adapter_codes)
    unique_total = len(all_codes)
    print(f'[totals] dashboard(主口径=5 流程 sheet 编号并集)={dashboard} '
          f'adapter(适配集)={adapter_total} uniqueTotal(7 sheet 并集)={unique_total}')
    print(f'[totals] 看板实测唯一编号={len(by_name[DASHBOARD_SHEET]["codes"])} '
          f'(汇总页，应等于主口径)')
    print(f'[totals] dashboard + adapter = {dashboard + adapter_total}')

    # 场景：按一级分类聚合 6 个明细 sheet（看板是汇总页，不作为来源），跨 sheet 同名合并
    scenario_sheets = [p for p in parsed if p['sheet'] != DASHBOARD_SHEET]
    scenario_map = {}
    secondary_map = {}
    for p in scenario_sheets:
        for primary, count in p['primaryCounts'].items():
            scenario_map.setdefault(primary, {})[p['sheet']] = count
            secondary_map.setdefault(primary, set()).update(
                p['primarySecondary'].get(primary, []))

    scenarios = []
    for primary in sorted(scenario_map):
        sources = scenario_map[primary]
        case_count = sum(sources.values())
        if len(sources) > 1:
            merged = ('跨 sheet 同名一级分类合并：' + '、'.join(sources.keys())
                      + '；按分类名去重，各明细来源计数分列，场景合计 ' + str(case_count))
        else:
            merged = '单一明细来源 sheet，无需合并'
        scenarios.append({
            'name': primary + '场景',
            'primaryCategory': primary,
            'sources': [{'sheet': s, 'caseCount': c} for s, c in sources.items()],
            'sourceSheets': list(sources.keys()),
            'caseCount': case_count,
            'secondaryCategories': sorted(secondary_map.get(primary, set())),
            'mergedFrom': merged,
        })

    case_sets = []
    for p in parsed:
        case_sets.append({
            'sheet': p['sheet'],
            'caseCount': p['caseCount'],
            'headerRow': p['headerRow'],
            'codeColumn': p['codeColumn'],
            'primaryColumn': p['primaryColumn'],
            'secondaryColumn': p['secondaryColumn'],
            'primaryCategoryCount': len(p['primaryCounts']),
            'secondaryCategoryCount': len(p['secondaryCategories']),
        })

    payload = {
        'project': {
            'name': PROJECT,
            'snapshot': 'fixtures/ed202/ED202测试用例_V0.4.xlsx',
            'snapshotSha256': snapshot_sha,
        },
        'caseSets': case_sets,
        'scenarios': scenarios,
        'totals': {
            'dashboard': dashboard,
            'adapter': adapter_total,
            'uniqueTotal': unique_total,
        },
    }

    # 对照表 Markdown：单一表格块，列数一致，单元格无裸竖线
    lines = [
        '# ED202 一级分类 → 场景对照表（2026-11-08）',
        '',
        '> 来源：`fixtures/ed202/ED202测试用例_V0.4.xlsx`（只读快照，SHA-256 '
        + snapshot_sha + '，严禁改动）。',
        f'> 口径：主口径 {dashboard} / 适配集 {adapter_total} / 合计 {unique_total} '
        '唯一用例编号（定案见 docs/plan/评审结论与拍板定案-v2.2-20261108.md §4 C4、C5）。',
        '> 生成方式：`python3 tools/ed202_organization.py`，逐 sheet 找表头行后提取'
        '「用例编号 / 一级分类 / 二级分类」三列，全部计数由 xlsx 真实算出，未硬编码。',
        f'> 来源 sheet 范围：{len(scenario_sheets)} 个明细 sheet（5 个详细测试流程 + '
        'UECGSE适配）；「测试用例看板」是汇总页不是明细，不作为场景来源，避免与主口径重复计数。',
        '> 跨 sheet 同名一级分类合并为一行，「合并依据」列写明合并的 sheet 与计数；'
        '「该来源用例数」按「来源 sheet」列顺序一一对应。',
        '',
        '| 一级分类 | 场景名 | 来源 sheet | 该来源用例数 | 合并依据 |',
        '|---|---|---|---|---|',
    ]
    for sc in scenarios:
        src = '、'.join(clean(s) for s in sc['sourceSheets'])
        counts = '、'.join(str(x['caseCount']) for x in sc['sources'])
        lines.append('| ' + clean(sc['primaryCategory']) + ' | ' + clean(sc['name'])
                     + ' | ' + src + ' | ' + counts + ' | '
                     + clean(sc['mergedFrom']) + ' |')
    MD_OUT.parent.mkdir(parents=True, exist_ok=True)
    MD_OUT.write_text('\n'.join(lines) + '\n', encoding='utf-8')

    JSON_OUT.parent.mkdir(parents=True, exist_ok=True)
    JSON_OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n',
                        encoding='utf-8')

    print(f'[output] {MD_OUT.relative_to(ROOT)} 数据行={len(scenarios)}')
    print(f'[output] {JSON_OUT.relative_to(ROOT)}')
    print(f'[check] caseSets={len(case_sets)} scenarios={len(scenarios)} '
          f'sum(scenario.caseCount)={sum(s["caseCount"] for s in scenarios)} '
          f'dashboard+adapter={dashboard + adapter_total} uniqueTotal={unique_total}')
    if dashboard + adapter_total != unique_total or \
            sum(s['caseCount'] for s in scenarios) != unique_total:
        print('[WARN] 分账或场景合计与全量唯一编号不一致，README 须标注偏差原因')


if __name__ == '__main__':
    main()
