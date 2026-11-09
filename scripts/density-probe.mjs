#!/usr/bin/env node
/**
 * 3.9 前置调研 · 列表/表单密度基线探针（只读、真实浏览器）。
 *
 * 度量三件事（都与「工作区没用满 / 排版太长 / 按钮过多」直接相关）：
 *   ① 列表页：表格可视高度 / 视口高度 / 每页条数 / 剩余空白 px
 *   ② 计划页：可见按钮与可点控件数量、卡片区块数
 *   ③ 报告模板页：字段行数、选中区行数、整页滚动高度
 *
 * 用法：node scripts/density-probe.mjs
 */
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    for (const line of readFileSync(`${homedir()}/.config/robot-autotest/env`, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch { /* 统一报缺 */ }
  return null
}
const PASS = envValue('QA_E2E_PASSWORD')
if (!PASS) { console.error('缺少夹具口令 QA_E2E_PASSWORD'); process.exit(2) }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

class Session {
  constructor(ws) {
    this.ws = ws; this.id = 0
    this.pending = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id)
        msg.error ? reject(new Error(`${msg.error.message} (${msg.error.code})`)) : resolve(msg.result)
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); reject(new Error(`CDP 超时: ${method}`)) } }, 30000)
    })
  }
  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw new Error(`页面脚本异常: ${r.exceptionDetails.text}`)
    return r.result.value
  }
}

async function openSession() {
  const created = await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })
  const target = await created.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', () => rej(new Error('CDP 连接失败')), { once: true })
  })
  const s = new Session(ws)
  await s.send('Page.enable'); await s.send('Runtime.enable')
  return { session: s, targetId: target.id }
}

async function goto(s, url, width = 1440, height = 900) {
  await s.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await s.send('Page.navigate', { url })
  await sleep(900)
  for (let i = 0; i < 15; i += 1) {
    if (await s.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
  await sleep(600)
}

const LIST_EXPR = `(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e)
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden' }
  const tables = [...document.querySelectorAll('table')].filter(vis)
  const rows = tables.length ? [...tables[0].querySelectorAll('tbody tr')].filter(vis) : []
  const scrollers = [...document.querySelectorAll('*')].filter((e) => {
    if (!vis(e)) return false
    const s = getComputedStyle(e)
    return /(auto|scroll)/.test(s.overflowY) && e.scrollHeight > e.clientHeight + 4
  })
  const tableBox = tables.length ? tables[0].getBoundingClientRect() : null
  const pager = document.querySelector('.el-pagination, .n-pagination')
  const pagerBox = pager ? pager.getBoundingClientRect() : null
  return {
    path: location.pathname + location.search,
    viewport: { w: innerWidth, h: innerHeight },
    docScrollHeight: document.documentElement.scrollHeight,
    tableCount: tables.length,
    visibleRows: rows.length,
    tableHeight: tableBox ? Math.round(tableBox.height) : null,
    tableBottom: tableBox ? Math.round(tableBox.bottom) : null,
    pagerBottom: pagerBox ? Math.round(pagerBox.bottom) : null,
    unusedBelowPager: pagerBox ? Math.max(0, Math.round(innerHeight - pagerBox.bottom)) : null,
    pagerText: pager ? (pager.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 60) : null,
    scrollerCount: scrollers.length,
    scrollerMaxHeight: scrollers.slice(0, 3).map((e) => getComputedStyle(e).maxHeight),
  }
})()`

const PLAN_EXPR = `(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e)
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden' }
  const btns = [...document.querySelectorAll('button, [role="button"], a.btn')].filter(vis)
  const texts = btns.map((b) => (b.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean)
  const cards = [...document.querySelectorAll('.card')].filter(vis)
  return {
    path: location.pathname,
    buttonCount: btns.length,
    cardCount: cards.length,
    buttonTexts: texts,
    duplicateTexts: texts.filter((x, i) => texts.indexOf(x) !== i),
    docScrollHeight: document.documentElement.scrollHeight,
    viewportH: innerHeight,
  }
})()`

const TPL_EXPR = `(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e)
    return r.width > 0 && r.height > 0 && s.display !== 'none' }
  const avail = document.querySelector('[data-testid="available-fields"]')
  const sel = document.querySelector('[data-testid="selected-fields"]')
  const rows = (el) => el ? [...el.querySelectorAll('li')].filter(vis).length : 0
  return {
    path: location.pathname,
    availableRows: rows(avail),
    selectedRows: rows(sel),
    availHeight: avail ? Math.round(avail.getBoundingClientRect().height) : null,
    selHeight: sel ? Math.round(sel.getBoundingClientRect().height) : null,
    docScrollHeight: document.documentElement.scrollHeight,
    viewportH: innerHeight,
    scrollScreens: Number((document.documentElement.scrollHeight / innerHeight).toFixed(2)),
  }
})()`

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}  [app] ${APP}\n`)
  const { session: s, targetId } = await openSession()
  try {
    await goto(s, `${APP}/login`, 1440)
    const login = await s.evaluate(`(async () => {
      const resp = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(PASS)} }) })
      const body = await resp.json()
      if (!resp.ok || !body.data || !body.data.token) return { ok: false, status: resp.status }
      localStorage.setItem('auth_token', body.data.token)
      localStorage.setItem('auth_expires_at', body.data.expiresAt ?? '')
      return { ok: true }
    })()`)
    if (!login?.ok) throw new Error(`登录失败: ${JSON.stringify(login)}`)
    console.log('[auth] 登录成功\n')

    const result = { generated_at: new Date().toISOString(), browser: version.Browser, lists: [], plans: null, template: null }

    for (const route of ['/cases', '/runs', '/assets']) {
      await goto(s, `${APP}${route}`, 1440, 900)
      const m = await s.evaluate(LIST_EXPR)
      result.lists.push(m)
      console.log(`[list] ${m.path} rows=${m.visibleRows} tableH=${m.tableHeight} pagerBottom=${m.pagerBottom} 下方空白=${m.unusedBelowPager}px pager="${m.pagerText}" maxH=${JSON.stringify(m.scrollerMaxHeight)}`)
    }

    await goto(s, `${APP}/plans`, 1440, 900)
    result.plans = await s.evaluate(PLAN_EXPR)
    console.log(`[plans] buttons=${result.plans.buttonCount} cards=${result.plans.cardCount} docH=${result.plans.docScrollHeight} viewportH=${result.plans.viewportH}`)
    console.log(`[plans] texts=${JSON.stringify(result.plans.buttonTexts)}`)

    await goto(s, `${APP}/report-templates`, 1440, 900)
    result.template = await s.evaluate(TPL_EXPR)
    console.log(`[tpl] availableRows=${result.template.availableRows} selectedRows=${result.template.selectedRows} docH=${result.template.docScrollHeight} screens=${result.template.scrollScreens}`)

    const out = 'docs/evidence/v3.9.0/EW39-02/density-probe.json'
    mkdirSync(out.split('/').slice(0, -1).join('/'), { recursive: true })
    writeFileSync(out, JSON.stringify(result, null, 2), 'utf8')
    console.log(`\nDENSITY_PROBE -> ${out}`)
  } finally {
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error('探针失败:', e.message); process.exit(1) })
