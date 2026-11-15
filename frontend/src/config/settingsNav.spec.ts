/**
 * 系统设置单一数据源（v4.9.2 / OPT-01 / FR-49-01~04）。
 * 纯数据断言：5 项、固定顺序、两模式派生结果深度相等、public 能力。
 *
 * v5.9.0 / FR-v5.9-04：「用户管理」迁入「用户与权限」一级菜单，故 6 → 5 项。
 * 迁移理由不是「少一项更清爽」，而是**不能留第二套写入口**。
 */
import { describe, expect, it } from 'vitest'
import {
  SETTINGS_NAV,
  projectSettingsNav,
  globalSettingsNav,
  projectSettingsPath,
  type SettingsNavItem,
} from '@/config/settingsNav'

/** 名称 / 顺序 / 能力维度（路径因命名空间不同，单独断言）。 */
function shape(items: readonly SettingsNavItem[]): Pick<SettingsNavItem, 'key' | 'labelKey' | 'capability'>[] {
  return items.map(({ key, labelKey, capability }) => ({ key, labelKey, capability }))
}

describe('SETTINGS_NAV 单一数据源', () => {
  it('固定 5 项，顺序为平台设置 / AI 助手 / 审计日志 / 关于 / 版本迭代', () => {
    expect(SETTINGS_NAV).toHaveLength(5)
    expect(SETTINGS_NAV.map((item) => item.key)).toEqual([
      'settings-platform',
      'settings-ai',
      'settings-audit',
      'settings-about',
      'settings-changelog',
    ])
  })

  it('capability 依次为 settings:read / ai:config / audit:read / public / public', () => {
    expect(SETTINGS_NAV.map((item) => item.capability)).toEqual([
      'settings:read',
      'ai:config',
      'audit:read',
      'public',
      'public',
    ])
  })

  it('about 与 changelog 的 capability 显式为字符串 public', () => {
    expect(SETTINGS_NAV.find((item) => item.key === 'settings-about')?.capability).toBe('public')
    expect(SETTINGS_NAV.find((item) => item.key === 'settings-changelog')?.capability).toBe('public')
  })

  it('两模式派生结果（名称 / 顺序 / 能力）深度相等，仅路径命名空间不同', () => {
    const project = projectSettingsNav()
    const global = globalSettingsNav()
    expect(shape(global)).toEqual(shape(project))

    expect(global.map((item) => item.path)).toEqual([
      '/g/settings',
      '/g/settings/ai',
      '/g/admin/audit',
      '/g/about',
      '/g/changelog',
    ])
    expect(project.map((item) => item.path)).toEqual([
      '/settings',
      '/settings/ai',
      '/admin/audit',
      '/about',
      '/changelog',
    ])
  })

  it('projectSettingsPath 去掉 /g 前缀，非 /g 路径原样返回', () => {
    expect(projectSettingsPath('/g/settings/ai')).toBe('/settings/ai')
    expect(projectSettingsPath('/workbench')).toBe('/workbench')
  })
})
