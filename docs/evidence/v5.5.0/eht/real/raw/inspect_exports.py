#!/usr/bin/env python3
"""评估导出产物的内容质量：docx/xlsx 解包取文本，PDF 用纯文本抽取（若可用）。"""
import pathlib
import re
import sys
import zipfile

RAW = pathlib.Path('/home/evergreen/robot-autotest/docs/evidence/v5.5.0/eht/real/raw')


def docx_text(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read('word/document.xml').decode('utf-8', 'replace')
    text = re.sub(r'<w:p[^>]*>', '\n', xml)
    text = re.sub(r'<[^>]+>', '', text)
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    return lines


def xlsx_text(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        shared = []
        if 'xl/sharedStrings.xml' in names:
            raw = z.read('xl/sharedStrings.xml').decode('utf-8', 'replace')
            shared = [re.sub(r'<[^>]+>', '', s) for s in re.findall(r'<si>(.*?)</si>', raw, re.S)]
        sheets = [n for n in names if n.startswith('xl/worksheets/sheet')]
        counts = {n: z.read(n).decode('utf-8', 'replace').count('<row ') for n in sheets}
    return shared, counts


for p in sorted(RAW.glob('report-*.docx')):
    lines = docx_text(p)
    print('=' * 60)
    print(p.name, '段落数=', len(lines))
    print('\n'.join(lines[:25]))
for p in sorted(RAW.glob('report-*.xlsx')):
    shared, counts = xlsx_text(p)
    print('=' * 60)
    print(p.name, '共享字符串=', len(shared), '各表行数=', counts)
    print('前 20 个字符串:', shared[:20])
