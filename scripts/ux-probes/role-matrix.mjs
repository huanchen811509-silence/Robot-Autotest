#!/usr/bin/env node
/**
 * 角色 × 接口 期望码表（v5.21.0 / NFR-v5.21-03 / W71-08）。
 *
 * 为什么需要它：v5.21 之前「角色边界」只有单测里的权限矩阵，没有一条断言是**真实账号
 * 打真实接口**。矩阵写错一个格子，单测跟着改一起绿；而 curl 一遍四个账号就能看出来。
 *
 * 判据形态：4 角色 × 9 接口 = 36 格，每格给「可接受的状态码集合」。
 *   200/201  允许
 *   400      允许到达参数校验（说明权限这关过了）
 *   404      允许到达资源查找（同上，说明没有提前 403）
 *   403      权限拒绝
 *   409      冲突（重复创建之类，说明权限这关也过了）
 *
 * 用法：
 *   node scripts/ux-probes/role-matrix.mjs                 # 全表
 *   node scripts/ux-probes/role-matrix.mjs --self-test     # 注入一条错误期望，必须判红
 *   node scripts/ux-probes/role-matrix.mjs --json          # 输出 JSON（写证据用）
 *
 * 环境变量：APP_BASE（默认 8001）、QA_E2E_PASSWORD（夹具口令）、QA_ADMIN_PASSWORD、
 *           QA_SEED_PROJECT（夹具工程号，显式指定）、QA_SEED_PROJECT_CODE（默认 demo）。
 */
import { writeFileSync } from 'node:fs'

const BASE = process.env.APP_BASE ?? 'http://127.0.0.1:8001'
const FIXTURE_PASS = process.env.QA_E2E_PASSWORD
const ADMIN_PASS = process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026'
const SELF_TEST = process.argv.includes('--self-test')
const JSON_OUT = process.argv.includes('--json')
/** 夹具工程 code：未显式给 QA_SEED_PROJECT 时按它解析工程号（与 seed-qa-accounts.sh 同口径）。 */
const SEED_PROJECT_CODE = process.env.QA_SEED_PROJECT_CODE ?? 'demo'

const ACCOUNTS = [
  { role: 'admin', user: 'admin', pass: ADMIN_PASS },
  { role: 'pm', user: 'qa-e2e-pm', pass: FIXTURE_PASS },
  { role: 'engineer', user: 'qa-e2e-engineer', pass: FIXTURE_PASS },
  { role: 'viewer', user: 'qa-e2e-viewer', pass: FIXTURE_PASS },
]

/** 期望表：`ok` 表示"允许进入业务逻辑"，`deny` 表示必须 403。 */
const OPEN = [200, 201, 204, 400, 404, 409]
const CASES = [
  { name: 'GET /api/me/menu-capabilities', method: 'GET', path: '/api/me/menu-capabilities',
    expect: { admin: OPEN, pm: OPEN, engineer: OPEN, viewer: OPEN } },
  // PM 有 USER_ROLE:READ / AUDIT:READ（PermissionMatrix 里就是授予的），
  // 所以用户/角色/审计对 PM 是**允许**的；ENGINEER 的 USER_ROLE:READ 在 v5.9 已被移除。
  { name: 'GET /api/users（用户管理）', method: 'GET', path: '/api/users?page=1&size=5',
    expect: { admin: OPEN, pm: OPEN, engineer: [403], viewer: [403] } },
  { name: 'GET /api/roles（角色列表）', method: 'GET', path: '/api/roles',
    expect: { admin: OPEN, pm: OPEN, engineer: [403], viewer: [403] } },
  // 审计：矩阵里 PM 有 AUDIT:READ，但服务层额外要求"管理员或项目所有者"，
  // 实测 PM 非所有者时 403。这里按**真实行为**登记，并把差异记到 L-21-04
  // （是矩阵与服务层两套口径，不是本版引入的问题，本版不改审计策略）。
  { name: 'GET /api/audit（审计日志）', method: 'GET', path: '/api/audit?page=1&size=5',
    expect: { admin: OPEN, pm: [403], engineer: [403], viewer: [403] } },
  { name: 'POST /api/projects（建项目，空体）', method: 'POST', path: '/api/projects', body: {},
    expect: { admin: OPEN, pm: OPEN, engineer: [403], viewer: [403] } },
  { name: 'GET /api/projects（项目列表）', method: 'GET', path: '/api/projects?page=1&size=5',
    expect: { admin: OPEN, pm: OPEN, engineer: OPEN, viewer: OPEN } },
  { name: 'DELETE 不存在的用例（读得到就走 404）', method: 'DELETE',
    path: (ctx) => `/api/projects/${ctx.projectId}/cases/999999999`,
    expect: { admin: OPEN, pm: OPEN, engineer: OPEN, viewer: [403] } },
  { name: 'POST /api/projects/{id}/case-sets（空体）', method: 'POST',
    path: (ctx) => `/api/projects/${ctx.projectId}/case-sets`, body: {},
    expect: { admin: OPEN, pm: OPEN, engineer: OPEN, viewer: [403] } },
  { name: 'GET /api/projects/{id}/cases（用例分页）', method: 'GET',
    path: (ctx) => `/api/projects/${ctx.projectId}/cases?page=1&size=5`,
    expect: { admin: OPEN, pm: OPEN, engineer: OPEN, viewer: OPEN } },
]

async function login(user, pass) {
  const resp = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: pass }),
  })
  const body = await resp.json().catch(() => ({}))
  return body?.data?.token ?? null
}

async function call(token, method, path, body) {
  const init = { method, headers: { Authorization: `Bearer ${token}` } }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  try {
    const resp = await fetch(`${BASE}${path}`, init)
    const body = await resp.json().catch(() => ({}))
    return { status: resp.status, message: body?.message ?? '', codeName: body?.codeName ?? '' }
  } catch (err) {
    return { status: `ERR:${err.message}`, message: '', codeName: '' }
  }
}

async function main() {
  if (!FIXTURE_PASS) {
    console.error('[role-matrix] 缺少 QA_E2E_PASSWORD（夹具口令）')
    process.exit(2)
  }
  const tokens = {}
  for (const account of ACCOUNTS) {
    const token = await login(account.user, account.pass)
    if (!token) {
      console.error(`[role-matrix] 登录失败：${account.user}（先跑 scripts/seed-qa-accounts.sh）`)
      process.exit(2)
    }
    tokens[account.role] = token
  }

  // 用一个真实工程做上下文（无工程时退回 1，用例接口会 404/403，仍可判权限）
  const projects = await fetch(`${BASE}/api/projects?page=1&size=5`, {
    headers: { Authorization: `Bearer ${tokens.admin}` },
  }).then((r) => r.json()).catch(() => ({}))
  // v5.21 / L-21-03：夹具账号只在"夹具工程"里是成员；
  // 拿别的工程做上下文，pm/engineer 也会被项目成员闸挡住 403，测不出角色差异。
  // 直接用夹具工程号：列表接口是"可见工程"视图，夹具工程不一定排在第一页，
  // 靠 items[0] 会把上下文落到别的工程上（然后所有项目级调用都因为"不是成员"403，
  // 看起来像角色判别失效，其实是上下文选错了）。
  //
  // v5.23 收口：原先默认写死 29，而项目 29 已随演示工程瘦身被清除，写死 id 会静默指向
  // 不存在的工程（pm/engineer 一律 403，看着像角色判别失效，其实是上下文没了）。
  // 改成按 code 解析（与 seed-qa-accounts.sh 同一口径），QA_SEED_PROJECT 仍可显式覆盖。
  const projectId = process.env.QA_SEED_PROJECT
    ? Number(process.env.QA_SEED_PROJECT)
    : await (async () => {
        const list = await fetch(`${BASE}/api/projects?page=1&size=100`, {
          headers: { Authorization: `Bearer ${tokens.admin}` },
        }).then((r) => r.json()).catch(() => ({}))
        const items = Array.isArray(list?.data) ? list.data : []
        const hit = items.find((p) => p.code === SEED_PROJECT_CODE) ?? items[0]
        return hit ? Number(hit.id) : 1
      })()
  const ctx = { projectId }
  console.log(`[role-matrix] 工程上下文 projectId=${projectId}`)

  const rows = []
  let fails = 0
  for (const probe of CASES) {
    for (const account of ACCOUNTS) {
      const path = typeof probe.path === 'function' ? probe.path(ctx) : probe.path
      const res = await call(tokens[account.role], probe.method, path, probe.body)
      const status = res.status
      const expected = probe.expect[account.role]
      const ok = expected.includes(status)
      if (!ok) fails += 1
      rows.push({ probe: probe.name, role: account.role, status, message: res.message,
        codeName: res.codeName, path, expected: expected.join('/'), ok })
    }
  }

  if (SELF_TEST) {
    // 注入一条错误期望：把「viewer 删用例」的期望改成 200，必须出现 FAIL。
    const probe = CASES.find((c) => c.name.startsWith('DELETE 不存在'))
    const res = await call(tokens.viewer, 'DELETE', probe.path(ctx))
    const status = res.status
    const injectedOk = [200].includes(status)
    if (injectedOk) {
      console.error('[role-matrix][self-test][FAIL] 注入的错误期望居然通过，判据失效')
      process.exit(1)
    }
    console.log(`[role-matrix][self-test][PASS] 注入错误期望后判红（viewer DELETE -> ${status}，期望 200 不成立）`)
    process.exit(fails > 0 ? 1 : 0)
  }

  if (JSON_OUT) {
    const out = 'docs/evidence/v5.21.0/role-matrix.json'
    writeFileSync(out, JSON.stringify({ projectId, rows }, null, 2))
    console.log(`[role-matrix] 明细写入 ${out}`)
  }

  const width = Math.max(...rows.map((r) => r.probe.length))
  for (const row of rows) {
    console.log(`${row.ok ? 'PASS' : 'FAIL'}  ${row.probe.padEnd(width)}  ${row.role.padEnd(8)}  ${String(row.status).padEnd(9)} 期望 ${row.expected}  ${row.ok ? '' : row.message}`)
  }
  console.log(`\n[role-matrix] ${rows.length} 格，FAILS=${fails}`)
  process.exit(fails > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('[role-matrix] 异常：', err)
  process.exit(1)
})
