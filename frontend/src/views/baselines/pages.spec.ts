/**
 * MENU-05：性能基线只读页（路由 / 只读 / 空态的纯断言，不挂载组件、不新增依赖）。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import router from '@/router'

const VIEWS = [
  'IperfBaselineView.vue',
  'PerftestBaselineView.vue',
  'CclBaselineView.vue',
] as const

const ROUTES: Array<[path: string, name: string]> = [
  ['/baselines/iperf', 'baseline-iperf'],
  ['/baselines/perftest', 'baseline-perftest'],
  ['/baselines/ccl', 'baseline-ccl'],
]

function source(file: string): string {
  try {
    return readFileSync(new URL(`./${file}`, import.meta.url), 'utf-8')
  } catch {
    // vite-node 若把 import.meta.url 解析成非 file:// URL，回退到 vitest 的 CWD（frontend/）
    return readFileSync(`src/views/baselines/${file}`, 'utf-8')
  }
}

describe('MENU-05 性能基线只读页', () => {
  it('3 条路由都解析到真实页面（不落到 not-found）', () => {
    for (const [path, name] of ROUTES) {
      const matched = router.resolve(path).matched.at(-1)?.name
      expect(matched, path).toBe(name)
      expect(matched).not.toBe('not-found')
    }
  })

  it('每条显示来源 run_id 与 SHA，空态显示「0 条」', () => {
    for (const file of VIEWS) {
      const text = source(file)
      expect(text, file).toContain('run_id')
      expect(text, file).toContain('SHA')
      expect(text, file).toContain('0 条')
    }
  })

  it('不显示历史占位数值（330 / 730 / 813 等一律不出现）', () => {
    for (const file of VIEWS) {
      const text = source(file)
      for (const placeholder of ['330', '730', '813']) {
        expect(text, `${file} 不应出现 ${placeholder}`).not.toContain(placeholder)
      }
    }
  })

  it('3 个视图均为只读：无写请求', () => {
    for (const file of VIEWS) {
      const text = source(file)
      expect(text, file).not.toMatch(/method\s*:\s*['"](POST|PUT|DELETE|PATCH)['"]/i)
      expect(text, file).not.toContain('requestMultipart')
      expect(text, file).toContain('只读')
    }
  })
})
