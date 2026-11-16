#!/usr/bin/env node
/**
 * 写操作按钮 × 角色（v5.21.0 / FR-v5.21-03 判据 1/2/3 / W71-05）。
 *
 * 判据：viewer 在用例库 / 用例集页看到的**写按钮数必须为 0**；engineer 按矩阵可见；
 * admin 全可见。这里不靠截图肉眼看，而是数"可见的、文字像写动作的按钮"。
 *
 * 「可见」的定义：`offsetParent !== null` 且矩形有宽高。用 `display:none` 隐藏的
 * 元素矩形为 0，会被正确排除——这正是 `v-can` 指令的实现方式，两者口径一致。
 *
 * 用法：
 *   node scripts/ux-probes/role-buttons.mjs
 *   node scripts/ux-probes/role-buttons.mjs --self-test   # 注入一个可见写按钮，必须判红
 *   node scripts/ux-probes/role-buttons.mjs --json
 */
import { writeFileSync } from 'node:fs'
import { APP, assertCdp, closeSession, goto, login, log, openSession, selectProject, sleep } from './cdp.mjs'

const SELF_TEST = process.argv.includes('--self-test')
const JSON_OUT = process.argv.includes('--json')
const FIXTURE_PASS = process.env.QA_E2E_PASSWORD
const ADMIN_PASS = process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026'

const ACCOUNTS = [
  { role: 'admin', user: 'admin', pass: ADMIN_PASS, maxWrite: 99 },
  { role: 'engineer', user: 'qa-e2e-engineer', pass: FIXTURE_PASS, maxWrite: 99 },
  { role: 'viewer', user: 'qa-e2e-viewer', pass: FIXTURE_PASS, maxWrite: 0 },
]

const PAGES = [
  { path: '/cases', label: '用例库' },
  { path: '/cases/sets', label: '用例集' },
  { path: '/execution-plans', label: '执行计划' },
]

/** 写动作词表：只用来**数**，判定靠角色与数量，不靠"这几颗按钮一定在"。 */
const WRITE_WORDS = '新建|创建|导入|编辑|删除|提交|审核|通过|驳回|管理用例|立即执行|发起执行|保存|移除|上传'

const COUNT_EXPR = `(() => {
  const re = new RegExp(${JSON.stringify(WRITE_WORDS)})
  // 注意：**不禁用也算可见**。「有按钮但暂时禁用」与「按钮被权限藏掉」是两件事：
  // 前者是 v5.20 W70-02 要求"说明禁用原因"的正常态（如未选用例时的发起执行），
  // 后者才是本探针要抓的越权。把 disabled 排除掉会让对照组的按钮凭空消失。
  const visible = (el) => {
    if (!el) return false
    const r = el.getBoundingClientRect()
    return el.offsetParent !== null && r.width > 0 && r.height > 0
  }
  const items = [...document.querySelectorAll('button, a.btn, [role="button"]')]
    .filter((el) => visible(el) && re.test((el.textContent || '').trim()))
  return {
    count: items.length,
    labels: items.slice(0, 12).map((el) => (el.textContent || '').trim().slice(0, 12)),
    page: location.pathname,
  }
})()`

async function main() {
  if (!FIXTURE_PASS) {
    console.error('[role-buttons] 缺少 QA_E2E_PASSWORD')
    process.exit(2)
  }
  await assertCdp()
  const s = await openSession({ width: 1440, height: 900 })
  const rows = []
  let fails = 0
  try {
    for (const account of ACCOUNTS) {
      const ok = await login(s, { user: account.user, pass: account.pass })
      if (!ok) {
        console.error(`[role-buttons] 登录失败：${account.user}`)
        process.exit(2)
      }
      // 固定一个真实工程做上下文；无工程时页面走空态，写按钮同样是 0，判据不失效
      // 固定夹具工程做上下文：否则执行计划页/用例库都停在「请先选择项目」空态，
      // 数出来全是 0，admin 的对照组也失去意义。
      await selectProject(s, Number(process.env.QA_SEED_PROJECT ?? 29))
      for (const page of PAGES) {
        await goto(s, `${APP}${page.path}`, 1600)
        await sleep(400)
        const res = await s.evaluate(COUNT_EXPR)
        const bad = res.count > account.maxWrite
        if (bad) fails += 1
        rows.push({ role: account.role, page: page.label, path: page.path, count: res.count, labels: res.labels, ok: !bad })
      }
    }

    if (SELF_TEST) {
      // 注入一个可见的「新建」按钮：viewer 口径下必须判红（count > 0）
      await login(s, { user: 'qa-e2e-viewer', pass: FIXTURE_PASS })
      await goto(s, `${APP}/cases`, 1500)
      await s.evaluate(`(() => {
        const b = document.createElement('button')
        b.textContent = '新建用例'
        b.id = '__ux_probe_fake_write'
        document.body.appendChild(b)
        return 1
      })()`)
      const injected = await s.evaluate(COUNT_EXPR)
      await s.evaluate(`(() => { document.getElementById('__ux_probe_fake_write')?.remove(); return 1 })()`)
      if (injected.count === 0) {
        console.error('[role-buttons][self-test][FAIL] 注入可见写按钮后仍数到 0，计数逻辑失效')
        process.exit(1)
      }
      console.log(`[role-buttons][self-test][PASS] 注入后判红（viewer 用例库写按钮 ${injected.count} 个）`)
      process.exit(fails > 0 ? 1 : 0)
    }

    if (JSON_OUT) {
      writeFileSync('docs/evidence/v5.21.0/role-buttons.json', JSON.stringify({ rows }, null, 2))
      console.log('[role-buttons] 明细写入 docs/evidence/v5.21.0/role-buttons.json')
    }
    for (const row of rows) {
      log(`${row.ok ? 'PASS' : 'FAIL'}  ${row.role.padEnd(9)} ${row.page.padEnd(6)} 写按钮 ${String(row.count).padEnd(3)} ${row.labels.join(' / ')}`)
    }
    log(`\n[role-buttons] ${rows.length} 组，FAILS=${fails}`)
  } finally {
    await closeSession(s)
  }
  process.exit(fails > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('[role-buttons] 异常：', err)
  process.exit(1)
})
