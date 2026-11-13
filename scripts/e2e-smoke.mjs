#!/usr/bin/env node
/**
 * v5.6.0 · UI 冒烟 E2E（FR-v5.6-73 / W56-55）
 *
 * 一条链路跑完：登录 → 进工程 → 在 pytest 工作台发起 fake 执行 → 打开报告看用例明细。
 *
 * 为什么不用组件单测代替
 * ---------------------
 * 单测能证明每个组件自己会渲染，证明不了「接上真实后端后这条链路成立」：
 * 路由守卫把页面踢回登录、执行发起按钮因预检未过而禁用、报告页取错 runId 显示空表——
 * 这三类缺陷在 jsdom 里全是绿的。本脚本用真实 Chrome 走一遍，失败截图 + 失败断言。
 *
 * 与第 13 步 browser-regression.mjs 的分工
 * ----------------------------------------
 * browser-regression：多路由 × 两视口的**版式回归**（栅格、横滚、异常）。
 * e2e-smoke        ：单条**业务链路**（能不能发起、能不能看到结果），不判版式。
 *
 * 用法
 * ----
 *   node scripts/e2e-smoke.mjs                     # 正常跑，0 = 全 PASS
 *   node scripts/e2e-smoke.mjs --shot-dir /tmp/x   # 额外保存截图
 *   node scripts/e2e-smoke.mjs --keep-open         # 结束后不关标签页，便于人工看
 *
 * 前置：Chrome --remote-debugging-port=9222；入口 :8001；凭据在
 *       ~/.config/robot-autotest/env（QA_E2E_PASSWORD）。
 * 退出码：0 = 全 PASS；1 = 有断言失败；2 = 环境不可用（CDP / 口令缺失）。
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const PROJECT_ID = process.env.E2E_PROJECT_ID ?? '95'
const SHOT_DIR = readArgValue('--shot-dir') ?? ''
const KEEP_OPEN = process.argv.includes('--keep-open')

function readArgValue(flag) {
  const idx = process.argv.indexOf(flag)
  return idx >= 0 ? process.argv[idx + 1] : null
}

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

const PASSWORD = envValue('QA_E2E_PASSWORD')
if (!PASSWORD) {
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
      } else if (msg.method && this.handlers.has(msg.method)) {
        this.handlers.get(msg.method)(msg.params)
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
      }, 60000)
    })
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error(`页面脚本异常: ${r.exceptionDetails.text} `
        + `${r.exceptionDetails.exception?.description ?? ''}`)
    }
    return r.result.value
  }
}

async function openSession() {
  const created = await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })
  if (!created.ok) {
    throw new Error(`新建标签页失败 HTTP ${created.status}（Chrome 是否带 --remote-debugging-port=9222？）`)
  }
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
function check(id, ok, detail = '') {
  checks.push({ id, ok: Boolean(ok), detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${detail}`)
}

async function goto(session, url, width = 1440, height = 900) {
  await session.send('Emulation.setDeviceMetricsOverride',
    { width, height, deviceScaleFactor: 1, mobile: false })
  await session.send('Page.navigate', { url })
  await sleep(900)
  for (let i = 0; i < 20; i += 1) {
    if (await session.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
}

/** 轮询直到页面内条件成立（默认 20s）。返回是否成立。 */
async function waitFor(session, expression, timeoutMs = 20000, intervalMs = 300) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      if (await session.evaluate(expression)) return true
    } catch { /* 导航中 evaluate 会抛，继续等 */ }
    await sleep(intervalMs)
  }
  return false
}

async function shot(session, name) {
  if (!SHOT_DIR) return
  mkdirSync(SHOT_DIR, { recursive: true })
  const data = await session.send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(SHOT_DIR, `${name}.png`), Buffer.from(data.data, 'base64'))
}

async function login(session) {
  // 先把上一次会话的凭据清掉：CDP profile 是复用的，残留 auth_token 会让 /login 直接
  // 跳 /workbench，「登录表单」这条断言就变成假红（页面没错，是上一条用例留下的状态）。
  await goto(session, `${APP}/login`)
  await session.evaluate(`(() => {
    try { localStorage.removeItem('auth_token'); localStorage.removeItem('auth_expires_at') } catch (e) {}
    try { localStorage.clear() } catch (e) {}
    return true
  })()`)
  await goto(session, `${APP}/login`)
  const isLogin = await waitFor(session, `Boolean(document.querySelector('input[autocomplete="username"]'))`, 15000)
  check('e2e-01-login-form', isLogin, `path=${await session.evaluate('location.pathname')}`)
  if (!isLogin) return false

  // naive-ui 的输入是受控组件：只 set value 不触发 input 事件，Vue 的 v-model 收不到。
  await session.evaluate(`(() => {
    const set = (sel, value) => {
      const el = document.querySelector(sel)
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(el, value)
      el.dispatchEvent(new Event('input', { bubbles: true }))
      el.dispatchEvent(new Event('change', { bubbles: true }))
    }
    set('input[autocomplete="username"]', ${JSON.stringify(USER)})
    set('input[autocomplete="current-password"]', ${JSON.stringify(PASSWORD)})
    return true
  })()`)
  await sleep(200)
  await session.evaluate(`(() => {
    const btn = document.querySelector('button[type="submit"]')
      || [...document.querySelectorAll('button')].find((b) => /登录|Sign in|Log in/i.test(b.innerText))
    if (btn) btn.click()
    return Boolean(btn)
  })()`)

  // 判据用「离开登录页 + 拿到 auth_token」：auth store 的键是 auth_token，
  // 认 document.cookie 会永远为假（后端走 Bearer，不下发 cookie）。
  const loggedIn = await waitFor(session,
    `!location.pathname.startsWith('/login') && Boolean(localStorage.getItem('auth_token'))`,
    25000)
  const path = await session.evaluate('location.pathname')
  check('e2e-02-login-redirect', loggedIn && !path.startsWith('/login'), `path=${path}`)
  return loggedIn
}

async function openPytestWorkbench(session) {
  // 指定工程：auth store 从 localStorage 的 current_project_id 初始化，
  // 不绑就会读到上一次会话残留的项目号，发起页对着别的工程报「未绑定用例」。
  await goto(session, `${APP}/workbench`)
  await session.evaluate(`localStorage.setItem('current_project_id', ${JSON.stringify(PROJECT_ID)})`)
  await goto(session, `${APP}/pytest-workbench`, 1440)

  const ready = await waitFor(session, `Boolean(document.querySelector('[data-test="pytest-launch"]'))`, 25000)
  check('e2e-03-launch-form', ready, `path=${await session.evaluate('location.pathname')}`)
  if (!ready) return false

  // 预检要先出结论：直接点提交换来的只是「按钮禁用」，看不出是预检没过还是别的原因。
  const precheckDone = await waitFor(session,
    `Boolean(document.querySelector('[data-test="precheck-state"]'))`, 30000)
  const stateText = precheckDone
    ? await session.evaluate(`document.querySelector('[data-test="precheck-state"]').innerText.trim()`)
    : '(预检没渲染)'
  check('e2e-04-precheck', precheckDone, stateText)
  return precheckDone
}

async function launchFakeRun(session) {
  // 默认 fake：执行发起页的 mode 初值就是 fake，这里显式确认一遍，
  // 免得将来默认值改了以后 E2E 悄悄跑成 real（那就是登录 DUT 的真执行）。
  const mode = await session.evaluate(
    `document.querySelector('[data-test="mode"]')?.value ?? ''`)
  check('e2e-05-mode-fake', mode === 'fake', `mode=${mode}`)

  // 只跑 1 条：全量 469 条 fake 要几十秒，冒烟脚本不该把门禁拖成分钟级。
  await session.evaluate(`(() => {
    const only = document.querySelector('[data-test="only-selected"]')
    if (only && !only.checked) only.click()
    return Boolean(only)
  })()`)
  await sleep(400)
  const picked = await session.evaluate(`(() => {
    const box = document.querySelector('[data-test="node-list"] input[type="checkbox"]')
    if (!box) return false
    if (!box.checked) box.click()
    return true
  })()`)
  check('e2e-06-node-picked', picked, picked ? '已勾选 1 条用例' : '用例清单里没有可勾选项')
  await sleep(300)

  await session.evaluate(`document.querySelector('[data-test="submit"]').click()`)
  const submitted = await waitFor(session,
    `Boolean(document.querySelector('[data-test="launch-notice"]')
      || document.querySelector('[data-test="launch-error"]'))`, 30000)
  const notice = await session.evaluate(`(() => {
    const ok = document.querySelector('[data-test="launch-notice"]')
    const bad = document.querySelector('[data-test="launch-error"]')
    return { ok: ok ? ok.innerText.trim() : '', error: bad ? bad.innerText.trim() : '' }
  })()`)
  check('e2e-07-submitted', submitted && !notice.error, notice.ok || notice.error)

  // 等终态：进度面板出现「完成/失败」字样，或出现取消按钮消失。
  const finished = await waitFor(session, `(() => {
    const text = document.body.innerText
    return /已完成|已结束|完成|失败|取消/.test(text) && !document.querySelector('[data-test="cancel"]')
  })()`, 120000, 800)
  const panel = await session.evaluate(`(() => {
    const el = document.querySelector('[data-test="pytest-progress"], .progress, [data-test="progress"]')
    return el ? el.innerText.trim().slice(0, 200) : document.body.innerText.slice(0, 200)
  })()`)
  check('e2e-08-run-terminal', finished, panel.replace(/\\n/g, ' | ').slice(0, 160))

  const runId = await session.evaluate(`(() => {
    const m = document.body.innerText.match(/EHT-(?:FAKE|REAL)-[A-Za-z0-9-]+/) 
      || document.body.innerText.match(/E2E-[A-Za-z0-9-]+/)
    return m ? m[0] : ''
  })()`)
  return runId
}

async function openReport(session, runId) {
  if (!runId) {
    check('e2e-09-report', false, '拿不到 runId，无法打开报告')
    return
  }
  await goto(session, `${APP}/reports/${encodeURIComponent(runId)}`, 1440)
  const rendered = await waitFor(session, `(() => {
    const text = document.body.innerText
    return text.includes('用例') && !text.includes('运行不存在') && !text.includes('报告不存在')
  })()`, 30000)
  const detail = await session.evaluate(`(() => {
    const text = document.body.innerText
    return {
      notFound: text.includes('运行不存在') || text.includes('报告不存在'),
      // 只判「页面不报错」是不够的：曾经出现过「执行成功、报告空白」——
      // pytest 入口跑完不入库，页面照样渲染，断言照样绿。这里必须看到用例编号。
      hasCaseCode: /IM-[A-Z]{3}-[0-9]{3}/.test(text),
      empty: text.includes('暂无用例') || text.includes('没有用例'),
      rows: document.querySelectorAll('tbody tr').length,
    }
  })()`)
  const ok = rendered && !detail.notFound && detail.hasCaseCode && !detail.empty
  check('e2e-09-report', ok, JSON.stringify(detail))
}

/** 长列表滚动帧率：目标场景是 10000 行，本机没有该数据量，如实标注。 */
async function measureFps(session) {
  await goto(session, `${APP}/cases`, 1440)
  await sleep(1200)
  const result = await session.evaluate(`(async () => {
    const rows = document.querySelectorAll('tbody tr').length
    const box = document.querySelector('.app-main') || document.documentElement
    const frames = []
    let last = performance.now()
    const started = last
    await new Promise((resolve) => {
      const step = (t) => {
        frames.push(t - last)
        last = t
        if (box) box.scrollTop = (box.scrollTop + 200) % Math.max(1, box.scrollHeight - box.clientHeight || 1)
        if (t - started < 2000) requestAnimationFrame(step)
        else resolve()
      }
      requestAnimationFrame(step)
    })
    const fps = frames.length / ((performance.now() - started) / 1000)
    const sorted = [...frames].sort((a, b) => a - b)
    return {
      rows,
      fps: Number(fps.toFixed(1)),
      worstFrameMs: Number((sorted[sorted.length - 1] || 0).toFixed(1)),
      frames: frames.length,
    }
  })()`)
  return result
}

async function main() {
  const started = new Date().toISOString()
  let session
  try {
    ;({ session } = await openSession())
  } catch (error) {
    console.error(`[e2e] 环境不可用：${error.message}`)
    return 2
  }

  const exceptions = []
  session.on('Runtime.exceptionThrown', (p) => {
    exceptions.push(p.exceptionDetails?.exception?.description ?? p.exceptionDetails?.text ?? '')
  })

  try {
    const ok = await login(session)
    if (!ok) {
      await shot(session, 'e2e-login-failed')
    } else {
      const formOk = await openPytestWorkbench(session)
      let runId = ''
      if (formOk) {
        runId = await launchFakeRun(session)
        await shot(session, 'e2e-run')
      }
      await openReport(session, runId)
      await shot(session, 'e2e-report')
    }

    const fps = await measureFps(session).catch(() => null)
    check('e2e-10-no-page-exception', exceptions.length === 0,
      exceptions.length ? exceptions[0].slice(0, 200) : '无 JS 异常')

    const failed = checks.filter((c) => !c.ok)
    console.log(`\nE2E_RESULT ${failed.length === 0 ? 'PASS' : `FAIL(${failed.length})`}`)

    const evidence = {
      capturedAt: started,
      app: APP,
      projectId: PROJECT_ID,
      result: failed.length === 0 ? 'PASS' : 'FAIL',
      checks,
      exceptions: exceptions.slice(0, 10),
      fps,
    }
    const out = process.env.E2E_EVIDENCE ?? ''
    if (out) {
      mkdirSync(out.substring(0, out.lastIndexOf('/')) || '.', { recursive: true })
      writeFileSync(out, `${JSON.stringify(evidence, null, 2)}\n`)
      console.log(`已写 ${out}`)
    }
    return failed.length === 0 ? 0 : 1
  } finally {
    if (!KEEP_OPEN) {
      try { await session.send('Page.close') } catch { /* 标签页已经没了 */ }
    }
  }
}

main().then((code) => process.exit(code)).catch((error) => {
  console.error(`[e2e] 未捕获异常：${error.stack ?? error}`)
  process.exit(1)
})
