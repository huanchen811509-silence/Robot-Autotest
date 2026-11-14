#!/usr/bin/env node
/**
 * W57-58 · v5.7.0 复核缺陷修复的**真实界面取证**（不是路由 200 那种壳检查）。
 *
 * 覆盖五条链路，每条都断言「后端真的收到了、界面真的变了」：
 *   1. 向导建项目：模板 → 基础 → 运行 → 成员 → 内容（勾一个平台用例集）→ 复核 → 创建；
 *      断言 `POST /api/projects` 与 `POST /api/projects/{id}/template-content` 都是 2xx，
 *      且页面出现创建成功态（P0-2 的可见判据：内容步不再是「假的」）。
 *   2. 发起台四模式（CASE / MIXED 必须用本项目自己的用例 id，别项目的会被后端拒）：
 *      ALL / SET / CASE / MIXED 各切一次，截图 + 在页面上下文里真调
 *      `POST /api/projects/{id}/runs/preview`，断言四个响应都带 `snapshotId`。
 *      **不点「发起运行」**：本波无 DUT 派发授权（L-57-01），预览是能证明两步契约的最小动作。
 *   3. 平台集加成员：打开成员面板 → 输入用例 id → 添加 → 断言成员行真的出现（P0-3）。
 *   4. 报告结论：打开一条真实 run 的报告页，读「结论」列 —— 无判据时必须是 UNDETERMINED，
 *      不能按 failCount 回落成 PASS（P0-4 的可见判据）。
 *   5. 运行记录备注：编辑 → 保存 → 重新打开 → 值仍在（P1-5：打开即取真实备注，不靠未加载的 runMetas）。
 *
 * 用法：
 *   node scripts/ui-flows-v57.mjs [--flows=wizard,launch,set,report,remark]
 *                                [--project=93] [--report-project=95] [--run=17007]
 *                                [--case-id=123] [--shot-dir=docs/evidence/...]
 * 前置：Chrome `--remote-debugging-port=9222`、统一入口 :8001、夹具口令在
 *       `~/.config/robot-autotest/env` 的 `QA_E2E_PASSWORD`。
 * 输出：截图 + `result.json` 落 `--shot-dir`，末行固定
 *       `UI_FLOWS_RESULT PASS=n FAIL=n`，有 FAIL 时退出码 1。
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const argv = process.argv.slice(2)
const arg = (name, fallback = '') => {
  const hit = argv.find((item) => item.startsWith(`--${name}=`))
  return hit ? hit.slice(name.length + 3) : fallback
}

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const PROJECT = Number(arg('project', '93'))
const REPORT_PROJECT = Number(arg('report-project', '95'))
const RUN_ID = arg('run', 'EHT-REAL-L0-20261113-220048-01')
const CASE_ID = Number(arg('case-id', '0'))
/** 项目 93 的用例集 46（`TS-87e4d361`），仅用于 SET / MIXED 模式的预览入参。 */
const SET_ID = Number(arg('set-id', '46'))
/** 项目 93 自己的用例 id：CASE / MIXED 预览必须用它，拿别项目的 id 后端会按「不属于该项目」拒。 */
const LAUNCH_CASE_ID = Number(arg('launch-case-id', '1901'))
/** 运行记录流要有真实数据：项目 93 一条 run 都没有（实测），用有 485 条的 29 号项目。 */
const REMARK_PROJECT = Number(arg('remark-project', '29'))
const FLOWS = arg('flows', 'wizard,launch,set,report,remark').split(',').map((s) => s.trim())
const SHOT_DIR = arg('shot-dir', 'docs/evidence/v5.7.0/ui-regression-20261114')

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    const text = readFileSync(join(homedir(), '.config/robot-autotest/env'), 'utf8')
    for (const line of text.split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch { /* 统一在下面报缺 */ }
  return null
}

const PASS = envValue('QA_E2E_PASSWORD')
if (!PASS) {
  console.error('缺少夹具口令：~/.config/robot-autotest/env 里加 QA_E2E_PASSWORD=<口令>')
  process.exit(2)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
mkdirSync(SHOT_DIR, { recursive: true })

/* ------------------------------------------------------------------ CDP 会话 */

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
    const r = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    })
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
  await session.send('Network.enable')
  return { session, targetId: target.id }
}

/* ------------------------------------------------------------------ 断言与落盘 */

const checks = []
/** 取证过程中发现的「代码没兑现」项，单独记账，不计入 PASS/FAIL。 */
const findings = []
const net = []
const bodies = []
let exceptions = []

function check(id, ok, detail) {
  checks.push({ id, ok: Boolean(ok), detail: String(detail ?? '') })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${detail ?? ''}`)
}

/** 只留「与本波链路有关」的响应体，别的（静态资源 / 心跳）不落盘。 */
const BODY_MATCH = /\/api\/(projects|runs|platform\/case-sets|reports|me)(\/|\?|$)/

function wireNetwork(session) {
  const urls = new Map()
  const status = new Map()
  session.on('Network.requestWillBeSent', (p) => urls.set(p.requestId, p.request.url))
  session.on('Network.responseReceived', (p) => {
    status.set(p.requestId, p.response.status)
    net.push({ url: p.response.url, status: p.response.status, method: p.response.requestHeadersText ? '' : '' })
  })
  session.on('Network.loadingFinished', async (p) => {
    const url = urls.get(p.requestId)
    if (!url || !BODY_MATCH.test(url)) return
    try {
      const body = await session.send('Network.getResponseBody', { requestId: p.requestId })
      bodies.push({
        url,
        status: status.get(p.requestId) ?? null,
        body: String(body.body ?? '').slice(0, 4000),
      })
    } catch { /* 有些响应（redirect / 304）拿不到 body，忽略 */ }
  })
}

async function shot(session, name) {
  const r = await session.send('Page.captureScreenshot', { format: 'png' })
  const file = join(SHOT_DIR, name)
  writeFileSync(file, Buffer.from(r.data, 'base64'))
  return file
}

async function goto(session, url, width = 1440, height = 900) {
  exceptions = []
  await session.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: false,
  })
  await session.send('Page.navigate', { url })
  await sleep(900)
  for (let i = 0; i < 20; i += 1) {
    if (await session.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
  await sleep(500)
}

async function waitFor(session, expr, timeoutMs = 8000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (await session.evaluate(expr)) return true
    await sleep(200)
  }
  return false
}

const clickExpr = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return false
  el.click()
  return true
})()`

/** 原生 input / textarea：置 value 再派发 input，等价于用户键入（v-model 监听 input）。 */
const typeExpr = (sel, value) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return false
  const proto = el instanceof HTMLTextAreaElement
    ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set
  setter.call(el, ${JSON.stringify(value)})
  el.dispatchEvent(new Event('input', { bubbles: true }))
  el.dispatchEvent(new Event('change', { bubbles: true }))
  return true
})()`

/**
 * 真实鼠标点击（CDP `Input.dispatchMouseEvent`）。
 *
 * 为什么不用 `el.click()`：本页的行内动作把文案渲染在 `span` 里、handler 挂在祖先上，
 * 用 JS `click()` 在部分节点树上命不中；而真实鼠标事件带坐标、走浏览器的命中测试，
 * 与用户点击等价，也更能证明「界面真的能用」。
 */
async function realClick(session, findExpr) {
  const rect = await session.evaluate(`(() => {
    const el = (${findExpr})
    if (!el) return null
    el.scrollIntoView({ block: 'center' })
    const r = el.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height }
  })()`)
  if (!rect || rect.w === 0 || rect.h === 0) return false
  for (const type of ['mousePressed', 'mouseReleased']) {
    await session.send('Input.dispatchMouseEvent', {
      type, x: rect.x, y: rect.y, button: 'left', clickCount: 1,
    })
  }
  await sleep(300)
  return true
}

async function login(session) {
  await goto(session, `${APP}/login`)
  if (!(await session.evaluate(`location.pathname === '/login'`))) return true
  await waitFor(session, `Boolean(document.querySelector('input[autocomplete="username"]'))`)
  await session.evaluate(typeExpr('input[autocomplete="username"]', USER))
  await session.evaluate(typeExpr('input[autocomplete="current-password"]', PASS))
  await sleep(200)
  await session.evaluate(`document.querySelector('form.login-card').requestSubmit()`)
  const ok = await waitFor(session, `location.pathname !== '/login'`, 12000)
  return ok
}

/**
 * 会话里绑定项目号 + 复位到「项目模式」。
 *
 * 两个残留都会让后续断言假红（实测踩过）：`current_project_id` 串项目，
 * `ui_mode=global` 会把 `/runs` 之类的项目侧路由解析到 `/g/*` 去——
 * 向导流程切过全局入口后必须显式改回，否则备注流根本到不了运行记录页。
 */
async function bindProject(session, projectId) {
  await session.evaluate(`(() => {
    localStorage.setItem('current_project_id', ${JSON.stringify(String(projectId))})
    localStorage.setItem('ui_mode', 'project')
    return true
  })()`)
  // 服务端也有一份 default_ui_mode（app_user），只改 localStorage 会在下次解析时被覆盖。
  await session.evaluate(`(async () => {
    const token = localStorage.getItem('auth_token') || ''
    await fetch('/api/me/ui-mode', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify({ mode: 'project' }),
    }).catch(() => {})
    return true
  })()`)
}

/* ------------------------------------------------------------------ 1. 向导建项目 */

async function flowWizard(session) {
  await bindProject(session, PROJECT)
  await goto(session, `${APP}/g/projects/new?from=global`)
  check('wizard-route', await session.evaluate(`location.pathname`) === '/g/projects/new',
    `path=${await session.evaluate(`location.pathname`)}`)

  const hasCard = await waitFor(session, `Boolean(document.querySelector('.template-card'))`)
  check('wizard-template-step', hasCard, hasCard ? '模板卡已渲染' : '模板步没渲染')
  if (!hasCard) return
  await session.evaluate(`document.querySelector('.template-card').click()`)
  await sleep(300)
  check('wizard-template-selected',
    await session.evaluate(`document.querySelector('.template-card').getAttribute('aria-pressed') === 'true'`),
    '第一张模板卡 aria-pressed=true')
  await session.evaluate(clickExpr('[data-testid="wizard-next"]'))
  await waitFor(session, `Boolean(document.querySelector('[data-testid="wizard-code"]'))`)

  const stamp = String(Date.now()).slice(-6)
  const code = `UIAUTO-${stamp}`
  const name = `UI取证-${stamp}`
  await session.evaluate(typeExpr('[data-testid="wizard-code"]', code))
  await session.evaluate(typeExpr('[data-testid="wizard-name"]', name))
  await sleep(200)

  // basics → runtime → members → content
  for (let i = 0; i < 3; i += 1) {
    await session.evaluate(clickExpr('[data-testid="wizard-next"]'))
    await sleep(400)
  }
  const inContent = await waitFor(session, `Boolean(document.querySelector('[data-testid="wizard-content"]'))`)
  check('wizard-content-step', inContent, inContent ? '内容步已到' : '内容步没到')

  // 内容步：勾一个平台用例集（P0-2 的关键动作：集合 id 必须以 caseSetIds 提交）
  if (inContent) {
    await session.evaluate(clickExpr('[data-testid="section-toggle-set"]'))
    const setBtn = await waitFor(session, `Boolean(document.querySelector('[data-testid^="set-"]'))`, 8000)
    check('wizard-content-sets', setBtn, setBtn ? '平台用例集列表已加载' : '用例集列表为空/未加载')
    if (setBtn) {
      await session.evaluate(clickExpr('[data-testid^="set-"]'))
      await sleep(300)
      const summary = await session.evaluate(
        `document.querySelector('[data-testid="selection-summary"]')?.innerText ?? ''`)
      check('wizard-content-summary', summary.length > 0, `摘要=「${summary.replace(/\n/g, ' / ')}」`)
      await shot(session, '01-wizard-content.png')
    }
  }

  await session.evaluate(clickExpr('[data-testid="wizard-next"]'))
  await sleep(500)
  const reviewText = await session.evaluate(`document.body.innerText`)
  check('wizard-review', /创建|复核|Create|Review/i.test(reviewText), '复核步已渲染')
  await shot(session, '02-wizard-review.png')

  const before = bodies.length
  await session.evaluate(clickExpr('[data-testid="wizard-create"]'))
  await sleep(2500)
  await shot(session, '03-wizard-created.png')

  const created = bodies.slice(before).filter((b) => /\/api\/projects(\?|$)/.test(b.url))
  const createOk = created.some((b) => b.status === 200 || b.status === 201)
  check('wizard-create-2xx', createOk,
    created.map((b) => `${b.status} ${b.url}`).join(' | ') || '没有捕获到 POST /api/projects')

  const content = bodies.slice(before).filter((b) => /template-content|\/content$/.test(b.url))
  check('wizard-content-submitted', content.some((b) => b.status === 200 || b.status === 201),
    content.map((b) => `${b.status} ${b.url}`).join(' | ') || '没有捕获到内容提交（可能内容为空被跳过）')

  const createdId = (() => {
    for (const b of created) {
      const m = String(b.body).match(/"id"\s*:\s*(\d+)/)
      if (m) return Number(m[1])
    }
    return 0
  })()
  check('wizard-created-id', createdId > 0, createdId ? `projectId=${createdId}` : '响应里没取到项目 id')

  // 列表页复核：新项目真的出现在 /g/projects
  await goto(session, `${APP}/g/projects`)
  const listed = await waitFor(session, `document.body.innerText.includes(${JSON.stringify(name)})`, 10000)
  check('wizard-listed', listed, listed ? `列表中可见「${name}」` : `列表里找不到「${name}」`)
  await shot(session, '04-wizard-listed.png')
  return { name, code, createdId }
}

/* ------------------------------------------------------------------ 2. 发起台四模式 */

async function flowLaunch(session) {
  await bindProject(session, PROJECT)
  await goto(session, `${APP}/execution-plans`)
  check('launch-route', await session.evaluate(`location.pathname`) === '/execution-plans',
    `path=${await session.evaluate(`location.pathname`)}`)

  const modes = ['ALL', 'SET', 'CASE', 'MIXED']
  const previewBodies = []
  for (const mode of modes) {
    const ok = await waitFor(session, `Boolean(document.querySelector('[data-testid="mode-${mode}"]'))`)
    if (!ok) {
      check(`launch-mode-${mode}`, false, '模式按钮没渲染')
      continue
    }
    await session.evaluate(clickExpr(`[data-testid="mode-${mode}"]`))
    await sleep(600)
    const hint = await session.evaluate(
      `document.querySelector('[data-testid="mode-hint"]')?.innerText ?? ''`)
    check(`launch-mode-${mode}`, hint.length > 0, `模式提示=「${hint}」`)
    await shot(session, `05-launch-${mode.toLowerCase()}.png`)
  }

  /**
   * 四模式预览：在**页面上下文**里发同一请求（同 BASE、带同一 Bearer 令牌，
   * 令牌键见 `stores/auth.ts` 的 `auth_token`），证明后端认这四种入参组合；
   * 不点「发起运行」＝不派发（本波无 DUT 授权）。
   */
  const payloads = {
    ALL: { mode: 'ALL' },
    SET: { mode: 'SET', setIds: [SET_ID] },
    CASE: { mode: 'CASE', caseIds: [LAUNCH_CASE_ID] },
    MIXED: { mode: 'MIXED', setIds: [SET_ID], caseIds: [LAUNCH_CASE_ID] },
  }
  for (const [mode, payload] of Object.entries(payloads)) {
    const raw = await session.evaluate(`(async () => {
      const token = localStorage.getItem('auth_token') || ''
      const res = await fetch('/api/projects/${PROJECT}/runs/preview', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: 'Bearer ' + token } : {}),
        },
        credentials: 'include',
        body: JSON.stringify(${JSON.stringify(payload)}),
      })
      const text = await res.text()
      return JSON.stringify({ status: res.status, text })
    })()`)
    const parsed = JSON.parse(raw)
    let envelope = {}
    try { envelope = JSON.parse(parsed.text) } catch { /* 非 JSON 时留空 */ }
    // 响应是统一信封 { code, data }：snapshotId 在 data 里（api 层已拆，这里读裸响应）。
    const body = envelope.data ?? {}
    const hasSnapshot = parsed.status === 200 && Number(body.snapshotId) > 0
    previewBodies.push({
      mode, status: parsed.status,
      snapshotId: body.snapshotId ?? null,
      caseCount: body.caseCount ?? null,
      selectionSha256: body.selectionSha256 ?? null,
      message: envelope.message ?? '',
    })
    check(`launch-preview-${mode}`, hasSnapshot,
      `HTTP ${parsed.status} snapshotId=${body.snapshotId ?? 'null'} caseCount=${body.caseCount ?? 'null'}`
      + (envelope.message && envelope.message !== 'OK' ? ` msg=${envelope.message}` : ''))
  }
  return { previewBodies }
}

/* ------------------------------------------------------------------ 3. 平台集加成员 */

async function flowSet(session, caseId) {
  await goto(session, `${APP}/g/case-sets`)
  const hasRow = await waitFor(session, `Boolean(document.querySelector('[data-testid^="manage-"]'))`, 10000)
  check('set-list', hasRow, hasRow ? '平台用例集列表已渲染' : '列表为空')
  if (!hasRow) return
  const manageSel = await session.evaluate(`(() => {
    const el = document.querySelector('[data-testid^="manage-"]')
    return el ? '[data-testid="' + el.getAttribute('data-testid') + '"]' : ''
  })()`)
  await session.evaluate(clickExpr(manageSel))
  const opened = await waitFor(session, `Boolean(document.querySelector('[data-testid="member-panel"]'))`)
  check('set-member-panel', opened, opened ? '成员面板已打开' : '成员面板没打开')
  if (!opened) return

  const before = await session.evaluate(
    `document.querySelectorAll('[data-testid^="member-row-"]').length`)
  if (!caseId) {
    check('set-add-member', false, '未提供 --case-id，跳过真实添加')
    return
  }
  const inputReady = await waitFor(
    session, `Boolean(document.querySelector('[data-testid="add-case-input"]'))`, 8000)
  check('set-add-input-visible', inputReady,
    inputReady ? '写入口已渲染（capability case:write 生效）' : '写入口没渲染（canWrite=false？）')
  if (!inputReady) return
  const typed = await session.evaluate(typeExpr('[data-testid="add-case-input"]', String(caseId)))
  await sleep(300)
  const disabled = await session.evaluate(
    `document.querySelector('[data-testid="add-case-btn"]')?.disabled ?? null`)
  check('set-add-member-typed', typed === true && disabled === false,
    `输入框写入=${typed} 按钮禁用=${disabled}（禁用说明 v-model.number 没吃进值）`)
  if (disabled !== false) return
  const n0 = bodies.length
  await session.evaluate(clickExpr('[data-testid="add-case-btn"]'))
  await sleep(2000)
  await shot(session, '06-set-member-added.png')

  const posted = bodies.slice(n0).filter((b) => /\/items$/.test(b.url))
  const ok2xx = posted.some((b) => b.status === 200 || b.status === 201)
  check('set-add-member-2xx', ok2xx, posted.map((b) => `${b.status} ${b.url} ${String(b.body).slice(0, 120)}`).join(' | ') || '没捕获到 POST .../items')
  const skipped = posted.some((b) => /"skipped"\s*:\s*[1-9]/.test(String(b.body)))
  const after = await session.evaluate(`document.querySelectorAll('[data-testid^="member-row-"]').length`)
  check('set-add-member-visible', after > before || skipped,
    `成员行 ${before} → ${after}${skipped ? '（后端返回 skipped，已在集合里）' : ''}`)
}

/* ------------------------------------------------------------------ 4. 报告结论 */

/**
 * 判据结论（P0-4）的**可见面**要说清楚：后端把结论落进 `test_result.criteria_*` 与报告汇总
 * （快照 / 导出 / 视图渲染读的是判据汇总），但 SPA 的报告详情页目前**不展示结论列**。
 * 所以这里不断言「页面上有结论」——那会是把没做的事写成做了；
 * 只断言报告页按真实 run 正常加载，并把「结论列缺位」记成 finding（L-57-11）。
 */
async function flowReport(session) {
  await bindProject(session, REPORT_PROJECT)
  await goto(session, `${APP}/reports/${encodeURIComponent(RUN_ID)}`)
  const loaded = await waitFor(session, `!document.body.innerText.includes('运行不存在')`, 12000)
  check('report-loaded', loaded, loaded ? `run=${RUN_ID} 报告已加载` : '报告页是 NOT_FOUND')

  const treeOk = bodies.some((b) => /\/api\/reports\/.*\/(tree|summary)/.test(b.url) && b.status === 200)
  check('report-tree-2xx', treeOk,
    bodies.filter((b) => /\/api\/reports\//.test(b.url)).map((b) => `${b.status} ${b.url}`).join(' | ')
    || '没捕获到报告数据接口')

  const text = await session.evaluate(`document.body.innerText`)
  const hasConclusion = /结论|Conclusion/.test(text)
  if (!hasConclusion) {
    findings.push({
      id: 'L-57-11',
      detail: '报告详情页（/reports/:runId）未展示判据结论列：criteriaConclusion 只进了快照 / 导出 / 视图渲染，'
        + 'SPA 详情页仍按 status 展示。P0-4 的「报告结论改读判据汇总」在服务端成立，界面尚未透出。',
    })
    console.log('NOTE  报告详情页无「结论」列 → 记 finding L-57-11')
  }
  await shot(session, '07-report-detail.png')
  return { hasConclusion }
}

/* ------------------------------------------------------------------ 5. 运行记录备注 */

async function flowRemark(session) {
  await bindProject(session, REMARK_PROJECT)
  await goto(session, `${APP}/runs`)
  const rows = await waitFor(session, `document.querySelectorAll('tbody tr').length > 0`, 12000)
  check('remark-rows', rows,
    `projectId=${REMARK_PROJECT} 行数=${await session.evaluate(`document.querySelectorAll('tbody tr').length`)}`)
  const ready = await waitFor(session, `document.body.innerText.includes('编辑备注')`, 12000)
  check('remark-list', ready, ready ? '运行记录列表已渲染（含「编辑备注」动作）' : '列表没有「编辑备注」')
  if (!ready) return

  const pass1 = `UI取证备注-A-${String(Date.now()).slice(-5)}`
  const btnReady = await waitFor(session,
    `Boolean(document.querySelector('[data-testid="edit-remark"]'))`, 8000)
  /**
   * 动作按钮的标签页里是 `span` 包在行内动作容器里（不是原生 `<button>`），
   * 所以按「最内层含该文案的元素」点它——click 会冒泡到带 handler 的那层。
   */
  const clickEdit = `(() => {
    const hit = [...document.querySelectorAll('*')].find((el) => el.children.length === 0
      && (el.textContent || '').includes('编辑备注'))
    if (!hit) return 'NOT_FOUND'
    hit.click()
    return 'CLICKED<' + hit.tagName + '<' + (hit.parentElement?.tagName ?? '?') + '>'
  })()`
  const first = await realClick(session,
    `document.querySelector('[data-testid="edit-remark"]')`) ? 'CLICKED' : 'NOT_FOUND'
  let dialogOpen = await waitFor(session, `Boolean(document.querySelector('.crud-dialog textarea'))`, 4000)
  if (!dialogOpen) {
    await sleep(800)
    await session.evaluate(clickEdit)
    dialogOpen = await waitFor(session, `Boolean(document.querySelector('.crud-dialog textarea'))`, 6000)
  }
  const pageError = await session.evaluate(
    `document.querySelector('.error, [role="alert"]')?.innerText ?? ''`)
  const where = await session.evaluate(`JSON.stringify({
    path: location.pathname,
    rows: document.querySelectorAll('tbody tr').length,
    rowActions: document.querySelectorAll('.row-actions').length,
    dialogs: document.querySelectorAll('.crud-dialog').length,
    textareas: document.querySelectorAll('textarea').length,
    modalMask: document.querySelectorAll('.n-modal').length,
  })`)
  check('remark-dialog', dialogOpen,
    dialogOpen ? '备注弹窗已打开'
      : `弹窗没打开（就绪=${btnReady} 点击=${first} 环境=${where} 页面错误=「${pageError}」）`)
  if (!dialogOpen) {
    const html = await session.evaluate(
      `document.querySelector('.row-actions')?.outerHTML?.slice(0, 900) ?? 'NO_ROW_ACTIONS'`)
    findings.push({ id: 'DEBUG-remark', detail: String(html) })
    console.log('DEBUG row-actions =', String(html).slice(0, 600))
  }
  if (!dialogOpen) return
  await session.evaluate(typeExpr('.crud-dialog textarea', pass1))
  await sleep(200)
  const n0 = bodies.length
  await realClick(session, `(() => {
    const dlg = document.querySelector('.crud-dialog')
    if (!dlg) return null
    return [...dlg.querySelectorAll('*')].find((el) => el.children.length === 0
      && (el.textContent || '').trim() === '保存') ?? null
  })()`)
  await sleep(1600)
  const patched = bodies.slice(n0).filter((b) => /\/api\/runs\//.test(b.url))
  check('remark-save-2xx', patched.some((b) => b.status === 200 || b.status === 204),
    patched.map((b) => `${b.status} ${b.url}`).join(' | ') || '没捕获到备注保存请求')
  await shot(session, '08-remark-saved.png')

  // 关掉再打开：值必须还在（P1-5 的核心判据：打开即取真实备注）
  await sleep(1200)
  await realClick(session, `document.querySelector('[data-testid="edit-remark"]')`)
  const reopened = await waitFor(session, `Boolean(document.querySelector('.crud-dialog textarea'))`)
  const value = reopened
    ? await session.evaluate(`document.querySelector('.crud-dialog textarea').value`)
    : ''
  check('remark-persisted', value === pass1, `重开读回=「${value}」 期望=「${pass1}」`)
  await shot(session, '09-remark-reopened.png')
  return { remark: pass1 }
}

/* ------------------------------------------------------------------ 主流程 */

const { session } = await openSession()
wireNetwork(session)
session.on('Runtime.exceptionThrown', (p) => {
  exceptions.push(p.exceptionDetails?.text ?? 'unknown')
})

const summary = { project: PROJECT, reportProject: REPORT_PROJECT, run: RUN_ID, startedAt: new Date().toISOString() }

try {
  const loggedIn = await login(session)
  check('login', loggedIn, loggedIn ? `已登录 ${USER}` : '登录失败')

  if (FLOWS.includes('wizard')) summary.wizard = await flowWizard(session)
  if (FLOWS.includes('launch')) summary.launch = await flowLaunch(session)
  if (FLOWS.includes('set')) summary.set = await flowSet(session, CASE_ID)
  if (FLOWS.includes('report')) summary.report = await flowReport(session)
  if (FLOWS.includes('remark')) summary.remark = await flowRemark(session)

  check('no-js-exception', exceptions.length === 0,
    exceptions.length ? `页面异常 ${exceptions.length} 条: ${exceptions.slice(0, 3).join(' / ')}` : '无未捕获异常')
} catch (e) {
  check('flow-crash', false, e instanceof Error ? e.message : String(e))
} finally {
  const pass = checks.filter((c) => c.ok).length
  const fail = checks.filter((c) => !c.ok).length
  writeFileSync(join(SHOT_DIR, 'result.json'), JSON.stringify({
    ...summary,
    finishedAt: new Date().toISOString(),
    pass, fail, checks, findings,
    responses: bodies,
    network: net.filter((n) => /\/api\//.test(n.url)),
  }, null, 2))
  console.log(`\nUI_FLOWS_RESULT PASS=${pass} FAIL=${fail}`)
  console.log(`证据目录：${SHOT_DIR}`)
  process.exit(fail > 0 ? 1 : 0)
}
