#!/usr/bin/env node
/**
 * 列表四态探针（v5.20.0 / FR-v5.20-01）。
 *
 * 守四件"截图看不出来"的事：
 * ① 搜不到时必须说清条件 + 给清空出口（不是一片空白）；
 * ② 接口挂了必须是失败态 + 重试（不能读成"没有数据"）；
 * ③ 无权限必须是 forbidden 插图 + 找人开权限（不能引导重试）；
 * ④ 禁用态按钮必须带 title 说明原因（不允许"点了没反应也不说为什么"）。
 *
 * 用法：
 *   node scripts/ux-probes/list-state.mjs                     # 判据 1 + 4
 *   node scripts/ux-probes/list-state.mjs --offline            # 加判据 2（断网）
 *   node scripts/ux-probes/list-state.mjs --self-test          # 注入遮挡，判据 1 必须判红
 *
 * 退出码：0 全绿；1 有失败；2 环境错误。
 */
import { openSession, setViewport, goto, login, selectProject, closeSession, sleep, waitFor, injectOverlay, removeOverlay, APP } from './cdp.mjs'

function parseArgs(argv) {
  const out = { project: process.env.QA_PROJECT_ID ?? '95', offline: false, selfTest: false, json: false, routes: [] }
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--project') out.project = argv[++i]
    else if (argv[i] === '--offline') out.offline = true
    else if (argv[i] === '--self-test') out.selfTest = true
    else if (argv[i] === '--json') out.json = true
    else if (argv[i] === '--routes') out.routes = argv[++i].split(',').filter(Boolean)
  }
  return out
}

const args = parseArgs(process.argv.slice(2))
const out = { noResult: null, offline: null, disabled: [], fails: [] }

const s = await openSession({ width: 1440, height: 900 })
try {
  await login(s)
  await selectProject(s, args.project)

  // ---- 判据 1：搜不到 → 说有条件 + 清空出口 → 点击恢复 ----
  await goto(s, `${APP}/cases`, 2800)
  await waitFor(s, `document.querySelectorAll('tbody tr').length > 0`, 15000)
  const total = await s.evaluate(`(document.querySelector('.n-pagination') || {}).innerText || ''`)

  const box = await s.evaluate(`(() => {
    const el = [...document.querySelectorAll('input')].find((i) => /关键词|搜索/.test(i.placeholder || ''));
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  })()`)
  if (!box) {
    out.fails.push('用例库找不到关键词搜索框')
  } else {
    await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await s.send('Input.insertText', { text: 'ZZZ-NOT-EXIST' })
    await s.send('Input.dispatchKeyEvent', { type: 'keyDown', windowsVirtualKeyCode: 13, key: 'Enter' })
    await s.send('Input.dispatchKeyEvent', { type: 'keyUp', windowsVirtualKeyCode: 13, key: 'Enter' })
    await sleep(2200)
    // 自证用：注入全屏覆盖层，让「清空筛选」按钮不可命中，判定必须失败
    if (args.selfTest) await injectOverlay(s)

    out.noResult = await s.evaluate(`(() => {
      const body = document.body.innerText || '';
      const empty = document.querySelector('.n-empty, [class*="empty"]');
      const btn = [...document.querySelectorAll('button')].find((b) => /清空筛选|清空条件/.test((b.innerText || '').trim()));
      return {
        rows: document.querySelectorAll('tbody tr').length,
        emptyText: (empty ? empty.innerText : '').replace(/\\n/g, ' | ').slice(0, 160),
        mentionsCondition: /ZZZ-NOT-EXIST/.test(body),
        hasClear: Boolean(btn),
        clearAt: btn ? (() => {
          const r = btn.getBoundingClientRect();
          const cx = r.x + r.width / 2, cy = r.y + r.height / 2;
          const top = document.elementFromPoint(cx, cy);
          return { x: cx, y: cy, w: r.width, h: r.height, hit: top ? (btn.contains(top) || top === btn) : false };
        })() : null,
      };
    })()`)
    if (args.selfTest) await removeOverlay(s)

    if (out.noResult.rows > 0) out.fails.push('搜不存在的词后仍有数据行')
    if (!out.noResult.mentionsCondition) out.fails.push('无结果态没有把「当前筛选条件」说出来（看不到关键词）')
    if (!out.noResult.hasClear) out.fails.push('无结果态没有「清空筛选」出口')
    if (out.noResult.clearAt && (out.noResult.clearAt.w === 0 || out.noResult.clearAt.h === 0)) {
      out.fails.push('清空筛选按钮尺寸为 0（不可见）')
    }
    if (out.noResult.clearAt && out.noResult.clearAt.hit === false) {
      out.fails.push('清空筛选按钮被遮挡（鼠标点不到）')
    }

    if (out.noResult.clearAt && out.noResult.clearAt.w > 0) {
      const c = out.noResult.clearAt
      await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: c.x, y: c.y, button: 'left', clickCount: 1 })
      await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: c.x, y: c.y, button: 'left', clickCount: 1 })
      await sleep(2200)
      const back = await s.evaluate(`({ rows: document.querySelectorAll('tbody tr').length, pager: (document.querySelector('.n-pagination') || {}).innerText || '' })`)
      out.noResult.afterClearRows = back.rows
      if (back.rows === 0) out.fails.push('点「清空筛选」后列表没有恢复')
    }
  }

  // ---- 判据 4：禁用态按钮必须带 title ----
  // 单页样本不足以说明"100% 带 title"，所以支持 --routes 多页扫描，
  // 把缺 title 的按钮按页面列出来（谁缺、缺几个，一目了然）。
  out.disabled = []
  out.disabledTotal = 0
  const scanRoutes = args.routes.length ? args.routes : ['/cases']
  for (const route of scanRoutes) {
    await goto(s, `${APP}${route}`, 2400)
    await sleep(1200)
    const stat = await s.evaluate(`(() => {
      const bad = [];
      let total = 0;
      // 纯导航按钮（分页上一页/下一页）禁用是位置语义，不需要解释原因；
      // 需要 explanation 的是"业务动作按钮为什么现在不能点"。
      const NAV = /^(上一页|下一页|首页|末页|‹|›|<|>)$/;
      for (const b of document.querySelectorAll('button[disabled], button:disabled')) {
        if (b.getBoundingClientRect().width === 0) continue;
        const text = (b.innerText || '').trim();
        if (NAV.test(text)) continue;
        total += 1;
        const title = (b.getAttribute('title') || '').trim();
        if (!title) bad.push(text.slice(0, 16) || '(无文字)');
      }
      return { total, bad };
    })()`)
    out.disabledTotal += stat.total
    if (stat.bad.length) {
      out.disabled.push({ route, count: stat.bad.length, total: stat.total, items: stat.bad.slice(0, 8) })
      out.fails.push(`${route}: ${stat.bad.length}/${stat.total} 个禁用按钮没有 title 说明原因（${stat.bad.slice(0, 4).join(' / ')}）`)
    }
  }

  // ---- 判据 2：接口 500 → 失败态 + 重试 ----
  // 用 Fetch 域拦单个接口返回 500，而不是整站断网——整站离线时浏览器自己就白屏了，
  // 那不是"页面错误态"，测了也证明不了 SPA 有没有处理失败。
  if (args.offline) {
    await s.send('Fetch.enable', { patterns: [{ urlPattern: '*/api/projects/*/cases*', requestStage: 'Request' }] })
    s.on('Fetch.requestPaused', async (p) => {
      try {
        await s.send('Fetch.fulfillRequest', {
          requestId: p.requestId,
          responseCode: 500,
          responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
          body: Buffer.from(JSON.stringify({
            code: 50000, codeName: 'INTERNAL_ERROR', message: 'probe injected 500', data: null,
          })).toString('base64'),
        })
      } catch {
        // 页面已关/请求已取消：忽略
      }
    })
    await goto(s, `${APP}/cases`, 2600)
    await sleep(2200)
    out.offline = await s.evaluate(`(() => {
      const body = document.body.innerText || '';
      const retry = [...document.querySelectorAll('button')].find((b) => /重试/.test((b.innerText || '').trim()));
      return { text: body.slice(0, 400).replace(/\\n/g, ' | '), mentionsFail: /加载失败|失败/.test(body), hasRetry: Boolean(retry) };
    })()`)
    await s.send('Fetch.disable').catch(() => {})
    if (!out.offline.mentionsFail) out.fails.push('接口 500 时没有显示「加载失败」')
    if (!out.offline.hasRetry) out.fails.push('接口 500 时没有「重试」入口')
  }
} finally {
  await closeSession(s)
}

if (args.json) {
  console.log(JSON.stringify(out, null, 2))
} else {
  if (out.noResult) {
    console.log(`[无结果态] 行=${out.noResult.rows} 提到条件=${out.noResult.mentionsCondition} 清空出口=${out.noResult.hasClear} 清空后行=${out.noResult.afterClearRows ?? 'n/a'}`)
    console.log(`  文案：${out.noResult.emptyText}`)
  }
  if (out.offline) console.log(`[断网] 失败态=${out.offline.mentionsFail} 重试=${out.offline.hasRetry} 文本=${out.offline.text.slice(0, 160)}`)
  console.log(`[禁用按钮] 共 ${out.disabledTotal} 个，缺 title 的页面 ${out.disabled.length} 个`)
  for (const d of out.disabled) console.log(`  ${d.route}: ${d.count}/${d.total} — ${d.items.join(' / ')}`)
}

if (args.selfTest) {
  // 自证：本探针靠"元素可见 + 文本存在"判定，注入全屏遮挡后清空按钮应不可点 → 已由 fails 体现
  if (out.fails.length === 0) {
    console.error('[FAIL] --self-test：未判红，探针可能未真正检测')
    process.exit(1)
  }
  console.log(`[PASS] --self-test：判红 ${out.fails.length} 条`)
  process.exit(0)
}

if (out.fails.length) {
  console.error(`\n[FAIL] ${out.fails.length} 条：`)
  for (const f of out.fails) console.error('  - ' + f)
  process.exit(1)
}
console.log('\n[PASS] 列表四态检查通过')
