#!/usr/bin/env node
/**
 * 3.9 前置调研 · 暗色模式下拉可读性探针（只读、真实浏览器）。
 *
 * 复用门禁 13 步的 CDP 通道（:9222）与夹具登录，不引入新依赖。
 * 采集：每个 <select> 的 computed backgroundColor / color / colorScheme / appearance，
 * 以及首个 <option> 的 computed 颜色；对「背景透明」或「前景/背景对比不足」判红。
 *
 * 用法：
 *   node scripts/dark-select-probe.mjs                # 采集 + 判定，打印 JSON
 *   node scripts/dark-select-probe.mjs --selfcheck    # 注入一个白底浅字 select，判据必红
 */
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
// 默认覆盖「有下拉控件」的全部一级落点，而不是只看 4 个页：
// 少了这些路由，Q1 修复后仍可能漏掉某个业务页的 select。
const ROUTES = (process.env.DARK_PROBE_ROUTES
  ?? '/,/settings,/plans,/runs,/assets,/cases,/reports,/test-reports,/admin,/imports,/plans/groups,/runs/failure-clusters').split(',')
const SELFCHECK = process.argv.includes('--selfcheck')
// 主题可切换：亮色回归必须能自动跑（PRD FR-v3.9-01 判据 ⑤「亮色主题不回归」）。
// PROBE_THEME=light 时输出 light-select-probe.json，判据不变（不透明底 + 对比 ≥4.5）。
const THEME = process.env.PROBE_THEME ?? 'dark'

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

/** 偏好备份：探针只读改进，跑完必须还原夹具账号的主题偏好。 */
let cdpPrefBackup = null

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

class Session {
  constructor(ws) {
    this.ws = ws; this.id = 0
    this.pending = new Map(); this.handlers = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id)
        msg.error ? reject(new Error(`${msg.error.message} (${msg.error.code})`)) : resolve(msg.result)
      } else if (msg.method) { const cb = this.handlers.get(msg.method); if (cb) cb(msg.params) }
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
  if (!created.ok) throw new Error(`新建标签页失败 HTTP ${created.status}`)
  const target = await created.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('CDP WebSocket 连接失败')), { once: true })
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
  await sleep(500)
}

/** 解析 rgb()/rgba() 为 [r,g,b,a]；transparent / 空串 → a=0。 */
const PARSE = `(c) => {
  if (!c) return [0,0,0,0]
  if (c === 'transparent') return [0,0,0,0]
  const m = c.match(/rgba?\\(([^)]+)\\)/)
  if (!m) return [0,0,0,0]
  const p = m[1].split(',').map((x) => parseFloat(x.trim()))
  return [p[0]||0, p[1]||0, p[2]||0, p.length > 3 ? p[3] : 1]
}`

const SELECT_EXPR = `(() => {
  const parse = ${PARSE}
  const lum = ([r,g,b]) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4) }
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
  }
  const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b); const hi = Math.max(l1,l2), lo = Math.min(l1,l2); return (hi + 0.05) / (lo + 0.05) }
  const root = getComputedStyle(document.documentElement)
  const rootBg = parse(root.backgroundColor)
  const items = [...document.querySelectorAll('select')].map((el) => {
    const s = getComputedStyle(el)
    const bg = parse(s.backgroundColor)
    const fg = parse(s.color)
    const opaque = bg[3] > 0.95
    // 背景透明时按「生效背景 = 最近不透明祖先」估算对比
    let eff = bg.slice()
    if (!opaque) {
      let p = el.parentElement
      while (p && p !== document.documentElement) {
        const c = parse(getComputedStyle(p).backgroundColor)
        if (c[3] > 0.95) { eff = c; break }
        p = p.parentElement
      }
      if (!(eff[3] > 0.95)) eff = rootBg
    }
    const opt = el.querySelector('option')
    const os = opt ? getComputedStyle(opt) : null
    // option 的弹层底色取自身 computed 值；未显式设置时 Chromium 会回落到 UA 默认（白底）。
    // 只有 alpha > 0.95 才算「显式给了不透明底」——半透明仍会被 UA 白底透出来。
    const obg = os ? parse(os.backgroundColor) : [0, 0, 0, 0]
    const ofg = os ? parse(os.color) : [0, 0, 0, 0]
    return {
      id: el.id || '', name: el.getAttribute('name') || '', cls: el.className || '',
      options: el.options.length,
      bg: s.backgroundColor, fg: s.color,
      bgTransparent: !opaque,
      effectiveBg: 'rgb(' + eff.slice(0,3).map(Math.round).join(',') + ')',
      colorScheme: s.colorScheme,
      appearance: s.appearance,
      contrast: Number(((eff[3] > 0.95) ? ratio(fg, eff) : 0).toFixed(2)),
      optionBg: os ? os.backgroundColor : null,
      optionFg: os ? os.color : null,
      optionBgTransparent: os ? !(obg[3] > 0.95) : false,
      optionContrast: os && obg[3] > 0.95 ? Number(ratio(ofg, obg).toFixed(2)) : 0,
    }
  })
  return { path: location.pathname + location.search, theme: document.documentElement.getAttribute('data-theme'),
    rootColorScheme: root.colorScheme, rootBg: root.backgroundColor, selects: items }
})()`

function judge(sel) {
  const flags = []
  if (sel.bgTransparent) flags.push('背景透明')
  if (sel.contrast > 0 && sel.contrast < 4.5) flags.push(`对比 ${sel.contrast}:1`)
  // 弹层（option）单独判一次：select 底色修好了，option 仍可能落回 UA 白底。
  if (sel.optionBgTransparent) flags.push('option 背景透明')
  if (sel.optionContrast > 0 && sel.optionContrast < 4.5) flags.push(`option 对比 ${sel.optionContrast}:1`)
  return flags
}

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}  [app] ${APP}  theme=${THEME}  routes=${ROUTES.join(',')}\n`)
  const { session: s, targetId } = await openSession()
  try {
    await goto(s, `${APP}/login`, 1440)
    await s.evaluate(`localStorage.setItem('theme', ${JSON.stringify(THEME)})`)
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
    // 服务端偏好是权威（登录后覆盖 localStorage），必须写偏好才能稳定暗色。
    // 探针是**只读改进**：先备份原值，跑完在 finally 里还原，不改夹具账号的持久状态。
    const prefBefore = await s.evaluate(`(async () => {
      const r = await fetch('/api/users/me/preferences', { headers: { Authorization: 'Bearer ' + localStorage.getItem('auth_token') } })
      const b = await r.json(); return (b && b.data) || {}
    })()`)
    const prefPut = await s.evaluate(`(async () => {
      const r = await fetch('/api/users/me/preferences', { method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + localStorage.getItem('auth_token') },
        body: JSON.stringify({ theme: ${JSON.stringify(THEME)} }) })
      return r.ok
    })()`)
    if (!prefPut) throw new Error(`写入 theme=${THEME} 偏好失败`)
    cdpPrefBackup = prefBefore
    await s.evaluate(`localStorage.setItem('theme', ${JSON.stringify(THEME)})`)
    console.log(`[auth] 登录成功；已写偏好 theme=${THEME}（原 theme=${prefBefore.theme ?? 'unset'}，跑完还原）\n`)

    if (SELFCHECK) {
      await goto(s, `${APP}/`, 1440)
      await s.evaluate(`(() => {
        const sel = document.createElement('select')
        sel.id = 'dark-select-probe-negative'
        sel.style.cssText = 'background:#ffffff;color:#9aa7b4;position:fixed;top:0;left:0;z-index:99999'
        const o = document.createElement('option'); o.textContent = 'x'; sel.appendChild(o)
        document.body.appendChild(sel)
      })()`)
      const m = await s.evaluate(SELECT_EXPR)
      const bad = m.selects.find((x) => x.id === 'dark-select-probe-negative')
      const red = Boolean(bad) && judge(bad).length > 0
      console.log(`[selfcheck] 注入白底浅字 select -> bg=${bad?.bg} fg=${bad?.fg} contrast=${bad?.contrast} 判红=${red}`)
      await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
      console.log(`DARK_SELECT_SELFCHECK ${red ? 'PASS' : 'FAIL'}`)
      process.exit(red ? 0 : 1)
    }

    const pages = []
    let totalSelects = 0
    let flagged = 0
    for (const route of ROUTES) {
      await goto(s, `${APP}${route}`, 1440)
      const m = await s.evaluate(SELECT_EXPR)
      for (const sel of m.selects) {
        totalSelects += 1
        const flags = judge(sel)
        if (flags.length) flagged += 1
        sel.flags = flags
        sel.red = flags.length > 0
      }
      pages.push(m)
      console.log(`[page] ${m.path} theme=${m.theme} colorScheme=${m.rootColorScheme} selects=${m.selects.length}`)
      for (const sel of m.selects) {
        console.log(`   - #${sel.id || '(no-id)'} .${String(sel.cls).slice(0, 28)} bg=${sel.bg} fg=${sel.fg} scheme=${sel.colorScheme} contrast=${sel.contrast} option=${sel.optionBg}/${sel.optionContrast} ${sel.red ? '[RED] ' + sel.flags.join(' / ') : '[ok]'}`)
      }
    }

    const result = {
      generated_at: new Date().toISOString(),
      browser: version.Browser,
      method: 'CDP computed style 采样（暗色主题强制 theme=dark）',
      routes: ROUTES,
      summary: { pages: pages.length, selects: totalSelects, flagged },
      pages,
    }
    const out = THEME === 'dark'
      ? 'docs/evidence/v3.9.0/EW39-01/dark-select-probe.json'
      : `docs/evidence/v3.9.0/EW39-01/${THEME}-select-probe.json`
    const { mkdirSync, writeFileSync } = await import('node:fs')
    mkdirSync(out.split('/').slice(0, -1).join('/'), { recursive: true })
    writeFileSync(out, JSON.stringify(result, null, 2), 'utf8')
    console.log(`\nDARK_SELECT_PROBE selects=${totalSelects} flagged=${flagged} -> ${out}`)
    // 判红即非 0 退出：否则接进门禁也只是打印一行，红不红没人管。
    if (flagged > 0) process.exitCode = 1
  } finally {
    if (cdpPrefBackup) {
      const restore = cdpPrefBackup.theme ?? 'system'
      try {
        await s.evaluate(`(async () => {
          await fetch('/api/users/me/preferences', { method: 'PUT',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + localStorage.getItem('auth_token') },
            body: JSON.stringify({ theme: ${JSON.stringify(restore)} }) })
          localStorage.setItem('theme', ${JSON.stringify(restore)})
        })()`)
        console.log(`[restore] 已还原 theme=${restore}`)
      } catch (e) { console.error('[restore] 还原失败，需手工改回 theme=' + restore) }
    }
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error('探针失败:', e.message); process.exit(1) })
