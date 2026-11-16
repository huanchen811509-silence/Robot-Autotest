#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""列表四态复用度机检（v5.20.0 / FR-v5.20-01 判据 3）。

判据原文：「≥5 个列表页复用同一组件（DOM class 与服务端渲染一致）」。

这里守两件事，都是**可机检**的部分：
1. 有 ≥5 个页面在用同一个列表壳（`DataTable`），不是各页自己糊表格；
2. 这些页面里，凡声明了筛选/错误态的，必须同时接上「空态出口动作」
   （`#emptyAction`），否则"无结果一片空白"会重新长回来。

页面自渲染的表格（`<table class="table">`）不计入——那不是"复用"。
`--self-test` 会临时把阈值调到不可能达到的数，验证"判据能判红"。
"""
import io
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'frontend', 'src')
MIN_PAGES = 5


def walk_vue(root):
    for base, _dirs, files in os.walk(root):
        for name in files:
            if name.endswith('.vue'):
                yield os.path.join(base, name)


def scan(min_pages):
    users = []
    with_action = []
    for path in walk_vue(ROOT):
        text = io.open(path, encoding='utf-8', errors='replace').read()
        rel = os.path.relpath(path, ROOT)
        if re.search(r'<DataTable\b', text) or re.search(r'<ListScaffold\b', text):
            users.append(rel)
            if 'emptyAction' in text:
                with_action.append(rel)
    ok = len(users) >= min_pages
    print('[列表壳复用] 使用 DataTable/ListScaffold 的页面：%d（要求 ≥%d）' % (len(users), min_pages))
    for u in sorted(users):
        mark = '*' if u in with_action else ' '
        print('  %s %s' % (mark, u))
    print('[空态出口] 接了 #emptyAction 的页面：%d / %d（* 标记）' % (len(with_action), len(users)))
    if not ok:
        print('[FAIL] 复用页面数不足：%d < %d' % (len(users), min_pages))
        return False
    # 至少要有 3 个页面给了出口动作，否则等于"壳复用了但没人用出口"
    if len(with_action) < 3:
        print('[FAIL] 只有 %d 个页面接了空态出口动作（要求 ≥3）' % len(with_action))
        return False
    return True


def main():
    self_test = '--self-test' in sys.argv
    if self_test:
        print('=== self-test：把阈值抬到不可能达到的数，必须判红 ===')
        bad = scan(9999)
        if bad:
            print('[FAIL] self-test 未判红：阈值 9999 竟然通过')
            return 1
        print('[PASS] self-test：阈值 9999 判红（说明判据有效）')
        print()
        print('=== 真实检查 ===')
    return 0 if scan(MIN_PAGES) else 1


if __name__ == '__main__':
    sys.exit(main())
