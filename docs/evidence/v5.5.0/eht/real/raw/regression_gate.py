#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EHT 执行入口回归探针（fail-closed + 重复 runId + fake 兼容）。

用法（WSL 侧，环境变量需先注入）：
    set -a; . ~/.config/robot-autotest/env; set +a
    python3 regression_gate.py

判据（全部 must-pass）：
  1) fake + 新 runId              -> 200，planOnly=true
  2) fake + 同 runId 重放         -> 4xx（CONFLICT，不得 500）
  3) real + 缺 confirmReal        -> 400
  4) real + 未绑定 nodeId         -> 400（fail-closed）
  5) real 请求打到 fake 端点      -> 400（不得静默降级）
"""
import json
import os
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081'
USER = 'qa-e2e-admin'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'eht-real-regression-gate.json')

OK_NODE = 'tests/nic/base/test_im_fun_020.py::test_im_fun_020_pci_device_info'
UNBOUND_NODE = 'tests/nic/new_scripts/test_im_vir_001.py::test_im_vir_001_sriov_basic'


def call(method, path, token=None, body=None):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=300) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8') or '{}')
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode('utf-8', 'replace') or '{}'
        try:
            return exc.code, json.loads(raw)
        except Exception:
            return exc.code, {'message': raw[:300]}


def msg(body):
    if not isinstance(body, dict):
        return ''
    return (body.get('message') or (body.get('error') or {}).get('message') or '')


def main():
    _, body = call('POST', '/api/auth/login',
                   body={'username': USER, 'password': os.environ['QA_E2E_PASSWORD']})
    token = (body.get('data') or {}).get('token') or (body.get('data') or {}).get('accessToken')
    assert token, 'login failed: %s' % body
    print('login ok as %s' % USER)

    stamp = int(time.time())
    results = []

    # 1) fake 新 runId -> 200 / planOnly=true
    rid = 'EHT-REG-FAKE-%d' % stamp
    s, b = call('POST', '/api/projects/95/pytest/runs/fake', token, {
        'runId': rid, 'nodeIds': [OK_NODE],
        'metadata': {'CASE_ID': 'SMOKE', 'RUN_ID': rid},
    })
    d = b.get('data') or {}
    results.append({
        'id': 'fake-new-runId', 'expect': '200 + planOnly=true',
        'http': s, 'planOnly': d.get('planOnly'), 'statusCounts': d.get('statusCounts'),
        'exports': d.get('exportable'), 'notes': d.get('artifactNotes'),
        'pass': s == 200 and d.get('planOnly') is True,
    })
    print('1) fake 新 runId      : http=%s planOnly=%s counts=%s'
          % (s, d.get('planOnly'), d.get('statusCounts')))

    # 2) 同 runId 重放 -> 409/400，不得 500
    s, b = call('POST', '/api/projects/95/pytest/runs/fake', token, {'runId': rid})
    results.append({'id': 'duplicate-runId', 'expect': '4xx（非 500）',
                    'http': s, 'message': msg(b)[:120],
                    'pass': 400 <= s < 500})
    print('2) 重复 runId         : http=%s msg=%s' % (s, msg(b)[:70]))

    rid3 = 'EHT-REG-REAL-%d' % stamp
    # 3) real 缺 confirmReal -> 400
    s, b = call('POST', '/api/projects/95/pytest/runs', token, {
        'runId': rid3, 'mode': 'real', 'nodeIds': [OK_NODE],
    })
    results.append({'id': 'real-without-confirm', 'expect': '400',
                    'http': s, 'message': msg(b)[:120], 'pass': s == 400})
    print('3) real 无 confirmReal: http=%s msg=%s' % (s, msg(b)[:70]))

    # 4) real 打未绑定 nodeId -> fail-closed 400
    s, b = call('POST', '/api/projects/95/pytest/runs', token, {
        'runId': rid3 + 'B', 'mode': 'real', 'confirmReal': True, 'nodeIds': [UNBOUND_NODE],
    })
    m4 = msg(b)
    results.append({'id': 'real-unbound-node', 'expect': '400 fail-closed',
                    'http': s, 'message': m4[:160],
                    'pass': s == 400 and 'fail-closed' in m4})
    print('4) real 未绑定 nodeId : http=%s msg=%s' % (s, m4[:90]))

    # 5) real 请求打到 fake 端点 -> 400，不得静默降级
    s, b = call('POST', '/api/projects/95/pytest/runs/fake', token, {
        'runId': rid3 + 'C', 'mode': 'real', 'nodeIds': [OK_NODE],
    })
    results.append({'id': 'real-on-fake-endpoint', 'expect': '400',
                    'http': s, 'message': msg(b)[:120], 'pass': s == 400})
    print('5) real 打 fake 端点  : http=%s msg=%s' % (s, msg(b)[:70]))

    failed = [r['id'] for r in results if not r['pass']]
    summary = {'generatedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
               'base': BASE, 'total': len(results),
               'passed': len(results) - len(failed), 'failed': failed,
               'checks': results}
    with open(OUT, 'w', encoding='utf-8') as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
    print('\nRESULT %d/%d PASS, failed=%s -> %s'
          % (len(results) - len(failed), len(results), failed or 'none', OUT))
    return 0 if not failed else 1


if __name__ == '__main__':
    raise SystemExit(main())
