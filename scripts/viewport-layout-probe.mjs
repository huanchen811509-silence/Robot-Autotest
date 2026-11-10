/**
 * v4.0 / FR-v4.0-04 · 折叠态留白与内容右留白探针（只读、真实浏览器）。
 *
 * 判据（PRD §3.1 FR-v4.0-04）：
 *   1. 窄轨宽度 = 64px（令牌 --rail-w-collapsed），折叠按钮 elementFromPoint 仍命中自身；
 *   2. 内容区右内边距 = 28px（.app-body 计算值，走令牌/样式表而非 JS 猜测）；
 *   3. 1024/1280/1440/1920 与 125%/150% 缩放（等效 CSS 宽度）下：
 *      `.app-main` 右边缘留白 ≥ 28px、整页无横滚（scrollWidth ≤ innerWidth + 1）；
 *   4. 折叠态下上述视口同样无整页横滚。
 *
 * 缩放建模：浏览器缩放在 CDP 里等价于 CSS 视口变窄，因此 1440@125% 取 1152、
 * 1440@150% 取 960、1920@125% 取 1536、1920@150% 取 1280（并保留 1280 原生档）。
 *
 * 用法：
 *   node scripts/viewport-layout-probe.mjs                 # 采集 + 判定，0 = 全 PASS
 *   node scripts/viewport-layout-probe.mjs --selfcheck     # 注入 4px 右留白 + 56px 窄轨，判据必红
 *
 * 前置：Chrome --remote-debugging-port=9222；:8001 可用；QA_E2E_PASSWORD 在
 *       ~/.config/robot-autotest/env 或环境变量。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const OUT = process.env.LAYOUT_PROBE_OUT ?? ''
const SELFCHECK = process.argv.includes('--selfcheck')
const SIDE_KEY = 'robot.layout.side.collapsed'
const RAIL = 64
const PAD_RIGHT = 28

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
    this.ws = ws; this.id = 0; this.pending = new Map()
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

async function setViewport(s, width, height) {
  await s.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await sleep(260)
}

async function goto(s, url, width, height) {
  await setViewport(s, width, height)
  await s.send('Page.navigate', { url })
  await sleep(900)
  for (let i = 0; i < 15; i += 1) {
    if (await s.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
  await sleep(500)
}

/** 一次取回布局读数。 */
const SNAP = `(() => {
  const shell = document.querySelector('.app-shell')
  const side = document.querySelector('.app-side')
  const body = document.querySelector('.app-body')
  const main = document.querySelector('.app-main')
  const toggle = document.querySelector('.app-side-toggle')
  if (!shell || !body || !main) return { missing: true }
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect()
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) } }
  const tb = toggle ? toggle.getBoundingClientRect() : null
  const cx = tb ? Math.round(tb.x + tb.width / 2) : -1
  const cy = tb ? Math.round(tb.y + tb.height / 2) : -1
  const hit = (cx >= 0 && cy >= 0) ? document.elementFromPoint(cx, cy) : null
  return {
    innerWidth: window.innerWidth,
    docScrollW: document.documentElement.scrollWidth,
    bodyScrollW: document.body.scrollWidth,
    main: r(main),
    mainScrollW: main.scrollWidth, mainClientW: main.clientWidth,
    sideW: side ? Math.round(side.getBoundingClientRect().width) : null,
    padRight: getComputedStyle(body).paddingRight,
    shellCols: getComputedStyle(shell).gridTemplateColumns,
    collapsed: shell.classList.contains('side-collapsed'),
    toggleRect: tb ? r(toggle) : null,
    toggleCenter: { x: cx, y: cy },
    toggleHit: hit ? !!(toggle === hit || toggle.contains(hit)) : false,
    hitClass: hit ? String(hit.className || '').slice(0, 50) : null,
    /* 内容右边缘到视口右边缘 = 内边距 + （工作区右边缘到视口右边缘：滚动条 / AI 抽屉占位）。
       .app-main 自身会一直铺到列尾，所以直接量它的 right 永远是 0——必须量 app-body 的内容盒。 */
    contentRightGap: Math.round(window.innerWidth - body.getBoundingClientRect().right + parseFloat(getComputedStyle(body).paddingRight)),
  }
})()`

async function measure(s, label) {
  return s.evaluate(SNAP).then((v) => ({ label, ...v }))
}

async function realClick(s, x, y) {
  await s.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', clickCount: 0 })
  await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
  await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
  await sleep(420)
}

const CASES = [
  { label: '1024x768', w: 1024, h: 768 },
  { label: '1280x800', w: 1280, h: 800 },
  { label: '1440x900', w: 1440, h: 900 },
  { label: '1920x1080', w: 1920, h: 1080 },
  { label: '1440@125%(1152)', w: 1152, h: 720 },
  { label: '1440@150%(960)', w: 960, h: 600 },
  { label: '1920@125%(1536)', w: 1536, h: 864 },
  { label: '1920@150%(1280)', w: 1280, h: 720 },
]

function judgeCase(m, collapsed) {
  const rows = []
  const ok = {
    pad: m.padRight === `${PAD_RIGHT}px`,
    gap: m.contentRightGap >= PAD_RIGHT,
    noDocScroll: m.docScrollW <= m.innerWidth + 1,
    noMainScroll: m.mainScrollW <= m.mainClientW + 1,
  }
  rows.push({ name: `右内边距=${PAD_RIGHT}px`, ok: ok.pad, detail: `paddingRight=${m.padRight}` })
  rows.push({ name: `内容右留白≥${PAD_RIGHT}px`, ok: ok.gap, detail: `gap=${m.contentRightGap}` })
  rows.push({ name: '整页无横滚', ok: ok.noDocScroll, detail: `docScrollW=${m.docScrollW} innerWidth=${m.innerWidth}` })
  rows.push({ name: '工作区无横向溢出', ok: ok.noMainScroll, detail: `scrollW=${m.mainScrollW} clientW=${m.mainClientW}` })
  if (collapsed) {
    rows.push({ name: `折叠窄轨=${RAIL}px`, ok: m.sideW === RAIL, detail: `sideW=${m.sideW}` })
    rows.push({ name: '折叠按钮命中自身', ok: m.toggleHit === true, detail: `hit=${m.hitClass}` })
  }
  return rows
}

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}  [app] ${APP}${SELFCHECK ? '  [selfcheck]' : ''}\n`)
  const { session: s, targetId } = await openSession()
  let backup = null
  try {
    await goto(s, `${APP}/login`, 1440, 900)
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

    backup = await s.evaluate(`localStorage.getItem(${JSON.stringify(SIDE_KEY)})`)
    await s.evaluate(`localStorage.setItem(${JSON.stringify(SIDE_KEY)}, 'false')`)
    await goto(s, `${APP}/workbench`, 1440, 900)
    if (!(await s.evaluate(`document.querySelector('.app-main') !== null`))) throw new Error('未渲染 .app-main（未登录或被守卫挡住）')

    const snapshots = []
    for (const c of CASES) {
      await setViewport(s, c.w, c.h)
      snapshots.push({ ...(await measure(s, c.label)), collapsed: false })
    }

    // 折叠态：真实点击折叠按钮，再在四个原生视口复核窄轨与留白
    await setViewport(s, 1440, 900)
    const pre = await measure(s, '1440 collapse-before')
    await realClick(s, pre.toggleCenter.x, pre.toggleCenter.y)
    const collapsedAt = await measure(s, '1440 collapsed')
    for (const c of CASES.filter((x) => [1024, 1280, 1440, 1920].includes(x.w) && x.label.indexOf('%') < 0)) {
      await setViewport(s, c.w, c.h)
      snapshots.push({ ...(await measure(s, `${c.label} collapsed`)), collapsed: true })
    }

    if (SELFCHECK) {
      await s.evaluate(`(() => {
        const st = document.createElement('style'); st.id = 'probe-bad-layout'
        st.textContent = [
          '.app-body { padding-right: 4px !important; }',
          '.app-shell.side-collapsed { grid-template-columns: 56px minmax(0, 1fr) !important; }',
        ].join('\\n')
        document.head.appendChild(st)
      })()`)
      await sleep(200)
      await setViewport(s, 1024, 768)
      snapshots.push({ ...(await measure(s, 'selfcheck-1024 collapsed')), collapsed: true })
    }

    const rows = []
    for (const snap of snapshots) {
      if (snap.missing) { rows.push({ name: `[${snap.label}] 布局元素缺失`, ok: false, detail: '' }); continue }
      for (const r of judgeCase(snap, snap.collapsed)) rows.push({ name: `[${snap.label}] ${r.name}`, ok: r.ok, detail: r.detail })
    }
    const fails = rows.filter((r) => !r.ok).length
    for (const r of rows) console.log(`${r.ok ? '[PASS]' : '[FAIL]'} ${r.name}  ${r.detail}`)
    console.log(`\nLAYOUT_PROBE cases=${snapshots.length} checks=${rows.length} FAILS=${fails}`)
    console.log(`[collapse] before=${pre.collapsed} after=${collapsedAt.collapsed} rail=${collapsedAt.sideW} toggleHit=${collapsedAt.toggleHit}`)

    if (OUT) {
      writeFileSync(OUT, JSON.stringify({
        generatedAt: new Date().toISOString(), selfcheck: SELFCHECK, rail: RAIL, padRight: PAD_RIGHT,
        browser: version.Browser, snapshots, rows, fails,
      }, null, 2), 'utf8')
      console.log(`[out] ${OUT}`)
    }
    if (SELFCHECK) {
      console.log(fails > 0 ? '[selfcheck] PASS 注入 4px 留白 + 56px 窄轨后判据变红' : '[selfcheck] FAIL 注入后判据仍绿')
      if (fails === 0) process.exitCode = 1
    } else if (fails > 0) {
      process.exitCode = 1
    }
  } finally {
    try {
      if (backup !== null) await s.evaluate(`localStorage.setItem(${JSON.stringify(SIDE_KEY)}, ${JSON.stringify(backup)})`)
      else await s.evaluate(`localStorage.removeItem(${JSON.stringify(SIDE_KEY)})`)
    } catch { /* 标签页已关就算了 */ }
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error(`[error] ${e.message}`); process.exit(1) })
