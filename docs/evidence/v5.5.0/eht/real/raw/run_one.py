#!/usr/bin/env python3
"""单条用例真实复跑：验证「同批失败、单跑是否通过」的状态残留假设。

用法: run_one.py <nodeid>
"""
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081'
PROJECT = 95
HOSTS = ['gpu01@10.202.54.101', 'gpu02@10.202.54.102']
RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')


def call(method, path, token=None, body=None, timeout=1800):
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
            return exc.code, {'raw': raw[:400]}


def main():
    node = sys.argv[1]
    tag = sys.argv[2] if len(sys.argv) > 2 else 'ISOLATED'
    status, body = call('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
    token = (body.get('data') or {}).get('token') or (body.get('data') or {}).get('accessToken')
    run_id = 'EHT-REAL-%s-%s' % (tag, time.strftime('%Y%m%d-%H%M%S'))
    status, body = call('POST', '/api/projects/%d/pytest/runs' % PROJECT, token, {
        'runId': run_id, 'mode': 'real', 'confirmReal': True,
        'nodeIds': [node], 'hostKeys': HOSTS, 'timeoutSeconds': 1200,
        'metadata': {'CASE_ID': 'EHT-ISO', 'RUN_ID': run_id, 'QA_PROJECT': 'EHT-PYTEST-LIB',
                     'DUT': 'gpu01,gpu02', 'LINK': 'ens17np0', 'SW_BUILD': 'v5.5.0-eht',
                     'CASE_KIND': 'PYTEST-REAL-ISO'},
    })
    data = body.get('data') or {}
    print('http=%s success=%s counts=%s runDir=%s'
          % (status, data.get('success'), data.get('statusCounts'), data.get('runDir')))
    out = RAW / ('eht-real-isolated-%s.json' % tag.lower())
    out.write_text(json.dumps({'nodeId': node, 'runId': run_id, 'status': status,
                               'response': data}, ensure_ascii=False, indent=2) + '\n',
                   encoding='utf-8')
    print('证据 %s' % out)


if __name__ == '__main__':
    main()
