#!/usr/bin/env python3
"""通过平台真实执行入口跑指定用例，并归档每次 run 的响应。

用法: run_real.py <L0|L1|ALL> [每批条数] [最多条数]
"""
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081'
PROJECT = 95
HOSTS = ['gpu01@10.202.54.101', 'gpu02@10.202.54.102']
RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')


def call(method, path, token=None, body=None, timeout=3600):
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
            return exc.code, {'raw': raw[:500]}


def login():
    status, body = call('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
    if status != 200:
        raise SystemExit('登录失败 %s' % body)
    data = body.get('data') or {}
    return data.get('token') or data.get('accessToken')


def mysql(sql):
    cfg = {}
    for line in (pathlib.Path.home() / '.config/robot-autotest/env').read_text(
            encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            cfg[k.strip()] = v.strip().strip('"').strip("'")
    r = subprocess.run(['mysql', '-h', cfg['DB_HOST'], '-P', cfg['DB_PORT'],
                        '-u', cfg['DB_USERNAME'], '-p' + cfg['DB_PASSWORD'], '-D', cfg['DB_NAME'],
                        '-N', '-B', '-e', sql], capture_output=True, text=True)
    return [ln.split('\t') for ln in r.stdout.strip().splitlines() if ln.strip()]


def main():
    group = sys.argv[1] if len(sys.argv) > 1 else 'L0'
    batch = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 10 ** 6
    where = '' if group == 'ALL' else " AND c.safety_level='%s'" % group
    rows = mysql("SELECT c.case_code, b.node_id, c.safety_level FROM case_script_binding b "
                 "JOIN test_case c ON c.id=b.case_id WHERE b.project_id=%d%s "
                 "ORDER BY c.case_code" % (PROJECT, where))
    node_ids = [r[1] for r in rows][:limit]
    print('[INFO] 目标 %d 条（分组 %s，每批 %d）' % (len(node_ids), group, batch))

    token = login()
    runs = []
    started = time.time()
    for index in range(0, len(node_ids), batch):
        chunk = node_ids[index:index + batch]
        run_id = 'EHT-REAL-%s-%s-%02d' % (group, time.strftime('%Y%m%d-%H%M%S'),
                                          index // batch + 1)
        stamp = time.strftime('%Y-%m-%dT%H:%M:%S')
        status, body = call('POST', '/api/projects/%d/pytest/runs' % PROJECT, token, {
            'runId': run_id, 'mode': 'real', 'confirmReal': True,
            'nodeIds': chunk, 'hostKeys': HOSTS, 'timeoutSeconds': 3600,
            'metadata': {
                'CASE_ID': 'EHT-REAL-%s' % group, 'RUN_ID': run_id,
                'QA_PROJECT': 'EHT-PYTEST-LIB', 'DUT': 'gpu01,gpu02',
                'LINK': 'ens17np0', 'SW_BUILD': 'v5.5.0-eht',
                'BASELINE': 'eht-real-20261113', 'OPERATOR': 'xuetu',
                'STARTED_AT': stamp, 'CASE_KIND': 'PYTEST-REAL',
            },
        })
        data = body.get('data') or {}
        entry = {'runId': run_id, 'status': status, 'count': len(chunk),
                 'success': data.get('success'), 'reason': data.get('reason'),
                 'exitCode': data.get('exitCode'), 'elapsedMs': data.get('elapsedMs'),
                 'statusCounts': data.get('statusCounts'), 'totalCases': data.get('totalCases'),
                 'passRateDisplay': data.get('passRateDisplay'),
                 'runDir': data.get('runDir'), 'artifactNotes': data.get('artifactNotes'),
                 'nodeIds': chunk}
        runs.append(entry)
        print('[RUN] %s http=%s success=%s total=%s counts=%s 用时=%ss'
              % (run_id, status, data.get('success'), data.get('totalCases'),
                 data.get('statusCounts'), round((data.get('elapsedMs') or 0) / 1000.0, 1)))
        if status != 200:
            print('[FAIL] %s' % json.dumps(body, ensure_ascii=False)[:400])

    report = {'group': group, 'hosts': HOSTS, 'generatedAt': time.strftime('%Y-%m-%d %H:%M:%S%z'),
              'elapsedSeconds': round(time.time() - started, 1), 'runs': runs}
    out = RAW / ('eht-real-runs-%s.json' % group)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[DONE] %s' % out)


if __name__ == '__main__':
    main()
