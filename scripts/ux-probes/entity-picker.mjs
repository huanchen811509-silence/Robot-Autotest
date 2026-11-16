#!/usr/bin/env node
/**
 * 实体选择器探针（v5.20.0 / FR-v5.20-02）。
 *
 * 守四件事：
 * ① 凭据处是「可搜索下拉」而不是裸输入框；
 * ② 展开后候选里有真实凭据（名字来自接口，不是编的）；
 * ③ 选中后框里显示「名称（ID）」——不是一串数字，否则等于没换；
 * ④ 「高级：手输 ID」仍可用（兜底不丢）。
 *
 * 全程真实鼠标：点开下拉 → 点候选 → 读框内文本 → 点手输开关。
 *
 * 用法：node scripts/ux-probes/entity-picker.mjs [--project 95] [--json]
 * 退出码：0 全绿；1 有失败；2 环境错误。
 */
import { openSession, goto, login, selectProject, closeSession, sleep, waitFor, APP } from './cdp.mjs'

function parseArgs(argv) {
  const out = { project: process.env.QA_PROJECT_ID ?? '95', json: false }
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--project') out.project = argv[++i]
    else if (argv[i] === '--json') out.json = true
  }
  return out
}

const args = parseArgs(process.argv.slice(2))
const fails = []
const out = {}

async function click(s, box) {
  await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
}

const s = await openSession({ width: 1440, height: 900 })
try {
  await login(s)
  await selectProject(s, args.project)
  await goto(s, `${APP}/environment/collect`, 2800)
  await waitFor(s, `Boolean(document.querySelector('[data-testid="entity-picker"]'))`, 15000)
  await sleep(1200)

  // ① 是下拉（naive-ui 的 selection 壳）
  out.isSelect = await s.evaluate(`(() => {
    const root = document.querySelector('[data-testid="entity-picker"]');
    if (!root) return false;
    const sel = root.classList.contains('n-base-selection') ? root : root.querySelector('.n-base-selection');
    return Boolean(sel);
  })()`)
  if (!out.isSelect) fails.push('凭据处不是可搜索下拉（找不到 .n-base-selection）')

  // ② 展开后候选有真实名称
  const trigger = await s.evaluate(`(() => {
    const root = document.querySelector('[data-testid="entity-picker"]');
    const el = root.classList.contains('n-base-selection') ? root : root.querySelector('.n-base-selection');
    if (!el) return null;
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  })()`)
  if (trigger) {
    await click(s, trigger)
    await sleep(1000)
    out.options = await s.evaluate(`(() => {
      const items = [...document.querySelectorAll('.n-base-select-option')].map((e) => (e.innerText || '').trim());
      return items.slice(0, 8);
    })()`)
    if (out.options.length === 0) fails.push('下拉展开后没有任何候选（凭据候选没加载出来）')
    const looksLabelled = out.options.some((t) => /（\d+）/.test(t))
    if (out.options.length > 0 && !looksLabelled) {
      fails.push('候选文案不是「名称（ID）」格式：' + out.options.slice(0, 3).join(' / '))
    }

    // ③ 选中第一项 → 框内文本
    const first = await s.evaluate(`(() => {
      const e = document.querySelector('.n-base-select-option');
      if (!e) return null;
      const r = e.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    })()`)
    if (!first) fails.push('候选列表找不到可点的项')
    else {
      await click(s, first)
      await sleep(1000)
      out.selectedText = await s.evaluate(`(() => {
        const root = document.querySelector('[data-testid="entity-picker"]');
        const sel = root.classList.contains('n-base-selection') ? root : root.querySelector('.n-base-selection');
        return (sel.innerText || '').trim().replace(/\\n/g, ' ');
      })()`)
      if (!/（\d+）/.test(out.selectedText || '')) {
        fails.push('选中后框内没有显示「名称（ID）」：' + (out.selectedText || '(空)'))
      }
    }
  } else {
    fails.push('找不到凭据下拉的可点区域')
  }

  // ④ 高级手输兜底
  const toggle = await s.evaluate(`(() => {
    const b = document.querySelector('[data-testid="entity-picker-toggle"]');
    if (!b) return null;
    const r = b.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, text: (b.innerText || '').trim() };
  })()`)
  if (!toggle) fails.push('没有「高级：手输 ID」兜底入口')
  else {
    await click(s, toggle)
    await sleep(700)
    out.manualVisible = await s.evaluate(`Boolean(document.querySelector('[data-testid="entity-picker-manual"]'))`)
    if (!out.manualVisible) fails.push('点了手输开关但没有出现手输输入框')
  }
} finally {
  await closeSession(s)
}

if (args.json) console.log(JSON.stringify({ ...out, fails }, null, 2))
else {
  console.log(`[下拉] ${out.isSelect}  [候选] ${(out.options || []).join(' | ')}`)
  console.log(`[选中后] ${out.selectedText ?? 'n/a'}  [手输兜底] ${out.manualVisible}`)
}

if (fails.length) {
  console.error(`\n[FAIL] ${fails.length} 条：`)
  for (const f of fails) console.error('  - ' + f)
  process.exit(1)
}
console.log('\n[PASS] 实体选择器检查通过')
