#!/usr/bin/env node
/**
 * 测试报告「从执行记录选 run」探针（FR-v3.10-02 判据① ② ③ ④）。
 *
 * 为什么单开一支：这四条判据全在**交互之后**才看得出来——
 *   ① 下拉里有没有 run（空下拉也「渲染成功」，静态检查看不出）；
 *   ② 关键词过滤是否真接上（没接上界面照常显示，只是过滤不生效）；
 *   ③ 选中后标题有没有自动带出（不带就提交空标题，后端接受，报告叫「无标题」）；
 *   ④ 手打入口是否已降级（还留在主路径上就没有实现这条判据）。
 *
 * 用哪个项目：默认 29——本库 run 最多的项目（470 条）。演示项目 1 号只有 8 条、
 * 关键词过滤几乎测不出差异。
 *
 * 已知局限（写进证据，不掩饰）：
 *   - 本库全部 run 都落在最近 5 天内，「近 7 天 / 近 30 天」切不出差异，探针只验证
 *     控件存在且可切换，时间档边界由 `frontend/src/utils/runPicker.spec.ts` 钉住
 *     （恰好 7 天前保留、早 1 ms 剔除）。
 *   - 选项计数读的是**已渲染**的选项节点：naive-ui 用虚拟列表，100 条时只渲染可见
 *     那部分。所以过滤后「只剩 1 条」这种判断只在结果集很小时可靠，正好够用。
 *
 * 用法：node scripts/report-picker-probe.mjs
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const OUT = process.env.REPORT_OUT ?? 'docs/evidence/v3.10.0/EW310-02/report-picker.json'
const PROJECT_ID = process.env.REPORT_PROJECT_ID ?? '29'

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    for (const line of readFileSync(`${homedir()}/.config/robot-autotest/env`, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch {
    /* 没有配置文件就靠环境变量 */
  }
  return ''
}

async function http(path, options = {}) {
  const response = await fetch(`${CDP}${path}`, options)
  if (!response.ok) throw new Error(`${path} -> ${response.status}`)
  return response.json()
}

class Session {
  constructor(url) {
    this.ws = new WebSocket(url)
    this.seq = 0
    this.pending = new Map()
    this.ws.addEventListener('message', (event) => {
      const data = JSON.parse(event.data)
      const waiter = this.pending.get(data.id)
      if (!waiter) return
      this.pending.delete(data.id)
      data.error ? waiter.reject(new Error(data.error.message)) : waiter.resolve(data.result)
    })
  }

  ready() {
    return new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true })
      this.ws.addEventListener('error', reject, { once: true })
    })
  }

  send(method, params = {}) {
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }

  close() {
    this.ws.close()
  }
}

/** 页面静态结构：三个入口在不在一处、手打框是否已被挪走。 */
const STRUCT_EXPR = `(() => {
  const vis = (el) => {
    if (!el) return false
    const r = el.getBoundingClientRect()
    const s = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden'
  }
  const label = (el) => (el?.textContent || '').replace(/\\s+/g, ' ').trim()
  return {
    path: location.pathname,
    picker: vis(document.querySelector('[data-testid="run-picker-select"]')),
    keyword: vis(document.querySelector('[data-testid="run-picker-keyword"]')),
    range: vis(document.querySelector('[data-testid="run-picker-range"]')),
    rangeLabel: label(document.querySelector('[data-testid="run-picker-range"]')),
    manualInput: vis(document.querySelector('[data-testid="manual-run-id"]')),
    titleInput: vis(document.querySelector('[data-testid="draft-title"]')),
    toggleLabel: label(document.querySelector('[data-testid="toggle-advanced"]')),
    toggleAdvanced: vis(document.querySelector('[data-testid="toggle-advanced"]')),
    sourceRun: vis(document.querySelector('[data-testid="source-run"]')),
    sourceCaseSet: vis(document.querySelector('[data-testid="source-case-set"]')),
    caseSetPicker: vis(document.querySelector('[data-testid="case-set-select"]')),
    caseSetRunInput: vis(document.querySelector('[data-testid="case-set-run-id"]')),
    emptyHint: vis(document.querySelector('[data-testid="run-picker-empty"]')),
    failedHint: vis(document.querySelector('[data-testid="run-picker-failed"]')),
  }
})()`

/**
 * 打开下拉并读已渲染选项。
 *
 * 关下拉用 `document.body.dispatchEvent(keydown Escape)`：点空白处会命中表格行，
 * 在报告页可能顺手打开详情抽屉，把后续读数搅乱。
 */
const OPEN_EXPR = `(async () => {
  const vis = (el) => {
    if (!el) return false
    const r = el.getBoundingClientRect()
    return r.width > 0 && r.height > 0
  }
  const root = document.querySelector('[data-testid="run-picker-select"]')
  if (!root) return { options: [], error: 'no picker' }
  const selection = root.querySelector('.n-base-selection')
  if (selection) selection.click()
  await new Promise((r) => setTimeout(r, 400))
  const menus = [...document.querySelectorAll('.n-base-select-menu')].filter(vis)
  const menu = menus[menus.length - 1]
  const options = menu
    ? [...menu.querySelectorAll('.n-base-select-option')]
        .filter(vis)
        .map((el) => (el.textContent || '').replace(/\\s+/g, ' ').trim())
    : []
  return { options }
})()`

const CLOSE_EXPR = `(async () => {
  document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  document.body.click()
  await new Promise((r) => setTimeout(r, 250))
  return true
})()`

/** 往 NInput 里填值：直接改 value 不触发 Vue 的 v-model，必须走原生 setter + input 事件。 */
function typeExpr(testid, value) {
  return `(async () => {
    const input = document.querySelector('[data-testid="${testid}"] input, input[data-testid="${testid}"]')
    if (!input) return { ok: false, error: 'no input' }
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(input, ${JSON.stringify(value)})
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await new Promise((r) => setTimeout(r, 400))
    return { ok: true, value: input.value }
  })()`
}

/** 点第一个选项，返回选中后的标题值。 */
const PICK_FIRST_EXPR = `(async () => {
  const vis = (el) => {
    if (!el) return false
    const r = el.getBoundingClientRect()
    return r.width > 0 && r.height > 0
  }
  const menus = [...document.querySelectorAll('.n-base-select-menu')].filter(vis)
  const menu = menus[menus.length - 1]
  const first = menu?.querySelector('.n-base-select-option')
  if (!first) return { ok: false, error: 'no option' }
  first.click()
  await new Promise((r) => setTimeout(r, 500))
  const title = document.querySelector('[data-testid="draft-title"] input, input[data-testid="draft-title"]')
  return { ok: true, title: title ? title.value : null }
})()`

async function loginInPage(session, password) {
  await session.send('Page.navigate', { url: `${APP}/login` })
  await new Promise((r) => setTimeout(r, 1500))
  const result = await session.evaluate(`(async () => {
    const resp = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(password)} }),
    })
    if (!resp.ok) return { ok: false, status: resp.status }
    const body = await resp.json()
    const token = body?.data?.token ?? body?.token
    if (!token) return { ok: false, status: resp.status, shape: Object.keys(body ?? {}) }
    localStorage.setItem('auth_token', token)
    localStorage.setItem('auth_expires_at', body?.data?.expiresAt ?? '')
    localStorage.setItem('current_project_id', ${JSON.stringify(PROJECT_ID)})
    return { ok: true }
  })()`)
  if (!result?.ok) throw new Error(`登录失败: ${JSON.stringify(result)}`)
}

async function main() {
  const password = envValue('QA_E2E_PASSWORD')
  if (!password) throw new Error('缺 QA_E2E_PASSWORD（~/.config/robot-autotest/env）')
  const target = await http('/json/new?about:blank', { method: 'PUT' })
  const session = new Session(target.webSocketDebuggerUrl)
  await session.ready()
  try {
    await session.send('Page.enable')
    await session.send('Runtime.enable')
    await loginInPage(session, password)
    await session.send('Page.navigate', { url: `${APP}/test-reports` })
    await new Promise((r) => setTimeout(r, 2800))

    /* ---------- 判据④：主路径是下拉，手打入口不在默认视图 ---------- */
    const before = await session.evaluate(STRUCT_EXPR)

    /* ---------- 判据①：下拉里能列出 run，四段信息齐全 ---------- */
    const opened = await session.evaluate(OPEN_EXPR)
    const firstLabel = opened.options[0] ?? ''
    const segments = firstLabel.split(' · ')
    const firstRunId = segments[0] ?? ''

    /* ---------- 判据②：关键词过滤真接上 ---------- */
    await session.evaluate(CLOSE_EXPR)
    await session.evaluate(typeExpr('run-picker-keyword', firstRunId))
    const filtered = await session.evaluate(OPEN_EXPR)
    const filteredLabels = filtered.options

    /* 反证：关键词改成一定匹配不到的值，必须出现「没有执行记录」 */
    await session.evaluate(CLOSE_EXPR)
    await session.evaluate(typeExpr('run-picker-keyword', 'RUN-NOT-EXIST-ZZZ'))
    const missState = await session.evaluate(STRUCT_EXPR)

    /* ---------- 判据③：选中后自动带出标题 ---------- */
    await session.evaluate(typeExpr('run-picker-keyword', firstRunId))
    await session.evaluate(OPEN_EXPR)
    const picked = await session.evaluate(PICK_FIRST_EXPR)

    /* ---------- 判据④（后半）：高级入口可切出手打框，再切回来 ---------- */
    await session.evaluate(`(async () => {
      document.querySelector('[data-testid="toggle-advanced"]')?.click()
      await new Promise((r) => setTimeout(r, 400))
      return true
    })()`)
    const advanced = await session.evaluate(STRUCT_EXPR)
    await session.evaluate(`(async () => {
      document.querySelector('[data-testid="toggle-advanced"]')?.click()
      await new Promise((r) => setTimeout(r, 400))
      return true
    })()`)
    const back = await session.evaluate(STRUCT_EXPR)

    /* ---------- FR-v3.10-03：来源切到「按用例集」后换的是用例集选择器 ---------- */
    await session.evaluate(`(async () => {
      document.querySelector('[data-testid="source-case-set"]')?.click()
      await new Promise((r) => setTimeout(r, 900))
      return true
    })()`)
    const caseSet = await session.evaluate(STRUCT_EXPR)
    await session.evaluate(`(async () => {
      document.querySelector('[data-testid="source-run"]')?.click()
      await new Promise((r) => setTimeout(r, 600))
      return true
    })()`)
    const backToRun = await session.evaluate(STRUCT_EXPR)

    const fails = []
    if (!before.picker) fails.push('主路径没有执行记录下拉（[data-testid=run-picker-select] 不可见）')
    if (!before.keyword) fails.push('缺少关键词过滤输入')
    if (!before.range) fails.push('缺少时间范围选择')
    if (before.manualInput) fails.push('手打 runId 仍在主路径上（判据④ 要求移除或降级）')
    if (!before.titleInput) fails.push('缺少标题输入')
    if (before.failedHint) fails.push('执行记录加载失败（下拉不可用）')
    if (opened.options.length === 0) fails.push('下拉没有列出任何 run')
    if (segments.length !== 4) fails.push(`选项文案不是四段（runId · 时间 · 结论 · 用例数），实际 ${segments.length} 段：${firstLabel}`)
    if (filteredLabels.length !== 1) fails.push(`关键词过滤后应只剩 1 条，实际 ${filteredLabels.length} 条`)
    if (filteredLabels.length > 0 && !filteredLabels.every((l) => l.startsWith(firstRunId))) {
      fails.push('过滤后仍出现不含关键词的选项')
    }
    if (!missState.emptyHint) fails.push('关键词无匹配时没有给出空态提示')
    if (!picked.ok) fails.push(`选中选项失败：${picked.error ?? ''}`)
    if (!picked.title || !picked.title.includes(firstRunId)) {
      fails.push(`选中后标题未自动带出 runId（实际：「${picked.title}」）`)
    }
    if (!advanced.manualInput) fails.push('「高级」没有切出手打输入框')
    if (advanced.picker) fails.push('切到高级后下拉仍然可见（两个入口同时在场）')
    if (!back.picker || back.manualInput) fails.push('从高级切回下拉失败')
    // FR-v3.10-03：来源切换
    if (!before.sourceRun || !before.sourceCaseSet) fails.push('缺少「按执行记录 / 按用例集」来源切换')
    if (!caseSet.caseSetPicker) fails.push('切到「按用例集」后没有出现用例集选择器')
    if (caseSet.picker) fails.push('切到「按用例集」后 run 下拉仍然可见（两种来源混在一起）')
    if (caseSet.toggleAdvanced) fails.push('切到「按用例集」后仍显示「高级：手输 RUN_ID」（那条路径只属于按 run）')
    if (!caseSet.caseSetRunInput) fails.push('「按用例集」缺少「指定 RUN_ID」输入（无法钉死某一次执行）')
    if (!backToRun.picker || backToRun.caseSetPicker) fails.push('从「按用例集」切回「按执行记录」失败')

    const result = {
      generated_at: new Date().toISOString(),
      method: 'CDP：页面内登录 → /test-reports → 打开下拉读选项 → 关键词过滤 → 选中 → 高级切换',
      projectId: PROJECT_ID,
      criteria: {
        '①下拉列出本项目 run': {
          optionCount: opened.options.length,
          firstLabel,
          segments: segments.length,
        },
        '②关键词与时间过滤': {
          keyword: firstRunId,
          filteredOptions: filteredLabels.length,
          filteredLabels,
          missHintShown: missState.emptyHint,
          rangeLabel: before.rangeLabel,
          note: '本库全部 run 在最近 5 天内，7d/30d 切不出差异；时间档边界由 utils/runPicker.spec.ts 钉住',
        },
        '③选中自动带出标题': { titleAfterPick: picked.title },
        '④手打入口降级': {
          mainPathManualInput: before.manualInput,
          advancedManualInput: advanced.manualInput,
          toggleLabel: before.toggleLabel,
        },
        'FR-v3.10-03 来源切换': {
          sourceButtons: { run: before.sourceRun, caseSet: before.sourceCaseSet },
          caseSetPickerVisible: caseSet.caseSetPicker,
          caseSetRunInputVisible: caseSet.caseSetRunInput,
          runPickerHiddenInCaseSet: !caseSet.picker,
          backToRunOk: backToRun.picker && !backToRun.caseSetPicker,
        },
      },
      states: { before, advanced, back, miss: missState, caseSet, backToRun },
      fails,
      verdict: fails.length === 0 ? 'PASS' : 'FAIL',
    }

    mkdirSync(OUT.split('/').slice(0, -1).join('/'), { recursive: true })
    writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8')

    console.log(`[report-picker] 下拉选项=${opened.options.length} 首项=${firstLabel}`)
    console.log(`[report-picker] 关键词=${firstRunId} → 剩 ${filteredLabels.length} 条；无匹配空态=${missState.emptyHint}`)
    console.log(`[report-picker] 选中后标题=${JSON.stringify(picked.title)}`)
    console.log(
      `[report-picker] 主路径手打框=${before.manualInput} 高级手打框=${advanced.manualInput} 入口文案=${before.toggleLabel}`,
    )
    console.log(`REPORT_PICKER ${result.verdict} FAILS=${fails.length} -> ${OUT}`)
    for (const fail of fails) console.log(`  - ${fail}`)
    if (fails.length > 0) process.exitCode = 1
  } finally {
    session.close()
  }
}

main().catch((error) => {
  console.error(`[report-picker] 失败：${error.message}`)
  process.exit(1)
})
