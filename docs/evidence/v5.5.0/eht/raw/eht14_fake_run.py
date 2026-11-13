#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EHT-14 / TC-v5.5-07：Fake 70 tests 真实链路取证（独立裁判，不复用实现方自证）。

链路：管理员登录 -> 平台 POST /pytest/runs/fake -> 渲染 -> 探契约 -> 构命令 ->
进程执行 -> junit 归一 -> 响应判定；同时比对用例库真源前后快照，验证解耦 E2。

口令只从环境变量 QA_E2E_PASSWORD 读取，绝不写入证据。
用法：
  set -a; . ~/.config/robot-autotest/env; set +a
  python3 docs/evidence/v5.5.0/eht/raw/eht14_fake_run.py
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path('/home/evergreen/robot-autotest')
RAW = ROOT / 'docs/evidence/v5.5.0/eht/raw'
REPO = Path('/mnt/e/workspace/自动化测试/pytest-cases')
BASE = 'http://127.0.0.1:8081'
PROJECT_ID = 95
TZ = timezone(timedelta(hours=8))
SIX_REQUIRED = ['CASE_ID', 'RUN_ID', 'QA_PROJECT', 'DUT', 'LINK', 'SW_BUILD']

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method: str, path: str, token: str | None = None,
         body: dict | None = None, timeout: int = 1200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    headers['X-Project-Id'] = str(PROJECT_ID)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode('utf-8'))
    except urllib.error.HTTPError as err:
        raw = err.read().decode('utf-8', errors='replace')
        try:
            return err.code, json.loads(raw)
        except json.JSONDecodeError:
            return err.code, {'raw': raw[:500]}


def repo_snapshot() -> dict:
    head = subprocess.run(['git', '-C', str(REPO), 'rev-parse', 'HEAD'],
                          capture_output=True, text=True, check=True).stdout.strip()
    diff = subprocess.run(['git', '-C', str(REPO), 'diff', '--ignore-cr-at-eol', '--stat'],
                          capture_output=True, text=True).stdout.strip()
    status = subprocess.run(['git', '-C', str(REPO), 'status', '--short'],
                            capture_output=True, text=True).stdout
    return {
        'head': head,
        'diff_ignore_cr_at_eol': diff,
        'status_lines': len(status.splitlines()),
        'status_sha256': hashlib.sha256(status.encode('utf-8')).hexdigest(),
    }


def ensure_devices(token: str) -> dict:
    """渲染器要求至少一台主机；双端 NIC 用例要求资源池至少两台（实测 error 原文）。

    走平台 API 而不是物理 SQL，设备信息不含任何凭据。已存在则复用，不重复插入。
    """
    status, resp = call('GET', '/api/projects/95/devices?page=1&size=20', token=token, timeout=30)
    items = ((resp.get('data') or {}).get('items') or []) if isinstance(resp, dict) else []
    created = []
    index = len(items) + 1
    while len(items) + len(created) < 2:
        command = {
            'name': 'eht-fake-host-%d' % index,
            'deviceType': 'HOST',
            'model': 'fake-plan-only',
            'vendor': 'EHT',
            'managementIp': '127.0.0.%d' % index,
            'domainTag': 'lab-fake',
            'enabled': True,
        }
        created_status, created_body = call('POST', '/api/projects/95/devices',
                                            token=token, body=command, timeout=30)
        if created_status != 200:
            raise RuntimeError('设备登记失败 status=%s body=%s'
                               % (created_status, str(created_body)[:300]))
        created.append({'device_id': (created_body.get('data') or {}).get('id'),
                        'command': command})
        index += 1
    return {'action': 'created' if created else 'existing', 'status': status,
            'existing_ids': [d.get('id') for d in items], 'created': created}


def ensure_ports(token: str, device_ids: list[int]) -> dict:
    """双端用例的 case_hosts fixture 要求每台 Host 至少有 1 个网口，同机双口用例要 2 个。

    只通过平台 API 补资产台账（渲染器据此派生 inventory.yaml / run_env.yaml），
    不写用例库真源、不碰 SQL。型号取真源 device_specs.yaml 已登记的 connectx-7：
    框架按 product_model 查规格，型号为空会 KeyError（首轮实测 32 条因此 FAIL）。
    这是 plan-only 示例资产，不是现场真实设备，报告里如实标注。
    """
    created = []
    updated = []
    for device_id in device_ids:
        status, resp = call('GET', '/api/projects/95/devices/%s/ports' % device_id,
                            token=token, timeout=30)
        existing = (resp.get('data') or []) if isinstance(resp, dict) else []
        by_name = {(p.get('nicName') or p.get('portName') or ''): p for p in existing}
        for netdev in ('ens17np0', 'ens17np1'):
            command = {
                'portName': netdev, 'portType': 'NIC', 'protocol': 'ethernet',
                'macAddress': '', 'speedGbps': 200, 'mtu': 9000,
                'ipAddress': '', 'portStatus': 'UP', 'nicName': netdev, 'nicIndex': 0,
                'nicVendor': 'NVIDIA', 'nicManufacturer': 'NVIDIA',
                'nicModel': 'connectx-7', 'nicFormFactor': 'PCIe',
                'pciBdf': '0000:01:00.0', 'vendorId': '0x15b3',
                'pciDeviceId': '0x1021', 'driver': 'mlx5_core',
                'firmware': 'fake-plan-only', 'rdmaDevice': 'mlx5_0',
                'nicPort': 1, 'numaNode': 0, 'nicRole': 'fake-plan-only',
                'capabilitiesJson': '[]', 'counterPatternsJson': '{}',
            }
            row = by_name.get(netdev)
            if row is not None:
                if (row.get('nicModel') or '').strip():
                    continue
                put_status, put_body = call(
                    'PUT', '/api/projects/95/ports/%s' % row.get('id'),
                    token=token, body=command, timeout=30)
                if put_status != 200:
                    raise RuntimeError('网口画像补写失败 port=%s status=%s body=%s'
                                       % (row.get('id'), put_status, str(put_body)[:300]))
                updated.append({'port_id': row.get('id'), 'netdev': netdev})
                continue
            created_status, created_body = call(
                'POST', '/api/projects/95/devices/%s/ports' % device_id,
                token=token, body=command, timeout=30)
            if created_status != 200:
                raise RuntimeError('网口登记失败 device=%s netdev=%s status=%s body=%s'
                                   % (device_id, netdev, created_status,
                                      str(created_body)[:300]))
            created.append({'device_id': device_id, 'netdev': netdev})
    verified = []
    for device_id in device_ids:
        status, resp = call('GET', '/api/projects/95/devices/%s/ports' % device_id,
                            token=token, timeout=30)
        rows = (resp.get('data') or []) if isinstance(resp, dict) else []
        for row in rows:
            verified.append({'netdev': row.get('nicName') or row.get('portName'),
                             'nicModel': row.get('nicModel')})
    return {'devices_checked': len(device_ids), 'created': created, 'updated': updated,
            'verified': verified,
            'note': 'plan-only 示例资产；型号 connectx-7 取自用例库 device_specs.yaml 已登记项'}


def main() -> int:
    RAW.mkdir(parents=True, exist_ok=True)
    password = os.environ.get('QA_E2E_PASSWORD')
    if not password:
        print('[FAIL] 缺环境变量 QA_E2E_PASSWORD（先 source env）')
        return 2

    login_status, login = call('POST', '/api/auth/login',
                               body={'username': 'qa-e2e-admin', 'password': password},
                               timeout=30)
    token = (login.get('data') or {}).get('token') if isinstance(login, dict) else None
    if login_status != 200 or not token:
        print('[FAIL] 登录失败：status=%s' % login_status)
        return 2
    print('[OK] 管理员登录 200，令牌已获取（不入库）')

    health_status, health = call('GET', '/api/projects/95/pytest/health?refresh=true',
                                 token=token, timeout=120)
    healthy = ((health.get('data') or {}).get('healthy') is True) if isinstance(health, dict) else False
    print('[OK] health status=%s healthy=%s' % (health_status, healthy))

    device_setup = ensure_devices(token)
    print('[OK] 设备台账前置：%s' % device_setup['action'])
    device_ids = [d for d in (device_setup['existing_ids']
                              + [c['device_id'] for c in device_setup['created']]) if d]
    port_setup = ensure_ports(token, device_ids)
    print('[OK] 网口台账前置：新增 %d 个、补写画像 %d 个、回读 %d 条'
          % (len(port_setup['created']), len(port_setup['updated']),
             len(port_setup['verified'])))

    before = repo_snapshot()
    now = datetime.now(TZ)
    run_id = 'EHT-FAKE-' + now.strftime('%Y%m%d-%H%M%S')
    metadata = {
        'CASE_ID': 'EHT-FAKE-70',
        'RUN_ID': run_id,
        'QA_PROJECT': 'EHT-PYTEST-LIB',
        'DUT': 'NIC-FAKE-01',
        'LINK': 'lab-fake-link',
        'SW_BUILD': '5.0.0-55b16ff',
        'BASELINE': '915c2ca',
        'OPERATOR': 'qa-e2e-admin',
        'STARTED_AT': now.strftime('%Y-%m-%dT%H:%M:%S+08:00'),
        'CASE_KIND': 'pytest-fake',
    }
    request_body = {'runId': run_id, 'mode': 'fake', 'metadata': metadata}
    print('[..] 发起 Fake 全量执行 runId=%s（nodeIds 为空 = 整库收集 70 tests）' % run_id)
    status, response = call('POST', '/api/projects/95/pytest/runs/fake',
                            token=token, body=request_body)
    data = response.get('data') if isinstance(response, dict) else None
    if not isinstance(data, dict):
        print('[FAIL] 响应无 data：status=%s body=%s' % (status, str(response)[:400]))
        return 1
    print('[OK] 执行返回 status=%s success=%s totalCases=%s exitCode=%s'
          % (status, data.get('success'), data.get('totalCases'), data.get('exitCode')))

    after = repo_snapshot()
    # RunRecord 生命周期的独立回读：证明 fake 执行确实在 run 表留下活跃→终态事实，
    # 而不是只在响应里自报成功（也是孤儿清扫器不再误杀的根因）。
    run_check_status, run_check = call(
        'GET', '/api/execution/runs/%s?projectId=95' % run_id, token=token, timeout=30)
    run_row = (run_check.get('data') or {}) if isinstance(run_check, dict) else {}
    print('[OK] RunRecord 回读 status=%s runId=%s totalTests=%s state=%s'
          % (run_check_status, run_row.get('runId'), run_row.get('totalTests'),
             run_row.get('state')))

    run_dir = Path(data.get('runDir', ''))
    archived = []
    if run_dir.is_dir():
        for name in ('junit.xml', 'run.log', 'contract.json', 'run_metadata.json'):
            src = run_dir / name
            if src.is_file():
                dst = RAW / ('eht14-' + name)
                shutil.copy2(src, dst)
                archived.append(dst.name)
    print('[OK] 归档产物：%s' % archived)

    junit_stats = {}
    junit_file = run_dir / 'junit.xml'
    if junit_file.is_file():
        tree = ET.parse(junit_file)
        cases = list(tree.getroot().iter('testcase'))
        tally = {'skipped': 0, 'failure': 0, 'error': 0, 'passed': 0}
        for case in cases:
            kinds = [child.tag for child in case]
            if 'skipped' in kinds:
                tally['skipped'] += 1
            elif 'failure' in kinds:
                tally['failure'] += 1
            elif 'error' in kinds:
                tally['error'] += 1
            else:
                tally['passed'] += 1
        junit_stats = {'testcases': len(cases), 'tally': tally}
        print('[OK] junit 独立解析：%s' % junit_stats)
    else:
        print('[WARN] 未生成 junit.xml，junit 统计为空')

    conditions = data.get('junitConditions') or {}
    status_counts = data.get('statusCounts') or {}
    non_skipped = sum(v for k, v in status_counts.items() if k not in ('SKIP', 'XFAIL'))
    junit_count = junit_stats.get('testcases', 0)
    # 第一层 = 平台链路（TC-v5.5-07 的 L3/L4 通道）。response.success 是 pytest
    # 进程退出码，真源用例层有 ERROR/FAIL 时必为 false；那是用例层结论，
    # 不能拿来否定「平台是否把 70 条跑完并归档」。链路层改用 junit 独立解析
    # + 归一结果 + RunRecord 不被误杀（exitCode != 143）作判据。
    checks = {
        'http_200': status == 200,
        'health_true': healthy,
        'port_profile_written': bool(port_setup.get('verified')) and all(
            v['nicModel'] == 'connectx-7' for v in port_setup['verified']),
        'no_orphan_kill': data.get('exitCode') not in (143, -1),
        'junit_generated': bool(data.get('junitFiles')) and junit_count == 70,
        'junit_testcases_70': junit_count == 70,
        'normalized_total_70': data.get('totalCases') == 70,
        'run_record_persisted': run_check_status == 200
                                and run_row.get('runId') == run_id
                                and run_row.get('totalTests') == 70,
        'junit_three_conditions': len(conditions) == 3 and all(conditions.values()),
        'command_has_fake_mode': '--mode fake' in str(data.get('command', '')),
        'pass_rate_not_applicable': data.get('passRateApplicable') is False
                                    and data.get('passRateDisplay') == '不适用',
        'metadata_six_present': all(k in (data.get('metadata') or {}) for k in SIX_REQUIRED),
        'repo_head_unchanged': before['head'] == after['head'],
        'repo_diff_clean': not before['diff_ignore_cr_at_eol'] and not after['diff_ignore_cr_at_eol'],
        'repo_status_unchanged': before['status_sha256'] == after['status_sha256'],
    }
    # 第二层 = 真源用例层。FR-v5.5-08 原判据要求 verdict 全 PLANNED（全 SKIP）。
    # 实测真源 Plan-only 仍会因资产池缺项／用例写死设备名产生 ERROR/FAIL；
    # 这一层如实记 FAIL，由 EHT-15 回写为「降级达成」，不靠改判据抹平。
    case_layer = {
        'all_cases_skipped': non_skipped == 0,
        'non_skipped_cases': non_skipped,
        'reported_non_skipped': data.get('nonSkippedCases'),
        'status_counts': status_counts,
        'junit_tally': junit_stats.get('tally', {}),
        'pytest_exit_code': data.get('exitCode'),
        'pytest_success': data.get('success'),
        'verdict': 'PASS' if non_skipped == 0 else 'FAIL',
        'note': '用例层结论取决于 pytest-cases 真源与资产台账完整度；平台不得改写真源凑全 SKIP',
    }

    failed = [k for k, v in checks.items() if not v]
    report = {
        'captured_at': now.strftime('%Y-%m-%d %H:%M:%S'),
        'timezone': 'GMT+8',
        'purpose': 'EHT-14 / TC-v5.5-07 Fake 70 tests 真实链路验收',
        'project_id': PROJECT_ID,
        'endpoint': 'POST /api/projects/95/pytest/runs/fake',
        'request': {k: v for k, v in request_body.items() if k != 'metadata'},
        'metadata': metadata,
        'health': {'status': health_status, 'healthy': healthy},
        'device_setup': device_setup,
        'port_setup': port_setup,
        'repo_before': before,
        'repo_after': after,
        'response_status': status,
        'response_data': data,
        'run_record': {'status': run_check_status, 'row': run_row},
        'run_dir_files': sorted(p.name for p in run_dir.iterdir()) if run_dir.is_dir() else [],
        'archived_files': archived,
        'junit_independent_parse': junit_stats,
        'checks': checks,
        'check_scope': 'platform_chain',
        'case_layer': case_layer,
        'failed_checks': failed,
        'verdict': 'PASS' if not failed else 'FAIL',
        'case_layer_verdict': case_layer['verdict'],
        'degraded': case_layer['verdict'] == 'FAIL',
        'notes': [
            '口令只从环境变量读取，证据里不含凭据',
            '用例库 artifacts/ 在 .gitignore 内，框架运行产物不改变 git 追踪内容',
            'junit 由独立 XML 解析统计，不采信接口自报的 totalCases',
            'verdict 只判平台链路；case_layer_verdict 单独判真源用例层，两层不得混算',
            'response.success 是 pytest 退出码，非平台链路成功标志；原值保留在 response_data',
        ],
    }
    out = RAW / 'eht14-fake-run-report.json'
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[OK] 报告落盘 %s' % out)
    print('[RESULT] chain=%s case_layer=%s degraded=%s failed=%s'
          % (report['verdict'], report['case_layer_verdict'], report['degraded'], failed))
    print('[DIAG] reason=%s runDir=%s exists=%s'
          % (data.get('reason'), data.get('runDir'), run_dir.is_dir()))
    return 0 if report['verdict'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
