#!/usr/bin/env node
/**
 * 列表可达性探针（v5.19.0 / W69-01、W69-04、W69-06）。
 *
 * 断言的是三件「整页截图看不出来」的事：
 * ① 表格有真实高度（≥300px），不是只剩表头；
 * ② 首行中心点 `elementFromPoint` 落在该行（或其子孙）——行"看得见"但被别的层盖住时，
 *    肉眼与截图都正常，只有命中检测能发现；
 * ③ 行内操作按钮同理（`--targets row-actions`）。
 *
 * 用法：
 *   node scripts/ux-probes/row-hit.mjs --pages cases --viewports 1440x900
 *   node scripts/ux-probes/row-hit.mjs --pages cases,sets,runs,test-reports \
 *        --viewports 1440x900,1366x768,1920x1080 --targets row-actions --json
 *   node scripts/ux-probes/row-hit.mjs --pages cases --self-test   # 注入覆盖层，必须判红
 *
 * 退出码：0 全绿；1 有失败；2 参数/环境错误。
 */
import { openSession, setViewport, goto, login, selectProject, closeSession, injectOverlay, removeOverlay, waitFor, sleep, APP } from './cdp.mjs'

const PAGES = {
  cases: '/cases',
  sets: '/cases/sets',
  runs: '/runs',
  reports: '/reports',
  'test-reports': '/test-reports',
}

function parseArgs(argv) {
  const out = { pages: ['cases'], viewports: ['1440x900'], targets: 'row', json: false, selfTest: false, project: process.env.QA_PROJECT_ID ?? '95' }
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i]
    if (a === '--pages') out.pages = argv[++i].split(',')
    else if (a === '--viewports') out.viewports = argv[++i].split(',')
    else if (a === '--targets') out.targets = argv[++i]
    else if (a === '--project') out.project = argv[++i]
    else if (a === '--json') out.json = true
    else if (a === '--self-test') out.selfTest = true
  }
  return out
}

/** 在页面内测量：表格高度、首行命中、行内按钮命中 */
function measureExpr(pageKey, wantActions) {
  return `(() => {
    const rect = (e) => { const r = e.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) } };
    const dt = document.querySelector('.n-data-table');
    const tbody = document.querySelector('tbody');
    const rows = [...document.querySelectorAll('tbody tr')];
    const emptyText = (document.querySelector('.n-empty, .empty-state, [data-testid="empty"]')?.innerText || '').trim().slice(0, 60);
    const out = {
      page: ${JSON.stringify(pageKey)},
      path: location.pathname,
      rows: rows.length,
      tableH: dt ? Math.round(dt.getBoundingClientRect().height) : 0,
      tbodyH: tbody ? Math.round(tbody.getBoundingClientRect().height) : 0,
      docScrollTop: document.scrollingElement ? document.scrollingElement.scrollTop : -1,
      docScrollH: document.scrollingElement ? document.scrollingElement.scrollHeight : -1,
      clientH: document.scrollingElement ? document.scrollingElement.clientHeight : -1,
      emptyText,
      firstRowHit: null,
      firstRowRect: null,
      blockingAt: null,
      actions: [],
    };
    if (rows.length) {
      const tr = rows[0];
      const r = tr.getBoundingClientRect();
      out.firstRowRect = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
      const cx = r.x + Math.min(60, r.width / 2), cy = r.y + r.height / 2;
      const top = document.elementFromPoint(cx, cy);
      out.firstRowHit = top ? (tr.contains(top) || top === tr) : false;
      out.blockingAt = top ? top.tagName + '.' + (top.className || '').toString().slice(0, 40) : null;
    }
    if (${wantActions ? 'true' : 'false'}) {
      const btns = [...document.querySelectorAll('tbody button, tbody a')].filter((b) => (b.innerText || '').trim().length > 0 && b.getBoundingClientRect().width > 0);
      out.actions = btns.slice(0, 24).map((b) => {
        const r = b.getBoundingClientRect();
        const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        return {
          t: (b.innerText || '').trim().slice(0, 10),
          x: Math.round(r.x),
          hit: top ? (b.contains(top) || top === b || (top.innerText || '').trim() === (b.innerText || '').trim()) : false,
          topCls: top ? top.tagName + '.' + (top.className || '').toString().slice(0, 30) : null,
        };
      });
    }
    return out;
  })()`
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const bad = args.pages.filter((p) => !PAGES[p])
  if (bad.length) {
    console.error(`未知页面: ${bad.join(', ')}；可选: ${Object.keys(PAGES).join(', ')}`)
    process.exit(2)
  }

  const results = []
  const s = await openSession({ width: 1440, height: 900 })
  try {
    const ok = await login(s)
    if (!ok) throw new Error('登录失败（检查 QA_ADMIN / QA_ADMIN_PASSWORD）')
    await selectProject(s, args.project)

    for (const vp of args.viewports) {
      const [w, h] = vp.split('x').map(Number)
      await setViewport(s, w, h)
      for (const pageKey of args.pages) {
        await goto(s, `${APP}${PAGES[pageKey]}`, 2600)
        // 有数据时等首行出现；空列表也允许（后续按空态判定）
        await waitFor(s, `document.querySelectorAll('tbody tr').length > 0 || Boolean(document.querySelector('.n-empty, .empty-state'))`, 12000)
        await sleep(800)
        if (args.selfTest) await injectOverlay(s)
        const m = await s.evaluate(measureExpr(pageKey, args.targets === 'row-actions'))
        if (args.selfTest) await removeOverlay(s)
        m.viewport = vp
        results.push(m)
      }
    }
  } finally {
    await closeSession(s)
  }

  // 判定
  const fails = []
  for (const r of results) {
    const tag = `${r.page}@${r.viewport}`
    const hasData = r.rows > 0
    // 高度期望：数据多时要求吃掉剩余空间（≥300）；数据少时只要求不被裁（表头 + 行高）
    const needH = Math.min(300, r.rows * 44 + 44)
    if (hasData && r.tableH < needH) fails.push(`${tag}: 表格高度 ${r.tableH}px < ${needH}（rows=${r.rows}）`)
    if (hasData && r.firstRowHit !== true) fails.push(`${tag}: 首行不可命中（命中 ${r.blockingAt}）`)
    if (!hasData && !r.emptyText) fails.push(`${tag}: 无数据且无空态文案`)
    if (r.docScrollTop > 0) fails.push(`${tag}: 整页被滚动 scrollTop=${r.docScrollTop}`)
    if (args.targets === 'row-actions' && r.actions.length) {
      const miss = r.actions.filter((a) => !a.hit)
      if (miss.length) fails.push(`${tag}: ${miss.length}/${r.actions.length} 个行内操作不可命中（首个 "${miss[0].t}" 命中 ${miss[0].topCls}）`)
    }
  }

  if (args.json) {
    console.log(JSON.stringify({ viewports: args.viewports, pages: args.pages, results, fails }, null, 2))
  } else {
    for (const r of results) {
      console.log(
        `[${r.page}@${r.viewport}] rows=${r.rows} tableH=${r.tableH} 首行命中=${r.firstRowHit === null ? 'n/a' : r.firstRowHit}` +
          `${r.actions.length ? ` 行内操作=${r.actions.filter((a) => a.hit).length}/${r.actions.length} 可命中` : ''}` +
          `${r.emptyText ? ` 空态="${r.emptyText}"` : ''}`
      )
    }
  }

  if (args.selfTest) {
    // 自证：注入覆盖层后必须判红，否则说明探针的命中检测是假的
    if (fails.length === 0) {
      console.error('[FAIL] --self-test：注入覆盖层后仍未判红，探针失效')
      process.exit(1)
    }
    console.log(`[PASS] --self-test：注入覆盖层后判红 ${fails.length} 条`)
    process.exit(0)
  }

  if (fails.length) {
    console.error(`\n[FAIL] ${fails.length} 条：`)
    for (const f of fails) console.error('  - ' + f)
    process.exit(1)
  }
  console.log(`\n[PASS] ${results.length} 组全部通过`)
}

main().catch((e) => {
  console.error('[ERROR]', e.message)
  process.exit(2)
})
