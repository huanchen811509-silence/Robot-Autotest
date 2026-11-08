/**
 * 侧栏折叠态的一次性归位（v2.3.1 修复）。
 *
 * 现场：v2.3 把折叠按钮从顶栏挪到侧栏头部，遗留的 `robot.layout.side.collapsed=true`
 * 没人认领，用户看到「菜单只剩图标、找不到文字」，而能展开的按钮换了位置。
 *
 * 判据：升级后第一次加载必须归位为展开；归位只做一次，之后用户的折叠选择仍被尊重。
 * 模块级单例在导入时读 localStorage，所以每个用例都要 resetModules 后动态 import。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const SIDE_KEY = 'robot.layout.side.collapsed'
const MIGRATION_KEY = 'robot.layout.side.v23'

beforeEach(() => {
  localStorage.clear()
  vi.resetModules()
})

describe('侧栏折叠态迁移', () => {
  it('遗留的「已折叠」在升级后首次加载时归位为展开，并写下迁移标记', async () => {
    localStorage.setItem(SIDE_KEY, 'true')

    const mod = await import('@/composables/useAiDrawer')

    expect(mod.useAiDrawer().sideCollapsed.value).toBe(false)
    expect(localStorage.getItem(SIDE_KEY)).toBe('false')
    expect(localStorage.getItem(MIGRATION_KEY)).toBe('1')
  })

  it('已经迁移过则尊重用户当前选择（折叠仍然生效）', async () => {
    localStorage.setItem(MIGRATION_KEY, '1')
    localStorage.setItem(SIDE_KEY, 'true')

    const mod = await import('@/composables/useAiDrawer')

    expect(mod.useAiDrawer().sideCollapsed.value).toBe(true)
  })

  it('从未折叠过的用户不受影响，默认展开', async () => {
    const mod = await import('@/composables/useAiDrawer')

    expect(mod.useAiDrawer().sideCollapsed.value).toBe(false)
    expect(localStorage.getItem(SIDE_KEY)).toBeNull()
  })
})
