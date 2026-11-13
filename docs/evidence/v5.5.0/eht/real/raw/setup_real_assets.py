#!/usr/bin/env python3
"""把 gpu01/gpu02 的真实画像登记进项目 95 的设备台账（走平台 API）。

只做「建资产」这一件事：设备 2 台 + 每台 2 个网口（15 项 NIC 画像）。
不写用例库真源、不改任何已有设备。
"""
import json
import os
import pathlib
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081'
PROJECT = 95
RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')
RAW.mkdir(parents=True, exist_ok=True)


def call(method, path, token=None, body=None, timeout=60):
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


def login():
    status, body = call('POST', '/api/auth/login', body={
        'username': 'qa-e2e-admin', 'password': os.environ['QA_E2E_PASSWORD']})
    if status != 200:
        raise SystemExit('登录失败 %s %s' % (status, body))
    data = body.get('data') or {}
    return data.get('token') or data.get('accessToken')


DEVICES = [
    {
        'name': 'gpu01', 'deviceType': 'HOST', 'model': 'GPU 服务器',
        'vendor': 'Inspur', 'managementIp': '10.202.54.101',
        'location': 'EHT 实验室', 'rackNo': 'A01', 'domainTag': 'lab',
        'enabled': True, 'hostKey': None,
        'ports': [
            {'netdev': 'ens17np0', 'mac': 'f0:bc:50:6e:fb:d8', 'pci': '0000:45:00.0',
             'numa': 0, 'rdma': 'mlx5_0', 'role': 'rx-data'},
            {'netdev': 'ens24np0', 'mac': 'f0:bc:50:6e:80:40', 'pci': '0000:ac:00.0',
             'numa': 1, 'rdma': 'mlx5_3', 'role': 'tx-data'},
        ],
    },
    {
        'name': 'gpu02', 'deviceType': 'HOST', 'model': 'GPU 服务器',
        'vendor': 'Inspur', 'managementIp': '10.202.54.102',
        'location': 'EHT 实验室', 'rackNo': 'A02', 'domainTag': 'lab',
        'enabled': True, 'hostKey': None,
        'ports': [
            {'netdev': 'ens17np0', 'mac': 'f0:bc:50:6e:8e:00', 'pci': '0000:45:00.0',
             'numa': 0, 'rdma': 'mlx5_0', 'role': 'rx-data'},
            {'netdev': 'ens24np0', 'mac': 'f0:bc:50:6e:80:50', 'pci': '0000:ac:00.0',
             'numa': 1, 'rdma': 'mlx5_1', 'role': 'tx-data'},
        ],
    },
]


def main():
    token = login()
    status, body = call('GET', '/api/projects/%d/devices?page=1&size=50' % PROJECT, token)
    existing = {}
    for item in ((body.get('data') or {}).get('items') or []):
        existing[item.get('name')] = item
    print('[INFO] 现有设备: %s' % ', '.join(sorted(existing)) or '(none)')

    report = {'projectId': PROJECT, 'created': [], 'ports': [], 'skipped': []}
    for spec in DEVICES:
        if spec['name'] in existing:
            dev = existing[spec['name']]
            report['skipped'].append({'name': spec['name'], 'id': dev.get('id'),
                                      'why': 'already exists'})
            device_id = dev.get('id')
        else:
            command = {k: v for k, v in spec.items() if k != 'ports'}
            status, body = call('POST', '/api/projects/%d/devices' % PROJECT, token, command)
            if status != 200:
                raise SystemExit('建设备失败 %s: %s' % (spec['name'], body))
            device_id = (body.get('data') or {}).get('id')
            report['created'].append({'name': spec['name'], 'id': device_id,
                                      'body': body.get('data')})
            print('[OK] 建设备 %s id=%s' % (spec['name'], device_id))

        status, body = call('GET', '/api/projects/%d/devices/%s/ports' % (PROJECT, device_id),
                            token)
        have = {p.get('portName') for p in (body.get('data') or [])}
        for port in spec['ports']:
            if port['netdev'] in have:
                report['ports'].append({'device': spec['name'], 'netdev': port['netdev'],
                                        'action': 'exists'})
                continue
            command = {
                'portName': port['netdev'], 'portType': 'NIC', 'protocol': 'ethernet',
                'macAddress': port['mac'], 'speedGbps': 400, 'mtu': 9000,
                'ipAddress': '', 'portStatus': 'UP',
                'nicName': port['netdev'], 'nicIndex': port['numa'],
                'nicVendor': 'nvidia', 'nicManufacturer': 'NVIDIA',
                'nicModel': 'connectx-7', 'nicFormFactor': '1x400G',
                'pciBdf': port['pci'], 'vendorId': '0x15b3', 'pciDeviceId': '0x1021',
                'driver': 'mlx5_core', 'firmware': '28.44.1036',
                'rdmaDevice': port['rdma'], 'nicPort': 1, 'numaNode': port['numa'],
                'nicRole': port['role'],
                'capabilitiesJson': '["roce","rdma_write","rdma_read","checksum","tso","gro"]',
                'counterPatternsJson': None,
            }
            status, body = call('POST', '/api/projects/%d/devices/%s/ports'
                                % (PROJECT, device_id), token, command)
            if status != 200:
                report['ports'].append({'device': spec['name'], 'netdev': port['netdev'],
                                        'action': 'failed', 'status': status, 'body': body})
                print('[FAIL] 端口 %s/%s %s' % (spec['name'], port['netdev'], body))
                continue
            report['ports'].append({'device': spec['name'], 'netdev': port['netdev'],
                                    'action': 'created', 'id': (body.get('data') or {}).get('id')})
            print('[OK] 端口 %s/%s' % (spec['name'], port['netdev']))

    (RAW / 'eht-real-assets.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('[DONE] 证据 %s' % (RAW / 'eht-real-assets.json'))


if __name__ == '__main__':
    main()
