#!/usr/bin/env node
/**
 * 3.9 前置调研 · 关于页 / 版本迭代一致性探针（只读、真实浏览器）。
 *
 * 用户诉求（FR-v3.9-17）：版本开发完成后，「关于」与「版本迭代」都要更新。
 * 机制上两处都是动态读源的，所以真正要判的是**两处说的是不是同一版**：
 *   1. 关于页的「最近更新」区块存在，且列出至少 1 条
 *   2. 最近更新的首条标题里包含关于页显示的版本号（版本号与变更记录同版本）
 *   3. 「查看完整版本迭代」链接存在并指向 /changelog
 *   4. 版本迭代页能渲染出至少 1 个版本，且首个版本标题含同一版本号
 *
 * 用法：
 *   node scripts/about-changelog-probe.mjs                # 采集 + 判定
 *   node scripts/about-changelog-probe.mjs --selfcheck    # 抹掉「最近更新」区块，判据必红
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'

const CDP = process.env.CDP_BASE ?? 'http://127.0.0.1:9222'
const APP = process.env.APP_BASE ?? 'http://localhost:8001'
const USER = process.env.QA_USER ?? 'qa-e2e-admin'
const OUT = process.env.ABOUT_PROBE_OUT ?? ''
const SELFCHECK = process.argv.includes('--selfcheck')

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

async function goto(s, url, width = 1440, height = 900) {
  await s.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await s.send('Page.navigate', { url })
  await sleep(1000)
  for (let i = 0; i < 15; i += 1) {
    if (await s.evaluate('document.readyState') === 'complete') break
    await sleep(200)
  }
  await sleep(600)
}

/** 关于页：版本号 + 最近更新区块。 */
const ABOUT_EXPR = `(() => {
  const versionEl = document.querySelector('.version-value')
  const panels = [...document.querySelectorAll('.panel')]
  const target = panels.find((p) => p.querySelector('h2') &&
    /最近更新|Recent changes/.test(p.querySelector('h2').textContent || ''))
  if (!target) return { version: versionEl ? versionEl.textContent.trim() : null, found: false }
  const title = target.querySelector('.release-title')
  const items = [...target.querySelectorAll('.changes li')].map((li) => li.textContent.trim())
  const link = target.querySelector('a')
  return {
    version: versionEl ? versionEl.textContent.trim() : null,
    found: true,
    releaseTitle: title ? title.textContent.trim() : null,
    items,
    linkText: link ? link.textContent.trim() : null,
    linkHref: link ? link.getAttribute('href') : null,
  }
})()`

/** 版本迭代页：第一个 release 的标题与条目数。 */
const LOG_EXPR = `(() => {
  const articles = [...document.querySelectorAll('article')]
  const heads = [...document.querySelectorAll('article h2, article h3, h2')]
  const first = articles[0] || null
  const firstTitle = first ? (first.querySelector('h2, h3') || {}).textContent || '' : ''
  return {
    count: articles.length,
    firstTitle: String(firstTitle).trim(),
    headings: heads.slice(0, 3).map((h) => String(h.textContent || '').trim()),
    bodyChars: first ? String(first.textContent || '').length : 0,
  }
})()`

async function main() {
  const version = await (await fetch(`${CDP}/json/version`)).json()
  console.log(`[cdp] ${version.Browser}  [app] ${APP}${SELFCHECK ? '  [selfcheck]' : ''}\n`)
  const { session: s, targetId } = await openSession()
  const rows = []
  const push = (name, ok, detail) => rows.push({ name, ok, detail })
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

    await goto(s, `${APP}/about`, 1440)
    let about = await s.evaluate(ABOUT_EXPR)

    // 负向自检：把「最近更新」区块整块移除，判据必须变红
    if (SELFCHECK && about.found) {
      await s.evaluate(`(() => {
        const panels = [...document.querySelectorAll('.panel')]
        const target = panels.find((p) => p.querySelector('h2') &&
          /最近更新|Recent changes/.test(p.querySelector('h2').textContent || ''))
        if (target) target.remove()
      })()`)
      await sleep(150)
      about = await s.evaluate(ABOUT_EXPR)
    }

    push('关于页存在「最近更新」区块', about.found === true, `found=${about.found} version=${about.version}`)
    push('最近更新列出至少 1 条', (about.items || []).length >= 1, `items=${(about.items || []).length}`)
    push('最近更新首条版本 = 关于页显示版本',
      Boolean(about.releaseTitle && about.version && about.releaseTitle.includes(about.version)),
      `releaseTitle=${about.releaseTitle} version=${about.version}`)
    push('存在「查看完整版本迭代」链接且指向 /changelog',
      Boolean(about.linkHref && about.linkHref.indexOf('/changelog') >= 0),
      `href=${about.linkHref} text=${about.linkText}`)

    await goto(s, `${APP}/changelog`, 1440)
    const log = await s.evaluate(LOG_EXPR)
    push('版本迭代页渲染出至少 1 个版本', log.count >= 1, `count=${log.count} first=${log.firstTitle}`)
    push('版本迭代页首条版本 = 关于页显示版本',
      Boolean(about.version && log.firstTitle.includes(about.version)),
      `first=${log.firstTitle} version=${about.version}`)
    push('版本迭代页首条有正文内容', log.bodyChars > 80, `bodyChars=${log.bodyChars}`)

    const fails = rows.filter((r) => !r.ok).length
    for (const r of rows) console.log(`${r.ok ? '[PASS]' : '[FAIL]'} ${r.name}  ${r.detail}`)
    console.log(`\nABOUT_CHANGELOG_PROBE FAILS=${fails}`)

    if (OUT) {
      writeFileSync(OUT, JSON.stringify({
        generatedAt: new Date().toISOString(), selfcheck: SELFCHECK, version: version.Browser,
        about, changelog: log, rows, fails,
      }, null, 2), 'utf8')
      console.log(`[out] ${OUT}`)
    }
    if (SELFCHECK) {
      console.log(fails > 0 ? '[selfcheck] PASS 移除区块后判据变红' : '[selfcheck] FAIL 移除区块后判据仍绿')
      if (fails === 0) process.exitCode = 1
    } else if (fails > 0) {
      process.exitCode = 1
    }
  } finally {
    await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  }
}

main().catch((e) => { console.error('[error]', e.message); process.exit(1) })
