/**
 * MENU-04：维护与恢复只读页（路由 / 只读 / 空态的纯断言，不挂载组件、不新增依赖）。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import router from '@/router'
import { NAVIGATION, isGroup, type NavGroup } from '@/config/navigation'

const VIEWS = [
  'ExecutionPrinciplesView.vue',
  'RecoveryRecordsView.vue',
  'ReadOnlyRecoverView.vue',
] as const

const ROUTES: Array<[path: string, name: string]> = [
  ['/maintenance/principles', 'maintenance-principles'],
  ['/maintenance/records', 'maintenance-records'],
  ['/maintenance/recover', 'maintenance-recover'],
]

function source(file: string): string {
  try {
    return readFileSync(new URL(`./${file}`, import.meta.url), 'utf-8')
  } catch {
    // vite-node 若把 import.meta.url 解析成非 file:// URL，回退到 vitest 的 CWD（frontend/）
    return readFileSync(`src/views/maintenance/${file}`, 'utf-8')
  }
}

describe('MENU-04 维护与恢复只读页', () => {
  it('3 条路由都解析到真实页面（不落到 not-found）', () => {
    for (const [path, name] of ROUTES) {
      const matched = router.resolve(path).matched.at(-1)?.name
      expect(matched, path).toBe(name)
      expect(matched).not.toBe('not-found')
    }
  })

  it('3 个视图均为只读：无写请求、空态显示「0 条」', () => {
    for (const file of VIEWS) {
      const text = source(file)
      expect(text, file).not.toMatch(/method\s*:\s*['"](POST|PUT|DELETE|PATCH)['"]/i)
      expect(text, file).not.toContain('requestMultipart')
      expect(text, file).toContain('0 条')
      expect(text, file).toContain('只读')
    }
  })

  it('恢复记录不含恢复命令正文', () => {
    const text = source('RecoveryRecordsView.vue')
    expect(text).toContain('不含恢复命令正文')
    expect(text).not.toContain('<th>恢复命令</th>')
  })

  it('只读找回为 GET 且不产生新 run', () => {
    const text = source('ReadOnlyRecoverView.vue')
    expect(text).toContain('GET')
    expect(text).toContain('不产生新 run')
    expect(text).not.toMatch(/method\s*:\s*['"](POST|PUT|DELETE|PATCH)['"]/i)
  })

  it('「维护与恢复」一级分组含 3 个二级项，路径与路由一致', () => {
    const group = NAVIGATION.find((node) => node.key === 'maintenance') as NavGroup
    expect(group).toBeTruthy()
    expect(isGroup(group)).toBe(true)
    expect(group.children.length).toBeGreaterThanOrEqual(3)
    const paths = group.children.map((child) => child.path)
    for (const [path] of ROUTES) expect(paths, path).toContain(path)
  })
})
