#!/usr/bin/env python3
"""查 test_result 里失败用例的 failure_msg 是否入库。"""
import pathlib
import subprocess

cfg = {}
for line in (pathlib.Path.home() / '.config/robot-autotest/env').read_text(
        encoding='utf-8').splitlines():
    line = line.strip()
    if line and not line.startswith('#') and '=' in line:
        k, v = line.split('=', 1)
        cfg[k.strip()] = v.strip().strip('"').strip("'")


def q(sql):
    r = subprocess.run(['mysql', '-h', cfg['DB_HOST'], '-P', cfg['DB_PORT'],
                        '-u', cfg['DB_USERNAME'], '-p' + cfg['DB_PASSWORD'],
                        '-D', cfg['DB_NAME'], '-N', '-B', '-e', sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return ['ERR ' + r.stderr.strip().splitlines()[-1]]
    return r.stdout.strip().splitlines()


print('=== run 表名候选 ===')
print('\n'.join(q("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
                  "AND table_name LIKE '%run%'")))

print('\n=== 本次 run 的 test_result ===')
print('\n'.join(q("SELECT t.case_key, t.verdict, LEFT(COALESCE(t.failure_msg,'(NULL)'),70) "
                  "FROM test_result t WHERE t.run_id IN "
                  "(SELECT id FROM run WHERE run_id='EHT-REAL-L1-20261113-220422-01')")))
