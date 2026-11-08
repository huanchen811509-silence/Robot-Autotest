#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SCEN-04（v2.2 V2/V5）：把 ED202 公共用例库真正落成平台数据。

做四件事，全部走真实 HTTP 链路（urllib，无第三方依赖，不连库、不 INSERT 业务表）：
  1. 建项目 ED202-COMMON（POST /api/projects，已存在则复用）；
  2. 按 sheet 分 7 个用例集（POST /api/projects/{pid}/case-sets），条目数对齐 sheet 明细行；
  3. 用例经两步式导入入库（POST .../cases/import/preview → /commit），适配集 83 条不得丢；
  4. 39 个一级分类场景入库（POST /api/scenarios），场景的 caseSetIds 与对照表来源 sheet 一致。

幂等：项目 / 用例集 / 场景按 code 或 name 复用，用例按 caseCode 判重，第二次执行不翻倍。

为什么按 sheet 拆成独立 xlsx 再导入：只读快照严禁改动，且「测试用例看板」是汇总页
（730 = 5 个流程 sheet 并集），整本导入会被文件内编号去重吞掉明细归属。生成件只写到
临时目录，快照字节不动（脚本开头校验 SHA-256）。

用法（仓库根）：
  python3 tools/seed_ed202_organization.py                 # 全流程
  python3 tools/seed_ed202_organization.py --dry-run       # 只做 preview，不 commit
  python3 tools/seed_ed202_organization.py --skip-execute  # 跳过场景执行（复跑用）
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / 'fixtures/ed202/ED202测试用例_V0.4.xlsx'
ORG_JSON = ROOT / 'docs/evidence/v3.0.1/v22/SCEN-04/ed202-organization.json'
EXPECTED_SHA = '9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39'

BASE = os.environ.get('SEED_BASE', 'http://127.0.0.1:8001').rstrip('/')
USER = os.environ.get('SEED_USER', 'qa-e2e-admin')
PROJECT_CODE = 'ED202-COMMON'
PROJECT_NAME = 'ED202 公共用例库'

DASHBOARD_SHEET = '测试用例看板'
ADAPTER_SHEET = 'UECGSE适配'
# 导入顺序即用例集顺序；看板是汇总页，不导入，只建引用同一批 730 条用例的用例集
IMPORT_SHEETS = [
    'Import详细测试流程',
    'XCCL详细测试流程',
    'AI参数网_详细测试流程',
    '存储_详细测试流程',
    'HCI详细测试流程',
    ADAPTER_SHEET,
]
SET_CODES = {
    '测试用例看板': 'ED202-CS-DASHBOARD',
    'Import详细测试流程': 'ED202-CS-IMPORT',
    'XCCL详细测试流程': 'ED202-CS-XCCL',
    'AI参数网_详细测试流程': 'ED202-CS-AIPARAM',
    '存储_详细测试流程': 'ED202-CS-STORAGE',
    'HCI详细测试流程': 'ED202-CS-HCI',
    'UECGSE适配': 'ED202-CS-UECGSE',
}

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
RNS = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'
CODE = re.compile(r'^[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+$')
NUMBERED_LINE = re.compile(r'^(\d+)\s*[.、)．]\s*(.*)$')
CODE_HEADERS = ('用例编号', '测试编号', '测试用例编号')
PRIMARY_HEADERS = ('一级分类', '测试分类')
TITLE_HEADERS = ('用例名称', '标题', '测试名称', '测试用例名称', '测试用例', '名称')
PRIORITY_HEADERS = ('优先级', 'priority')
STEPS_HEADERS = ('测试步骤',)
PRECONDITION_HEADERS = ('预置条件', '前置条件', '前置')
EXPECTED_HEADERS = ('预期结果', '预期')

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class HttpFailure(RuntimeError):
    def __init__(self, code: int, body: str, method: str, path: str):
        super().__init__(f'HTTP {code} {method} {path}: {body[:400]}')
        self.code = code
        self.body = body


# --------------------------------------------------------------------- HTTP

def http(method: str, path: str, token: str | None = None, body=None,
         pid: int | None = None, timeout: int = 180) -> dict:
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if pid:
        headers['X-Project-Id'] = str(pid)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read() or b'{}')
    except urllib.error.HTTPError as exc:
        raise HttpFailure(exc.code, exc.read().decode('utf-8', 'replace'),
                          method, path) from exc


def post_multipart(path: str, filename: str, payload: bytes, token: str, pid: int) -> dict:
    boundary = '----ed202seed' + uuid.uuid4().hex
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
            f'filename="{filename}"\r\n'
            'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
            '\r\n\r\n').encode('utf-8')
    body = head + payload + f'\r\n--{boundary}--\r\n'.encode('utf-8')
    req = urllib.request.Request(BASE + path, data=body, method='POST', headers={
        'Authorization': 'Bearer ' + token,
        'X-Project-Id': str(pid),
        'Accept': 'application/json',
        'Content-Type': f'multipart/form-data; boundary={boundary}',
    })
    try:
        with _OPENER.open(req, timeout=180) as resp:
            return json.loads(resp.read() or b'{}')
    except urllib.error.HTTPError as exc:
        raise HttpFailure(exc.code, exc.read().decode('utf-8', 'replace'),
                          'POST', path) from exc


def env_value(key: str) -> str | None:
    if os.environ.get(key):
        return os.environ[key]
    path = Path.home() / '.config' / 'robot-autotest' / 'env'
    try:
        for line in path.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            name, value = line.split('=', 1)
            if name.strip() == key:
                return value.strip()
    except OSError:
        return None
    return None


def login() -> str:
    password = env_value('QA_E2E_PASSWORD')
    if not password:
        raise SystemExit('缺少 QA_E2E_PASSWORD（~/.config/robot-autotest/env）')
    payload = http('POST', '/api/auth/login',
                   body={'username': USER, 'password': password})
    token = ((payload or {}).get('data') or {}).get('token')
    if not token:
        raise SystemExit('登录失败：' + json.dumps(payload, ensure_ascii=False)[:300])
    return token


# ------------------------------------------------------------- xlsx 读取

def col_index(ref: str) -> int:
    letters = re.match(r'([A-Z]+)', ref).group(1)
    n = 0
    for ch in letters:
        n = n * 26 + ord(ch) - 64
    return n - 1


def cell_text(cell, shared) -> str:
    value = cell.find(f'{NS}v')
    if value is None:
        inline = cell.find(f'{NS}is')
        return ''.join(t.text or '' for t in inline.iter(f'{NS}t')) if inline is not None else ''
    return shared[int(value.text)] if cell.get('t') == 's' else (value.text or '')


def load_sheets() -> list[tuple[str, list[dict[int, str]]]]:
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
            path = target if target.startswith('xl/') else 'xl/' + target.lstrip('/')
            root = ET.fromstring(z.read(path))
            grid = []
            for row in root.findall(f'.//{NS}sheetData/{NS}row'):
                cells = {}
                for c in row.findall(f'{NS}c'):
                    cells[col_index(c.get('r'))] = cell_text(c, shared).strip()
                grid.append(cells)
            out.append((name, grid))
        return out


def find_header(grid):
    """与 tools/ed202_organization.py 同口径：前 40 行内同时出现编号列与一级分类列。"""
    for i, cells in enumerate(grid[:40]):
        width = max(cells, default=-1) + 1
        vals = [cells.get(j, '') for j in range(width)]
        code_col = next((j for j, v in enumerate(vals) if v in CODE_HEADERS), None)
        primary_col = next((j for j, v in enumerate(vals) if v in PRIMARY_HEADERS), None)
        if code_col is not None and primary_col is not None:
            return i, code_col, primary_col
    return None


def first_col(vals, names):
    for j, v in enumerate(vals):
        if v in names:
            return j
    return None


def normalize_priority(raw: str) -> str:
    """EDIM-02 同一规则：T0/T1/T2/T3 → P0/P1/P2/P3；空值留空由服务端落 P2。

    注意：表头写「优先级」但取值是 功能/性能/稳定性 的列（HCI 页第 5 列）不是优先级，
    调用方改用真实优先级列，避免把测试类型当优先级灌进去。
    """
    value = (raw or '').strip().upper()
    if not value:
        return ''
    if value in ('T0', 'T1', 'T2', 'T3'):
        return 'P' + value[1]
    return value


def split_steps(raw: str) -> list[str]:
    """与 CaseImportService.splitSteps 同口径：按编号行切分，无编号续行并入上一步。"""
    if not raw or not raw.strip():
        return []
    steps: list[str] = []
    current: list[str] | None = None
    for line in re.split(r'\r\n|\r|\n', raw):
        text = line.strip()
        if not text:
            continue
        m = NUMBERED_LINE.match(text)
        if m:
            if current is not None:
                steps.append('\n'.join(current))
            current = [m.group(2).strip()]
        elif current is not None:
            current.append(text)
        else:
            current = [text]
    if current is not None:
        steps.append('\n'.join(current))
    return steps


def parse_sheet(name, grid) -> dict:
    header = find_header(grid)
    if header is None:
        raise RuntimeError(f'{name}: 找不到含「用例编号 / 一级分类」的表头行')
    hrow, code_col, primary_col = header
    width = max(grid[hrow], default=-1) + 1
    vals = [grid[hrow].get(j, '') for j in range(width)]
    title_col = first_col(vals, TITLE_HEADERS)
    prio_col = first_col(vals, PRIORITY_HEADERS)
    steps_col = first_col(vals, STEPS_HEADERS)
    pre_col = first_col(vals, PRECONDITION_HEADERS)
    exp_col = first_col(vals, EXPECTED_HEADERS)

    # HCI 页表头错位：第 5 列写「优先级」但值是 功能/性能/稳定性，第 6 列写「测试类型」
    # 但值是 P0..P3。按取值判定真实优先级列，并把错位列标为测试类型（不参与导入）。
    prio_note = ''
    if prio_col is not None:
        sample = [grid[r].get(prio_col, '') for r in range(hrow + 1, len(grid))][:200]
        non_empty = [v for v in sample if v]
        if non_empty and not any(normalize_priority(v) in ('P0', 'P1', 'P2', 'P3')
                                 for v in non_empty):
            alt = next((j for j in range(width)
                        if j != prio_col and vals[j] in ('测试类型', '测试分类')
                        and any(normalize_priority(grid[r].get(j, '')) in
                                ('P0', 'P1', 'P2', 'P3')
                                for r in range(hrow + 1, len(grid)))), None)
            if alt is not None:
                prio_note = (f'表头错位：第 {prio_col + 1} 列为「{vals[prio_col]}」但取值是'
                             f'测试类型（功能/性能/稳定性），真实优先级在「{vals[alt]}」列，'
                             f'按第 {alt + 1} 列取值')
                prio_col = alt

    rows = []
    for cells in grid[hrow + 1:]:
        code = cells.get(code_col, '')
        if not CODE.fullmatch(code or ''):
            continue
        rows.append({
            'code': code,
            'title': cells.get(title_col, '') if title_col is not None else '',
            'category': cells.get(primary_col, '') or '未分类',
            'priority': normalize_priority(cells.get(prio_col, '')) if prio_col is not None else '',
            'precondition': cells.get(pre_col, '') if pre_col is not None else '',
            'expected': cells.get(exp_col, '') if exp_col is not None else '',
            'steps': split_steps(cells.get(steps_col, '')) if steps_col is not None else [],
        })
    return {
        'sheet': name,
        'headerRow': hrow + 1,
        'priorityNote': prio_note,
        'rows': rows,
        'codes': [r['code'] for r in rows],
    }


# --------------------------------------------------------- 生成单 sheet xlsx

def _esc(text: str) -> str:
    return (text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;'))


def _col_letter(index: int) -> str:
    letters = ''
    index += 1
    while index:
        index, rem = divmod(index - 1, 26)
        letters = chr(65 + rem) + letters
    return letters


def write_sheet_xlsx(path: Path, sheet_name: str, header: list[str],
                     rows: list[list[str]]) -> int:
    """写一个单 sheet 的最小 xlsx（inlineStr，无样式）。只用于投喂导入接口。"""
    xml_rows = []
    for r, cells in enumerate([header] + rows, start=1):
        parts = []
        for c, text in enumerate(cells):
            if not text:
                continue
            parts.append(f'<c r="{_col_letter(c)}{r}" t="inlineStr"><is>'
                         f'<t xml:space="preserve">{_esc(text)}</t></is></c>')
        if parts:
            xml_rows.append(f'<row r="{r}">' + ''.join(parts) + '</row>')
    sheet = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             '<sheetData>' + ''.join(xml_rows) + '</sheetData></worksheet>')
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
        '</Types>')
    root_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '</Relationships>')
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f'<sheets><sheet name="{_esc(sheet_name)}" sheetId="1" r:id="rId1"/></sheets>'
        '</workbook>')
    workbook_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
        '</Relationships>')
    styles = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
        '<fills count="2"><fill><patternFill patternType="none"/></fill>'
        '<fill><patternFill patternType="gray125"/></fill></fills>'
        '<borders count="1"><border/></borders>'
        '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        '<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>'
        '</styleSheet>')
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', content_types)
        z.writestr('_rels/.rels', root_rels)
        z.writestr('xl/workbook.xml', workbook)
        z.writestr('xl/_rels/workbook.xml.rels', workbook_rels)
        z.writestr('xl/styles.xml', styles)
        z.writestr('xl/worksheets/sheet1.xml', sheet)
    return len(rows)


# --------------------------------------------------------------- 业务步骤

def ensure_project(token: str) -> tuple[int, str]:
    projects = (http('GET', '/api/projects', token).get('data') or [])
    for p in projects:
        if p.get('code') == PROJECT_CODE:
            print(f'[project] 复用已存在项目 id={p["id"]} code={p["code"]} name={p.get("name")}')
            return int(p['id']), 'reused'
    created = http('POST', '/api/projects', token, body={
        'code': PROJECT_CODE,
        'name': PROJECT_NAME,
        'description': 'ED202 公共用例库（v2.2 SCEN-04 由 tools/seed_ed202_organization.py 经 HTTP 落库）',
        'visibility': 'private',
    }).get('data') or {}
    print(f'[project] 新建项目 id={created.get("id")} code={created.get("code")} '
          f'name={created.get("name")}')
    return int(created['id']), 'created'


def list_cases(token: str, pid: int) -> dict[str, int]:
    by_code: dict[str, int] = {}
    page = 1
    while True:
        data = http('GET', f'/api/projects/{pid}/cases?page={page}&size=200',
                    token, pid=pid).get('data') or {}
        items = data.get('items') or []
        for it in items:
            by_code[it['caseCode']] = int(it['id'])
        total = int(data.get('total') or 0)
        if not items or len(by_code) >= total:
            return by_code
        page += 1


def import_sheet(token: str, pid: int, sheet: dict, tmpdir: Path,
                 dry_run: bool) -> dict:
    name = sheet['sheet']
    header = ['用例编号', '标题', '一级分类', '优先级', '预置条件', '预期结果']
    rows = [[r['code'], r['title'], r['category'], r['priority'],
             r['precondition'], r['expected']] for r in sheet['rows']]
    path = tmpdir / (SET_CODES[name] + '.xlsx')
    write_sheet_xlsx(path, name, header, rows)
    payload = path.read_bytes()
    preview = post_multipart(f'/api/projects/{pid}/cases/import/preview',
                             path.name, payload, token, pid).get('data') or {}
    total_rows = int(preview.get('totalRows') or 0)
    valid_rows = int(preview.get('validRows') or 0)
    errors = preview.get('errors') or []
    print(f'[import:{name}] 生成件 {len(rows)} 行 / {len(payload)} 字节；'
          f'preview totalRows={total_rows} validRows={valid_rows} errors={len(errors)}')
    if errors:
        for err in errors[:5]:
            print(f'    preview error r{err.get("rowNum")} {err.get("field")}: {err.get("message")}')
    if dry_run:
        return {'sheet': name, 'totalRows': total_rows, 'validRows': valid_rows,
                'imported': 0, 'skipped': 0, 'errors': len(errors), 'dryRun': True}
    if not preview.get('rows'):
        print(f'[import:{name}] 无可导入行（全部已存在或全部校验失败），跳过 commit')
        return {'sheet': name, 'totalRows': total_rows, 'validRows': valid_rows,
                'imported': 0, 'skipped': valid_rows, 'errors': len(errors)}
    result = http('POST', f'/api/projects/{pid}/cases/import/commit', token,
                  body={'rows': preview['rows']}, pid=pid).get('data') or {}
    imported = int(result.get('imported') or 0)
    skipped = int(result.get('skipped') or 0)
    print(f'[import:{name}] commit imported={imported} skipped={skipped}')
    return {'sheet': name, 'totalRows': total_rows, 'validRows': valid_rows,
            'imported': imported, 'skipped': skipped, 'errors': len(errors)}


def ensure_case_set(token: str, pid: int, sheet_name: str) -> tuple[int, int, str]:
    set_code = SET_CODES[sheet_name]
    data = http('GET', f'/api/projects/{pid}/case-sets?keyword={set_code}&page=1&size=200',
                token, pid=pid).get('data') or {}
    for row in data.get('items') or []:
        if row.get('setCode') == set_code:
            return int(row['id']), int(row.get('itemCount') or 0), 'reused'
    created = http('POST', f'/api/projects/{pid}/case-sets', token, body={
        'setCode': set_code,
        'name': sheet_name,
        'description': f'ED202「{sheet_name}」sheet 明细用例集',
    }, pid=pid).get('data') or {}
    return int(created['id']), 0, 'created'


def fill_set_items(token: str, pid: int, set_id: int, case_ids: list[int]) -> int:
    existing = http('GET', f'/api/projects/{pid}/case-sets/{set_id}/items',
                    token, pid=pid).get('data') or []
    have = {int(it['id']) for it in existing}
    missing = [cid for cid in case_ids if cid not in have]
    for i, cid in enumerate(missing, start=1):
        http('POST', f'/api/projects/{pid}/case-sets/{set_id}/items', token,
             body={'caseId': cid}, pid=pid)
        if i % 200 == 0:
            print(f'    [set {set_id}] 已补 {i}/{len(missing)} 条')
    return len(missing)


def fill_steps(token: str, pid: int, case_ids: dict[str, int],
               steps_by_code: dict[str, list[str]]) -> dict:
    filled = skipped = empty = 0
    done = 0
    for code, steps in steps_by_code.items():
        cid = case_ids.get(code)
        if cid is None or not steps:
            empty += 1
            continue
        detail = http('GET', f'/api/projects/{pid}/cases/{cid}', token, pid=pid).get('data') or {}
        current = detail.get('steps') or []
        if len(current) >= len(steps):
            skipped += 1
            continue
        tc = detail.get('testCase') or {}
        http('PUT', f'/api/projects/{pid}/cases/{cid}', token, pid=pid, body={
            'caseCode': tc.get('caseCode') or code,
            'title': tc.get('title') or '',
            'category': tc.get('category'),
            'priority': tc.get('priority'),
            'precondition': tc.get('precondition'),
            'expected': tc.get('expected'),
            'domainTag': tc.get('domainTag'),
            'capability': tc.get('capability'),
            'description': tc.get('description'),
            'safetyLevel': tc.get('safetyLevel'),
            'steps': [{'stepNo': n, 'action': text, 'expectation': ''}
                      for n, text in enumerate(steps, start=1)],
        })
        filled += 1
        done += 1
        if done % 100 == 0:
            print(f'    [steps] 已补 {done} 条（跳过 {skipped}）')
    return {'filled': filled, 'skipped': skipped, 'noSteps': empty}


def build_scenarios(parsed: list[dict]) -> list[dict]:
    """与 tools/ed202_organization.py 同口径：按一级分类聚合 6 个明细来源 sheet。"""
    scenario_map: dict[str, dict[str, int]] = {}
    for sheet in parsed:
        if sheet['sheet'] == DASHBOARD_SHEET:
            continue
        for row in sheet['rows']:
            bucket = scenario_map.setdefault(row['category'], {})
            bucket[sheet['sheet']] = bucket.get(sheet['sheet'], 0) + 1
    out = []
    for primary in sorted(scenario_map):
        sources = scenario_map[primary]
        out.append({
            'name': primary + '场景',
            'primaryCategory': primary,
            'sourceSheets': list(sources.keys()),
            'caseCount': sum(sources.values()),
        })
    return out


def ensure_scenarios(token: str, pid: int, scenarios: list[dict],
                     set_ids: dict[str, int]) -> dict:
    data = http('GET', f'/api/scenarios?projectId={pid}', token, pid=pid).get('data') or {}
    existing = {row['name']: row for row in (data.get('items') or [])}
    created = reused = 0
    mapping = {}
    for sc in scenarios:
        case_sets = [set_ids[s] for s in sc['sourceSheets'] if s in set_ids]
        if len(case_sets) != len(sc['sourceSheets']):
            raise RuntimeError(f'场景 {sc["name"]} 有来源 sheet 未建用例集：{sc["sourceSheets"]}')
        row = existing.get(sc['name'])
        if row:
            sid = int(row['id'])
            reused += 1
            same = [int(x) for x in str(row.get('case_set_ids') or '').split(',') if x]
            if same != case_sets:
                raise RuntimeError(f'场景 {sc["name"]} 已存在但用例集绑定不一致：'
                                   f'库内={same} 期望={case_sets}')
        else:
            sid = int(http('POST', '/api/scenarios', token, body={
                'projectId': pid,
                'name': sc['name'],
                'caseSetIds': case_sets,
            }).get('data'))
            created += 1
        mapping[sc['name']] = {'scenarioId': sid, 'caseSetIds': case_sets,
                               'caseCount': sc['caseCount']}
    print(f'[scenarios] 目标 {len(scenarios)} 个：新建 {created}、复用 {reused}')
    return mapping


def read_scenarios(token: str, pid: int) -> list[dict]:
    data = http('GET', f'/api/scenarios?projectId={pid}', token, pid=pid).get('data') or {}
    return data.get('items') or []


def execute_twice(token: str, pid: int, scenario: dict, enabled: bool) -> dict:
    result = {'attempted': enabled, 'runIds': [], 'errors': []}
    if not enabled:
        result['skipped'] = True
        print('[execute] 已按 --skip-execute 跳过场景执行')
        return result
    for attempt in (1, 2):
        try:
            payload = http('POST', f'/api/scenarios/{scenario["scenarioId"]}/execute?projectId={pid}',
                           token, pid=pid).get('data') or {}
            run_id = payload.get('runId')
            result['runIds'].append(run_id)
            print(f'[execute] 第 {attempt} 次 scenario={scenario["scenarioId"]} '
                  f'runId={run_id} state={payload.get("state")}')
        except HttpFailure as exc:
            result['errors'].append(f'HTTP {exc.code}: {exc.body[:200]}')
            print(f'[execute] 第 {attempt} 次失败 HTTP {exc.code}：{exc.body[:200]}')
    return result


def patch_org_json(pid: int, code: str, case_sets: list[dict],
                   scenarios: dict) -> None:
    payload = json.loads(ORG_JSON.read_text(encoding='utf-8'))
    totals_before = dict(payload.get('totals') or {})
    payload['project'] = dict(payload.get('project') or {},
                              id=pid, code=code, db='robot_dev.test_project')
    for row in payload.get('caseSets') or []:
        info = next((c for c in case_sets if c['sheet'] == row['sheet']), None)
        if info:
            row['setId'] = info['id']
            row['setCode'] = SET_CODES[info['sheet']]
            row['dbItemCount'] = info['items']
    for row in payload.get('scenarios') or []:
        info = scenarios.get(row['name'])
        if info:
            row['scenarioId'] = info['scenarioId']
            row['caseSetIds'] = info['caseSetIds']
    payload['seed'] = {
        'tool': 'tools/seed_ed202_organization.py',
        'endpoints': ['POST /api/projects', 'POST /api/projects/{pid}/cases/import/preview',
                      'POST /api/projects/{pid}/cases/import/commit',
                      'POST /api/projects/{pid}/case-sets', 'POST /api/scenarios',
                      'POST /api/scenarios/{id}/execute'],
        'projectId': pid,
        'projectCode': code,
    }
    if payload.get('totals') != totals_before:
        raise RuntimeError('totals 被改动，拒绝写入')
    ORG_JSON.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n',
                        encoding='utf-8')
    print(f'[json] 已回填 {ORG_JSON.relative_to(ROOT)}：projectId={pid}、'
          f'7 个用例集 setId、39 个 scenarioId；totals 保持不变 {totals_before}')


# ------------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description='ED202 公共用例库落库（纯 HTTP）')
    parser.add_argument('--dry-run', action='store_true', help='只 preview，不 commit')
    parser.add_argument('--skip-execute', action='store_true', help='跳过场景执行（复跑用）')
    parser.add_argument('--skip-mapping', action='store_true', help='不回填 ed202-organization.json')
    args = parser.parse_args()

    started = time.strftime('%Y-%m-%d %H:%M:%S')
    print(f'== seed_ed202_organization == base={BASE} user={USER} 开始 {started}')

    sha = hashlib.sha256(SNAPSHOT.read_bytes()).hexdigest()
    print(f'[snapshot] {SNAPSHOT.relative_to(ROOT)}')
    print(f'[snapshot] SHA-256={sha}')
    if sha != EXPECTED_SHA:
        print('[snapshot][FATAL] SHA-256 与定案值不符，拒绝继续（快照被改动）')
        return 2

    token = login()
    print(f'[auth] 登录成功 user={USER}')

    pid, project_action = ensure_project(token)

    sheets = load_sheets()
    parsed = [parse_sheet(name, grid) for name, grid in sheets]
    by_name = {p['sheet']: p for p in parsed}
    for p in parsed:
        print(f'[sheet] {p["sheet"]}: 表头 r{p["headerRow"]} 明细行={len(p["rows"])} '
              f'唯一编号={len(set(p["codes"]))}')
        if p['priorityNote']:
            print(f'[sheet] {p["sheet"]}: {p["priorityNote"]}')

    dashboard = set(by_name[DASHBOARD_SHEET]['codes'])
    detail_union: set[str] = set()
    for name in IMPORT_SHEETS[:5]:
        detail_union |= set(by_name[name]['codes'])
    adapter = set(by_name[ADAPTER_SHEET]['codes'])
    unique_total = len(dashboard | detail_union | adapter)
    print(f'[totals] dashboard={len(dashboard)} adapter={len(adapter)} '
          f'uniqueTotal={unique_total} dashboard==detailUnion={dashboard == detail_union}')

    tmpdir = Path(tempfile.mkdtemp(prefix='ed202-seed-'))
    imports = []
    for name in IMPORT_SHEETS:
        imports.append(import_sheet(token, pid, by_name[name], tmpdir, args.dry_run))
    total_imported = sum(i['imported'] for i in imports)
    print(f'[import] 合计 imported={total_imported} '
          f'preview validRows={sum(i["validRows"] for i in imports)}')
    if args.dry_run:
        print('[dry-run] 只做 preview，不写库；退出')
        return 0

    case_ids = list_cases(token, pid)
    print(f'[cases] 项目内用例总数={len(case_ids)}（期望 813）')
    missing = sorted((dashboard | adapter) - set(case_ids))
    if missing:
        print(f'[cases][FATAL] 缺 {len(missing)} 条：{missing[:10]}')
        return 3

    case_sets = []
    set_ids: dict[str, int] = {}
    for name in [DASHBOARD_SHEET] + IMPORT_SHEETS:
        set_id, before, action = ensure_case_set(token, pid, name)
        targets = sorted({case_ids[c] for c in by_name[name]['codes'] if c in case_ids})
        added = fill_set_items(token, pid, set_id, targets)
        items = len(http('GET', f'/api/projects/{pid}/case-sets/{set_id}/items',
                         token, pid=pid).get('data') or [])
        expected = len(by_name[name]['codes'])
        case_sets.append({'sheet': name, 'id': set_id, 'setCode': SET_CODES[name],
                          'items': items, 'expected': expected, 'added': added,
                          'action': action})
        set_ids[name] = set_id
        flag = 'PASS' if items == expected else 'FAIL'
        print(f'[case-set] {SET_CODES[name]} id={set_id} name={name} '
              f'{action} 条目 {before}→{items}（期望 {expected}，本次补 {added}）{flag}')

    steps_by_code = {}
    for name in IMPORT_SHEETS:
        for row in by_name[name]['rows']:
            if row['steps']:
                steps_by_code[row['code']] = row['steps']
    steps_stat = fill_steps(token, pid, case_ids, steps_by_code)
    print(f'[steps] 目标 {len(steps_by_code)} 条用例：本次补写 {steps_stat["filled"]}、'
          f'已有跳过 {steps_stat["skipped"]}、无步骤文本 {steps_stat["noSteps"]}')

    scenarios = build_scenarios(parsed)
    print(f'[scenarios] 由 xlsx 口径算出 {len(scenarios)} 个场景')
    scenario_map = ensure_scenarios(token, pid, scenarios, set_ids)

    stored = read_scenarios(token, pid)
    order_zero = sum(1 for row in stored if int(row.get('step_order') or 0) == 0)
    print(f'[scenarios] 库内 test_scenario={len(stored)} 行；'
          f'step_order 为 0 的 {order_zero} 行'
          f'（POST /api/scenarios 无 stepOrder 入参，顺序锚点由服务端默认值 0 落库）')

    dry = next((s for s in scenarios if s['name'] == 'CCL通信测试场景'), scenarios[0])
    exec_target = scenario_map[dry['name']]
    print(f'[execute] 目标场景 {dry["name"]} id={exec_target["scenarioId"]} '
          f'caseSetIds={exec_target["caseSetIds"]}')
    exec_result = execute_twice(token, pid, exec_target, not args.skip_execute)
    if exec_result['runIds']:
        ids = "','".join(exec_result['runIds'])
        print('[execute] 清理语句（本次为自造 run 的清理，需人工执行）：')
        print(f"    delete from test_result where run_id in (select id from run where run_id in ('{ids}'));")
        print(f"    delete from run where run_id in ('{ids}');")

    if not args.dry_run and not args.skip_mapping:
        patch_org_json(pid, PROJECT_CODE, case_sets, scenario_map)

    print('== 汇总 ==')
    print(f'  project id={pid} code={PROJECT_CODE} action={project_action}')
    for row in imports:
        print(f'  import {row["sheet"]}: preview totalRows={row["totalRows"]} '
              f'validRows={row["validRows"]} imported={row["imported"]} '
              f'skipped={row["skipped"]} errors={row["errors"]}')
    for row in case_sets:
        print(f'  case-set {row["setCode"]} id={row["id"]} items={row["items"]}/{row["expected"]}')
    print(f'  test_case(项目内)={len(case_ids)} test_scenario={len(stored)} '
          f'steps filled={steps_stat["filled"]}')
    print(f'  runIds={exec_result["runIds"]} executeErrors={exec_result["errors"]}')
    ok = (len(case_ids) == unique_total
          and all(r['items'] == r['expected'] for r in case_sets)
          and len(stored) == len(scenarios))
    print(f'SEED_RESULT {"PASS" if ok else "FAIL"} '
          f'(cases={len(case_ids)}/{unique_total}, '
          f'sets={sum(1 for r in case_sets if r["items"] == r["expected"])}/{len(case_sets)}, '
          f'scenarios={len(stored)}/{len(scenarios)})')
    return 0 if ok else 4


if __name__ == '__main__':
    sys.exit(main())
