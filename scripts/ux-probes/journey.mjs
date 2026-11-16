#!/usr/bin/env node
/**
 * 真实链路复走（v5.19.0 / W69-12）。
 *
 * 目的不是"再点一遍"，而是留下与 UX 走查报告**同角度**的 8 张对照图，
 * 让"修好了"这件事可以被第三方复核：同一账号、同一条链路、同一视口。
 *
 * 链路：登录 → 用例库 → 用例集（窄屏抽屉）→ 执行计划 → 运行记录 → 执行报告 → 报告详情 → 导出区
 *
 * 用法：
 *   node scripts/ux-probes/journey.mjs --out /tmp/journey
 *   node scripts/ux-probes/journey.mjs --out docs/evidence/v5.19.0/journey --project 95
 *
 * 退出码：0 全部产出；1 有步骤失败（缺图 = 链路断了，不许当成功）。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { openSession, setViewport, goto, login, selectProject, closeSession, sleep, waitFor, APP } from './cdp.mjs'

function parseArgs(argv) {
  const out = { out: '/tmp/journey', project: process.env.QA_PROJECT_ID ?? '95', setsProject: null, width: 1440, height: 900 }
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--out') out.out = argv[++i]
    else if (argv[i] === '--project') out.project = argv[++i]
    else if (argv[i] === '--sets-project') out.setsProject = argv[++i]
    else if (argv[i] === '--viewport') {
      const [w, h] = argv[++i].split('x').map(Number)
      out.width = w
      out.height = h
    }
  }
  if (!out.setsProject) out.setsProject = out.project
  return out
}

async function shot(s, dir, name) {
  const r = await s.send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(dir, `${name}.png`), Buffer.from(r.data, 'base64'))
}

async function clickByText(s, re, scope = 'body') {
  const box = await s.evaluate(`(() => {
    const root = document.querySelector(${JSON.stringify(scope)}) || document.body;
    const el = [...root.querySelectorAll('button, a')].find((e) => ${re}.test((e.innerText || '').trim()));
    if (!el) return null;
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return null;
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  })()`)
  if (!box) return false
  await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  return true
}

const args = parseArgs(process.argv.slice(2))
mkdirSync(args.out, { recursive: true })

const steps = []
function record(name, ok, detail = '') {
  steps.push({ name, ok, detail })
  console.log(`${ok ? '[OK]' : '[MISS]'} ${name}${detail ? ' — ' + detail : ''}`)
}

const s = await openSession({ width: args.width, height: args.height })
try {
  await login(s)
  await selectProject(s, args.project)

  // 1 用例库
  await goto(s, `${APP}/cases`, 2800)
  await waitFor(s, `document.querySelectorAll('tbody tr').length > 0`, 15000)
  await sleep(700)
  await shot(s, args.out, '01-cases')
  const rows = await s.evaluate(`document.querySelectorAll('tbody tr').length`)
  record('用例库列表', rows > 0, `首屏 ${rows} 行`)

  // 2 用例集：主工程不一定有集合，允许单独指一个「有常驻集合」的工程出图（--sets-project）。
  if (String(args.setsProject) !== String(args.project)) {
    await selectProject(s, args.setsProject)
  }
  await goto(s, `${APP}/cases/sets`, 2800)
  await sleep(1200)
  await shot(s, args.out, '02-case-sets')
  const opened = await clickByText(s, '/管理用例/')
  if (opened) {
    await sleep(1800)
    await shot(s, args.out, '03-case-set-members')
    const hasMembers = await s.evaluate(`Boolean(document.querySelector('.set-item, [data-testid^="set-item-"]'))`)
    record('用例集成员面板', hasMembers, `工程 #${args.setsProject}`)
  } else {
    record('用例集成员面板', false, `工程 #${args.setsProject} 没有可点的「管理用例」（该工程无用例集）`)
  }
  if (String(args.setsProject) !== String(args.project)) {
    await selectProject(s, args.project)
  }

  // 3 执行计划
  await goto(s, `${APP}/execution-plans`, 2800)
  await sleep(1200)
  await shot(s, args.out, '04-execution-plans')
  const planOk = await s.evaluate(`/发起执行/.test(document.body.innerText)`)
  record('执行计划页', planOk)

  // 4 运行记录
  await goto(s, `${APP}/runs`, 2800)
  await sleep(1500)
  await shot(s, args.out, '05-runs')
  const runInfo = await s.evaluate(`(() => {
    const tr = document.querySelector('tbody tr');
    return { rows: document.querySelectorAll('tbody tr').length, first: tr ? (tr.innerText || '').replace(/\\n/g, ' | ').slice(0, 120) : '' };
  })()`)
  record('运行记录', runInfo.rows > 0, `${runInfo.rows} 行 | ${runInfo.first}`)

  // 5 执行报告列表
  await goto(s, `${APP}/reports`, 2800)
  await sleep(1500)
  await shot(s, args.out, '06-reports')
  const repText = await s.evaluate(`document.body.innerText.slice(0, 4000)`)
  const badLiteral = /undefined|NaN|\[object Object\]/.test(repText)
  record('执行报告列表', !badLiteral, badLiteral ? '出现 undefined/NaN 字面量' : '无字面量缺陷')

  // 6 报告详情（取第一行 runId）
  const rid = await s.evaluate(`(() => {
    const m = document.body.innerText.match(/[A-Z][A-Z0-9]*(?:-[A-Z0-9]+){2,}/);
    return m ? m[0] : null;
  })()`)
  if (rid) {
    await goto(s, `${APP}/reports/${rid}`, 3000)
    await sleep(1800)
    await shot(s, args.out, '07-report-detail')
    const detail = await s.evaluate(`(() => ({ hasMeta: /CASE_ID|RUN_ID/.test(document.body.innerText), hasExport: /导出/.test(document.body.innerText) }))()`)
    record('报告详情', detail.hasMeta || detail.hasExport, `runId=${rid}`)

    // 7 导出区
    await s.evaluate(`(() => { const el = [...document.querySelectorAll('*')].find((e) => (e.innerText || '').trim().startsWith('报告导出') && e.children.length < 8); if (el) el.scrollIntoView({ block: 'center' }); return 1 })()`)
    await sleep(800)
    await shot(s, args.out, '08-export')
    const expOk = await s.evaluate(`/导出/.test(document.body.innerText)`)
    record('导出区', expOk)
  } else {
    record('报告详情', false, '列表里没找到 runId')
    record('导出区', false, '跳过')
  }
} finally {
  await closeSession(s)
}

const failed = steps.filter((x) => !x.ok)
console.log(`\n产出目录：${args.out}`)
if (failed.length) {
  console.error(`[FAIL] ${failed.length}/${steps.length} 步未通过`)
  process.exit(1)
}
console.log(`[PASS] ${steps.length} 步全部产出`)
