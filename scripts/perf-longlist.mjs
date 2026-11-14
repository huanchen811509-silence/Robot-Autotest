#!/usr/bin/env node
/**
 * v5.8.0 · W58-10 10000 行长列表滚动帧率量测（双口径）
 *
 * 为什么是双口径
 * --------------
 * 预算里写的场景是「10000 行滚动 minFps ≥ 50」，但产品把每页硬顶在 100 行
 * （`PageQuery.MAX_SIZE = 100`，前端档位 10/20/50/100，全站没有任何列表取数超 100）。
 * 也就是说「10000 行同屏」在产品里**到不了**。硬报一个 rows=10000 的产品读数就是伪造。
 *
 * 于是分两个口径，各自都说清自己量的是什么：
 *   ① product：10000 条数据集 + 每页 100 行（产品上限）的真页面。← 判 PASS/FAIL 的口径
 *   ② harness：一次性 HTML 渲染 10000 行。← 只回答「浏览器扛不扛得住」，非产品页面
 *
 * 为什么必须判「真的滚了」
 * ----------------------
 * rAF 的空转也会给出 60fps。如果量测容器根本没溢出（`scrollHeight == clientHeight`），
 * `scrollTop = x` 是空操作，帧率会因为「什么都没发生」而漂亮得离谱。所以每一轮都要
 * 回报 `scrollable` 与累计滚动像素，python 侧判据要求 `scrollable === true` 且
 * `scrolledPx` 达到阈值——**量了个不动的东西不算样本**。
 *
 * 用法
 * ----
 *   node scripts/perf-longlist.mjs --project 123 --rounds 3 --seconds 3 \
 *        --out docs/evidence/v5.8.0/perf/longlist-raw.json
 *   node scripts/perf-longlist.mjs --product-only --rounds 3
 *
 * 前置：Chrome 带 --remote-debugging-port=9222；入口 :8001；凭据在
 *      ~/.config/robot-autotest/env（QA_E2E_PASSWORD）；数据集已由 tools/perf_seed.py 导入。
 * 退出码：0 = 两个口径都拿到有效样本；1 = 有口径没拿到有效样本；2 = 环境不可用。
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'

function readArgValue(flag) {
  const idx = process.argv.indexOf(flag)
  return idx >= 0 ? process.argv[idx + 1] : null
}

const PROJECT_ID = readArgValue('--project') ?? '123'
const ROUNDS = Number(readArgValue('--rounds') ?? 3)
const SECONDS = Number(readArgValue('--seconds') ?? 3)
const PAGE_SIZE = Number(readArgValue('--page-size') ?? 100)
const DATASET_ROWS = Number(readArgValue('--dataset-rows') ?? 10000)
const OUT = readArgValue('--out') ?? ''
const PRODUCT_ONLY = process.argv.includes('--product-only')
const KEEP_OPEN = process.argv.includes('--keep-open')

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
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) reject(new Error(`${msg.error.message} (${msg.error.code})`))
        else resolve(msg.result)
      }
    })
  }

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
      }, 90000)
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
  return session
}

async function goto(session, url, width = 1440, height = 900) {
  await session.send('Emulation.setDeviceMetricsOverride',
    { width, height, deviceScaleFactor: 1, mobile: false })
  await session.send('Page.navigate', { url })
  await sleep(900)
  for (let i = 0; i < 25; i += 1) {
    if (await session.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
}

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

async function login(session) {
  await goto(session, `${APP}/login`)
  await session.evaluate(`(() => {
    try { localStorage.clear() } catch (e) {}
    return true
  })()`)
  await goto(session, `${APP}/login`)
  const isLogin = await waitFor(session,
    `Boolean(document.querySelector('input[autocomplete="username"]'))`, 15000)
  if (!isLogin) return false
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
  return waitFor(session,
    `!location.pathname.startsWith('/login') && Boolean(localStorage.getItem('auth_token'))`,
    25000)
}

/**
 * 滚动采样表达式。刻意做成「先找准滚动容器、再验证它真的滚了」：
 * 找不到可滚动祖先时返回 scrollable=false，宁可这一轮作废，也不交一个空转的 60fps。
 */
function measureExpr(seconds) {
  return `(async () => {
    const rowCount = document.querySelectorAll('tbody tr').length
    const pickBox = () => {
      let el = document.querySelector('tbody tr')
      while (el && el !== document.body) {
        if (el.scrollHeight - el.clientHeight > 100) return el
        el = el.parentElement
      }
      const main = document.querySelector('.app-main')
      if (main && main.scrollHeight - main.clientHeight > 100) return main
      return null
    }
    const box = pickBox()
    if (!box) {
      return { rows: rowCount, scrollable: false, scrolledPx: 0, frames: 0,
               fps: null, worstFrameMs: null, p95FrameMs: null, elapsedMs: 0 }
    }
    box.scrollTop = 0
    const frames = []
    let scrolledPx = 0
    let last = performance.now()
    const started = last
    const budget = ${Math.round(seconds * 1000)}
    await new Promise((resolve) => {
      const step = (t) => {
        frames.push(t - last)
        last = t
        const max = Math.max(1, box.scrollHeight - box.clientHeight)
        const next = (box.scrollTop + 200) % max
        scrolledPx += Math.abs(next - box.scrollTop)
        box.scrollTop = next
        if (t - started < budget) requestAnimationFrame(step)
        else resolve()
      }
      requestAnimationFrame(step)
    })
    const elapsed = performance.now() - started
    const sorted = [...frames].sort((a, b) => a - b)
    return {
      rows: rowCount,
      scrollable: true,
      scrolledPx: Math.round(scrolledPx),
      frames: frames.length,
      fps: Number((frames.length / (elapsed / 1000)).toFixed(1)),
      worstFrameMs: Number((sorted[sorted.length - 1] || 0).toFixed(1)),
      p95FrameMs: Number((sorted[Math.floor(sorted.length * 0.95)] || 0).toFixed(1)),
      elapsedMs: Math.round(elapsed),
    }
  })()`
}

async function sampleRounds(session, label) {
  const rounds = []
  for (let i = 1; i <= ROUNDS; i += 1) {
    const measured = await session.evaluate(measureExpr(SECONDS))
    rounds.push({ round: i, ...measured })
    console.log(`  [${label}] round ${i}: rows=${measured.rows} fps=${measured.fps} `
      + `scrollable=${measured.scrollable} scrolledPx=${measured.scrolledPx}`)
    await sleep(600)
  }
  const valid = rounds.filter((r) => r.scrollable && r.fps !== null && r.scrolledPx > 1000)
  return {
    rows: rounds[0]?.rows ?? 0,
    rounds,
    validRounds: valid.length,
    minFps: valid.length ? Math.min(...valid.map((r) => r.fps)) : null,
    maxFps: valid.length ? Math.max(...valid.map((r) => r.fps)) : null,
  }
}

/** 一次性 10000 行页面。只回答「浏览器渲染 10000 行能跑多少帧」，不是产品页面。 */
function harnessHtml(rows) {
  const body = Array.from({ length: rows }, (_, i) => {
    const n = String(i + 1).padStart(6, '0')
    return `<tr><td>PERF-C${n}</td><td>perf case ${n}</td><td>active</td><td>P2</td>`
      + `<td>perf-longlist</td><td>LX</td><td>draft</td><td>2026-01-01 00:00:00</td></tr>`
  }).join('')
  return `<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>perf-longlist-10000</title>
<style>
  body { margin: 0; font: 13px/1.4 system-ui, sans-serif; }
  #box { height: 600px; overflow: auto; border: 1px solid #ccc; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 6px 8px; border-bottom: 1px solid #eee; text-align: left; white-space: nowrap; }
  thead th { position: sticky; top: 0; background: #fafafa; }
</style></head><body>
<div id="box"><table><thead><tr><th>用例编号</th><th>标题</th><th>状态</th><th>优先级</th>
<th>领域</th><th>安全等级</th><th>评审</th><th>更新时间</th></tr></thead>
<tbody>${body}</tbody></table></div>
</body></html>`
}

/** 起一个只服务这一份 HTML 的临时 http 服务（Windows Chrome 走 localhost 转发过来）。 */
function serveHarness(html) {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      res.end(html)
    })
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      resolve({ server, url: `http://localhost:${port}/perf-longlist.html` })
    })
  })
}

function machineInfo() {
  try {
    return execFileSync('uname', ['-srm'], { encoding: 'utf8' }).trim()
  } catch {
    return 'unknown'
  }
}

async function main() {
  const capturedAt = new Date().toISOString()
  let session
  try {
    session = await openSession()
  } catch (error) {
    console.error(`[perf] 环境不可用：${error.message}`)
    return 2
  }

  const evidence = {
    capturedAt,
    app: APP,
    project: Number(PROJECT_ID),
    scenario: { pageSize: PAGE_SIZE, datasetRows: DATASET_ROWS, seconds: SECONDS, rounds: ROUNDS },
    environment: { machine: machineInfo(), cdp: CDP },
    product: null,
    harness: null,
  }

  try {
    const loggedIn = await login(session)
    if (!loggedIn) {
      console.error(`[perf] 登录失败，无法量测产品口径（path=${await session.evaluate('location.pathname')}）`)
      evidence.product = { error: 'login-failed' }
    } else {
      evidence.environment.userAgent = await session.evaluate('navigator.userAgent')
      // 工程与页大小都写 localStorage：auth store 与列表页都从这两处初始化，
      // 不写就会量到上一次会话残留的工程/页大小。
      await goto(session, `${APP}/cases`)
      await session.evaluate(`(() => {
        localStorage.setItem('current_project_id', ${JSON.stringify(PROJECT_ID)})
        localStorage.setItem('robot.list.pageSize', ${JSON.stringify(String(PAGE_SIZE))})
        return true
      })()`)
      await goto(session, `${APP}/cases`, 1440)
      const rowsReady = await waitFor(session,
        `document.querySelectorAll('tbody tr').length >= ${Math.max(10, Math.floor(PAGE_SIZE / 2))}`,
        30000)
      // 保真校验：100 行可能来自任何一个用例够多的工程，那样量的是别人的列表。
      // 数据集里 case_code 是 PERF-C000001…，页面里必须看得到，否则这一轮不算数。
      const onPerfProject = await session.evaluate(`/PERF-C\\d{6}/.test(document.body.innerText)`)
      if (!rowsReady || !onPerfProject) {
        const seen = await session.evaluate(`document.querySelectorAll('tbody tr').length`)
        evidence.product = {
          error: rowsReady ? 'not-perf-project' : 'rows-not-rendered',
          renderedRows: seen,
          project: Number(PROJECT_ID),
        }
        console.error(`[perf] 产品口径作废：渲染 ${seen} 行，PERF 数据集可见=${onPerfProject}`
          + `（工程 ${PROJECT_ID} 是否已由 tools/perf_seed.py 导入？）`)
      } else {
        console.log(`[perf] 产品口径：${APP}/cases project=${PROJECT_ID} pageSize=${PAGE_SIZE}`)
        evidence.product = await sampleRounds(session, 'product')
        evidence.product.renderedRowsPerPage = PAGE_SIZE
        evidence.product.datasetRows = DATASET_ROWS
        evidence.product.datasetSignature = 'PERF-C######'
        evidence.product.project = Number(PROJECT_ID)
        evidence.product.scope = 'dataset=10000 rows / 每页 100 行（产品 PageQuery.MAX_SIZE 上限）'
      }
    }

    if (PRODUCT_ONLY) {
      console.log('[perf] --product-only：跳过 harness 口径')
    } else {
      const html = harnessHtml(DATASET_ROWS)
      const { server, url } = await serveHarness(html)
      try {
        await goto(session, url, 1440)
        const ok = await waitFor(session, `document.querySelectorAll('tbody tr').length >= ${DATASET_ROWS}`, 60000)
        if (!ok) {
          const seen = await session.evaluate(`document.querySelectorAll('tbody tr').length`)
          evidence.harness = { error: 'rows-not-rendered', renderedRows: seen, url }
          console.error(`[perf] harness 口径：只渲染了 ${seen} 行`)
        } else {
          console.log(`[perf] harness 口径：独立 HTML ${DATASET_ROWS} 行（非产品页面）`)
          evidence.harness = await sampleRounds(session, 'harness')
          evidence.harness.url = url
          evidence.harness.scope = `${DATASET_ROWS} 行一次性 HTML，非产品页面（产品分页上限 ${PAGE_SIZE}）`
        }
      } finally {
        server.close()
      }
    }

    const productOk = evidence.product && evidence.product.validRounds >= ROUNDS
    const harnessOk = PRODUCT_ONLY || (evidence.harness && evidence.harness.validRounds >= ROUNDS)
    evidence.result = productOk && harnessOk ? 'PASS' : 'FAIL'

    if (OUT) {
      mkdirSync(dirname(OUT) || '.', { recursive: true })
      writeFileSync(OUT, `${JSON.stringify(evidence, null, 2)}\n`)
      console.log(`已写 ${OUT}`)
    }
    console.log(`PERF_LONGLIST_RESULT ${evidence.result} `
      + `product_min_fps=${evidence.product?.minFps ?? 'n/a'} `
      + `harness_min_fps=${evidence.harness?.minFps ?? 'n/a'}`)
    return evidence.result === 'PASS' ? 0 : 1
  } finally {
    if (!KEEP_OPEN) {
      try { await session.send('Page.close') } catch { /* 标签页已经没了 */ }
    }
  }
}

main().then((code) => process.exit(code)).catch((error) => {
  console.error(`[perf] 未捕获异常：${error.stack ?? error}`)
  process.exit(1)
})
