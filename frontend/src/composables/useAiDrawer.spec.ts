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

/**
 * v2.6 / FR-AI-14：抽屉宽度 + agent/模型偏好。
 *
 * 宽度是**唯一真源**：拖拽、档位按钮、方向键、恢复偏好四条路径都过 clampWidth，
 * 所以这里只要锁住 clampWidth 与 setWidth，其余路径不会漏出边界。
 */
describe('抽屉宽度与偏好', () => {
  it('clampWidth 把任意输入收进 340–760，非法值回落默认 420', async () => {
    const mod = await import('@/composables/useAiDrawer')

    expect(mod.clampWidth(100)).toBe(340)
    expect(mod.clampWidth(340)).toBe(340)
    expect(mod.clampWidth(500)).toBe(500)
    expect(mod.clampWidth(760)).toBe(760)
    expect(mod.clampWidth(9999)).toBe(760)
    expect(mod.clampWidth(Number.NaN)).toBe(420)
    expect(mod.clampWidth(420.6)).toBe(421)
  })

  it('存量偏好越界时读成边界值，不把 280 这种老值带进新布局', async () => {
    localStorage.setItem('robot.ai.drawer.width', '280')

    const mod = await import('@/composables/useAiDrawer')

    expect(mod.useAiDrawer().width.value).toBe(mod.AI_DRAWER_MIN)
  })

  it('setWidth 钳制后写回 localStorage', async () => {
    const mod = await import('@/composables/useAiDrawer')
    const drawer = mod.useAiDrawer()

    drawer.setWidth(5000)
    expect(drawer.width.value).toBe(mod.AI_DRAWER_MAX)
    expect(localStorage.getItem('robot.ai.drawer.width')).toBe(String(mod.AI_DRAWER_MAX))

    drawer.setWidth(10)
    expect(drawer.width.value).toBe(mod.AI_DRAWER_MIN)
  })

  it('换 agent 会清掉模型：上一家的模型名换一家多半不存在', async () => {
    const mod = await import('@/composables/useAiDrawer')
    const drawer = mod.useAiDrawer()

    drawer.setAgent('pi')
    drawer.setModel('ark/deepseek-v4-flash')
    expect(localStorage.getItem('robot.ai.drawer.model')).toBe('ark/deepseek-v4-flash')

    drawer.setAgent('codex')
    expect(drawer.agent.value).toBe('codex')
    expect(drawer.model.value).toBe('')
    expect(localStorage.getItem('robot.ai.drawer.model')).toBe('')
  })
})
