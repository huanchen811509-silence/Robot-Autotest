#!/usr/bin/env node
/**
 * 工程 × 页面 矩阵（v5.21.0 / FR-v5.21-04 / W71-07）。
 *
 * 判据：4 工程 × 6 页面 = 24 格全部 PASS——每格要么"表格高度 ≥ 300 且首行可命中"，
 * 要么"空态文案可见"（无数据工程按空态判定，不判红，但报告里标注）。
 *
 * 为什么要按工程遍历：v5.19 的列表塌陷只在部分工程复现（`/cases` 表高 44px、
 * 首行不可命中）。当时修的是通用布局链，但只有"每个工程都过一遍"才能证明
 * 修复不是碰巧命中了某个工程的数据量。
 *
 * 用法：
 *   node scripts/ux-probes/project-matrix.mjs
 *   node scripts/ux-probes/project-matrix.mjs --self-test   # 注入遮挡层，必须判红
 *   node scripts/ux-probes/project-matrix.mjs --json        # CSV + JSON 证据
 */
import { writeFileSync } from 'node:fs'
import { APP, assertCdp, closeSession, goto, injectOverlay, login, log, openSession, removeOverlay, selectProject, sleep, waitFor } from './cdp.mjs'

const SELF_TEST = process.argv.includes('--self-test')
const JSON_OUT = process.argv.includes('--json')
const ADMIN_PASS = process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026'

/**
 * 两类页面用两条判据（v5.21 / W71-07）：
 *
 * - `list`：纯列表页，表格吃满剩余高度 → 要求「表高 ≥ 300 或空态可见」+ 首行可命中。
 * - `table-below-fold`：表单页下方的只读表格（环境采集）。首屏本来就不该看见表格，
 *   所以只要求「空态提示可见」或「滚到行后能命中」，不要求首屏有 300px 表高——
 *   把表单页压到 300px 表高反而是错的。
 */
const PAGES = [
  { path: '/cases', label: '用例库', kind: 'list' },
  { path: '/cases/sets', label: '用例集', kind: 'list' },
  { path: '/assets', label: '测试资产', kind: 'list' },
  { path: '/environment/collect', label: '环境采集', kind: 'table-below-fold' },
  { path: '/execution-plans', label: '执行计划', kind: 'list' },
  { path: '/reports', label: '执行报告', kind: 'list' },
]

const MEASURE = `(() => {
  const dt = document.querySelector('.n-data-table, .data-table, table.set-table, table.list');
  const rows = [...document.querySelectorAll('tbody tr')];
  const emptySel = document.querySelector('.n-empty, .empty-state, [data-testid="empty"], .empty-illustration');
  // 自绘表格（如环境采集的 .data-table）没有标准空态组件，但会写 "0 条"；
  // 只认组件类名会把"确实画了空态"判成"什么都没有"。
  const tableText = dt ? (dt.innerText || '') : '';
  const emptyHint = (emptySel?.innerText || '').trim() || (/0 条|暂无|没有|无记录/.test(tableText) ? tableText.trim().slice(0, 60) : '');
  const out = {
    rows: rows.length,
    tableH: dt ? Math.round(dt.getBoundingClientRect().height) : 0,
    emptyText: emptyHint.slice(0, 60),
    firstRowHit: null,
    blockingAt: null,
    scrolled: false,
  };
  if (rows.length) {
    const tr = rows[0];
    // 行在视口外时先滚进来再判命中：判据是"用户能否点到"，不是"首屏能否看到"。
    const before = tr.getBoundingClientRect();
    if (before.y + before.height > window.innerHeight || before.y < 0) {
      tr.scrollIntoView({ block: 'center' });
      out.scrolled = true;
      return out;
    }
    const r = tr.getBoundingClientRect();
    const top = document.elementFromPoint(r.x + Math.min(60, r.width / 2), r.y + r.height / 2);
    out.firstRowHit = top ? (tr.contains(top) || top === tr) : false;
    out.blockingAt = top ? top.tagName + '.' + String(top.className || '').slice(0, 40) : null;
  }
  return out;
})()`

async function main() {
  await assertCdp()
  const s = await openSession({ width: 1440, height: 900 })
  const rows = []
  let fails = 0
  try {
    if (!(await login(s, { user: 'admin', pass: ADMIN_PASS }))) {
      console.error('[project-matrix] 管理员登录失败')
      process.exit(2)
    }
    // 工程清单从 Node 侧取（不依赖页面上下文的 localStorage / 同源）：
    // 页面里 `fetch('/api/...')` 在 about:blank 或登录页跳转瞬间会失败，
    // 失败了还返回空数组，矩阵就"没有工程可验"——静默跳过比判红更危险。
    const loginResp = await fetch(`${APP}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: ADMIN_PASS }),
    })
    const token = (await loginResp.json())?.data?.token
    const listResp = await fetch(`${APP}/api/projects?page=1&size=50`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const listBody = await listResp.json()
    // 注意：/api/projects 返回的是**数组**（不是分页对象），别套 .items——
    // 套错了会得到空数组，然后矩阵"没有工程可验"地静默通过。
    const raw = Array.isArray(listBody?.data) ? listBody.data : (listBody?.data?.items ?? [])
    const projects = raw.map((p) => ({ id: p.id, name: p.name, kind: p.kind }))
    if (!projects.length) {
      console.error(`[project-matrix] 取工程失败：status=${listResp.status} body=${JSON.stringify(listBody).slice(0, 200)}`)
    }
    if (!projects.length) {
      console.error('[project-matrix] 没有任何工程，矩阵无从验证')
      process.exit(2)
    }
    log(`[project-matrix] 工程 ${projects.length} 个：${projects.map((p) => `${p.id}:${p.name}`).join(', ')}`)

    for (const project of projects) {
      await selectProject(s, project.id)
      for (const page of PAGES) {
        await goto(s, `${APP}${page.path}`, 1500)
        await waitFor(s, `Boolean(document.querySelector('.n-data-table') || document.querySelector('.n-empty, .empty-state, .empty-illustration'))`, 12000)
        await sleep(350)
        let m = await s.evaluate(MEASURE)
        if (m.scrolled) {
          // 滚进来之后再测一次（只重测一次，避免滚动抖动造成假绿/假红）
          await sleep(250)
          m = await s.evaluate(MEASURE)
        }
        const emptyOnly = m.rows === 0 && Boolean(m.emptyText)
        // 表高期望与 row-hit.mjs 同口径：数据多时要求吃掉剩余空间（≥300），
        // 数据少时只要求不被裁（表头 + 行高）。同仓两个探针不能对同一现象用两套标准——
        // 项目 1 的执行计划只有 2 行，表高 132 正好 = 2×44+44，硬卡 300 会把它判红，
        // 而 row-hit 对同一页面判绿。真塌陷（只剩表头）在两种口径下都过不了。
        const needH = Math.min(300, m.rows * 44 + 44)
        const tallEnough = page.kind === 'table-below-fold' ? true : m.tableH >= needH
        const ok = emptyOnly || (tallEnough && (m.rows === 0 || m.firstRowHit === true))
        if (!ok) fails += 1
        rows.push({
          projectId: project.id, project: project.name, kind: project.kind ?? '',
          page: page.label, path: page.path, ...m,
          verdict: emptyOnly ? '仅验空态' : ok ? 'PASS' : 'FAIL', ok,
        })
      }
    }

    if (SELF_TEST) {
      await selectProject(s, projects[0].id)
      await goto(s, `${APP}/cases`, 1500)
      await injectOverlay(s)
      await sleep(200)
      const blocked = await s.evaluate(MEASURE)
      await removeOverlay(s)
      if (blocked.firstRowHit === null || blocked.firstRowHit === true) {
        console.error('[project-matrix][self-test][FAIL] 注入遮挡后首行仍判可命中，判据失效')
        process.exit(1)
      }
      console.log(`[project-matrix][self-test][PASS] 注入遮挡后首行判不可命中（blocking=${blocked.blockingAt}）`)
      process.exit(fails > 0 ? 1 : 0)
    }

    if (JSON_OUT) {
      writeFileSync('docs/evidence/v5.21.0/project-matrix.json', JSON.stringify({ rows }, null, 2))
      const csv = ['工程,类型,页面,路径,行数,表高,首行可命中,空态,判定']
      for (const r of rows) {
        csv.push([r.project, r.kind, r.page, r.path, r.rows, r.tableH, r.firstRowHit ?? '-', r.emptyText ? '有' : '-', r.verdict].join(','))
      }
      writeFileSync('docs/evidence/v5.21.0/project-matrix.csv', csv.join('\n') + '\n')
      console.log('[project-matrix] 证据写入 docs/evidence/v5.21.0/project-matrix.{json,csv}')
    }
    for (const r of rows) {
      log(`${r.ok ? 'PASS' : 'FAIL'}  ${String(r.projectId).padEnd(3)} ${r.page.padEnd(6)} 行=${String(r.rows).padEnd(3)} 表高=${String(r.tableH).padEnd(4)} 首行=${r.firstRowHit ?? '-'} ${r.verdict === '仅验空态' ? '（无数据，仅验空态）' : ''}`)
    }
    log(`\n[project-matrix] ${rows.length} 格，FAILS=${fails}`)
  } finally {
    await closeSession(s)
  }
  process.exit(fails > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('[project-matrix] 异常：', err)
  process.exit(1)
})
