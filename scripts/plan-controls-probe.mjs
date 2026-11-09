#!/usr/bin/env node
/**
 * 计划页控件计数探针（FR-v3.10-01 判据① ②）。
 *
 * 为什么要单独一支探针：`density-probe.mjs` 数的是「整页 button 数」，把侧栏导航、
 * 顶栏通知、分页器全算进去了——那个数字只能说明「很挤」，没法判「首屏主操作 ≤ 8」。
 * 判据要的是**页面级主操作**，因此这里按区域排除：
 *   ① 全局导航（.app-side / .app-header）：不属于本页
 *   ② 表格行内（.n-data-table-tbody）：判据②单独约束「行内 1 个」
 *   ③ 分页器（.n-pagination）：翻页不是主操作
 * 另外单独数「每行控件数」与「行内菜单项数」，证明动作只是收进菜单、没有消失。
 *
 * 用法：
 *   node scripts/plan-controls-probe.mjs                       # 采集 + 判定
 *   PLAN_OUT=xxx.json node scripts/plan-controls-probe.mjs     # 指定输出名
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const OUT = process.env.PLAN_OUT ?? 'docs/evidence/v3.10.0/EW310-01/controls-after.json'
const LIMIT = Number(process.env.PLAN_PRIMARY_LIMIT ?? 8)
/* 用哪个项目取数：优先环境变量。默认 29 —— 本库计划最多的项目（55 条），
   演示项目 1 号只有 1 条、其余项目 0 条，「行内只有一个更多」这条判据
   在空列表上根本无从验证（0 行 / 0 控件，看着像通过）。 */
const PROJECT_ID = process.env.PLAN_PROJECT_ID ?? '29'
/* 行内菜单这条判据需要至少 1 行数据；没有数据时记 N/A 而不是判失败，
   但要把「为什么没验」写进结果，别让空列表冒充通过。 */
const ROW_MENU_MIN_ITEMS = 4

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    for (const line of readFileSync(`${homedir()}/.config/robot-autotest/env`, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch {
    /* 没有配置文件就靠环境变量 */
  }
  return ''
}

async function http(path, options = {}) {
  const response = await fetch(`${CDP}${path}`, options)
  if (!response.ok) throw new Error(`${path} -> ${response.status}`)
  return response.json()
}

class Session {
  constructor(url) {
    this.ws = new WebSocket(url)
    this.seq = 0
    this.pending = new Map()
    this.ws.addEventListener('message', (event) => {
      const data = JSON.parse(event.data)
      const waiter = this.pending.get(data.id)
      if (!waiter) return
      this.pending.delete(data.id)
      data.error ? waiter.reject(new Error(data.error.message)) : waiter.resolve(data.result)
    })
  }

  ready() {
    return new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true })
      this.ws.addEventListener('error', reject, { once: true })
    })
  }

  send(method, params = {}) {
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }

  close() {
    this.ws.close()
  }
}

/** 页面侧采集：主操作、行内控件、菜单项、分区可见性。 */
const EXPR = `(() => {
  const vis = (el) => {
    if (!el) return false
    const r = el.getBoundingClientRect()
    const s = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden'
  }
  const label = (el) => (el.textContent || '').replace(/\\s+/g, ' ').trim()
  const all = [...document.querySelectorAll('button, [role="button"]')].filter(vis)
  const primary = all.filter((el) => {
    if (el.closest('.n-data-table-tbody')) return false
    if (el.closest('.n-pagination')) return false
    if (el.closest('.app-side') || el.closest('.app-header')) return false
    return true
  })
  const rowEls = [...document.querySelectorAll('.n-data-table-tbody .n-data-table-tr')].filter(vis)
  const rows = rowEls.map((tr) =>
    [...tr.querySelectorAll('button, [role="button"]')].filter(vis).map(label),
  )
  return {
    path: location.pathname,
    viewport: { w: innerWidth, h: innerHeight },
    totalControls: all.length,
    primaryCount: primary.length,
    primaryLabels: primary.map(label),
    rowCount: rows.length,
    rowControls: rows,
    conflictCollapsed: !vis(document.querySelector('[data-testid="conflict-toggle"]')?.closest('.conflict-card')?.querySelector('.conflict-list')),
    skippedCollapsed: !vis(document.querySelector('.skipped-table')),
    hasToolbarMore: Boolean(document.querySelector('[data-testid="plan-toolbar-more"]')),
    screens: Number(((document.querySelector('.app-main')?.scrollHeight ?? 0) / innerHeight).toFixed(2)),
  }
})()`

/** 打开某一行的「更多」菜单，返回菜单项文案（证明动作只是收进去，没丢）。 */
const MENU_EXPR = `(async () => {
  const trigger = document.querySelector('.n-data-table-tbody .more-menu')
  if (!trigger) return { items: [], error: 'no row menu trigger' }
  trigger.click()
  await new Promise((r) => setTimeout(r, 300))
  const menus = [...document.querySelectorAll('.n-dropdown')]
  const last = menus[menus.length - 1]
  const items = last
    ? [...last.querySelectorAll('.n-dropdown-option')].map((el) => (el.textContent || '').trim())
    : []
  document.body.click()
  return { items }
})()`

/**
 * 登录在**页面里**做，不在 node 里做。
 *
 * 原因：token 要写进页面所在的 origin 的 localStorage，node 侧 fetch 拿到的 token
 * 还得再想办法塞进去；而且响应体是 `{ data: { token } }` 包一层，直接取 `.token`
 * 会拿到 undefined —— 第一次跑就是这样「登录成功但页面还是登录页」。
 */
async function loginInPage(session, password) {
  await session.send('Page.navigate', { url: `${APP}/login` })
  await new Promise((r) => setTimeout(r, 1500))
  const result = await session.evaluate(`(async () => {
    const resp = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(password)} }),
    })
    if (!resp.ok) return { ok: false, status: resp.status }
    const body = await resp.json()
    const token = body?.data?.token ?? body?.token
    if (!token) return { ok: false, status: resp.status, shape: Object.keys(body ?? {}) }
    localStorage.setItem('auth_token', token)
    localStorage.setItem('auth_expires_at', body?.data?.expiresAt ?? '')
    localStorage.setItem('current_project_id', ${JSON.stringify(PROJECT_ID)})
    return { ok: true }
  })()`)
  if (!result?.ok) throw new Error(`登录失败: ${JSON.stringify(result)}`)
}

async function main() {
  const password = envValue('QA_E2E_PASSWORD')
  if (!password) throw new Error('缺 QA_E2E_PASSWORD（~/.config/robot-autotest/env）')
  const target = await http('/json/new?about:blank', { method: 'PUT' })
  const session = new Session(target.webSocketDebuggerUrl)
  await session.ready()
  try {
    await session.send('Page.enable')
    await session.send('Runtime.enable')
    await loginInPage(session, password)
    await session.send('Page.navigate', { url: `${APP}/plans` })
    await new Promise((r) => setTimeout(r, 2500))

    const page = await session.evaluate(EXPR)
    const menu = page.rowCount > 0 ? await session.evaluate(MENU_EXPR) : { items: [], skipped: true }

    const fails = []
    if (page.primaryCount > LIMIT) {
      fails.push(`首屏主操作 ${page.primaryCount} > ${LIMIT}`)
    }
    if (page.rowCount > 0 && page.rowControls.some((list) => list.length > 1)) {
      fails.push('存在行内控件 > 1 的行')
    }
    if (!page.hasToolbarMore) fails.push('工具条缺「更多」入口')
    if (!page.conflictCollapsed) fails.push('冲突区未默认收起')
    if (!page.skippedCollapsed) fails.push('跳过事件区未默认收起')
    const rowMenuChecked = page.rowCount > 0
    if (rowMenuChecked && menu.items.length < ROW_MENU_MIN_ITEMS) {
      fails.push(`行内菜单项 ${menu.items.length} < ${ROW_MENU_MIN_ITEMS}（动作可能丢了）`)
    }

    const result = {
      generated_at: new Date().toISOString(),
      method: 'CDP：按区域排除导航/行内/分页后计页面级主操作',
      limit: LIMIT,
      projectId: PROJECT_ID,
      page,
      rowMenuChecked,
      rowMenuItems: menu.items,
      fails,
      verdict: fails.length === 0 ? 'PASS' : 'FAIL',
    }

    mkdirSync(OUT.split('/').slice(0, -1).join('/'), { recursive: true })
    writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8')

    console.log(
      `[plans] 总控件=${page.totalControls} 主操作=${page.primaryCount} 行=${page.rowCount} ` +
        `行内=${JSON.stringify(page.rowControls)} screens=${page.screens}`,
    )
    console.log(`[plans] 主操作文案=${JSON.stringify(page.primaryLabels)}`)
    console.log(`[plans] 行内菜单=${JSON.stringify(menu.items)}`)
    console.log(`[plans] 冲突收起=${page.conflictCollapsed} 跳过收起=${page.skippedCollapsed} -> ${OUT}`)
    console.log(`PLAN_CONTROLS ${result.verdict} FAILS=${fails.length}`)
    for (const fail of fails) console.log(`  - ${fail}`)
    if (fails.length > 0) process.exitCode = 1
  } finally {
    session.close()
  }
}

main().catch((error) => {
  console.error(`[plans] 失败：${error.message}`)
  process.exit(1)
})
