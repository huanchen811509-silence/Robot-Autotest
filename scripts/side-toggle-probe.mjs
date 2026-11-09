#!/usr/bin/env node
/**
 * 3.9 前置调研 · 侧栏收起按钮探针（只读、真实浏览器）。
 *
 * 复现用户报的现象：点左上角三角后「一二级菜单文字全消失、侧栏空间没收住、再也点不回来」。
 * 用 CDP Input.dispatchMouseEvent 做**真实坐标点击**（而非 el.click()），
 * 因为「点不到」本身就是被遮挡/移出视口造成的，脚本调用 click() 会掩盖这个问题。
 *
 * 采集点：
 *   1. 折叠前：app-shell 首列宽、app-side 内宽、brand/toggle 的 display 与 rect
 *   2. toggle 中心点的 elementFromPoint（判断是否被品牌名遮挡）
 *   3. 折叠后：同组数据 + app-side 的 scrollWidth/clientWidth（判断空间是否真收住）
 *   4. 折叠后再次真实点击，看状态是否翻转回来（判断能否还原）
 *
 * 用法：
 *   node scripts/side-toggle-probe.mjs                # 采集 + 判定
 *   node scripts/side-toggle-probe.mjs --selfcheck    # 注入遮挡与宽松样式，判据必红
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const OUT = process.env.SIDE_PROBE_OUT ?? ''
const SELFCHECK = process.argv.includes('--selfcheck')
const SIDE_KEY = 'robot.layout.side.collapsed'

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

/** 一次性取回侧栏几何与可见性快照。 */
const SNAP = `(() => {
  const shell = document.querySelector('.app-shell')
  const side = document.querySelector('.app-side')
  const head = document.querySelector('.app-side-head')
  const brand = document.querySelector('.app-brand')
  const toggle = document.querySelector('.app-side-toggle')
  if (!shell || !side || !toggle) return { missing: true }
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect()
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) } }
  const cs = (el) => el ? getComputedStyle(el) : null
  const tb = toggle.getBoundingClientRect()
  const cx = Math.round(tb.x + tb.width / 2), cy = Math.round(tb.y + tb.height / 2)
  const hit = document.elementFromPoint(cx, cy)
  const navLabels = [...document.querySelectorAll('.app-nav-item span')]
  const visibleLabels = navLabels.filter((el) => el.getBoundingClientRect().width > 0 && getComputedStyle(el).display !== 'none').length
  const navIcons = [...document.querySelectorAll('.app-nav-item svg, .app-nav-item .app-icon')]
  const visibleIcons = navIcons.filter((el) => el.getBoundingClientRect().width > 0 && getComputedStyle(el).display !== 'none').length
  return {
    collapsed: shell.classList.contains('side-collapsed'),
    gridCols: cs(shell).gridTemplateColumns,
    sideWidth: Math.round(side.getBoundingClientRect().width),
    sideScrollW: side.scrollWidth, sideClientW: side.clientWidth,
    sideOverflowX: cs(side).overflowX, sideOverflowY: cs(side).overflowY,
    brandDisplay: cs(brand) ? cs(brand).display : null,
    brandRect: r(brand),
    headJustify: cs(head) ? cs(head).justifyContent : null,
    toggleDisplay: cs(toggle).display,
    toggleRect: r(toggle),
    toggleCenter: { x: cx, y: cy },
    hitTag: hit ? hit.tagName : null,
    hitClass: hit ? String(hit.className || '').slice(0, 60) : null,
    toggleHit: hit ? (toggle === hit || toggle.contains(hit)) : false,
    navLabelTotal: navLabels.length, navLabelVisible: visibleLabels,
    navIconTotal: navIcons.length, navIconVisible: visibleIcons,
    stored: localStorage.getItem(${JSON.stringify(SIDE_KEY)}),
  }
})()`

async function realClick(s, x, y) {
  await s.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', clickCount: 0 })
  await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
  await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
  await sleep(420)
}

/** 判定：一行一条，任一红即 FAILS>0。 */
function judge(before, after, back) {
  const rows = []
  const push = (name, ok, detail) => rows.push({ name, ok, detail })

  push('折叠后可点性（按钮未被遮挡且在视口内）',
    after.toggleHit === true && after.toggleRect.x >= 0 && after.toggleRect.y >= 0,
    `hit=${after.hitTag}.${after.hitClass} toggleRect=${JSON.stringify(after.toggleRect)}`)

  push('折叠后空间收住（首列 = 56px 且无横向溢出）',
    after.sideWidth === 56 && after.sideScrollW <= after.sideClientW + 1,
    `width=${after.sideWidth} scrollW=${after.sideScrollW} clientW=${after.sideClientW}`)

  push('折叠后品牌区隐藏', after.brandDisplay === 'none', `display=${after.brandDisplay}`)

  push('折叠后文字隐藏', after.navLabelVisible === 0, `visible=${after.navLabelVisible}/${after.navLabelTotal}`)

  push('折叠后图标仍在（不能连图标一起藏）',
    after.navIconVisible > 0, `visible=${after.navIconVisible}/${after.navIconTotal}`)

  push('再次点击可还原', back.collapsed === false && back.sideWidth === before.sideWidth,
    `collapsed=${back.collapsed} width=${back.sideWidth} expect=${before.sideWidth}`)

  push('还原后文字全部回来', back.navLabelVisible === before.navLabelVisible,
    `visible=${back.navLabelVisible} expect=${before.navLabelVisible}`)

  return rows
}

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}  [app] ${APP}${SELFCHECK ? '  [selfcheck]' : ''}\n`)
  const { session: s, targetId } = await openSession()
  let backup = null
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

    // 探针是只读改进：先记原值，跑完还原，避免改掉夹具账号的侧栏状态
    backup = await s.evaluate(`localStorage.getItem(${JSON.stringify(SIDE_KEY)})`)
    await s.evaluate(`localStorage.setItem(${JSON.stringify(SIDE_KEY)}, 'false')`)
    await goto(s, `${APP}/`, 1440)
    if (await s.evaluate(`document.querySelector('.app-side-toggle') !== null`) === false) {
      throw new Error('页面没有渲染 .app-side-toggle（未登录或被路由挡住）')
    }

    const before = await s.evaluate(SNAP)

    // 负向自检：注入遮挡（品牌名绝对定位盖住按钮）+ 让折叠样式失效
    if (SELFCHECK) {
      await s.evaluate(`(() => {
        const st = document.createElement('style'); st.id = 'probe-bad-css'
        st.textContent = [
          '.app-shell.side-collapsed .app-side .app-brand { display: block !important; position: absolute !important; z-index: 99 !important; }',
          '.app-shell.side-collapsed { grid-template-columns: 200px minmax(0, 1fr) !important; }',
          '.app-shell.side-collapsed .app-side .app-nav-item span { display: inline !important; }',
        ].join('\\n')
        document.head.appendChild(st)
      })()`)
      await sleep(120)
    }

    const t1 = before.toggleCenter
    await realClick(s, t1.x, t1.y)
    const after = await s.evaluate(SNAP)

    // 第二次点击：用「遮挡后」的按钮位置再点一次，模拟用户尝试还原
    const t2 = after.toggleCenter
    await realClick(s, t2.x, t2.y)
    const back = await s.evaluate(SNAP)

    const rows = judge(before, after, back)
    const fails = rows.filter((r) => !r.ok).length

    for (const r of rows) console.log(`${r.ok ? '[PASS]' : '[FAIL]'} ${r.name}  ${r.detail}`)
    console.log(`\nSIDE_TOGGLE_PROBE collapsedBefore=${before.collapsed} collapsedAfter=${after.collapsed} restored=${back.collapsed === false} FAILS=${fails}`)

    if (OUT) {
      writeFileSync(OUT, JSON.stringify({
        generatedAt: new Date().toISOString(), selfcheck: SELFCHECK,
        viewport: '1440x900', version: version.Browser,
        before, after, back, rows, fails,
      }, null, 2), 'utf8')
      console.log(`[out] ${OUT}`)
    }
    if (SELFCHECK) {
      console.log(fails > 0 ? '[selfcheck] PASS 注入劣化后判据变红' : '[selfcheck] FAIL 注入劣化后判据仍绿')
      if (fails === 0) process.exitCode = 1
    } else if (fails > 0) {
      process.exitCode = 1
    }
  } finally {
    try {
      if (backup !== null) {
        await s.evaluate(`localStorage.setItem(${JSON.stringify(SIDE_KEY)}, ${JSON.stringify(backup)})`)
      } else {
        await s.evaluate(`localStorage.removeItem(${JSON.stringify(SIDE_KEY)})`)
      }
    } catch { /* 标签页已关就算了 */ }
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error('[error]', e.message); process.exit(1) })
