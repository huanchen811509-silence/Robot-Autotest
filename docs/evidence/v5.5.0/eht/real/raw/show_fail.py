#!/usr/bin/env python3
"""列出 junit 里失败/错误用例及首行原因。用法: show_fail.py <junit.xml>"""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()
print('suite attrs:', {k: root.get(k) for k in ('tests', 'failures', 'errors', 'skipped')})
for tc in root.iter('testcase'):
    for child in tc:
        if child.tag in ('error', 'failure'):
            print('CASE :', tc.get('classname'), '::', tc.get('name'))
            print('KIND :', child.tag)
            print('MSG  :', (child.get('message') or '').strip()[:700])
            body = (child.text or '').strip().splitlines()
            print('TRACE:', ' | '.join(body[:6])[:700])
            print('-' * 70)
