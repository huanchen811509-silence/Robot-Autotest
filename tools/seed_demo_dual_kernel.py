#!/usr/bin/env python3
"""v2.7 双内核演示项目种子脚本（幂等）。

用法：
  python3 tools/seed_demo_dual_kernel.py           # 建/补齐全部内容
  python3 tools/seed_demo_dual_kernel.py --verify  # 只校验一致性，不做写入

内容：
  1. 项目 1（demo）改名「演示项目-RobotFramework」；
  2. 新建/复用项目「演示项目-Pytest」（code=demo-pytest，testKernel=pytest）；
  3. 两项目各建 12 条同编号同标题用例（DEMO-001..012，P2）；
  4. 两项目各上传等价脚本：RF 单套件 / pytest.ini + tests/test_demo.py；
  5. 两项目登记同名同值环境资产（device / env 快照 / credential）。

判据（PRD FR-v2.7-05）：12 条编号/标题逐条一致、环境资产一致、脚本语义等价。
"""
import json
import os
import sys
import urllib.request

BASE = os.environ.get('SEED_BASE', 'http://127.0.0.1:8001').rstrip('/')

RF_PROJECT_ID = 1
PYTEST_PROJECT_ID = None  # 运行期解析


class HttpError(Exception):
    def __init__(self, code, body):
        super().__init__(f'HTTP {code}: {body[:300]}')
        self.code = code
        self.body = body


def env_value(key):
    value = os.environ.get(key)
    if not value:
        print(f'FATAL: 缺少环境变量 {key}', file=sys.stderr)
        sys.exit(2)
    return value


def http(method, path, token=None, body=None, pid=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if pid:
        headers['X-Project-Id'] = str(pid)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read().decode()) from e


# ---------------------------------------------------------------- 用例定义
# (编号, 标题, 类别)；步骤在下面按内核分别生成
CASES = [
    ('DEMO-001', '算术冒烟', '演示'),
    ('DEMO-002', '字符串大小写转换', '演示'),
    ('DEMO-003', '列表排序', '演示'),
    ('DEMO-004', '字典读写', '演示'),
    ('DEMO-005', '集合交集', '演示'),
    ('DEMO-006', '参数化三例', '演示'),
    ('DEMO-007', '条件跳过', '演示'),
    ('DEMO-008', '故意失败', '演示'),
    ('DEMO-009', '预期失败且失败发生', '演示'),
    ('DEMO-010', '预期失败但未发生', '演示'),
    ('DEMO-011', 'JSON 解析', '演示'),
    ('DEMO-012', '临时文件读写', '演示'),
]

# 预期结果口径（T4 比对的期望列）：
#   共同 10 条两内核逐条一致；DEMO-009/010 按映射口径一致（XFAIL≡PASS-via-expect-error 非失败、
#   XPASS≡FAIL 计失败）。ERROR 仅 pytest 侧可产生，不进演示套件（单测覆盖映射）。
EXPECTED = {
    'DEMO-001': 'PASS', 'DEMO-002': 'PASS', 'DEMO-003': 'PASS',
    'DEMO-004': 'PASS', 'DEMO-005': 'PASS',
    'DEMO-006': 'PASS x3', 'DEMO-007': 'SKIP', 'DEMO-008': 'FAIL',
    'DEMO-009': 'RF=PASS / pytest=XFAIL（映射 SKIP，均非失败）',
    'DEMO-010': 'RF=FAIL / pytest=XPASS（映射 FAIL，均计失败）',
    'DEMO-011': 'PASS', 'DEMO-012': 'PASS',
}

STEPS = {
    'DEMO-001': [('计算 1+1 并与 2 比对', '结果为 PASS')],
    'DEMO-002': [('将 demo 转大写并与 DEMO 比对', '结果为 PASS')],
    'DEMO-003': [('对 [3,1,2] 排序并与 [1,2,3] 比对', '结果为 PASS')],
    'DEMO-004': [('写入 k=v 后读回并与 v 比对', '结果为 PASS')],
    'DEMO-005': [('求 {1,2} 与 {2,3} 交集并与 {2} 比对', '结果为 PASS')],
    'DEMO-006': [('对 (1,2) (2,4) (3,6) 三组数据分别校验倍数关系', '三例均 PASS')],
    'DEMO-007': [('读环境变量 DEMO_SKIP 确定跳过分支', '结果为 SKIP（带原因）')],
    'DEMO-008': [('断言 1==2（故意失败，演示失败链路）', '结果为 FAIL')],
    'DEMO-009': [('执行预期抛错的操作并确认异常发生', '结果为非失败（预期失败达成）')],
    'DEMO-010': [('执行预期抛错的操作但操作成功', '结果为失败（预期失败未达成）')],
    'DEMO-011': [('解析 {"k":42} 并断言 k==42', '结果为 PASS')],
    'DEMO-012': [('写临时文件再读回比对内容', '结果为 PASS')],
}


def case_body(code, title):
    return {
        'caseCode': code, 'title': title, 'category': '演示', 'priority': 'P2',
        'precondition': '平台执行链就绪（无外部设备依赖）',
        'expected': EXPECTED[code], 'domainTag': '演示', 'capability': '',
        'description': 'v2.7 双内核等价演示用例',
        'steps': [{'stepNo': i + 1, 'action': a, 'expectation': e}
                  for i, (a, e) in enumerate(STEPS[code])],
        'safetyLevel': 'LX',
    }


# ---------------------------------------------------------------- RF 套件
# 套件原文在 rf_suite_content()；DEMO-007 两侧恒 SKIP（口径一致），DEMO-009/010 预期失败语义见 EXPECTED。

PYTEST_INI = """[pytest]
# v2.7 双内核演示：静态 metadata 由 conftest.py 注入（pytest-metadata 3.1.1
# 不再支持 ini/--metadata 进 json-report，实测见 PYTEST_CONFTEST 注释）。
"""

PYTEST_CONFTEST = '''"""v2.7 双内核演示：静态 metadata 注入。

pytest-metadata 3.1.1 不再把 ini/--metadata 写进 json-report（v2.7 实测），
conftest 在 pytest_configure 直写 ``config._metadata``——json-report 1.5.0
把它落在报告的 ``environment`` 键，后端从该键收十项门禁键（RUN_ID 由命令行透传）。
"""


def pytest_configure(config):
    md = getattr(config, "_metadata", None)
    if md is None:
        md = {}
        config._metadata = md
    md.update({
        "CASE_ID": "DEMO-COMMON",
        "QA_PROJECT": "演示项目-Pytest",
        "DUT": "workstation-local",
        "LINK": "local-loopback",
        "SW_BUILD": "v2.7",
        "BASELINE": "main",
        "OPERATOR": "qa-e2e-admin",
        "STARTED_AT": "seed-time",
        "CASE_KIND": "demo",
    })
'''

PYTEST_TEST = '''"""v2.7 双内核演示套件（与 demo RF 等价）。"""
import json
import os
import tempfile

import pytest


def test_DEMO_001_算术冒烟():
    assert 1 + 1 == 2


def test_DEMO_002_字符串大小写转换():
    assert "demo".upper() == "DEMO"


def test_DEMO_003_列表排序():
    assert sorted([3, 1, 2]) == [1, 2, 3]


def test_DEMO_004_字典读写():
    d = {}
    d["k"] = "v"
    assert d["k"] == "v"


def test_DEMO_005_集合交集():
    assert {1, 2} & {2, 3} == {2}


@pytest.mark.parametrize("base,value", [(1, 2), (2, 4), (3, 6)])
def test_DEMO_006_参数化三例(base, value):
    assert value == base * 2


def test_DEMO_007_条件跳过():
    pytest.skip("演示条件跳过（DEMO-007 恒 SKIP，与 RF Skip If 口径对齐）")


def test_DEMO_008_故意失败():
    assert 1 == 2


@pytest.mark.xfail(reason="演示预期失败且失败发生")
def test_DEMO_009_预期失败且失败发生():
    raise RuntimeError("boom")


@pytest.mark.xfail(reason="演示预期失败但未发生")
def test_DEMO_010_预期失败但未发生():
    assert 1 + 1 == 2  # 不抛错 -> XPASS


def test_DEMO_011_JSON解析():
    assert json.loads('{"k": 42}')["k"] == 42


def test_DEMO_012_临时文件读写():
    fd, path = tempfile.mkstemp()
    with os.fdopen(fd, "w") as fh:
        fh.write("demo")
    with open(path) as fh:
        assert fh.read() == "demo"
    os.unlink(path)
'''


def rf_suite_content():
    return """*** Settings ***
Documentation           v2.7 双内核演示套件（与 demo-pytest 等价）。
Metadata                CASE_ID        DEMO-COMMON
Metadata                QA_PROJECT     演示项目-RobotFramework
Metadata                DUT            workstation-local
Metadata                LINK           local-loopback
Metadata                SW_BUILD       v2.7
Metadata                BASELINE       main
Metadata                OPERATOR       qa-e2e-admin
Metadata                STARTED_AT     seed-time
Metadata                CASE_KIND      demo

*** Test Cases ***
DEMO-001 算术冒烟
    [Tags]              case:DEMO-001
    ${sum}=             Evaluate    1 + 1
    Should Be Equal As Integers    ${sum}    2

DEMO-002 字符串大小写转换
    [Tags]              case:DEMO-002
    ${upper}=           Evaluate    'demo'.upper()
    Should Be Equal     ${upper}    DEMO

DEMO-003 列表排序
    [Tags]              case:DEMO-003
    ${sorted}=          Evaluate    sorted([3, 1, 2])
    Should Be Equal     ${sorted}    ${{[1, 2, 3]}}

DEMO-004 字典读写
    [Tags]              case:DEMO-004
    ${value}=           Evaluate    dict(k='v')['k']
    Should Be Equal     ${value}    v

DEMO-005 集合交集
    [Tags]              case:DEMO-005
    ${inter}=           Evaluate    sorted(set([1, 2]) & set([2, 3]))
    Should Be Equal     ${inter}    ${{[2]}}

DEMO-006 参数化三例
    [Tags]              case:DEMO-006
    [Template]          倍数校验
    1    2
    2    4
    3    6

DEMO-007 条件跳过
    [Tags]              case:DEMO-007
    Skip    演示条件跳过（DEMO-007 恒 SKIP，与 pytest 侧口径对齐）

DEMO-008 故意失败
    [Tags]              case:DEMO-008
    Should Be Equal As Integers    1    2

DEMO-009 预期失败且失败发生
    [Tags]              case:DEMO-009
    Run Keyword And Expect Error    *boom*    Fail    boom

DEMO-010 预期失败但未发生
    [Tags]              case:DEMO-010
    Run Keyword And Expect Error    *boom*    Evaluate    1 + 1

DEMO-011 JSON 解析
    [Tags]              case:DEMO-011
    ${k}=               Evaluate    __import__('json').loads('{"k": 42}')['k']
    Should Be Equal As Integers    ${k}    42

DEMO-012 临时文件读写
    [Tags]              case:DEMO-012
    ${tmp}=             Evaluate    __import__('tempfile').mkstemp()[1]
    Evaluate            open(r'''${tmp}''', 'w').write('demo')
    ${content}=         Evaluate    open(r'''${tmp}''').read()
    Should Be Equal     ${content}    demo

*** Keywords ***
倍数校验
    [Arguments]         ${base}    ${value}
    ${expected}=        Evaluate    int(${base}) * 2
    Should Be Equal As Integers    ${value}    ${expected}
"""


# ---------------------------------------------------------------- 主流程
def login():
    return http('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': env_value('QA_E2E_PASSWORD'),
    })['data']['token']


def find_project(token, code):
    data = http('GET', '/api/projects', token).get('data') or []
    for p in data:
        if p.get('code') == code:
            return p
    return None


def ensure_projects(token):
    global PYTEST_PROJECT_ID
    http('PUT', f'/api/projects/{RF_PROJECT_ID}', token,
         body={'name': '演示项目-RobotFramework', 'description': 'v2.7 双内核演示（RF 侧）',
               'enabled': True, 'visibility': 'public', 'testKernel': 'robot'})
    proj = find_project(token, 'demo-pytest')
    if proj is None:
        created = http('POST', '/api/projects', token, body={
            'code': 'demo-pytest', 'name': '演示项目-Pytest',
            'description': 'v2.7 双内核演示（pytest 侧）', 'visibility': 'public',
            'testKernel': 'pytest'})
        proj = created.get('data') or created
        print(f"  建项目 demo-pytest id={proj['id']}")
    else:
        http('PUT', f"/api/projects/{proj['id']}", token, body={
            'name': '演示项目-Pytest', 'description': 'v2.7 双内核演示（pytest 侧）',
            'enabled': True, 'visibility': 'public', 'testKernel': 'pytest'})
    PYTEST_PROJECT_ID = proj['id']


def ensure_case(token, pid, code, title):
    page = http('GET', f'/api/projects/{pid}/cases?page=1&size=200', token, pid=pid)
    data = page.get('data')
    items = (data.get('items') or data.get('list') or []) if isinstance(data, dict) else (data or [])
    for item in items:
        if item.get('caseCode') == code:
            return
    try:
        http('POST', f'/api/projects/{pid}/cases', token, pid=pid,
             body=case_body(code, title))
    except HttpError as e:
        if e.code == 409:  # 已存在（列表分页/状态过滤下查不到也算幂等命中）
            return
        raise


def ensure_file(token, pid, path, content):
    http('PUT', f'/api/projects/{pid}/files', token, pid=pid,
         body={'path': path, 'content': content, 'comment': 'v2.7 双内核演示种子'})


def ensure_device(token, pid):
    existing = http('GET', f'/api/projects/{pid}/devices?page=1&size=50', token, pid=pid)
    for item in (existing.get('data') or {}).get('list', []):
        if item.get('name') == 'demo-dut-local':
            return
    try:
        http('POST', f'/api/projects/{pid}/devices', token, pid=pid, body={
            'name': 'demo-dut-local', 'deviceType': 'workstation', 'model': 'local',
            'vendor': 'n/a', 'serialNo': 'DEMO-LOCAL-001', 'managementIp': '127.0.0.1',
            'location': 'local', 'rackNo': '', 'domainTag': '演示', 'enabled': True,
            'ownerId': None})
    except HttpError as e:
        if e.code != 409:
            raise


def ensure_env_snapshot(token, pid):
    existing = http('GET', f'/api/projects/{pid}/snapshots?name=demo-env&page=1&size=20',
                    token, pid=pid)
    data = existing.get('data')
    items = (data.get('items') or data.get('list') or []) if isinstance(data, dict) else (data or [])
    for item in items:
        if item.get('name') == 'demo-env':
            return
    try:
        http('POST', f'/api/projects/{pid}/snapshots', token, pid=pid, body={
            'name': 'demo-env', 'profileType': 'demo',
            'description': 'v2.7 双内核演示环境（两项目同值）',
            'snapshotJson': json.dumps({
                'DEMO_URL': 'http://127.0.0.1:8001',
                'DEMO_USER': 'qa-e2e-admin',
                'DEMO_SKIP': '1'}, ensure_ascii=False)})
    except HttpError as e:
        if e.code != 409:
            raise


def seed_project_content(token):
    pid_rf = RF_PROJECT_ID
    pid_py = PYTEST_PROJECT_ID
    for pid in (pid_rf, pid_py):
        for code, title, _cat in CASES:
            ensure_case(token, pid, code, title)
        ensure_device(token, pid)
        ensure_env_snapshot(token, pid)
    ensure_file(token, pid_rf, 'demo/dual_kernel_demo.robot', rf_suite_content())
    ensure_file(token, pid_py, 'pytest.ini', PYTEST_INI)
    ensure_file(token, pid_py, 'tests/test_demo.py', PYTEST_TEST)
    ensure_file(token, pid_py, 'conftest.py', PYTEST_CONFTEST)


def verify(token):
    problems = []
    proj = find_project(token, 'demo-pytest')
    if proj is None:
        print('VERIFY FAIL: demo-pytest 不存在')
        return 1
    rf_cases = {}
    py_cases = {}
    for pid, sink in ((RF_PROJECT_ID, rf_cases), (proj['id'], py_cases)):
        page = http('GET', f'/api/projects/{pid}/cases?page=1&size=200', token, pid=pid)
        data = page.get('data')
        items = (data.get('items') or data.get('list') or []) if isinstance(data, dict) else (data or [])
        for item in items:
            sink[item['caseCode']] = item['title']
    for code, title, _cat in CASES:
        if rf_cases.get(code) != title or py_cases.get(code) != title:
            problems.append(f'{code}: RF={rf_cases.get(code)!r} pytest={py_cases.get(code)!r}')
    for pid in (RF_PROJECT_ID, proj['id']):
        files = http('GET', f'/api/projects/{pid}/files', token, pid=pid)
        names = [n.get('path') for n in (files.get('data') or [])]
        if pid == proj['id'] and 'tests/test_demo.py' not in names:
            problems.append(f'pytest 项目缺 tests/test_demo.py: {names}')
        if pid == RF_PROJECT_ID and 'demo/dual_kernel_demo.robot' not in names:
            problems.append(f'RF 项目缺 demo/dual_kernel_demo.robot: {names}')
    if problems:
        print('VERIFY FAIL:')
        for line in problems:
            print('  -', line)
        return 1
    print(f"VERIFY PASS: 两项目各 {len(CASES)} 条用例编号/标题一致，脚本与环境资产齐备")
    return 0


def main():
    verify_only = '--verify' in sys.argv
    token = login()
    if verify_only:
        sys.exit(verify(token))
    print('1. 确保两个演示项目')
    ensure_projects(token)
    print(f'2. 两项目各建 {len(CASES)} 条等价用例')
    seed_project_content(token)
    print('3. 校验一致性')
    sys.exit(verify(token))


if __name__ == '__main__':
    main()
