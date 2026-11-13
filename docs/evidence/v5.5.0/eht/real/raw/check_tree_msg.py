#!/usr/bin/env python3
"""检查报告树里失败用例的 failureMsg 是否落库（区分后端入库问题与导出渲染问题）。"""
import json
import os
import urllib.request

BASE = 'http://127.0.0.1:8081'
RUN = 'EHT-REAL-L1-20261113-220422-01'


def call(method, path, token=None, body=None):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=120) as resp:
        return json.loads(resp.read().decode('utf-8') or '{}')


body = call('POST', '/api/auth/login', body={
    'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
token = (body.get('data') or {}).get('token') or (body.get('data') or {}).get('accessToken')
tree = call('GET', '/api/reports/%s/tree?projectId=95' % RUN, token)['data']
for suite in tree.get('suites') or []:
    for case in suite.get('cases') or []:
        if case.get('verdict') != 'PASS':
            print(json.dumps(case, ensure_ascii=False, indent=2)[:900])
