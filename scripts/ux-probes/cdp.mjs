#!/usr/bin/env node
/**
 * UX 探针的 CDP 驱动（v5.19.0 / W69-01）。
 *
 * <p>为什么不用 Playwright 的测试运行器：这里的断言对象是**布局与命中**（`elementFromPoint`
 * 是否落在目标元素上），不是交互流程。裸 CDP 能精确控制视口、取元素矩形、注入覆盖层做
 * `--self-test`，且不需要额外依赖（`fetch` + `WebSocket` 已是 Node 18+ 内置）。
 *
 * <p>运行前提：本机有一个开启调试端口的 Chrome（路径见 README 的「UX 探针」一节），
 * 启动参数为 `--headless=new --no-sandbox --remote-debugging-port=9222 about:blank`。
 */
export const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
import { APP_BASE } from './base-url.mjs'

export const APP = APP_BASE
export const ADMIN_USER = process.env.QA_ADMIN ?? 'admin'
export const ADMIN_PASS = process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026'

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

export function log(...args) {
  console.log(...args)
}

class Session {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    /** 事件订阅（v5.20.0）：Fetch.requestPaused 这类"服务端主动推"的事件必须有地方接。 */
    this.handlers = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) reject(new Error(`${msg.error.message} (${msg.error.code})`))
        else resolve(msg.result)
        return
      }
      if (msg.method && this.handlers.has(msg.method)) {
        for (const handler of this.handlers.get(msg.method)) {
          try {
            handler(msg.params)
          } catch (err) {
            log(`[cdp] 事件处理异常 ${msg.method}: ${err.message}`)
          }
        }
      }
    })
  }

  /** 订阅 CDP 事件；返回取消订阅函数。 */
  on(method, handler) {
    if (!this.handlers.has(method)) this.handlers.set(method, new Set())
    this.handlers.get(method).add(handler)
    return () => this.handlers.get(method)?.delete(handler)
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP timeout: ${method}`))
        }
      }, 90000)
    })
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error(`页面异常: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`)
    }
    return r.result.value
  }
}

export async function assertCdp() {
  try {
    const r = await fetch(`${CDP}/json/version`)
    if (!r.ok) throw new Error(String(r.status))
  } catch (e) {
    throw new Error(
      `连不上 CDP ${CDP}（${e.message}）。先启动调试版 Chrome：\n` +
        '  ~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome --headless=new --no-sandbox \\\n' +
        '    --remote-debugging-port=9222 about:blank'
    )
  }
}

export async function openSession({ width = 1440, height = 900 } = {}) {
  await assertCdp()
  const created = await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })
  if (!created.ok) throw new Error(`新建标签页失败 ${created.status}`)
  const target = await created.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', () => rej(new Error('WebSocket 连接失败')), { once: true })
  })
  const s = new Session(ws)
  await s.send('Page.enable')
  await s.send('Runtime.enable')
  await s.send('Network.enable')
  await s.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  return s
}

export async function setViewport(s, width, height) {
  await s.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await sleep(200)
}

export async function goto(s, url, settle = 1200) {
  await s.send('Page.navigate', { url })
  await sleep(settle)
  for (let i = 0; i < 40; i += 1) {
    if (await s.evaluate('document.readyState') === 'complete') break
    await sleep(150)
  }
  await sleep(400)
}

export async function waitFor(s, expr, timeout = 20000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    try {
      if (await s.evaluate(expr)) return true
    } catch { /* 导航中 */ }
    await sleep(250)
  }
  return false
}

export async function login(s, { user = ADMIN_USER, pass = ADMIN_PASS } = {}) {
  await goto(s, `${APP}/login`, 900)
  await s.evaluate(`(() => { try { localStorage.clear() } catch (e) {} return 1 })()`)
  await goto(s, `${APP}/login`, 900)
  await waitFor(s, `Boolean(document.querySelector('input[autocomplete="username"]'))`, 15000)
  await s.evaluate(`(() => {
    const set = (sel, v) => {
      const el = document.querySelector(sel)
      if (!el) return
      const p = HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(p, 'value').set.call(el, v)
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    set('input[autocomplete="username"]', ${JSON.stringify(user)})
    set('input[autocomplete="current-password"]', ${JSON.stringify(pass)})
    const b = document.querySelector('button[type="submit"]')
    if (b) b.click()
    return 1
  })()`)
  const ok = await waitFor(s, `!location.pathname.startsWith('/login')`, 20000)
  await sleep(1200)
  return ok
}

export async function selectProject(s, projectId) {
  await s.evaluate(
    `(() => { localStorage.setItem('current_project_id', ${JSON.stringify(String(projectId))}); localStorage.setItem('ui_mode', 'project'); return 1 })()`
  )
}

export async function closeSession(s) {
  try {
    await s.send('Page.close')
  } catch { /* 已关闭 */ }
}

/** 注入一个全屏半透明覆盖层，用于验证「命中检测真的会判红」 */
export async function injectOverlay(s) {
  await s.evaluate(`(() => {
    const d = document.createElement('div')
    d.id = '__ux_probe_overlay'
    d.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:transparent'
    document.body.appendChild(d)
    return 1
  })()`)
}

export async function removeOverlay(s) {
  await s.evaluate(`(() => { const d = document.getElementById('__ux_probe_overlay'); if (d) d.remove(); return 1 })()`)
}
