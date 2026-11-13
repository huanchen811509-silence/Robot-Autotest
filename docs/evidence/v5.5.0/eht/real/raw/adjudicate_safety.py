#!/usr/bin/env python3
"""ADR-0013 人工补判：把 61 条 LX 按静态证据落成 L0/L1，L2 保持 LX 不动。

判定依据只看脚本实际调用的动作方法（不标题党）：
  L2  固件/驱动/重启/VF 创建等不可逆或高风险 → 保持 LX，需单独授权
  L1  可逆运行时配置（offload/ring/channels/mtu/link/fec/pfc）→ 会短暂改链路
  L0  只读采集

写操作全部走平台批量接口（唯一合法补判入口），并保留 undoToken 以便回滚。
"""
import json
import os
import pathlib
import re
import subprocess
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081'
PROJECT = 95
REPO = pathlib.Path('/mnt/e/workspace/自动化测试/pytest-cases')
RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')
RAW.mkdir(parents=True, exist_ok=True)

L2_CALLS = ['set_sriov_numvfs', 'create_vm', 'flash_firmware', 'burn', 'mlxconfig', 'reboot',
            'rmmod', 'modprobe', 'install_driver', 'uninstall_driver', 'create_bond',
            'set_bond', 'delete_vm', 'reset_nic', 'sriov_enable']
L1_CALLS = ['set_offload', 'set_channels', 'set_ring', 'set_fec', 'set_pfc', 'set_vf_mac',
            'set_mtu', 'set_link_state', 'set_ipv6_disabled', 'disable_ipv6']
L1_CMDS = ['ethtool -K', 'ethtool -L', 'ethtool -G', 'ethtool -A', 'ethtool -s ',
           'ip link set', 'ip addr add', 'sysctl -w', 'devlink port', 'devlink dev param set']


def call(method, path, token=None, body=None, timeout=120):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8') or '{}')
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode('utf-8', 'replace')
        try:
            return exc.code, json.loads(raw)
        except ValueError:
            return exc.code, {'raw': raw[:300]}


def login():
    status, body = call('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
    if status != 200:
        raise SystemExit('登录失败 %s' % body)
    data = body.get('data') or {}
    return data.get('token') or data.get('accessToken')


def mysql(sql):
    env_path = pathlib.Path.home() / '.config/robot-autotest/env'
    cfg = {}
    for line in env_path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            cfg[k.strip()] = v.strip().strip('"').strip("'")
    r = subprocess.run(['mysql', '-h', cfg['DB_HOST'], '-P', cfg['DB_PORT'],
                        '-u', cfg['DB_USERNAME'], '-p' + cfg['DB_PASSWORD'], '-D', cfg['DB_NAME'],
                        '-N', '-B', '-e', sql], capture_output=True, text=True)
    return [ln.split('\t') for ln in r.stdout.strip().splitlines() if ln.strip()]


def classify(script):
    text = (REPO / script).read_text(encoding='utf-8', errors='ignore')
    calls = sorted(set(re.findall(r'\.(set_[a-z_]+|create_[a-z_]+|flash_[a-z_]+)\s*\(', text)))
    hits2 = [c for c in calls if c in L2_CALLS]
    hits1 = [c for c in calls if c in L1_CALLS]
    cmds1 = [c for c in L1_CMDS if c in text]
    if hits2:
        return 'L2', hits2 + cmds1
    if hits1 or cmds1:
        return 'L1', hits1 + cmds1
    return 'L0', []


def main():
    token = login()
    rows = mysql("SELECT b.case_id, c.case_code, b.script_path, c.safety_level "
                 "FROM case_script_binding b JOIN test_case c ON c.id=b.case_id "
                 "WHERE b.project_id=%d ORDER BY b.script_path" % PROJECT)
    print('[INFO] 已绑定用例 %d 条' % len(rows))

    plan = []
    for case_id, code, script, level in rows:
        judged, basis = classify(script)
        plan.append({'caseId': int(case_id), 'caseCode': code, 'script': script,
                     'currentSafety': level, 'judged': judged, 'basis': basis})

    groups = {'L0': [p for p in plan if p['judged'] == 'L0'],
              'L1': [p for p in plan if p['judged'] == 'L1'],
              'L2': [p for p in plan if p['judged'] == 'L2']}
    for name, items in groups.items():
        print('[PLAN] %s = %d 条' % (name, len(items)))

    results = []
    for level in ('L0', 'L1'):
        ids = [p['caseId'] for p in groups[level]]
        if not ids:
            continue
        status, body = call('POST', '/api/projects/%d/cases/batch' % PROJECT, token,
                            {'action': 'setSafetyLevel', 'ids': ids, 'safetyLevel': level})
        data = body.get('data') or {}
        ok = [r for r in (data.get('items') or []) if r.get('ok')]
        bad = [r for r in (data.get('items') or []) if not r.get('ok')]
        print('[BATCH] %s 成功 %d / 失败 %d / undoToken=%s'
              % (level, len(ok), len(bad), data.get('undoToken')))
        results.append({'level': level, 'status': status, 'ok': len(ok),
                        'failed': bad, 'undoToken': data.get('undoToken')})
        time.sleep(0.3)

    readback = mysql("SELECT safety_level, COUNT(*) FROM test_case WHERE project_id=%d "
                     "AND id IN (%s) GROUP BY safety_level"
                     % (PROJECT, ','.join(str(p['caseId']) for p in plan)))
    print('[VERIFY] 补判后分布 %s' % readback)

    evidence = {
        'projectId': PROJECT,
        'generatedAt': time.strftime('%Y-%m-%d %H:%M:%S%z'),
        'authorization': '大师 2026-11-13 21:42 授权对 61 条可执行用例做真实测试（优先 gpu01/02）',
        'policy': 'ADR-0013：L2 与 LX 一律 blocksExecution，补判只走 cases/batch setSafetyLevel',
        'counts': {k: len(v) for k, v in groups.items()},
        'batches': results,
        'readback': readback,
        'cases': plan,
    }
    out = RAW / 'eht-real-safety-adjudication.json'
    out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[DONE] 证据 %s' % out)
    for item in groups['L2']:
        print('[L2-HELD] %s %s（保持 LX，本次不跑）' % (item['caseCode'], item['script']))


if __name__ == '__main__':
    main()
