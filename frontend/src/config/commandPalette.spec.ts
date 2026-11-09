/** 命令面板纯逻辑（NAV-04 / FR-v2.0-09）：中英文搜索、最近 10 条、收藏、作用域/折叠持久化。 */
import { beforeEach, describe, expect, it } from 'vitest'
import {
  RECENT_LIMIT,
  loadExpanded,
  loadFavorites,
  loadPaletteScope,
  loadRecent,
  pushRecent,
  saveExpanded,
  savePaletteScope,
  searchEntries,
  toggleFavorite,
  type PaletteEntry,
} from '@/config/commandPalette'

const entries: PaletteEntry[] = [
  { key: 'overview', label: '工作台', alias: 'Workspace', path: '/overview' },
  { key: 'reports', label: '报告', alias: 'Reports', path: '/reports' },
  { key: 'settings', label: '系统设置', alias: 'Settings', path: '/settings' },
]

beforeEach(() => localStorage.clear())

describe('中英文搜索', () => {
  it('中文界面用英文也能搜到，反之亦然', () => {
    expect(searchEntries(entries, 'work').map((e) => e.key)).toEqual(['overview'])
    expect(searchEntries(entries, '报告').map((e) => e.key)).toEqual(['reports'])
    expect(searchEntries(entries, 'SETTINGS').map((e) => e.key)).toEqual(['settings'])
  })

  it('路径与 key 也参与匹配；空关键词返回全量', () => {
    expect(searchEntries(entries, '/reports').map((e) => e.key)).toEqual(['reports'])
    expect(searchEntries(entries, '  ')).toHaveLength(entries.length)
  })

  it('无结果时返回空数组而不是抛错', () => {
    expect(searchEntries(entries, '不存在的页面')).toEqual([])
  })
})

describe('最近访问与收藏', () => {
  it('最近访问去重保序，最多 10 条', () => {
    let recent = pushRecent('reports', [])
    recent = pushRecent('overview', recent)
    recent = pushRecent('reports', recent)
    expect(recent).toEqual(['reports', 'overview'])
    for (let i = 0; i < 12; i += 1) recent = pushRecent(`key-${i}`, recent)
    expect(RECENT_LIMIT).toBe(10)
    expect(recent).toHaveLength(10)
    expect(recent[0]).toBe('key-11')
    expect(loadRecent()).toEqual(recent)
  })

  it('旧版只存 5 条时仍可直接读取（key 结构不变）', () => {
    localStorage.setItem(
      'robot.palette.recent',
      JSON.stringify(['overview', 'reports', 'settings', 'cases', 'runs']),
    )
    expect(loadRecent()).toEqual(['overview', 'reports', 'settings', 'cases', 'runs'])
  })

  it('收藏可加可去，刷新后仍在', () => {
    const first = toggleFavorite('reports', [])
    expect(first).toEqual(['reports'])
    const second = toggleFavorite('reports', first)
    expect(second).toEqual([])
    const third = toggleFavorite('overview', second)
    expect(loadFavorites()).toEqual(third)
  })
})

describe('实体搜索作用域持久化', () => {
  it('默认当前项目，切换后记住；脏值回落 current', () => {
    expect(loadPaletteScope()).toBe('current')
    savePaletteScope('all')
    expect(loadPaletteScope()).toBe('all')
    localStorage.setItem('robot.palette.scope', 'invalid')
    expect(loadPaletteScope()).toBe('current')
  })
})

describe('菜单折叠持久化', () => {
  it('保存后可读回，脏数据不炸', () => {
    saveExpanded({ admin: false, assets: true })
    expect(loadExpanded()).toEqual({ admin: false, assets: true })
    localStorage.setItem('robot.nav.expanded', '{not-json')
    expect(loadExpanded()).toEqual({})
  })
})
