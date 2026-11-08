#!/usr/bin/env node
/**
 * B4 第 13 步 · 浏览器回归（真实 Chrome + 统一入口 :8001）。
 *
 * 每个主路由在 1440 / 1280 两个视口下各断言 4 件事：
 *   1. 路由没有被守卫踢回 /login（`location.pathname` 等于目标路由）；
 *   2. 页面主容器 `.app-main` 存在，且没有 JS 异常（`Runtime.exceptionThrown`）；
 *   3. 1440 下内容区利用率 ≥ 78%（`.app-main` 宽度 / innerWidth）；
 *   4. 1280 下 `documentElement.scrollWidth ≤ innerWidth + 1`（无整页横滚）。
 *
 * 为什么进机器门：组件单测（jsdom）测不到真实栅格 / 滚动条 / 路由守卫，
 * 而上述四类缺陷都会让门禁全绿、页面却是错位或白屏。
 *
 * 用法：
 *   node scripts/browser-regression.mjs              # 正常回归，0 = 全 PASS
 *   node scripts/browser-regression.mjs --selfcheck  # 负向自检：注入横滚必须被判红
 *
 * 前置：Chrome --remote-debugging-port=9222；:8001 可用；夹具账号
 *       `QA_E2E_PASSWORD` 在 ~/.config/robot-autotest/env（或环境变量）。
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const ROUTES = (process.env.REGRESS_ROUTES ?? '/,/runs,/assets,/reports/templates,/nodes').split(',')
const VIEWPORTS = [1440, 1280]
/** 根路由 `/` 会被前端重定向到 `/overview`；其余路由要求原样命中。 */
const ROUTE_EXPECT = { '/': '/overview' }
const SELFCHECK = process.argv.includes('--selfcheck')
/**
 * B8：报告页的「对比 / 趋势」真实浏览器回归。
 * 组件单测只能证明表格/图表能渲染，证明不了「整页接上真实 run 数据后还能渲染」。
 * 给一个真实 runId 才跑；不给自己跳过（不判红），避免无数据的环境被误杀。
 */
const REPORT_RUN = process.env.REGRESS_REPORT_RUN ?? ''
/** 该 run 所属项目：报告接口一律按 projectId 取数，必须显式绑定，不能沿用 profile 残留值。 */
const REPORT_PROJECT = process.env.REGRESS_REPORT_PROJECT ?? ''
const SHOT_DIR = process.env.REGRESS_SHOT_DIR ?? ''

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    const text = readFileSync(`${homedir()}/.config/robot-autotest/env`, 'utf8')
    for (const line of text.split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch { /* 下面统一报缺 */ }
  return null
}

const PASS = envValue('QA_E2E_PASSWORD')
if (!PASS) {
  console.error('缺少夹具口令：~/.config/robot-autotest/env 里加 QA_E2E_PASSWORD=<口令>')
  process.exit(2)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

class Session {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.handlers = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) reject(new Error(`${msg.error.message} (${msg.error.code})`))
        else resolve(msg.result)
      } else if (msg.method) {
        const cb = this.handlers.get(msg.method)
        if (cb) cb(msg.params)
      }
    })
  }

  on(method, cb) { this.handlers.set(method, cb) }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP 超时: ${method}`))
        }
      }, 30000)
    })
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error(`页面脚本异常: ${r.exceptionDetails.text} ${r.exceptionDetails.exception?.description ?? ''}`)
    }
    return r.result.value
  }
}

async function openSession() {
  const created = await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })
  if (!created.ok) throw new Error(`新建标签页失败 HTTP ${created.status}（Chrome 是否带 --remote-debugging-port=9222？）`)
  const target = await created.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('CDP WebSocket 连接失败')), { once: true })
  })
  const session = new Session(ws)
  await session.send('Page.enable')
  await session.send('Runtime.enable')
  return { session, targetId: target.id }
}

const checks = []
function check(id, ok, detail) {
  checks.push({ id, ok: Boolean(ok), detail: detail ?? '' })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${detail ?? ''}`)
}

const measureExpr = `(() => {
  const main = document.querySelector('.app-main') || document.querySelector('main') || document.body
  const vw = window.innerWidth
  const rect = main.getBoundingClientRect()
  return {
    path: location.pathname,
    title: document.title,
    ready: document.readyState,
    hasMain: Boolean(document.querySelector('.app-main') || document.querySelector('main')),
    content: Math.round(rect.width),
    utilization: Number((rect.width / vw).toFixed(4)),
    scrollWidth: document.documentElement.scrollWidth,
    viewport: vw,
  }
})()`

async function goto(session, url, width, height = 900) {
  await session.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await session.send('Page.navigate', { url })
  await sleep(1100)
  for (let i = 0; i < 12; i += 1) {
    const ready = await session.evaluate('document.readyState')
    if (ready === 'complete') break
    await sleep(200)
  }
}

/**
 * B8 深检：真实 run 的报告页 —— 趋势图与「本轮 vs 基线」双列并置。
 *
 * 只断言「接上真实数据后整页仍能渲染、不抛异常、不横滚」，
 * 不对具体业务数值下判断（数值口径属于 RPT-03/04 的接口用例）。
 */
async function reportDeepCheck(session, exceptions) {
  const run = REPORT_RUN
  exceptions.length = 0
  // 报告详情/树/趋势/对比全按 auth.projectId 取数；auth 从 localStorage 的
  // current_project_id 初始化。CDP profile 是复用的，上一次夹具可能留下别的项目号，
  // 那样页面会显示「运行不存在: <runId>」，趋势/对比三项随之假红。
  // 显式绑定后由 goto() 触发整页重载，Pinia store 才会读到新值。
  if (REPORT_PROJECT) {
    await session.evaluate(`localStorage.setItem('current_project_id', ${JSON.stringify(REPORT_PROJECT)})`)
    console.log(`[report] 会话绑定 projectId=${REPORT_PROJECT}`)
  }
  await goto(session, `${APP}/reports/${encodeURIComponent(run)}`, 1440)
  for (let i = 0; i < 20; i += 1) {
    if (await session.evaluate(`document.body.innerText.includes('趋势')`)) break
    await sleep(300)
  }

  const m = await session.evaluate(measureExpr)
  check('report-route', m.path === `/reports/${run}` && m.path !== '/login', `path=${m.path}`)
  check('report-main', m.hasMain, `content=${m.content}px`)
  check('report-no-hscroll', m.scrollWidth <= m.viewport + 1,
    `scrollWidth=${m.scrollWidth} viewport=${m.viewport}`)
  // 显式断言「报告实体确实加载了」：只判趋势/对比会把 404 空页误读成渲染缺陷。
  const notFound = await session.evaluate(`(() => {
    const text = document.body.innerText
    return text.includes('运行不存在') || text.includes('报告不存在')
  })()`)
  check('report-no-not-found', !notFound,
    notFound ? '报告页是 NOT_FOUND 错误态（检查 run 与 projectId 是否匹配）' : 'detail/tree 已加载')

  const trend = await session.evaluate(`(() => {
    const text = document.body.innerText
    return {
      hasTitle: text.includes('趋势'),
      empty: text.includes('无历史数据'),
      marks: document.querySelectorAll('svg path, svg rect, svg line, svg circle').length,
      svgs: document.querySelectorAll('svg').length,
    }
  })()`)
  check('report-trend-rendered', trend.hasTitle && (trend.empty || trend.marks > 0), JSON.stringify(trend))

  const toggled = await session.evaluate(`(() => {
    const hit = [...document.querySelectorAll('label, .n-checkbox, button, span')]
      .find((n) => n.textContent && n.textContent.trim().includes('本轮 vs 基线'))
    if (!hit) return false
    hit.click()
    return true
  })()`)
  await sleep(1000)
  const compare = await session.evaluate(`(() => {
    const text = document.body.innerText
    return {
      title: text.includes('本轮 vs 基线（双列并置）'),
      noBaseline: text.includes('未找到同项目同环境的基线运行'),
      rows: document.querySelectorAll('.app-main table tbody tr').length,
    }
  })()`)
  check('report-compare-toggle', toggled, toggled ? 'clicked' : '未找到「本轮 vs 基线」开关')
  check('report-compare-rendered', compare.title && (compare.noBaseline || compare.rows > 0),
    JSON.stringify(compare))

  if (SHOT_DIR) {
    mkdirSync(SHOT_DIR, { recursive: true })
    const shot = await session.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
    const file = `${SHOT_DIR}/report-${run}.png`
    writeFileSync(file, Buffer.from(shot.data, 'base64'))
    console.log(`[shot] ${file}`)
  }

  check('report-no-js-error', exceptions.length === 0,
    exceptions.length ? exceptions[0].slice(0, 80) : '0 exception')
}

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}`)
  console.log(`[app] ${APP}  routes=${ROUTES.join(',')}  mode=${SELFCHECK ? 'selfcheck' : 'regress'}\n`)

  const { session } = await openSession()
  const exceptions = []
  session.on('Runtime.exceptionThrown', (p) => {
    exceptions.push(p.exceptionDetails?.exception?.description ?? p.exceptionDetails?.text ?? 'unknown')
  })

  await goto(session, `${APP}/login`, 1440)
  const login = await session.evaluate(`(async () => {
    const resp = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(PASS)} }),
    })
    const body = await resp.json()
    if (!resp.ok || !body.data || !body.data.token) return { ok: false, status: resp.status, body }
    localStorage.setItem('auth_token', body.data.token)
    localStorage.setItem('auth_expires_at', body.data.expiresAt ?? '')
    return { ok: true }
  })()`)
  if (!login?.ok) throw new Error(`登录失败: ${JSON.stringify(login).slice(0, 160)}`)
  console.log('[auth] 登录成功\n')

  if (SELFCHECK) {
    // 负向自检：在真实页面上强行制造横向溢出，断言检测器能把它判红。
    const route = ROUTES[0]
    await goto(session, `${APP}${route}`, 1280)
    await session.evaluate(`(() => { const d = document.createElement('div'); d.style.cssText = 'width:1600px;height:1px'; document.body.appendChild(d); })()`)
    const m = await session.evaluate(measureExpr)
    const detected = m.scrollWidth > m.viewport + 1
    console.log(`[selfcheck] 注入横滚后 scrollWidth=${m.scrollWidth} viewport=${m.viewport} -> ${detected ? '已判红（符合预期）' : '未判红（检测器失灵）'}`)
    console.log(`SELFCHECK_13 ${detected ? 'PASS' : 'FAIL'}`)
    process.exit(detected ? 0 : 1)
  }

  for (const route of ROUTES) {
    exceptions.length = 0
    for (const width of VIEWPORTS) {
      await goto(session, `${APP}${route}`, width)
      const m = await session.evaluate(measureExpr)
      const id = `${route}@${width}`
      check(`${id}-route`, m.path === (ROUTE_EXPECT[route] ?? route) && m.path !== '/login', `path=${m.path}`)
      check(`${id}-main`, m.hasMain, `content=${m.content}px`)
      if (width === 1440) {
        check(`${id}-utilization`, m.utilization >= 0.78, `utilization=${(m.utilization * 100).toFixed(1)}%`)
      }
      if (width === 1280) {
        check(`${id}-no-hscroll`, m.scrollWidth <= m.viewport + 1, `scrollWidth=${m.scrollWidth} viewport=${m.viewport}`)
      }
      check(`${id}-no-js-error`, exceptions.length === 0, exceptions.length ? exceptions[0].slice(0, 80) : '0 exception')
    }
  }

  if (REPORT_RUN) {
    console.log(`\n[report] 深检对比/趋势：${REPORT_RUN}`)
    await reportDeepCheck(session, exceptions)
  } else {
    console.log('\n[report] 跳过对比/趋势深检（未设置 REGRESS_REPORT_RUN）')
  }

  const fails = checks.filter((c) => !c.ok)
  console.log(`\nBROWSER_REGRESSION checks=${checks.length} fails=${fails.length}`)
  process.exit(fails.length ? 1 : 0)
}

main().catch((err) => {
  console.error(`FAIL 浏览器回归无法完成: ${err.message}`)
  process.exit(1)
})
