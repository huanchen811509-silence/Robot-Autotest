#!/usr/bin/env node
/**
 * v5.8.0 · W58-13 判据链端到端：报告详情页结论 = 该行 criteria_verdict。
 *
 * 为什么要三处比对
 * ----------------
 * PRD FR-v5.8-07 判据③的原话是「报告详情页的结论 = 该行 `criteria_verdict`」。
 * 只截一张写着「通过」的图证明不了任何事——那可能来自 `failCount == 0` 的旧口径
 * （v5.7.0 之前就是这么算的）。所以这里同时取三个值并**断言三者相等**：
 *
 *   ① 该行 `test_result.criteria_verdict`（真机执行落库的原始判据结论，由 DB 快照提供）
 *   ② `GET /api/reports/{runId}?projectId=` 的 `criteriaConclusion`（服务端聚合）
 *   ③ 报告详情页 `[data-testid="report-conclusion"]` 里渲染出的结论标签（用户实际看到的）
 *
 * 任何一个环节退化成「按失败数推断」，三方就会分叉：旧口径在判据缺失时会给出 PASS，
 * 而 ① 是 MISSING/UNDETERMINED。所以这项断言能抓住口径回退。
 *
 * 用法
 * ----
 *   node scripts/w13-criteria-ui.mjs \
 *        --run EHT-REAL-1794671083499 --project 95 \
 *        --row-verdict docs/evidence/v5.8.0/criteria/raw/row-verdict.json \
 *        --out docs/evidence/v5.8.0/criteria/raw
 *
 * 前置：Chrome --remote-debugging-port=9222；入口 :8001；QA_E2E_PASSWORD 在
 *       ~/.config/robot-autotest/env；run 已入库且 projectId 可访问。
 * 退出码：0 = 全部断言通过；1 = 有断言失败；2 = 前置缺失。
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'

function arg(flag, fallback = null) {
  const i = process.argv.indexOf(flag)
  return i >= 0 ? process.argv[i + 1] : fallback
}

const RUN = arg('--run')
const PROJECT = arg('--project')
const ROW_VERDICT = arg('--row-verdict')
const OUT = arg('--out', 'docs/evidence/v5.8.0/criteria/raw')
const TAG = arg('--tag', '')
const suffix = TAG ? `-${TAG}` : ''

if (!RUN || !PROJECT || !ROW_VERDICT) {
  console.error('用法：--run <RUN_ID> --project <id> --row-verdict <row-verdict.json>')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    for (const line of readFileSync(join(homedir(), '.config', 'robot-autotest', 'env'), 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch { /* 由下面统一报缺 */ }
  return null
}

const PASS = envValue('QA_E2E_PASSWORD')
if (!PASS) {
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
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error(`页面脚本异常: ${r.exceptionDetails.text} ${r.exceptionDetails.exception?.description ?? ''}`)
    }
    return r.result.value
  }

  async shot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    writeFileSync(file, Buffer.from(r.data, 'base64'))
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
  return session
}

const checks = []
function check(id, ok, detail = '') {
  checks.push({ id, ok: Boolean(ok), detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${detail}`)
}

async function goto(session, path) {
  await session.send('Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await session.send('Page.navigate', { url: `${APP}${path}` })
  await sleep(1000)
  for (let i = 0; i < 25; i += 1) {
    if ((await session.evaluate('document.readyState')) === 'complete') break
    await sleep(200)
  }
}

async function waitFor(session, expression, label, tries = 40) {
  for (let i = 0; i < tries; i += 1) {
    if (await session.evaluate(expression)) return true
    await sleep(300)
  }
  console.log(`[wait] 超时：${label}`)
  return false
}

/** 页面测量：结论卡片文本、标签、备注、横向溢出、JS 异常另记。 */
const measure = `(() => {
  const card = document.querySelector('[data-testid="report-conclusion"]')
  const tag = card?.querySelector('.badge, .status-tag, [class*="tag"]')
  const note = Array.from(card?.querySelectorAll('.muted') ?? [])
    .map((el) => el.innerText.trim()).filter(Boolean)
  return {
    path: location.pathname,
    cardMounted: Boolean(card),
    cardText: (card?.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 600),
    summaryText: (document.querySelector('.report-summary')?.innerText || '')
      .replace(/\\s+/g, ' ').trim().slice(0, 600),
    tagText: (tag?.innerText || '').trim(),
    tagClass: tag?.className || '',
    note,
    scrollWidth: document.documentElement.scrollWidth,
    viewport: window.innerWidth,
    bodyHasRunId: (document.body.innerText || '').includes(${JSON.stringify(RUN)}),
  }
})()`

async function main() {
  const rowSnapshot = JSON.parse(readFileSync(ROW_VERDICT, 'utf8'))
  const rawRowVerdict = String(rowSnapshot.criteriaVerdict || '').toUpperCase()
  // 后端口径：单行 MISSING / 未声明，聚合后是 UNDETERMINED —— 既不能当 PASS，也不能当 FAIL
  const rowVerdict = (rawRowVerdict === 'PASS' || rawRowVerdict === 'FAIL')
    ? rawRowVerdict
    : 'UNDETERMINED'
  check('row-verdict-present', Boolean(rawRowVerdict),
    `row=${rowSnapshot.id ?? '?'} case=${rowSnapshot.caseKey ?? '?'} verdict=${rawRowVerdict}→${rowVerdict}`)
  rowSnapshot.criteriaVerdictRaw = rawRowVerdict

  const session = await openSession()
  const exceptions = []
  session.on('Runtime.exceptionThrown', (p) => {
    exceptions.push(p.exceptionDetails?.exception?.description ?? p.exceptionDetails?.text ?? 'unknown')
  })

  await goto(session, '/login')
  const login = await session.evaluate(`(async () => {
    const resp = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(PASS)} }),
    })
    const body = await resp.json()
    if (!resp.ok || !body.data || !body.data.token) return { ok: false, status: resp.status }
    localStorage.setItem('auth_token', body.data.token)
    localStorage.setItem('auth_expires_at', body.data.expiresAt ?? '')
    localStorage.setItem('current_project_id', ${JSON.stringify(PROJECT)})
    return { ok: true, token: body.data.token }
  })()`)
  if (!login?.ok) throw new Error(`登录失败: ${JSON.stringify(login)}`)
  console.log('[auth] ok')

  // ① 接口侧：服务端聚合结论
  const headers = { Authorization: `Bearer ${login.token}`, 'X-Project-Id': PROJECT }
  const resp = await fetch(`${APP}/api/reports/${encodeURIComponent(RUN)}?projectId=${PROJECT}`, { headers })
  const body = await resp.json()
  writeFileSync(join(OUT, `report-detail${suffix}.json`), JSON.stringify(body, null, 2) + '\n')
  const data = body?.data ?? {}
  const apiConclusion = String(data.criteriaConclusion || '').toUpperCase()
  check('api-200', resp.ok, `HTTP ${resp.status}`)
  check('api-conclusion-present', Boolean(apiConclusion), `criteriaConclusion=${apiConclusion || '(空)'}`)
  check('api-criteria-counts',
    typeof data.criteriaEvaluated === 'number'
    && typeof data.criteriaMissingCount === 'number'
    && data.criteriaEvaluated <= (rowSnapshot.rowCount ?? data.criteriaEvaluated)
    && data.criteriaMissingCount <= (rowSnapshot.rowCount ?? data.criteriaMissingCount),
    `evaluated=${data.criteriaEvaluated} missing=${data.criteriaMissingCount} rows=${rowSnapshot.rowCount}`)

  // ② 界面侧：报告详情页
  exceptions.length = 0
  await goto(session, `/reports/${encodeURIComponent(RUN)}`)
  const mounted = await waitFor(session,
    `Boolean(document.querySelector('[data-testid="report-conclusion"]'))`, '结论卡片')
  // 结论是异步拿的，等标签文本落地（不是「未判定」的初值）
  await waitFor(session,
    `(() => { const c = document.querySelector('[data-testid="report-conclusion"]');
              return Boolean(c && c.innerText.trim().length > 4) })()`, '结论文本')
  const m = await session.evaluate(measure)

  check('ui-conclusion-mounted', mounted, m.cardMounted ? 'ok' : '结论卡片未渲染')
  check('ui-run-id-visible', m.bodyHasRunId, m.bodyHasRunId ? RUN : '页面未见 RUN_ID')
  check('ui-conclusion-tag-nonempty', m.tagText.length > 0, `tag="${m.tagText}" class="${m.tagClass}"`)
  check('ui-no-hscroll', m.scrollWidth <= m.viewport + 1,
    `scrollWidth=${m.scrollWidth} viewport=${m.viewport}`)
  check('ui-no-js-error', exceptions.length === 0, exceptions[0]?.slice(0, 140) ?? '0 exception')
  await session.shot(join(OUT, `ui-report-conclusion${suffix}-1440.png`))

  // ③ 三方一致：页面标签 = 接口结论 = 该行 DB verdict
  const LABELS = { PASS: '通过', FAIL: '不通过', UNDETERMINED: '未判定' }
  check('api-equals-row', apiConclusion.length > 0 && apiConclusion === rowVerdict,
    `api=${apiConclusion} row=${rowVerdict}`)
  check('ui-tag-equals-api', m.tagText === LABELS[apiConclusion],
    `ui="${m.tagText}" expect="${LABELS[apiConclusion] ?? '?'}" (api=${apiConclusion})`)
  // 判据缺失时结论卡片给的是「不按失败数推断」的说明，而不是计数；两种形态分别断言
  const noteOk = apiConclusion === 'UNDETERMINED'
    ? m.note.some((s) => s.includes('不按失败数推断') || s.includes('判据'))
    : (m.note.some((s) => s.includes('已评估') && s.includes(String(data.criteriaEvaluated)))
       && m.note.some((s) => s.includes('判据缺失') && s.includes(String(data.criteriaMissingCount))))
  check('ui-note-matches-counts', noteOk, m.note.join(' | '))
  // 「用例全通过但判据缺失」时绝不能出现「全部通过」这类按失败数推的结论
  check('ui-no-counted-verdict',
    !m.cardText.includes('全部通过') && !m.summaryText.includes('全部通过'),
    `card="${m.cardText.slice(0, 90)}" summary="${m.summaryText.slice(0, 90)}"`)

  writeFileSync(join(OUT, `ui-measure${suffix}.json`), JSON.stringify({
    capturedAt: new Date().toISOString(),
    app: APP,
    runId: RUN,
    projectId: Number(PROJECT),
    row: rowSnapshot,
    api: {
      criteriaConclusion: apiConclusion,
      criteriaEvaluated: data.criteriaEvaluated,
      criteriaMissingCount: data.criteriaMissingCount,
      counts: data.counts ?? null,
      exportable: data.exportable,
      metadataMissing: data.metadataMissing ?? [],
    },
    ui: {
      tagText: m.tagText,
      tagClass: m.tagClass,
      cardText: m.cardText,
      summaryText: m.summaryText,
      note: m.note,
    },
    checks,
  }, null, 2) + '\n')

  const failed = checks.filter((c) => !c.ok).length
  console.log(`\nW13_CRITERIA_UI ${failed === 0 ? 'PASS' : `FAIL(${failed})`}  out=${OUT}`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((err) => {
  console.error(`W13_CRITERIA_UI ERROR ${err.message}`)
  process.exit(1)
})
