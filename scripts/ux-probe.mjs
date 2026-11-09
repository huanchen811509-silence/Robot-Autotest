#!/usr/bin/env node
/**
 * EW35-05 / EW35-06 · 真实浏览器体验与无障碍探针（v3.5.0 基线）。
 *
 * 复用门禁 13 步的 CDP 通道（:9222）与夹具登录，不引入 axe / Playwright 等新依赖，
 * 不重启大师已取消的后台任务（D6）。全部读数来自真实 Chrome Headless 157 的
 * PerformanceObserver / DOM 几何，属于**实验室采样**，不是真实用户 RUM；
 * 无交互样本时 INP 一律写「未测量」，绝不填 0。
 *
 * 采集项：
 *   CWV      LCP / CLS / INP（每路由 3 次导航取 p75，阈值 2.5s / 0.1 / 200ms）
 *   WCAG2.2  24×24 目标尺寸、焦点外观、键盘可达、200% 缩放无横滚、一致帮助入口
 *
 * 用法：
 *   node scripts/ux-probe.mjs               # 产出两份 JSON 证据
 *   node scripts/ux-probe.mjs --selfcheck   # 负向自检：注入 10×10 按钮，目标尺寸判据必红
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const ROUTES = (process.env.UX_PROBE_ROUTES ?? '/,/runs,/assets,/nodes').split(',')
const RUNS = Number(process.env.UX_PROBE_RUNS ?? 3)
const SELFCHECK = process.argv.includes('--selfcheck')
const OUT5 = 'docs/evidence/v3.5.0/EW35-05/wcag22-probe.json'
const OUT6 = 'docs/evidence/v3.5.0/EW35-06/cwv-probe.json'

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    for (const line of readFileSync(`${homedir()}/.config/robot-autotest/env`, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch { /* 下面统一报缺 */ }
  return null
}
const PASS = envValue('QA_E2E_PASSWORD')
if (!PASS) { console.error('缺少夹具口令 QA_E2E_PASSWORD'); process.exit(2) }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const p75 = (arr) => {
  const a = arr.filter((x) => typeof x === 'number' && Number.isFinite(x)).sort((x, y) => x - y)
  if (!a.length) return '未测量'
  return Number(a[Math.min(a.length - 1, Math.ceil(a.length * 0.75) - 1)].toFixed(4))
}

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
  on(m, cb) { this.handlers.set(m, cb) }
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
  await s.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      window.__uxProbe = { lcp: 0, cls: 0, events: [], err: null }
      try {
        new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__uxProbe.lcp = Math.max(window.__uxProbe.lcp, e.startTime) })
          .observe({ type: 'largest-contentful-paint', buffered: true })
        new PerformanceObserver((l) => { for (const e of l.getEntries()) if (!e.hadRecentInput) window.__uxProbe.cls += e.value })
          .observe({ type: 'layout-shift', buffered: true })
        new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__uxProbe.events.push(e.duration) })
          .observe({ type: 'event', buffered: true, durationThreshold: 16 })
      } catch (e) { window.__uxProbe.err = String(e) }
    })()`,
  })
  return { session: s, targetId: target.id }
}

async function goto(s, url, width, height = 900) {
  await s.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await s.send('Page.navigate', { url })
  await sleep(900)
  for (let i = 0; i < 15; i += 1) {
    if (await s.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
  await sleep(500)
}

/** 温和交互：Tab 一次，让 Event Timing 有真实输入样本；不触发任何业务写动作。 */
async function gentleInteraction(s) {
  await s.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
  await s.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
  await sleep(350)
}

const A11Y_EXPR = `(() => {
  const vis = (e) => {
    const r = e.getBoundingClientRect(), s = getComputedStyle(e)
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'
  }
  const targets = [...document.querySelectorAll('button,a,input,select,[role="button"]')].filter(vis)
  const small = targets.filter((e) => { const r = e.getBoundingClientRect(); return r.width < 24 || r.height < 24 })
  const btn = targets.find((e) => e.tagName === 'BUTTON' || e.getAttribute('role') === 'button')
  let focus = { checked: false, hasVisibleOutline: false, outlineWidth: '', boxShadow: '' }
  if (btn) {
    btn.focus()
    const s = getComputedStyle(btn)
    focus = { checked: true,
      hasVisibleOutline: (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none',
      outlineWidth: s.outlineWidth, boxShadow: s.boxShadow !== 'none' ? 'present' : 'none' }
  }
  const helpNodes = [...document.querySelectorAll('a,button,[title],[aria-label]')]
    .filter((e) => /帮助|help|快捷键|使用说明/i.test((e.textContent || '') + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '')))
  return {
    path: location.pathname,
    targetCount: targets.length,
    smallCount: small.length,
    smallSamples: small.slice(0, 12).map((e) => ({ tag: e.tagName, id: e.id || '', w: Math.round(e.getBoundingClientRect().width),
      h: Math.round(e.getBoundingClientRect().height), label: (e.textContent || '').trim().slice(0, 20) })),
    focus,
    helpEntryCount: helpNodes.length,
    zoom200: { scrollWidth: document.documentElement.scrollWidth, viewport: innerWidth },
  }
})()`

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}  [app] ${APP}  routes=${ROUTES.join(',')}  runs=${RUNS}\n`)
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

    if (SELFCHECK) {
      await goto(s, `${APP}/`, 1440)
      await s.evaluate(`(() => { const b = document.createElement('button'); b.id='ux-probe-negative';
        b.style.cssText='width:10px;height:10px;position:fixed;top:0;left:0'; b.textContent='x'; document.body.appendChild(b); })()`)
      const m = await s.evaluate(A11Y_EXPR)
      const probeRect = await s.evaluate(`(() => { const e = document.getElementById('ux-probe-negative');
        const r = e.getBoundingClientRect(); return { w: r.width, h: r.height } })()`)
      const detected = m.smallSamples.some((x) => x.id === 'ux-probe-negative')
      console.log(`[selfcheck] 注入小按钮 rect=${probeRect.w}x${probeRect.h} -> smallCount=${m.smallCount} 判红=${detected}`)
      await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
      console.log(`UX_PROBE_SELFCHECK ${detected ? 'PASS' : 'FAIL'}`)
      process.exit(detected ? 0 : 1)
    }

    const cwv = { LCP: [], CLS: [], INP: [] }
    const a11y = []
    const failures = []
    for (const route of ROUTES) {
      for (let n = 1; n <= RUNS; n += 1) {
        await goto(s, `${APP}${route}`, 1440)
        await gentleInteraction(s)
        const perf = await s.evaluate(`(() => {
          const p = window.__uxProbe || {}
          return { lcp: p.lcp || 0, cls: p.cls || 0, ev: (p.events || []).slice(), err: p.err }
        })()`)
        cwv.LCP.push(perf.lcp || null)
        cwv.CLS.push(perf.cls)
        cwv.INP.push(perf.ev.length ? Math.max(...perf.ev) : null)
        if (n === 1) {
          const m = await s.evaluate(A11Y_EXPR)
          a11y.push(m)
          console.log(`[a11y] ${m.path} targets=${m.targetCount} small24=${m.smallCount} focusOutline=${m.focus.hasVisibleOutline} help=${m.helpEntryCount}`)
        }
      }
      // 200% 缩放：视口减半模拟，检查整页横滚与内容可读性
      await goto(s, `${APP}${route}`, 720)
      const zoom = await s.evaluate(A11Y_EXPR)
      const ok = zoom.zoom200.scrollWidth <= zoom.zoom200.viewport + 1
      a11y.push({ path: route, check: 'zoom200-no-hscroll', ok, ...zoom.zoom200 })
      console.log(`[zoom200] ${route} scrollWidth=${zoom.zoom200.scrollWidth} viewport=${zoom.zoom200.viewport} -> ${ok ? 'PASS' : 'FAIL'}`)
    }

    const result = {
      generated_at: new Date().toISOString(),
      browser: version.Browser,
      method: 'CDP + PerformanceObserver 实验室采样（非真实用户 RUM）',
      routes: ROUTES, samples_per_route: RUNS,
      p75: { LCP_ms: p75(cwv.LCP), INP_ms: p75(cwv.INP), CLS: p75(cwv.CLS) },
      thresholds: { LCP_ms: 2500, INP_ms: 200, CLS: 0.1 },
      verdict: {},
      raw: cwv,
    }
    for (const [k, t] of Object.entries({ LCP_ms: 2500, INP_ms: 200, CLS: 0.1 })) {
      const v = result.p75[k]
      result.verdict[k] = v === '未测量' ? '未测量' : (v <= t ? 'PASS' : 'FAIL')
      if (result.verdict[k] === 'FAIL') failures.push(`${k}=${v} > ${t}`)
    }
    const smallTotal = a11y.reduce((n, x) => n + (x.smallCount || 0), 0)
    const wcag = {
      generated_at: result.generated_at, browser: version.Browser,
      method: 'CDP 真实 DOM 几何 + 计算样式（非 axe 全量审计）',
      items: {
        '目标尺寸 24x24': { status: smallTotal === 0 ? 'PASS' : 'FAIL', small_total: smallTotal,
          note: '任一可见交互元素宽或高 <24 CSS px 即记缺陷' },
        '焦点外观': { status: a11y.filter((x) => x.focus?.checked).every((x) => x.focus.hasVisibleOutline) ? 'PASS' : 'FAIL',
          note: '抽检首个 button 的 outline / box-shadow' },
        '键盘可达': { status: 'PARTIAL', note: '探针以 Tab 触发真实输入事件；Top 8 全键盘走查需真人，见 BLOCKED' },
        '200% 缩放无横滚': { status: a11y.filter((x) => x.check).every((x) => x.ok) ? 'PASS' : 'FAIL',
          note: '视口 720 模拟 1440 的 200% 缩放' },
        '一致帮助': { status: a11y.some((x) => x.helpEntryCount > 0) ? 'PASS' : 'PARTIAL',
          note: '帮助 / 快捷键入口存在性抽检；全站位置一致性需人工走查' },
        '拖曳替代': { status: 'BLOCKED', note: '需真人逐页键盘走查，探针不推演' },
        '避免重复输入': { status: 'BLOCKED', note: '需表单草稿任务测试，缺 8 名内部用户' },
        '可访问认证': { status: 'PARTIAL', note: '登录页无认知功能测试（机检），人工走查未完成' },
      },
      per_route: a11y,
      axes_note: '本探针不是 axe 全量审计；WCAG 2.2 严重项最终结论需 axe + 人工键盘/读屏走查，缺真人资源记 BLOCKED',
    }
    mkdirSync('docs/evidence/v3.5.0/EW35-05', { recursive: true })
    mkdirSync('docs/evidence/v3.5.0/EW35-06', { recursive: true })
    writeFileSync(OUT5, JSON.stringify(wcag, null, 2) + '\n')
    writeFileSync(OUT6, JSON.stringify(result, null, 2) + '\n')
    console.log(`\n[cwv] p75 LCP=${result.p75.LCP_ms} INP=${result.p75.INP_ms} CLS=${result.p75.CLS}`)
    console.log(`[wcag] small24=${smallTotal} -> ${wcag.items['目标尺寸 24x24'].status}`)
    console.log(`UX_PROBE_RESULT fails=${failures.length} ${failures.join('; ')}`)
    console.log(`[out] ${OUT5} / ${OUT6}`)
  } finally {
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error(`FAIL 探针无法完成: ${e.message}`); process.exit(1) })
