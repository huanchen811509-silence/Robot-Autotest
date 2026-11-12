#!/usr/bin/env node
/* v5.0 W50-26 · 6 个体验回归集的静态契约检查。
 * 重要：本脚本**不启动浏览器、不替代 CDP 探针**。D1 裁定 CDP :9222 保持取消，
 * 真实浏览器读数在本版不可采集，人工项登记在 manual-backlog.md。
 * 这里守的是「回归点定义齐全、探针脚本存在、历史证据存在」，防止删回归点换绿。 */
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname ?? '.', '..')
const POINTS = [
  { id: 'REG-50-01', name: '18 处交互目标', probe: 'scripts/ux-probe.mjs', evidence: 'docs/evidence/v3.5.0/EW35-05/remediation.md' },
  { id: 'REG-50-02', name: '暗色下拉 10 个 select', probe: 'scripts/dark-select-probe.mjs', evidence: 'docs/evidence/v3.5.0/EW35-05/remediation.md' },
  { id: 'REG-50-03', name: '列表密度与分页', probe: 'scripts/density-probe.mjs', evidence: 'docs/evidence/v3.5.0/EW35-05/remediation.md' },
  { id: 'REG-50-04', name: '侧栏收起与顶栏遮挡', probe: 'scripts/side-toggle-probe.mjs', evidence: 'docs/evidence/v3.5.0/EW35-05/remediation.md' },
  { id: 'REG-50-05', name: '关于页与版本迭代同源', probe: 'scripts/about-changelog-probe.mjs', evidence: 'README.md' },
  { id: 'REG-50-06', name: '六态状态字典一致性', probe: 'tools/check_states.py', evidence: 'docs/evidence/v3.5.0/EW35-05/remediation.md' },
]

function check(points) {
  const bad = []
  if (points.length !== 6) bad.push('回归点数应为 6，实为 ' + points.length)
  const ids = new Set(points.map(p => p.id))
  if (ids.size !== points.length) bad.push('回归点编号重复')
  for (const p of points) {
    if (!/^REG-50-\d{2}$/.test(p.id)) bad.push('编号格式错: ' + p.id)
    if (!existsSync(path.join(ROOT, p.probe))) bad.push('探针缺失: ' + p.probe)
    if (!existsSync(path.join(ROOT, p.evidence))) bad.push('历史证据缺失: ' + p.evidence)
  }
  return bad
}

if (process.argv.includes('--selfcheck')) {
  const broken = POINTS.slice(0, 5)
  if (check(broken).length === 0) { console.log('[FAIL] selfcheck：删回归点未判红'); process.exit(1) }
  if (check(POINTS).length !== 0) { console.log('[FAIL] selfcheck：完整集合不应判红'); process.exit(1) }
  console.log('[PASS] ux-regression selfcheck：删点判红 / 完整判绿')
} else {
  const bad = check(POINTS)
  if (bad.length) { bad.forEach(x => console.log('[FAIL] ' + x)); process.exit(1) }
  console.log('[PASS] 6 个回归集定义齐全（静态契约检查，不替代真实浏览器探针）')
}
