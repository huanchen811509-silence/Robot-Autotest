#!/usr/bin/env node
/**
 * 受限路由直达 × 角色（v5.21.0 / FR-v5.21-03 判据 4/5/6 / W71-06）。
 *
 * 判据：viewer 直接输 URL 进 `/execution-plans`、`/runs`、`/g/iam/users` 必须落到 403 页，
 * 且 403 页文案含**角色名**、有「返回工作台」按钮；admin 同样三条必须正常打开。
 *
 * 为什么单独一条探针：路由守卫与导航渲染是两处代码，菜单藏了但路由能进，是这类
 * 系统最常见的越权形态——"看不见但打得开"。
 *
 * 用法：
 *   node scripts/ux-probes/role-routes.mjs
 *   node scripts/ux-probes/role-routes.mjs --self-test   # 移除 403 页的角色行，必须判红
 */
import { writeFileSync } from 'node:fs'
import { APP, assertCdp, closeSession, goto, login, log, openSession, sleep } from './cdp.mjs'

const SELF_TEST = process.argv.includes('--self-test')
const JSON_OUT = process.argv.includes('--json')
const FIXTURE_PASS = process.env.QA_E2E_PASSWORD
const ADMIN_PASS = process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026'

const ACCOUNTS = [
  { role: 'viewer', user: 'qa-e2e-viewer', pass: FIXTURE_PASS, expect403: true },
  { role: 'admin', user: 'admin', pass: ADMIN_PASS, expect403: false },
]

const ROUTES = [
  { path: '/execution-plans', label: '执行计划' },
  { path: '/runs', label: '运行记录' },
  { path: '/g/iam/users', label: '用户管理' },
]

const STATE_EXPR = `(() => {
  const page = document.querySelector('[data-testid="forbidden-page"]')
  const role = document.querySelector('[data-testid="forbidden-role"]')
  const back = [...document.querySelectorAll('button')].some((b) => /工作台|workspace/i.test(b.textContent || ''))
  return {
    path: location.pathname,
    forbidden: Boolean(page),
    roleText: role ? role.textContent.trim() : '',
    hasBack: back,
    renderedText: (document.body.innerText || '').slice(0, 120).replace(/\\s+/g, ' '),
  }
})()`

async function main() {
  if (!FIXTURE_PASS) {
    console.error('[role-routes] 缺少 QA_E2E_PASSWORD')
    process.exit(2)
  }
  await assertCdp()
  const s = await openSession({ width: 1440, height: 900 })
  const rows = []
  let fails = 0
  try {
    for (const account of ACCOUNTS) {
      if (!(await login(s, { user: account.user, pass: account.pass }))) {
        console.error(`[role-routes] 登录失败：${account.user}`)
        process.exit(2)
      }
      for (const route of ROUTES) {
        await goto(s, `${APP}${route.path}`, 1600)
        await sleep(400)
        const state = await s.evaluate(STATE_EXPR)
        const landed = state.forbidden || state.path === '/403'
        const ok = account.expect403
          ? landed && Boolean(state.roleText) && state.hasBack
          : !landed
        if (!ok) fails += 1
        rows.push({ role: account.role, route: route.label, path: route.path, landed, ...state, ok })
      }
    }

    if (SELF_TEST) {
      await login(s, { user: 'qa-e2e-viewer', pass: FIXTURE_PASS })
      await goto(s, `${APP}/execution-plans`, 1600)
      const removed = await s.evaluate(`(() => {
        const el = document.querySelector('[data-testid="forbidden-role"]')
        if (!el) return false
        el.remove()
        return true
      })()`)
      const after = await s.evaluate(STATE_EXPR)
      if (!removed) {
        console.error('[role-routes][self-test][FAIL] 403 页上找不到角色行，无法做负向注入')
        process.exit(1)
      }
      if (after.roleText) {
        console.error('[role-routes][self-test][FAIL] 移除角色行后仍读到角色名，判据失效')
        process.exit(1)
      }
      console.log('[role-routes][self-test][PASS] 移除 403 页角色行后判红（roleText 为空）')
      process.exit(fails > 0 ? 1 : 0)
    }

    if (JSON_OUT) {
      writeFileSync('docs/evidence/v5.21.0/role-routes.json', JSON.stringify({ rows }, null, 2))
      console.log('[role-routes] 明细写入 docs/evidence/v5.21.0/role-routes.json')
    }
    for (const row of rows) {
      log(`${row.ok ? 'PASS' : 'FAIL'}  ${row.role.padEnd(7)} ${row.route.padEnd(6)} -> ${row.path} 403=${row.forbidden} 角色="${row.roleText}" 返回按钮=${row.hasBack}`)
    }
    log(`\n[role-routes] ${rows.length} 组，FAILS=${fails}`)
  } finally {
    await closeSession(s)
  }
  process.exit(fails > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('[role-routes] 异常：', err)
  process.exit(1)
})
