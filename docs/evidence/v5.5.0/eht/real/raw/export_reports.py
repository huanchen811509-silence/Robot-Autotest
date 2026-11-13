#!/usr/bin/env python3
"""导出三格式并下载产物到证据目录，供质量评估。"""
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
            return exc.code, {'raw': raw[:300]}


def download(path, token, target):
    req = urllib.request.Request(BASE + path)
    req.add_header('Authorization', 'Bearer ' + token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=600) as resp:
        target.write_bytes(resp.read())
    return target.stat().st_size


def main():
    _, body = call('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
    token = (body.get('data') or {}).get('token') or (body.get('data') or {}).get('accessToken')
    evidence = []
    for run_id in RUNS:
        for fmt in ('pdf', 'docx', 'xlsx'):
            s, b = call('POST', '/api/reports/%s/export?projectId=%d&mode=external&format=%s'
                        % (run_id, PROJECT, fmt), token)
            data = b.get('data') or {}
            item = {'runId': run_id, 'format': fmt, 'status': s,
                    'bytes': data.get('bytes'), 'exportId': data.get('exportId')}
            if s == 200 and data.get('downloadUrl'):
                target = RAW / ('report-%s-%s.%s' % (run_id, fmt, fmt))
                item['saved'] = str(target)
                item['savedBytes'] = download(data['downloadUrl'], token, target)
            evidence.append(item)
            print('[EXPORT] %s %s http=%s bytes=%s' % (run_id, fmt, s, item.get('bytes')))
    (RAW / 'eht-real-report-exports.json').write_text(
        json.dumps({'generatedAt': time.strftime('%Y-%m-%d %H:%M:%S%z'), 'items': evidence},
                   ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[DONE]')


if __name__ == '__main__':
    main()
