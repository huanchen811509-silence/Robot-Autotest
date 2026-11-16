#!/usr/bin/env node
/**
 * 全站字面量扫描探针（v5.19.0 / W69-03）。
 *
 * 抓每个路由**可见文本**，扫 `undefined` / `NaN` / `[object Object]` 三种"把内部值打到界面上"
 * 的痕迹。报告页曾把 `undefined / undefined / undefined` 当计数显示，整页截图里它只是三格灰字，
 * 只有文本扫描能稳定抓到。
 *
 * 排除区：`pre` / `code` / `.log-viewer` / `[data-allow-raw]` —— 日志与命令原文里出现
 * `undefined` 是**真实输出**，不是渲染缺陷。
 *
 * 用法：
 *   node scripts/ux-probes/text-scan.mjs
 *   node scripts/ux-probes/text-scan.mjs --routes /reports,/runs --json
 *   node scripts/ux-probes/text-scan.mjs --self-test    # 注入 "undefined"，必须判红
 */
import { openSession, setViewport, goto, login, selectProject, closeSession, waitFor, sleep, APP } from './cdp.mjs'

const DEFAULT_ROUTES = [
  '/workbench',
  '/overview',
  '/cases',
  '/cases/sets',
  '/reports',
  '/runs',
  '/test-reports',
  '/environment/collect',
  '/g/cases',
  '/g/projects',
  '/pytest-workbench',
]

const PATTERNS = ['undefined', 'NaN', '[object Object]']

function parseArgs(argv) {
  const out = { routes: DEFAULT_ROUTES, json: false, selfTest: false, regex: null, project: process.env.QA_PROJECT_ID ?? '95' }
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i]
    if (a === '--routes') out.routes = argv[++i].split(',')
    else if (a === '--json') out.json = true
    else if (a === '--self-test') out.selfTest = true
    else if (a === '--regex') out.regex = argv[++i]
    else if (a === '--project') out.project = argv[++i]
  }
  return out
}

/** 取可见文本，剔除日志/代码区 */
const TEXT_EXPR = `(() => {
  const SKIP = 'pre, code, .log-viewer, [data-allow-raw], textarea, script, style';
  const walk = (root) => {
    let out = '';
    const it = document.createNodeIterator(root, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = it.nextNode())) {
      const p = n.parentElement;
      if (!p) continue;
      if (p.closest(SKIP)) continue;
      const cs = getComputedStyle(p);
      if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;
      const t = n.nodeValue;
      if (t && t.trim()) out += t + '\\n';
    }
    return out;
  };
  return walk(document.body);
})()`

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const s = await openSession({ width: 1440, height: 900 })
  const findings = []
  try {
    const ok = await login(s)
    if (!ok) throw new Error('登录失败（检查 QA_ADMIN / QA_ADMIN_PASSWORD）')
    await selectProject(s, args.project)
    await setViewport(s, 1440, 900)

    for (const route of args.routes) {
      await goto(s, `${APP}${route}`, 2600)
      await sleep(1200)
      if (args.selfTest) {
        await s.evaluate(`(() => {
          const d = document.createElement('div')
          d.id = '__scan_selftest'
          d.textContent = 'undefined'
          document.body.appendChild(d)
          return 1
        })()`)
      }
      const text = await s.evaluate(TEXT_EXPR)
      if (args.regex) {
        // --regex：按正则扫（用于裸英文状态词这类"多个词一个判据"的场景）。
        // 与默认字面量扫描分开：字面量里的 `[object Object]` 含方括号，不能直接当正则。
        const re = new RegExp(args.regex, 'g')
        let m
        while ((m = re.exec(text)) !== null) {
          const ctx = text.slice(Math.max(0, m.index - 40), m.index + m[0].length + 40).replace(/\s+/g, ' ')
          findings.push({ route, pattern: m[0], ctx })
          if (m[0].length === 0) re.lastIndex += 1
        }
      } else {
        for (const p of PATTERNS) {
          let idx = text.indexOf(p)
          while (idx !== -1) {
            const ctx = text.slice(Math.max(0, idx - 40), idx + p.length + 40).replace(/\s+/g, ' ')
            findings.push({ route, pattern: p, ctx })
            idx = text.indexOf(p, idx + p.length)
          }
        }
      }
      if (args.selfTest) await s.evaluate(`(() => { const d = document.getElementById('__scan_selftest'); if (d) d.remove(); return 1 })()`)
    }
  } finally {
    await closeSession(s)
  }

  if (args.json) {
    console.log(JSON.stringify({ routes: args.routes, findings, total: findings.length }, null, 2))
  } else {
    for (const f of findings) console.log(`[命中] ${f.route} :: ${f.pattern} :: …${f.ctx}…`)
  }

  if (args.selfTest) {
    if (findings.length === 0) {
      console.error('[FAIL] --self-test：注入 undefined 后仍未判红，扫描失效')
      process.exit(1)
    }
    console.log(`[PASS] --self-test：注入后判红 ${findings.length} 条`)
    process.exit(0)
  }

  if (findings.length) {
    console.error(`\n[FAIL] ${findings.length} 处命中（${args.routes.length} 路由）${args.regex ? `，正则 /${args.regex}/` : ''}`)
    process.exit(1)
  }
  console.log(
    args.regex
      ? `[PASS] ${args.routes.length} 个路由无 /${args.regex}/ 命中`
      : `[PASS] ${args.routes.length} 个路由无 undefined / NaN / [object Object]`,
  )
}

main().catch((e) => {
  console.error('[ERROR]', e.message)
  process.exit(2)
})
