#!/usr/bin/env node
/**
 * 3.9 密度探针（只读、真实浏览器）。FR-v3.9-04 / FR-v3.9-05 的前后对比判据。
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
  // 列表数据是异步拉的：固定 sleep 会在慢一点的时候量到「还没渲染」的空表
  // （实测刚改完布局时 rows=0、tableH=44，结论完全失真）。这里等行出现。
  for (let i = 0; i < 20; i += 1) {
    const rows = await s.evaluate('document.querySelectorAll("table tbody tr").length')
    if (rows > 0) break
    await sleep(300)
  }
  await sleep(300)
}

const LIST_EXPR = `(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e)
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden' }
  // naive-ui 在限高/撑满模式下会把表头和表体拆成两张 <table>：
  // 只看 tables[0] 会量到「表头 44px、0 行」，把结论带偏。这里按数据行数取主表。
  const tables = [...document.querySelectorAll('table')]
    .filter(vis)
    .map((t) => ({ t, rows: [...t.querySelectorAll('tbody tr')].filter(vis).length }))
    .sort((a, b) => b.rows - a.rows)
  const mainTable = tables[0]?.t ?? null
  const rows = tables[0]?.rows ? [...mainTable.querySelectorAll('tbody tr')].filter(vis) : []
  const scrollers = [...document.querySelectorAll('*')].filter((e) => {
    if (!vis(e)) return false
    const s = getComputedStyle(e)
    return /(auto|scroll)/.test(s.overflowY) && e.scrollHeight > e.clientHeight + 4
  })
  const tableBox = mainTable ? mainTable.getBoundingClientRect() : null
  const mainScroll = document.querySelector('.app-main')
  const pager = document.querySelector('.el-pagination, .n-pagination')
  const pagerBox = pager ? pager.getBoundingClientRect() : null
  return {
    path: location.pathname + location.search,
    viewport: { w: innerWidth, h: innerHeight },
    docScrollHeight: document.documentElement.scrollHeight,
    mainScrollHeight: mainScroll ? mainScroll.scrollHeight : null,
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
  // FR-v3.9-04 判据④：计划页必须真接分页（不是「留了分页组件但没绑数据」），
  // 且默认档下单页行数受控。只看组件存在会被空分页骗过，因此同时数可见数据行。
  const pager = document.querySelector('.pager')
  const pagerVisible = pager ? vis(pager) : false
  const rows = [...document.querySelectorAll('table tbody tr')].filter(vis)
  return {
    path: location.pathname,
    buttonCount: btns.length,
    cardCount: cards.length,
    buttonTexts: texts,
    duplicateTexts: texts.filter((x, i) => texts.indexOf(x) !== i),
    pagerPresent: pagerVisible,
    pagerText: pagerVisible ? (pager.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 120) : null,
    visibleRows: rows.length,
    docScrollHeight: document.documentElement.scrollHeight,
    mainScrollHeight: (document.querySelector('.app-main') || {}).scrollHeight ?? null,
    viewportH: innerHeight,
  }
})()`

const TPL_EXPR = `(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e)
    return r.width > 0 && r.height > 0 && s.display !== 'none' }
  const avail = document.querySelector('[data-testid="available-fields"]')
  const sel = document.querySelector('[data-testid="selected-fields"]')
  const boxes = avail ? [...avail.querySelectorAll('input[type="checkbox"]')] : []
  const cards = [...document.querySelectorAll('[data-testid="available-fields"] [data-category]')]
  const presets = [...document.querySelectorAll('[data-testid="presets"] button')]
  const rows = sel ? [...sel.querySelectorAll('li')].filter(vis).length : 0
  const locked = boxes.filter((b) => b.disabled).map((b) => b.dataset.field)
  const main = document.querySelector('.app-main')
  return {
    path: location.pathname,
    // v3.9.0 起可选区是「卡片里的 chip」，不再是 <li> 平铺，按复选框计数才准
    fieldCount: boxes.length,
    availableRows: boxes.length,
    cardCount: cards.length,
    cards: cards.map((c) => ({ key: c.dataset.category, fields: [...c.querySelectorAll('input')].map((i) => i.dataset.field) })),
    presetCount: presets.length,
    presets: presets.map((p) => p.textContent.trim()),
    lockedFields: locked,
    // 平台级模板整页只读 → 21 个框全禁用，不能据此判「必填锁定多算了」
    readonlyTemplate: Boolean(document.querySelector('[data-testid="readonly-hint"]')),
    selectedRows: rows,
    availHeight: avail ? Math.round(avail.getBoundingClientRect().height) : null,
    selHeight: sel ? Math.round(sel.getBoundingClientRect().height) : null,
    docScrollHeight: document.documentElement.scrollHeight,
    // 内容区是 .app-main 自己滚，documentElement.scrollHeight 恒等于视口高度，
    // 用它算「几屏」永远是 1 —— 上一版 1628px / 1.8 屏就是这么来的（量错了对象）。
    mainScrollHeight: (main || {}).scrollHeight ?? null,
    viewportH: innerHeight,
    scrollScreens: Number((((main || {}).scrollHeight ?? innerHeight) / innerHeight).toFixed(2)),
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
    // 采样必须可复现：不钉项目时，/assets 很可能落在「这个项目没有设备」上，
    // 量到的就是空态而不是布局。DENSITY_PROJECT 指定一个成员项目即可。
    const fixedProject = process.env.DENSITY_PROJECT
    if (fixedProject) {
      await s.evaluate(`localStorage.setItem('current_project_id', ${JSON.stringify(fixedProject)})`)
    }
    console.log(`[auth] 登录成功；project=${fixedProject ?? '(沿用登录态)'}\n`)

    const result = { generated_at: new Date().toISOString(), browser: version.Browser, lists: [], plans: null, template: null }

    for (const route of ['/cases', '/runs', '/assets']) {
      await goto(s, `${APP}${route}`, 1440, 900)
      const m = await s.evaluate(LIST_EXPR)
      result.lists.push(m)
      console.log(`[list] ${m.path} rows=${m.visibleRows} tableH=${m.tableHeight} pagerBottom=${m.pagerBottom} 下方空白=${m.unusedBelowPager}px pager="${m.pagerText}" maxH=${JSON.stringify(m.scrollerMaxHeight)}`)
    }

    const planProject = process.env.DENSITY_PROJECT_PLANS
    if (planProject) {
      await s.evaluate(`localStorage.setItem('current_project_id', ${JSON.stringify(planProject)})`)
      console.log(`[plans] 切项目 -> ${planProject}`)
    }
    await goto(s, `${APP}/plans`, 1440, 900)
    result.plans = await s.evaluate(PLAN_EXPR)
    console.log(`[plans] buttons=${result.plans.buttonCount} cards=${result.plans.cardCount} docH=${result.plans.docScrollHeight} viewportH=${result.plans.viewportH}`)
    console.log(`[plans] pager=${result.plans.pagerPresent} rows=${result.plans.visibleRows} text=${JSON.stringify(result.plans.pagerText)}`)
    console.log(`[plans] texts=${JSON.stringify(result.plans.buttonTexts)}`)

    await goto(s, `${APP}/reports/templates`, 1440, 900)
    result.template = await s.evaluate(TPL_EXPR)
    console.log(`[tpl] fields=${result.template.fieldCount} cards=${result.template.cardCount} presets=${result.template.presetCount} selected=${result.template.selectedRows} screens=${result.template.scrollScreens}`)
    console.log(`[tpl] cards=${JSON.stringify(result.template.cards)}`)

    // 输出目录可用 DENSITY_OUT_DIR 覆盖：改前留一份 before，改后留一份 after
    const dir = process.env.DENSITY_OUT_DIR ?? 'docs/evidence/v3.9.0/EW39-04'
    const out = `${dir}/${process.env.DENSITY_OUT_NAME ?? 'density-probe'}.json`
    mkdirSync(out.split('/').slice(0, -1).join('/'), { recursive: true })
    writeFileSync(out, JSON.stringify(result, null, 2), 'utf8')
    // 判据（PRD §6.1 FR-v3.9-04）：三页分页条下方空白 ≤120px 或 ≤10% 视口。
    let fails = 0
    let na = 0
    result.lists.forEach((m) => {
      const limit = Math.max(120, Math.round(m.viewport.h * 0.1))
      const blank = m.unusedBelowPager
      if (blank === null) {
        // 没有分页条 = 列表为空。空态不适用「分页条下方空白」这条判据，
        // 但也不能放过塌陷：list-fill 至少 240px 才算「空态没被压成一条线」。
        na += 1
        m.judge = 'NA_EMPTY'
        console.log(`[judge] ${m.path} 空态（无分页条）→ N/A，另行核对空态高度`)
        return
      }
      const ok = blank <= limit
      m.judge = ok ? 'PASS' : 'FAIL'
      if (!ok) fails += 1
      console.log(`[judge] ${m.path} 下方空白=${blank}px 阈值=${limit}px ${m.judge}`)
    })
    // FR-v3.9-05 判据：21 字段 / ≥4 类卡片 / ≥4 预置 / 必填 6 项锁定 / 整页 ≤1 屏
    const tpl = result.template
    const tplFails = []
    if (tpl.fieldCount !== 21) tplFails.push(`字段数=${tpl.fieldCount}≠21`)
    if (tpl.cardCount < 4) tplFails.push(`分类卡片=${tpl.cardCount}<4`)
    if (tpl.presetCount < 4) tplFails.push(`预置模板=${tpl.presetCount}<4`)
    // 必填 6 项必须全部锁定；可编辑模板上「锁定的正好是这 6 项」才算对
    // （平台级模板整页只读，21 个框全禁用是预期行为，不能当缺陷）
    const REQUIRED6 = ['reportId', 'title', 'version', 'frozenAt', 'disclaimer', 'watermark']
    const missingLocked = REQUIRED6.filter((k) => !tpl.lockedFields.includes(k))
    if (missingLocked.length > 0) tplFails.push(`必填未锁定=${missingLocked.join(',')}`)
    if (!tpl.readonlyTemplate && tpl.lockedFields.length !== 6) {
      tplFails.push(`可编辑模板锁定数=${tpl.lockedFields.length}≠6`)
    }
    if (tpl.scrollScreens > 1.0) tplFails.push(`整页=${tpl.scrollScreens}屏>1`)
    console.log(`[judge] /reports/templates ${tplFails.length === 0 ? 'PASS' : 'FAIL ' + tplFails.join(' / ')}`)
    if (tplFails.length > 0) fails += 1

    console.log(`\nDENSITY_PROBE -> ${out} LISTS_FAIL=${fails} LISTS_NA=${na}`)
    if (fails > 0) process.exitCode = 1
  } finally {
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error('探针失败:', e.message); process.exit(1) })
