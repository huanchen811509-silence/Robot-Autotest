#!/usr/bin/env node
/**
 * 环境与拓扑 5 页 + 维护与恢复 3 页的浏览器探针（FR-v3.11-02 / 03）。
 *
 * 为什么必须有这支：单测只能证明「源码里没有写方法」，证明不了**运行时真的没发**。
 * v2.2 的只读口径要求「打开页面不产生副作用」，唯一能判的是在真 Chrome 里
 * 录下每次 /api/ 请求的方法。判据：
 *   ① 8 页各自都真的发了 GET /api/（接了真实接口，不是恒空数组）；
 *   ② 只读 6 页（总览/准备/分析 + 维护 3 页）加载期间 0 个写请求；
 *   ③ 采集/拓扑两页加载期间也不写；点一次「采集/建快照」只进入确认态、仍不写；
 *   ④ 采集页白名单 10 类可见、每项带命令；
 *   ⑤ 只读找回检索只发 GET /search，不产生新 run；
 *   ⑥ 空态/错误态控件存在（data-testid 齐备）。
 *
 * 用法：node scripts/env-pages-probe.mjs
 * 输出：docs/evidence/v3.11.0/EW311-02/env-pages.json
 *       docs/evidence/v3.11.0/EW311-03/maintenance-pages.json
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname } from 'node:path'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const PROJECT_ID = process.env.PROJECT_ID ?? '29'
const OUT_ENV = process.env.OUT_ENV ?? 'docs/evidence/v3.11.0/EW311-02/env-pages.json'
const OUT_MAINT = process.env.OUT_MAINT ?? 'docs/evidence/v3.11.0/EW311-03/maintenance-pages.json'

function envValue(key) {
  if (process.env[key]) return process.env[key]
  try {
    for (const line of readFileSync(`${homedir()}/.config/robot-autotest/env`, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/)
      if (m && m[1] === key) return m[2].trim()
    }
  } catch { /* 靠环境变量 */ }
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
    this.requests = []
    this.ws.addEventListener('message', (event) => {
      const data = JSON.parse(event.data)
      if (data.method === 'Network.requestWillBeSent') {
        const req = data.params?.request ?? {}
        this.requests.push({ method: req.method ?? '', url: req.url ?? '' })
      }
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
      expression, awaitPromise: true, returnByValue: true,
    })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }
  clearRequests() { this.requests = [] }
  apiRequests() {
    return this.requests.filter((r) => r.url.includes('/api/'))
      .map((r) => ({ method: r.method, url: r.url.replace(/^https?:\/\/[^/]+/, '') }))
  }
  close() { this.ws.close() }
}

const VIS_EXPR = (testids) => `(() => {
  const vis = (el) => {
    if (!el) return false
    const r = el.getBoundingClientRect()
    const s = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden'
  }
  const ids = ${JSON.stringify(testids)};
  const out = {};
  for (const id of ids) out[id] = vis(document.querySelector('[data-testid="' + id + '"]'));
  out.path = location.pathname;
  out.bodyHasEmpty = (document.body.innerText || '').includes('0 条') || (document.body.innerText || '').includes('0 records');
  out.commandRows = document.querySelectorAll('[data-testid="collect-commands"] li').length;
  out.tableRows = document.querySelectorAll('tbody tr').length;
  return out;
})()`

async function loginInPage(session, password) {
  await session.send('Page.navigate', { url: `${APP}/login` })
  await new Promise((r) => setTimeout(r, 1400))
  const result = await session.evaluate(`(async () => {
    const resp = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(password)} }),
    })
    if (!resp.ok) return { ok: false, status: resp.status }
    const body = await resp.json()
    const token = body?.data?.token ?? body?.token
    if (!token) return { ok: false, status: resp.status }
    localStorage.setItem('auth_token', token)
    localStorage.setItem('auth_expires_at', body?.data?.expiresAt ?? '')
    localStorage.setItem('current_project_id', ${JSON.stringify(PROJECT_ID)})
    return { ok: true }
  })()`)
  if (!result?.ok) throw new Error(`登录失败: ${JSON.stringify(result)}`)
}

const clickExpr = (testid) => `(async () => {
  const el = document.querySelector('[data-testid="${testid}"]');
  if (!el) return { ok: false };
  el.click();
  await new Promise((r) => setTimeout(r, 600));
  return { ok: true, confirming: !!document.querySelector('[data-testid="collect-confirming"]') };
})()`

const PAGES = [
  // required = 无条件渲染、加载完必须可见；其余 testid 是空态/错误态（条件渲染），
  // 只在 pages.spec.ts 里验「存在」，不能拿「可见」来判——有数据时它们本来就不该出现。
  { key: 'overview', path: '/environment/overview', group: 'env', readOnly: true,
    apis: ['/snapshots/profiles', '/devices?', '/asset-host-inventory?'],
    required: ['overview-devices', 'overview-envs', 'overview-latest', 'overview-missing'],
    testids: ['overview-devices', 'overview-envs', 'overview-latest', 'overview-missing', 'overview-empty', 'overview-failed'] },
  { key: 'collect', path: '/environment/collect', group: 'env', readOnly: false,
    apis: ['/asset-host-inventory/commands', '/asset-host-inventory?'],
    required: ['collect-commands', 'collect-confirm', 'collect-host'],
    testids: ['collect-commands', 'collect-confirm', 'collect-host', 'collect-history', 'collect-empty', 'collect-failed'] },
  { key: 'topology', path: '/environment/topology', group: 'env', readOnly: false,
    apis: ['/snapshots/profiles'],
    required: ['topology-env', 'topology-diff', 'topology-confirm'],
    testids: ['topology-env', 'topology-empty', 'topology-diff', 'topology-confirm', 'topology-failed'] },
  { key: 'prepare', path: '/environment/prepare', group: 'env', readOnly: true,
    apis: ['/snapshots/profiles'],
    required: ['prepare-env', 'prepare-latest'],
    testids: ['prepare-env', 'prepare-latest', 'prepare-empty', 'prepare-failed'] },
  { key: 'analysis', path: '/environment/analysis', group: 'env', readOnly: true,
    apis: ['/asset-host-inventory?'],
    required: ['analysis-trend', 'analysis-keyword'],
    testids: ['analysis-trend', 'analysis-keyword', 'analysis-empty', 'analysis-failed'] },
  { key: 'principles', path: '/maintenance/principles', group: 'maint', readOnly: true,
    apis: ['/system/settings', '/settings'],
    required: ['principles-settings'],
    testids: ['principles-settings', 'principles-empty', 'principles-failed'] },
  { key: 'records', path: '/maintenance/records', group: 'maint', readOnly: true,
    apis: ['/audit?'],
    required: ['records-scanned'],
    testids: ['records-scanned', 'records-empty', 'records-failed'] },
  { key: 'recover', path: '/maintenance/recover', group: 'maint', readOnly: true,
    apis: ['/search?'],
    required: ['recover-keyword', 'recover-search', 'recover-hint'],
    testids: ['recover-keyword', 'recover-search', 'recover-hint', 'recover-empty', 'recover-failed'] },
]

const WRITES = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

async function main() {
  const password = envValue('QA_E2E_PASSWORD')
  if (!password) throw new Error('缺 QA_E2E_PASSWORD')
  const target = await http('/json/new?about:blank', { method: 'PUT' })
  const session = new Session(target.webSocketDebuggerUrl)
  await session.ready()
  const checks = []
  const results = { env: [], maint: [] }
  const pass = (name, ok, detail) => { checks.push({ name, ok, detail }); console.log(`[${ok ? 'PASS' : 'FAIL'}] ${name}${detail ? ' · ' + detail : ''}`) }

  try {
    await session.send('Page.enable')
    await session.send('Runtime.enable')
    await session.send('Network.enable')
    await loginInPage(session, password)

    for (const page of PAGES) {
      session.clearRequests()
      await session.send('Page.navigate', { url: `${APP}${page.path}` })
      await new Promise((r) => setTimeout(r, 2100))
      const dom = await session.evaluate(VIS_EXPR(page.testids))
      const requests = session.apiRequests()
      const writes = requests.filter((r) => WRITES.has(r.method))
      const gets = requests.filter((r) => r.method === 'GET')
      const record = { page: page.key, path: page.path, dom, requests, writes, getCount: gets.length }
      results[page.group === 'env' ? 'env' : 'maint'].push(record)

      const missedApis = (page.apis ?? []).filter((needle) =>
        !gets.some((r) => r.url.includes(needle)))
      pass(`${page.key} 打到自己的真实数据接口`, gets.length > 0 && missedApis.length === 0,
        missedApis.length ? `缺 ${missedApis.join(', ')}` : `GET ${gets.length} 条，期望接口全部命中`)
      const missing = page.required.filter((id) => !dom[id])
      pass(`${page.key} 关键控件可见`, missing.length === 0,
        missing.length ? `缺 ${missing.join(', ')}` : `${page.required.length}/${page.required.length} 个必现控件可见`)
      pass(`${page.key} 加载期不写`, writes.length === 0,
        writes.length ? writes.map((w) => `${w.method} ${w.url}`).join(' | ') : '0 个写请求')
      if (page.readOnly) {
        // 空态/错误态控件是否**存在**由 pages.spec.ts 逐页钉死；这里只判运行时副作用。
        pass(`${page.key} 只读口径（无执行/采集类写路径）`,
          !writes.some((w) => /\/collect|\/runs|\/snapshots|\/batch/.test(w.url)), '')
      }

      if (page.key === 'collect') {
        pass('采集页命令白名单 10 类可见', dom.commandRows === 10, `实际 ${dom.commandRows} 条`)
        session.clearRequests()
        const clicked = await session.evaluate(clickExpr('collect-confirm'))
        const after = session.apiRequests().filter((r) => WRITES.has(r.method))
        pass('采集须二次确认（点一次不写、出现确认态）',
          clicked.ok && clicked.confirming && after.length === 0,
          `confirming=${clicked.confirming} 写请求=${after.length}`)
      }
      if (page.key === 'topology') {
        session.clearRequests()
        await session.evaluate(`(async () => {
          document.querySelector('[data-testid="topology-confirm"]')?.click();
          await new Promise((r) => setTimeout(r, 400));
          return true;
        })()`)
        const after = session.apiRequests().filter((r) => WRITES.has(r.method))
        pass('建快照须二次确认（点一次不写）', after.length === 0, `写请求=${after.length}`)

        // 有两份真实快照时，点「对比」应走 /snapshots/diff 并渲染差异表。
        const canCompare = await session.evaluate(`(() => {
          const b = document.querySelector('[data-testid="topology-before"]');
          const a = document.querySelector('[data-testid="topology-after"]');
          return !!b && !!a && b.options.length >= 3 && a.options.length >= 3;
        })()`)
        if (canCompare) {
          session.clearRequests()
          await session.evaluate(`(async () => {
            document.querySelector('[data-testid="topology-diff"]')?.click();
            await new Promise((r) => setTimeout(r, 900));
            return true;
          })()`)
          const diffReqs = session.apiRequests()
          const diffPost = diffReqs.some((r) => r.method === 'POST' && r.url.includes('/snapshots/diff'))
          const table = await session.evaluate(`!!document.querySelector('[data-testid="topology-diff-table"]')`)
          pass('两快照对比走真实 diff 并渲染差异表', diffPost && table,
            `POST diff=${diffPost} 表可见=${table}`)
          record.diffRequests = diffReqs
        } else {
          console.log('[SKIP] 拓扑对比：当前环境不足两份快照（夹具脚本应先跑）')
        }
      }
      if (page.key === 'recover') {
        // 换个关键字再搜：http.ts 对 GET 有短时缓存，同参复搜不发网络请求，
        // 那样「只发 GET」就变成了「什么都没发」，判据会假红。
        session.clearRequests()
        await session.evaluate(`(async () => {
          const input = document.querySelector('[data-testid="recover-keyword"] input, input[data-testid="recover-keyword"]');
          if (input) {
            const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            setter.call(input, 'RUN-2026');
            input.dispatchEvent(new Event('input', { bubbles: true }));
          }
          await new Promise((r) => setTimeout(r, 250));
          document.querySelector('[data-testid="recover-search"]')?.click();
          await new Promise((r) => setTimeout(r, 1000));
          return true;
        })()`)
        const reqs = session.apiRequests()
        const writesAfter = reqs.filter((r) => WRITES.has(r.method))
        const searchGet = reqs.some((r) => r.method === 'GET' && r.url.includes('/api/search'))
        pass('只读找回检索只发 GET /search，不产生新 run',
          writesAfter.length === 0 && searchGet,
          `GET /search=${searchGet} 写请求=${writesAfter.length}`)
        record.searchRequests = reqs
      }
    }

    const fails = checks.filter((c) => !c.ok)
    for (const [file, data] of [[OUT_ENV, results.env], [OUT_MAINT, results.maint]]) {
      mkdirSync(dirname(file), { recursive: true })
      writeFileSync(file, JSON.stringify({
        generatedAt: new Date().toISOString(),
        app: APP, projectId: PROJECT_ID,
        checks: checks.filter((c) => data.some((p) => c.name.startsWith(p.page + ' '))
          || ['采集', '建快照', '只读找回'].some((k) => c.name.startsWith(k))),
        pages: data,
        fails: fails.length,
      }, null, 2) + '\n', 'utf8')
      console.log(`[probe] ${file}（${data.length} 页）`)
    }
    console.log(`ENV_PAGES ${fails.length === 0 ? 'PASS' : 'FAIL'} FAILS=${fails.length}`)
    if (fails.length) process.exitCode = 1
  } finally {
    session.close()
  }
}

main().catch((e) => { console.error('[probe] 失败:', e.message); process.exitCode = 1 })
