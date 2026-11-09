/**
 * FR-v3.11-03：维护与恢复 3 页（路由 / 真实数据源 / 只读 / 不展示命令正文）。
 *
 * v3.11 之前这 3 页行集恒为空，判据只能验「不写」；接上真实数据后要同时验：
 * 数据源（审计日志 / 全局搜索 / 真实设置）、只读口径、以及两条安全约束——
 * 恢复记录不展示命令正文、只读找回只发 GET 且不产生新 run。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import router from '@/router'
import { NAVIGATION, isGroup, type NavGroup } from '@/config/navigation'
import { isRecoveryAction, UNDO_ACTION_PREFIX } from '@/api/maintenance'

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

const WRITE_RE = /method\s*:\s*['"](POST|PUT|DELETE|PATCH)['"]|requestMultipart/i

function source(file: string): string {
  try {
    return readFileSync(new URL(`./${file}`, import.meta.url), 'utf-8')
  } catch {
    return readFileSync(`src/views/maintenance/${file}`, 'utf-8')
  }
}

describe('FR-v3.11-03 维护与恢复 3 页', () => {
  it('3 条路由都解析到真实页面（不落到 not-found）', () => {
    for (const [path, name] of ROUTES) {
      const matched = router.resolve(path).matched.at(-1)?.name
      expect(matched, path).toBe(name)
      expect(matched).not.toBe('not-found')
    }
  })

  it('3 页都接真实数据源，且都有空态 / 错误态 / 只读标注', () => {
    const prefixes: Record<string, string> = {
      'ExecutionPrinciplesView.vue': 'principles',
      'RecoveryRecordsView.vue': 'records',
      'ReadOnlyRecoverView.vue': 'recover',
    }
    for (const file of VIEWS) {
      const text = source(file)
      const p = prefixes[file]
      expect(text, file).toContain('@/api/maintenance')
      expect(text, file).toContain('onMounted')
      expect(text, file).toContain(`data-testid="${p}-empty"`)
      expect(text, file).toContain(`data-testid="${p}-failed"`)
      expect(text, file).toContain('0 条')
      expect(text, file).toContain('只读')
      expect(text, file).not.toMatch(WRITE_RE)
    }
  })

  it('恢复记录不含恢复命令正文：视图模型只搬四个字段', () => {
    const text = source('RecoveryRecordsView.vue')
    expect(text).toContain('不含恢复命令正文')
    expect(text).not.toContain('<th>恢复命令</th>')
    // 注释里会解释「为什么不取 detail」，所以只禁属性访问，不禁这个单词。
    expect(text).not.toMatch(/\.detail\b/)
    expect(text).toContain('fetchRecoveryRecords')
  })

  it('只读找回为 GET 且不产生新 run', () => {
    const text = source('ReadOnlyRecoverView.vue')
    expect(text).toContain('GET')
    expect(text).toContain('不产生新 run')
    expect(text).toContain('findHistoricalRuns')
    expect(text).not.toContain('executionApi.create')
    expect(text).not.toMatch(WRITE_RE)
  })

  it('API 层只调三个既有 GET 接口，不引入任何写方法', () => {
    const text = readFileSync('src/api/maintenance.ts', 'utf-8')
    expect(text).toContain('auditApi.page')
    expect(text).toContain('settingsApi.platform')
    expect(text).toContain('searchEntities')
    expect(text).not.toMatch(WRITE_RE)
    expect(text).not.toMatch(/\.detail\b/)
  })

  it('恢复动作过滤只认撤销类 action，普通执行不会被混进恢复记录', () => {
    expect(isRecoveryAction(`${UNDO_ACTION_PREFIX}submit`)).toBe(true)
    expect(isRecoveryAction('batch.undo')).toBe(true)
    expect(isRecoveryAction('RUN_CREATE')).toBe(false)
    expect(isRecoveryAction('case.batch.submit')).toBe(false)
    expect(isRecoveryAction('auth.login')).toBe(false)
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
