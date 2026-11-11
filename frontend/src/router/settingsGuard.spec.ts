/**
 * 全局模式守卫的两条独立判据（v4.9.2 / OPT-01 / FR-49-10~12）。
 *
 * 这里只测导出的两个纯函数，不挂载路由、不碰 pinia / localStorage：
 *   - shouldPreserveGlobalMode 只看 meta.global；
 *   - canEnterGlobalMode 只看角色；
 *   - 二者互不干扰（组合断言）。
 */
import { describe, expect, it } from 'vitest'
import { shouldPreserveGlobalMode, canEnterGlobalMode, type GlobalModeRouteLike } from '@/router'

describe('shouldPreserveGlobalMode（模式保持只看 meta.global）', () => {
  it('meta.global === true 的全局路由保持模式', () => {
    expect(shouldPreserveGlobalMode({ meta: { global: true } })).toBe(true)
  })

  it('无 meta / meta.global 非 true / 缺 meta 均不保持', () => {
    expect(shouldPreserveGlobalMode({})).toBe(false)
    expect(shouldPreserveGlobalMode({ meta: {} })).toBe(false)
    expect(shouldPreserveGlobalMode({ meta: { global: false } })).toBe(false)
    expect(shouldPreserveGlobalMode({ meta: { global: 'true' } })).toBe(false)
    expect(shouldPreserveGlobalMode(null)).toBe(false)
  })

  it('不读角色字段：带任何角色都不改变模式保持结果', () => {
    // 故意附带额外字段，验证函数只取 meta.global
    const withRole = (role: string): GlobalModeRouteLike =>
      ({ meta: { global: true }, role }) as GlobalModeRouteLike
    expect(shouldPreserveGlobalMode(withRole('viewer'))).toBe(true)
    expect(shouldPreserveGlobalMode(withRole('admin'))).toBe(true)
  })
})

describe('canEnterGlobalMode（角色准入只看角色）', () => {
  it('ADMIN / PM 允许（大小写不敏感）', () => {
    expect(canEnterGlobalMode('admin')).toBe(true)
    expect(canEnterGlobalMode('ADMIN')).toBe(true)
    expect(canEnterGlobalMode('pm')).toBe(true)
    expect(canEnterGlobalMode('Pm')).toBe(true)
  })

  it('ENGINEER / VIEWER / 未知 / 空角色一律拒绝', () => {
    expect(canEnterGlobalMode('engineer')).toBe(false)
    expect(canEnterGlobalMode('viewer')).toBe(false)
    expect(canEnterGlobalMode('guest')).toBe(false)
    expect(canEnterGlobalMode('')).toBe(false)
    expect(canEnterGlobalMode(null)).toBe(false)
  })

  it('不读 meta：与路由字段无关', () => {
    expect(canEnterGlobalMode('engineer')).toBe(false)
    expect(canEnterGlobalMode('admin')).toBe(true)
  })
})

describe('两条判据互不耦合', () => {
  it('工程师访问全局路由：模式该保持、角色仍被拒', () => {
    const route = { meta: { global: true } }
    expect(shouldPreserveGlobalMode(route)).toBe(true)
    expect(canEnterGlobalMode('engineer')).toBe(false)
  })

  it('管理员访问非全局路由：角色允许、模式不保持', () => {
    expect(canEnterGlobalMode('admin')).toBe(true)
    expect(shouldPreserveGlobalMode({ meta: {} })).toBe(false)
  })

  it('改 meta.global 不影响 canEnterGlobalMode；改角色不影响 shouldPreserveGlobalMode', () => {
    const viewer = 'viewer'
    expect(canEnterGlobalMode(viewer)).toBe(false)
    expect(shouldPreserveGlobalMode({ meta: { global: true } } as GlobalModeRouteLike)).toBe(true)
    expect(shouldPreserveGlobalMode({ meta: {} })).toBe(false)
    expect(canEnterGlobalMode('admin')).toBe(true)
  })
})
