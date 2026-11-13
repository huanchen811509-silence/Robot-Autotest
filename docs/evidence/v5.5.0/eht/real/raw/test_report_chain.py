#!/usr/bin/env python3
"""报告链路实测：parse → detail → tree → summary → export(html/pdf) → 导出历史。

只读 + 生成报告；导出产物落证据目录，不写用例库真源。
"""
import json
import os
import pathlib
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081'
PROJECT = 95
RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')

RUNS = ['EHT-REAL-L0-20261113-220048-01', 'EHT-REAL-L1-20261113-220422-01']


def call(method, path, token=None, body=None, timeout=600):
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
    status, body = call('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
    token = (body.get('data') or {}).get('token') or (body.get('data') or {}).get('accessToken')
    evidence = {'generatedAt': time.strftime('%Y-%m-%d %H:%M:%S%z'), 'runs': []}

    for run_id in RUNS:
        item = {'runId': run_id}
        s, b = call('POST', '/api/reports/%s/parse?projectId=%d' % (run_id, PROJECT), token)
        item['parse'] = {'status': s, 'data': b.get('data') if s == 200 else b}
        print('[PARSE] %s http=%s' % (run_id, s))

        s, b = call('GET', '/api/reports/%s?projectId=%d' % (run_id, PROJECT), token)
        detail = b.get('data') or {}
        item['detail'] = {'status': s, 'data': detail}
        print('[DETAIL] http=%s exportable=%s missing=%s metaKeys=%s'
              % (s, detail.get('exportable'), detail.get('metadataMissing'),
                 sorted((detail.get('metadata') or {}).keys())))

        s, b = call('GET', '/api/reports/%s/tree?projectId=%d' % (run_id, PROJECT), token)
        item['tree'] = {'status': s, 'data': b.get('data')}
        cases = []
        for suite in ((b.get('data') or {}).get('suites') or []):
            for case in (suite.get('cases') or []):
                cases.append({'code': case.get('caseKey'), 'verdict': case.get('verdict'),
                              'durationMs': case.get('durationMs')})
        item['cases'] = cases
        print('[TREE] http=%s cases=%d' % (s, len(cases)))

        s, b = call('GET', '/api/reports/%s/summary?projectId=%d' % (run_id, PROJECT), token)
        item['summary'] = {'status': s, 'data': b.get('data')}
        print('[SUMMARY] %s' % json.dumps(b.get('data'), ensure_ascii=False)[:300])

        for fmt in ('html', 'pdf'):
            s, b = call('POST', '/api/reports/%s/export?projectId=%d&mode=external&format=%s'
                        % (run_id, PROJECT, fmt), token)
            item.setdefault('export', {})[fmt] = {'status': s,
                                                  'data': b.get('data') if s == 200 else b}
            print('[EXPORT-%s] http=%s %s' % (fmt.upper(), s,
                                              json.dumps(b.get('data'), ensure_ascii=False)[:220]))

        s, b = call('GET', '/api/reports/%s/exports?projectId=%d' % (run_id, PROJECT), token)
        item['exportHistory'] = {'status': s, 'data': b.get('data')}
        print('[HISTORY] http=%s count=%s' % (s, len(b.get('data') or [])))
        evidence['runs'].append(item)

    out = RAW / 'eht-real-report-chain.json'
    out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[DONE] %s' % out)


if __name__ == '__main__':
    main()
