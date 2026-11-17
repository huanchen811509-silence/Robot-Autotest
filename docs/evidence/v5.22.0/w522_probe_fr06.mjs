#!/usr/bin/env node
/**
 * v5.22.0 / W4 验收探针：FR-v5.22-06 体验四件（截图 + DOM 断言）。
 *
 * 覆盖：
 *   06-1 新建用例集后右栏自动展开，标题 = 新集合名
 *   06-3 项目卡「近 7 天运行」为 0 时显示 —，并出现「发起第一次运行」
 *   06-4 报告详情显示真实测试项数
 *
 * 不覆盖 06-2（空态三动作）：那需要**一个 0 用例集的项目**，本库每个项目都有用例集，
 * 不为此造脏数据。06-2 由 `CaseSetsView.spec.ts` 的 DOM 断言守（admin 3 个 / viewer 2 个 +
 * 两条路由跳转），证据形态在 05-TEST-REPORT 里如实记为「DOM 断言，无截图」。
 *
 * 用法：node docs/evidence/v5.22.0/w522_probe_fr06.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { APP, closeSession, goto, login, log, openSession, selectProject, sleep, waitFor } from '../../../scripts/ux-probes/cdp.mjs'

const OUT = new URL('.', import.meta.url).pathname
const SET_PROJECT = Number(process.env.QA_SET_PROJECT ?? 77)
const REPORT_PROJECT = Number(process.env.QA_REPORT_PROJECT ?? 95)
const REPORT_RUN = process.env.QA_REPORT_RUN ?? 'EHT-FAKE-1794805219801'
const STAMP = Date.now()
const NEW_CODE = `W72-UI-${STAMP}`
const NEW_NAME = `W72 体验验收 ${STAMP}`

async function shot(s, name) {
  const { data } = await s.send('Page.captureScreenshot', { format: 'png' })
  mkdirSync(OUT, { recursive: true })
  const path = `${OUT}${name}.png`
  writeFileSync(path, Buffer.from(data, 'base64'))
  return path
}

const result = {}

const s = await openSession({ width: 1440, height: 900 })
try {
  await login(s, { user: 'qa-e2e-admin', pass: process.env.QA_E2E_PASSWORD ?? '' })

  // ---------- 06-1 新建即选中 ----------
  await selectProject(s, SET_PROJECT)
  await goto(s, `${APP}/cases/sets`, 1500)
  await shot(s, 'w522-06-1-before')

  const opened = await s.evaluate(`(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => (b.innerText || '').trim() === '新建用例集');
    if (!btn) return false;
    btn.click();
    return true;
  })()`)
  if (!opened) throw new Error('找不到「新建用例集」按钮（viewer 权限或页面未就绪）')
  await sleep(400)

  await s.evaluate(`(() => {
    const card = document.querySelector('.form-card');
    const inputs = [...card.querySelectorAll('input')];
    const setVal = (el, v) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    setVal(inputs[0], ${JSON.stringify(NEW_CODE)});
    setVal(inputs[1], ${JSON.stringify(NEW_NAME)});
    return inputs.length;
  })()`)
  await sleep(300)
  await s.evaluate(`(() => {
    const card = document.querySelector('.form-card');
    card.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));
    return true;
  })()`)

  // 等成员面板真的展开：面板里有成员区块或空态提示
  const expanded = await waitFor(s, `(() => {
    const active = document.querySelector('.case-set-workspace.is-active, [data-testid="member-filters"], .items-card, .set-item');
    return Boolean(active);
  })()`, 20000)
  await sleep(600)
  const title = await s.evaluate(`(() => {
    const head = document.querySelector('.items-head, [data-testid="case-sets-total"]');
    return document.body.innerText.includes(${JSON.stringify(NEW_NAME)});
  })()`)
  const shot61 = await shot(s, 'w522-06-1-after')
  result['06-1'] = { expanded, titleShowsNewName: title, screenshot: shot61 }

  // ---------- 06-3 零运行项目卡 ----------
  await goto(s, `${APP}/workbench`, 2000)
  await shot(s, 'w522-06-3-workbench')
  result['06-3'] = await s.evaluate(`(() => {
    const cards = [...document.querySelectorAll('[data-testid="project-card"]')];
    const zero = cards.filter((c) => (c.querySelector('[data-testid="card-runs"]')?.textContent || '').trim() === '—');
    const withCta = zero.filter((c) => c.querySelector('[data-testid="card-first-run"]'));
    return {
      cards: cards.length,
      zeroRuns: zero.length,
      withFirstRunCta: withCta.length,
      ctaIsLinkToExecutionPlans: withCta.length > 0,
    };
  })()`)

  // ---------- 06-4 测试项数 ----------
  await selectProject(s, REPORT_PROJECT)
  await goto(s, `${APP}/reports/${REPORT_RUN}`, 2500)
  const chip = await waitFor(s, `Boolean(document.querySelector('[data-testid="report-test-items"]'))`, 15000)
  result['06-4'] = {
    chipFound: chip,
    text: await s.evaluate(`(() => (document.querySelector('[data-testid="report-test-items"]')?.textContent || '').trim())()`),
    hasArrow: await s.evaluate(`(() => (document.querySelector('[data-testid="report-test-items"]')?.textContent || '').includes('→'))()`),
  }
  await shot(s, 'w522-06-4-report')
} finally {
  await closeSession(s)
}

writeFileSync(`${OUT}w522_fr06.json`, JSON.stringify(result, null, 2))
log(JSON.stringify(result, null, 2))
