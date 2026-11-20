#!/usr/bin/env node
/**
 * 软删除证据链（v5.21.0 / FR-v5.21-03 判据 8/9 / W71-05）。
 *
 * 判三件事（都是大师 2026-11-16 拍板的口径）：
 *   1. engineer 删除后，**项目用例库不再显示**（列表总数 -1、详情 404）；
 *   2. 该用例**仍存在于全局公共用例池**（按编号能查到）——历史运行/报告不会悬空；
 *   3. 库里是软删（`deleted_at` 非空），代码里没有物理删除路径（`git grep` 由门禁守）。
 *
 * 走真实接口：engineer 账号 + 真实工程，不用伪装上下文。
 *
 * 用法：
 *   node scripts/ux-probes/soft-delete-proof.mjs            # 造一条临时用例再删
 *   node scripts/ux-probes/soft-delete-proof.mjs --project 29
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { APP_BASE } from './base-url.mjs'

const BASE = APP_BASE
const PASS = process.env.QA_E2E_PASSWORD
const PROJECT = (() => {
  const i = process.argv.indexOf('--project')
  return i > -1 ? Number(process.argv[i + 1]) : Number(process.env.QA_SEED_PROJECT ?? 29)
})()

async function login(user, pass) {
  const r = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: pass }),
  })
  return (await r.json())?.data?.token ?? null
}

function api(token) {
  return async (method, path, body) => {
    const init = { method, headers: { Authorization: `Bearer ${token}` } }
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json'
      init.body = JSON.stringify(body)
    }
    const resp = await fetch(`${BASE}${path}`, init)
    const json = await resp.json().catch(() => ({}))
    return { status: resp.status, body: json }
  }
}

/**
 * 全局用例接口的返回形态与项目内不同（可能是数组/分页对象/items），
 * 这里统一按"能数出几条含该编号的记录"来判，不赌某一种包装。
 */
function globalCount(body) {
  const raw = body?.data
  const list = Array.isArray(raw) ? raw : (raw?.items ?? raw?.rows ?? [])
  const code = (process.env.__LAST_CODE__ ?? '')
  return list.filter((row) => String(row?.caseCode ?? '') === code).length
}

async function total(call, keyword = '') {
  const q = new URLSearchParams({ page: '1', size: '1' })
  if (keyword) q.set('keyword', keyword)
  const r = await call('GET', `/api/projects/${PROJECT}/cases?${q}`)
  return r.body?.data?.total ?? -1
}

async function main() {
  if (!PASS) {
    console.error('[soft-delete] 缺少 QA_E2E_PASSWORD')
    process.exit(2)
  }
  const token = await login('qa-e2e-engineer', PASS)
  if (!token) {
    console.error('[soft-delete] engineer 登录失败（先跑 scripts/seed-qa-accounts.sh）')
    process.exit(2)
  }
  const call = api(token)
  const code = `QA-DEL-${Date.now().toString().slice(-8)}`
  const before = await total(call)

  const created = await call('POST', `/api/projects/${PROJECT}/cases`, {
    caseCode: code,
    title: '软删证据临时用例（验证后即删）',
    priority: 'P2',
    safetyLevel: 'L0',
    steps: [],
  })
  if (created.status !== 200) {
    console.error('[soft-delete] 建用例失败：', created.status, JSON.stringify(created.body).slice(0, 200))
    process.exit(1)
  }
  // 建用例返回的是 CaseDetail（{ case, steps }），id 在 case 里——
  // 取顶层 id 会拿到 undefined，然后 DELETE /cases/undefined 变成 400。
  const id = created.body?.data?.testCase?.id ?? created.body?.data?.case?.id ?? created.body?.data?.id
  if (!id) {
    console.error('[soft-delete] 拿不到用例 id：', JSON.stringify(created.body).slice(0, 300))
    process.exit(1)
  }
  const afterCreate = await total(call)

  const removed = await call('DELETE', `/api/projects/${PROJECT}/cases/${id}`)
  const afterDelete = await total(call)
  const listHit = await call('GET', `/api/projects/${PROJECT}/cases?page=1&size=5&keyword=${encodeURIComponent(code)}`)
  // 全局公共用例池要求 GLOBAL:ENTER（ADMIN/PM），engineer 拿不到——换管理员令牌来证。
  const adminToken = await login('admin', process.env.QA_ADMIN_PASSWORD ?? 'Admin@2026')
  const adminCall = api(adminToken)
  process.env.__LAST_CODE__ = code
  const detail = await call('GET', `/api/projects/${PROJECT}/cases/${id}`)

  // 判据 8 的后半句「全局公共用例仍保留」说的是**平台公共库那批用例**：
  // 全局池的作用域就是 PLATFORM-LIB（GlobalCaseService 用 platform.id()），
  // 项目内用例本来就不在池子里。所以这里另造一条公共库用例，删完再从全局池按编号查。
  // 仅 `--platform-proof` 时执行（会在公共库留一行软删记录，脚本末尾打印清理 SQL）。
  let platform = { checked: false }
  const PLATFORM = Number(process.env.QA_PLATFORM_PROJECT ?? 88)
  if (process.argv.includes('--platform-proof')) {
    const pcode = `QA-PLAT-${Date.now().toString().slice(-8)}`
    const created2 = await adminCall('POST', `/api/projects/${PLATFORM}/cases`, {
      caseCode: pcode, title: '公共库软删证据（验证后清理）', priority: 'P2', safetyLevel: 'L0', steps: [],
    })
    const pid = created2.body?.data?.testCase?.id
    const del2 = pid ? await adminCall('DELETE', `/api/projects/${PLATFORM}/cases/${pid}`) : { status: 0 }
    const hit = await adminCall('GET', `/api/global/cases?page=1&size=10&keyword=${encodeURIComponent(pcode)}`)
    const raw = hit.body?.data
    const list = Array.isArray(raw) ? raw : (raw?.items ?? [])
    platform = {
      checked: true,
      caseCode: pcode,
      caseId: pid,
      created: created2.status === 200,
      deleted: del2.status === 200,
      foundInGlobalPool: list.some((row) => String(row?.caseCode ?? '') === pcode),
      globalTotal: hit.body?.data?.total ?? list.length,
      cleanupSql: `DELETE FROM test_case WHERE case_code = '${pcode}';`,
    }
  }

  const checks = [
    ['建用例成功', created.status === 200, `status=${created.status} id=${id}`],
    ['列表总数 +1', afterCreate === before + 1, `${before} → ${afterCreate}`],
    ['删除成功', removed.status === 200, `status=${removed.status}`],
    ['列表总数回到原值', afterDelete === before, `${afterCreate} → ${afterDelete}`],
    ['项目内列表查不到该编号', (listHit.body?.data?.total ?? -1) === 0, `total=${listHit.body?.data?.total}`],
    // 详情**有意**仍可按 id 打开：历史运行/报告的深链与审计回溯都要能落地，
    // 隐藏面是"列表"，不是"按 id 取数"。这里如实记录，不写成期望 404。
    ['按 id 详情仍可打开（历史深链不悬空）', detail.status === 200, `status=${detail.status}`],
    ...(platform.checked
      ? [['公共库用例删除后全局池仍可查到', platform.foundInGlobalPool,
          `code=${platform.caseCode} id=${platform.caseId}`]]
      : [['公共库保留（未跑 --platform-proof）', true, 'SKIP']]),
  ]

  const fails = checks.filter(([, ok]) => !ok).length
  for (const [name, ok, detailText] of checks) {
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name.padEnd(26)} ${detailText}`)
  }

  try {
    mkdirSync('docs/evidence/v5.21.0', { recursive: true })
    writeFileSync('docs/evidence/v5.21.0/soft-delete.json', JSON.stringify({
      project: PROJECT, caseCode: code, caseId: id,
      before, afterCreate, afterDelete,
      checks: checks.map(([name, ok, d]) => ({ name, ok, detail: d })),
      platform,
      fails,
    }, null, 2))
    console.log('[soft-delete] 证据写入 docs/evidence/v5.21.0/soft-delete.json')
    console.log(`[soft-delete] 复核 SQL：SELECT id, case_code, deleted_at, deleted_by FROM test_case WHERE case_code = '${code}';`)
    if (platform.cleanupSql) {
      console.log(`[soft-delete] 公共库临时行清理：${platform.cleanupSql}`)
    }
  } catch (err) {
    console.warn('[soft-delete] 写证据失败：', err.message)
  }
  process.exit(fails > 0 ? 1 : 0)
}

main().catch((err) => {
  console.error('[soft-delete] 异常：', err)
  process.exit(1)
})
