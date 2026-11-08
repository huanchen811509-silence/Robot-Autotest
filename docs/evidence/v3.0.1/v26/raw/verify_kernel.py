#!/usr/bin/env python3
"""v2.6.1 真实 HTTP 验收：项目测试内核 + 抽屉宽度预设（前端）回归点。

判据（写进 v2.6 文档回写）：
1. 卡牌接口带 testKernel，存量项目 = robot；
2. 建项目可指定 pytest 并落库回读；
3. 更新接口可改内核；未知内核 400；
4. ai/agents 接口不受影响（回归）。
"""
import json
import os
import sys
import urllib.request

BASE = 'http://127.0.0.1:8081/api'
OUT = os.path.expanduser('~/robot-autotest/docs/evidence/v3.0.1/v26/raw')


def call(path, method='GET', body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, None


def main():
    user = os.environ.get('PERF_USER', 'qa-e2e-admin')
    password = os.environ.get('QA_E2E_PASSWORD')
    if not password:
        print('FATAL: QA_E2E_PASSWORD not set')
        sys.exit(1)
    status, payload = call('/auth/login', 'POST', {'username': user, 'password': password})
    assert status == 200, f'login {status}'
    token = payload['data']['token']

    results = {}

    # 1. 卡牌接口带 testKernel
    status, cards = call('/projects/cards', token=token)
    assert status == 200, f'cards {status}'
    items = cards['data']
    assert all('testKernel' in c for c in items), 'card missing testKernel'
    defaults = {c['code']: c['testKernel'] for c in items}
    results['cards_testKernel'] = defaults

    # 2. 建项目带 pytest 内核
    code = f'kernel-verify-{os.getpid()}'
    status, created = call('/projects', 'POST', {
        'code': code, 'name': '内核验证项目', 'visibility': 'private',
        'testKernel': 'pytest'}, token=token)
    assert status == 200 or status == 201, f'create {status}: {created}'
    pid = created['data']['id']
    assert created['data']['testKernel'] == 'pytest', created['data']
    results['created'] = {'id': pid, 'code': code, 'testKernel': created['data']['testKernel']}

    # 3. 更新内核回 robot
    status, updated = call(f'/projects/{pid}', 'PUT', {
        'name': '内核验证项目', 'description': None, 'enabled': True,
        'visibility': 'private', 'testKernel': 'robot'}, token=token)
    assert status == 200, f'update {status}: {updated}'
    assert updated['data']['testKernel'] == 'robot', updated['data']
    results['updated_testKernel'] = updated['data']['testKernel']

    # 4. 未知内核 400
    status, bad = call(f'/projects/{pid}', 'PUT', {
        'name': '内核验证项目', 'enabled': True, 'testKernel': 'junit'}, token=token)
    results['invalid_kernel_status'] = status
    assert status == 400, f'expected 400, got {status}: {bad}'

    # 5. agents 接口回归
    status, agents = call('/ai/agents?projectId=1', token=token)
    assert status == 200, f'agents {status}'
    assert not any('baseUrl' in i or 'apiKeyEnc' in i for i in agents['data']['items'])
    results['agents_count'] = len(agents['data']['items'])

    # 清理验证项目（空的，直接删）
    status, _ = call(f'/projects/{pid}', 'DELETE', token=token)
    results['cleanup_status'] = status

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'kernel-verify.json'), 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    print('ALL PASS')


if __name__ == '__main__':
    main()
