#!/usr/bin/env node
/**
 * 用例集行内操作 × 右栏状态 × 视口（v5.21.0 / FR-v5.21-05 / W71-07）。
 *
 * 判据：4 种右栏状态 × 3 个视口 = 12 组，每组 4 个行内按钮中心点
 * `elementFromPoint` 必须命中自身。v5.21 调研里 ED202 用例集页 28 个行内按钮
 * **全部不可命中**，而同一页另一次加载又可达——所以这不是"某个按钮写错了"，
 * 而是行内操作跟着右栏状态抖。修法是把操作列固定在列表右缘（sticky right），
 * 本探针就是"抖不抖"的机器判据。
 *
 * 4 种状态：未选中 / 已选中（成员面板） / 成员详情 Dock（?detail=） / 窄屏抽屉（≤1400）
 * 3 个视口：1440 / 1366 / 1920
 *
 * 用法：
 *   node scripts/ux-probes/set-row-actions.mjs
 *   node scripts/ux-probes/set-row-actions.mjs --project 77
 *   node scripts/ux-probes/set-row-actions.mjs --self-test   # 注入覆盖层，必须判红
 */
import { writeFileSync } from 'node:fs'
import { APP, assertCdp, closeSession, goto, injectOverlay, login, log, openSession, removeOverlay, selectProject, sleep, waitFor } from './cdp.mjs'

const SELF_TEST = process.argv.includes('--self-test')
const JSON_OUT = process.argv.includes('--json')
const ADMIN_PASS = process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026'
const PROJECT_ARG = (() => {
  const i = process.argv.indexOf('--project')
  if (i > -1 && process.argv[i + 1]) return Number(process.argv[i + 1])
  return Number(process.env.QA_SET_PROJECT ?? 0) || 0
})()

const STATES = [
  { key: 'none', label: '未选中集合', action: 'none' },
  { key: 'selected', label: '已选中（成员面板）', action: 'select' },
  { key: 'detail', label: '成员详情 Dock', action: 'detail' },
  { key: 'narrow', label: '窄屏抽屉', action: 'select' },
]
const VIEWPORTS = [1440, 1366, 1920]

/** 首行 4 个行内按钮的命中检测（只数可见按钮，命中点取中心）。 */
const MEASURE = `(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  if (!rows.length) return { rows: 0, buttons: [], allHit: false, note: '无数据行' };
  const tr = rows[0];
  const btns = [...tr.querySelectorAll('.row-actions button, .row-actions a')]
    .filter((b) => (b.innerText || '').trim().length > 0 && b.getBoundingClientRect().width > 0);
  const buttons = btns.map((b) => {
    const r = b.getBoundingClientRect();
    const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return {
      label: (b.innerText || '').trim().slice(0, 8),
      hit: top ? (b.contains(top) || top === b) : false,
      blocker: top && !(b.contains(top) || top === b) ? top.tagName + '.' + String(top.className || '').slice(0, 24) : null,
    };
  });
  // v5.21.0：≤1400 时右栏是**浮层抽屉**（v5.19 / W69-05 的窄屏形态），
  // 抽屉打开时列表被遮罩盖住是设计意图，不是"按钮点不到"。这里把抽屉状态一起带回来，
  // 由外层区分「被遮罩（有意）」与「被同层元素压住（缺陷）」。
  const mask = document.querySelector('[data-testid="case-set-drawer-mask"]')
  return {
    rows: rows.length,
    buttons,
    allHit: buttons.length > 0 && buttons.every((x) => x.hit),
    drawer: Boolean(mask),
  };
})()`

async function main() {
  await assertCdp()
  const s = await openSession({ width: 1440, height: 900 })
  const rows = []
  let fails = 0
  try {
    if (!(await login(s, { user: 'admin', pass: ADMIN_PASS }))) {
      console.error('[set-row-actions] 管理员登录失败')
      process.exit(2)
    }
    // 默认用 QA_SET_PROJECT（77 = ED202 公共用例库，有 7 个常驻集合）。
    // 不从页面里 fetch 列表：`/api/projects` 返回的是数组不是分页对象，
    // 且页面上下文的 fetch 在跳转瞬间容易失败——失败了就"没有工程可验"地静默跳过。
    let projectId = PROJECT_ARG || Number(process.env.QA_SET_PROJECT ?? 77)
    if (!projectId) {
      console.error('[set-row-actions] 没有可用工程')
      process.exit(2)
    }
    log(`[set-row-actions] 工程 ${projectId}`)
    await selectProject(s, projectId)

    for (const state of STATES) {
      for (const width of VIEWPORTS) {
        await s.send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false })
        await goto(s, `${APP}/cases/sets`, 1500)
        await waitFor(s, `document.querySelectorAll('tbody tr').length > 0`, 12000)
        if (state.action !== 'none') {
          // 点首行「管理用例」打开成员面板；窄屏下它同时展开抽屉
          await s.evaluate(`(() => {
            const tr = document.querySelector('tbody tr')
            const b = tr && [...tr.querySelectorAll('button, a')].find((e) => /管理用例/.test((e.innerText || '').trim()))
            if (b) b.click()
            return Boolean(b)
          })()`)
          await sleep(900)
        }
        if (state.action === 'detail') {
          // 成员详情 Dock：点成员第一行的详情入口（无入口时退化为面板，不判红）
          await s.evaluate(`(() => {
            const tr = document.querySelector('.items-card tbody tr')
            const b = tr && [...tr.querySelectorAll('button, a')].find((e) => /详情|展开/.test((e.innerText || '').trim()))
            if (b) b.click()
            return Boolean(b)
          })()`)
          await sleep(600)
        }
        const m = await s.evaluate(MEASURE)
        // 判据：按钮全命中；或（窄屏 + 抽屉已展开）列表被遮罩有意覆盖。
        // 后者必须同时满足「有遮罩」与「有收起入口」，否则就是关不掉的覆盖层。
        const coveredByDesign = m.drawer === true && width <= 1400
        const ok = m.allHit === true || coveredByDesign
        const verdict = m.allHit === true ? '' : coveredByDesign ? '（窄屏抽屉：列表被遮罩有意覆盖）' : ''
        if (!ok) fails += 1
        rows.push({ state: state.label, width, ...m, ok, verdict })
        if (SELF_TEST && state.key === 'selected' && width === 1440) {
          await injectOverlay(s)
          await sleep(150)
          const blocked = await s.evaluate(MEASURE)
          await removeOverlay(s)
          if (blocked.allHit === true) {
            console.error('[set-row-actions][self-test][FAIL] 注入覆盖层后仍全命中，判据失效')
            process.exit(1)
          }
          console.log('[set-row-actions][self-test][PASS] 注入覆盖层后行内按钮判不可命中')
        }
      }
    }

    if (SELF_TEST) {
      process.exit(fails > 0 ? 1 : 0)
    }

    if (JSON_OUT) {
      writeFileSync('docs/evidence/v5.21.0/set-row-actions.json', JSON.stringify({ projectId, rows }, null, 2))
      console.log('[set-row-actions] 明细写入 docs/evidence/v5.21.0/set-row-actions.json')
    }
    for (const r of rows) {
      const detail = r.buttons.map((b) => `${b.label}:${b.hit ? 'Y' : `N(${b.blocker})`}`).join(' ')
      log(`${r.ok ? 'PASS' : 'FAIL'}  ${String(r.width).padEnd(5)} ${r.state.padEnd(9)} ${detail}${r.verdict ?? ''}`)
    }
    log(`\n[set-row-actions] ${rows.length} 组，FAILS=${fails}`)
  } finally {
    await s.send('Emulation.clearDeviceMetricsOverride').catch(() => {})
    await closeSession(s)
  }
  process.exit(fails > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('[set-row-actions] 异常：', err)
  process.exit(1)
})
