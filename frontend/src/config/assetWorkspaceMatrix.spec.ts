/**
 * W62-07 / W62-15：差异矩阵 = 唯一数据源，且能判红。
 * 行数与 PRD §3 表格对齐（读文档源码数 `|` 开头的行），不靠人肉声明。
 */
import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  ASSET_WORKSPACE_MATRIX,
  PRD_MATRIX_ROW_COUNT,
  columnKeyOf,
  differenceCount,
  filterColumnsByMatrix,
  isVisible,
  matrixProjection,
  validateMatrixColumns,
  visibleActions,
  visibleTabs,
  type MatrixRow,
} from '@/config/assetWorkspaceMatrix'

function readSource(rel: string): string {
  for (const base of [process.cwd(), join(process.cwd(), '..')]) {
    const full = join(base, rel)
    if (existsSync(full)) return readFileSync(full, 'utf-8')
  }
  throw new Error(`source not found: ${rel}`)
}

/** PRD §3 表格：从「## 3.」到「> 这张表是唯一真值」之间的 `|` 数据行。 */
function prdMatrixRows(): number {
  const src = readSource('docs/release/_draft/v5.12.0/PRD.md')
  const section = src.split('## 3. 资产工作台差异矩阵')[1]?.split('> 这张表是唯一真值')[0]
  if (!section) throw new Error('PRD §3 section not found')
  // 表头是「| 列 / 动作 | ...」，数据行里也有以「列：」开头的四行，不能一刀切掉
  return section.split('\n').filter((line) => {
    const s = line.trim()
    return s.startsWith('|') && !s.startsWith('|---') && !s.startsWith('| 列 / 动作')
  }).length
}

const DIFF_KEYS = ['ownerScope', 'source', 'refProjects', 'authStatus']
const allCols = [
  { key: 'name' }, { key: 'managementIp' },
  ...DIFF_KEYS.map((key) => ({ key })),
]

describe('资产工作台差异矩阵（W62-07）', () => {
  it('矩阵行数 = PRD §3 表格行数 = 16', () => {
    expect(ASSET_WORKSPACE_MATRIX).toHaveLength(PRD_MATRIX_ROW_COUNT)
    expect(ASSET_WORKSPACE_MATRIX).toHaveLength(prdMatrixRows())
  })

  it('每行都有 key / kind / 两 scope 布尔值 / labelKey / note，且 key 唯一', () => {
    const keys = new Set<string>()
    for (const row of ASSET_WORKSPACE_MATRIX) {
      expect(row.key).toMatch(/^(tab|col|action)\./)
      expect(['tab', 'column', 'action']).toContain(row.kind)
      expect(typeof row.projectScope).toBe('boolean')
      expect(typeof row.globalScope).toBe('boolean')
      expect(row.labelKey).toMatch(/^w62\.matrix\./)
      expect(row.note.length).toBeGreaterThan(0)
      expect(keys.has(row.key)).toBe(false)
      keys.add(row.key)
    }
  })

  it('页签数：project 4 / global 2', () => {
    expect(visibleTabs('project')).toHaveLength(4)
    expect(visibleTabs('global')).toHaveLength(2)
    expect(visibleTabs('global').map((r) => r.key)).toEqual(['tab.devices', 'tab.credentials'])
  })

  it('动作投影里 ❌ 的条目不出现', () => {
    const project = visibleActions('project').map((r) => r.key)
    const global = visibleActions('global').map((r) => r.key)
    expect(project).not.toContain('action.importExport')
    expect(global).not.toContain('action.referencePlatform')
    expect(matrixProjection('global')).not.toContain('tab.links')
  })

  it('差异行数用于约束组件里的 scope 分支数', () => {
    expect(differenceCount()).toBe(8)
  })
})

describe('矩阵机检能判红（W62-15）', () => {
  it('project 无「引用项目数」列；global 无「授权状态」列', () => {
    const projectKeys = filterColumnsByMatrix('project', allCols).map((c) => c.key)
    const globalKeys = filterColumnsByMatrix('global', allCols).map((c) => c.key)
    expect(projectKeys).not.toContain('refProjects')
    expect(projectKeys).not.toContain('ownerScope')
    expect(projectKeys).toContain('source')
    expect(globalKeys).not.toContain('authStatus')
    expect(globalKeys).not.toContain('source')
    expect(globalKeys).toContain('refProjects')
  })

  it('两 scope 渲染结果都通过 validateMatrixColumns', () => {
    expect(validateMatrixColumns('project', filterColumnsByMatrix('project', allCols).map((c) => c.key))).toEqual([])
    expect(validateMatrixColumns('global', filterColumnsByMatrix('global', allCols).map((c) => c.key))).toEqual([])
  })

  it('反证 1：把 ❌ 的列塞进渲染结果 → 判红', () => {
    const issues = validateMatrixColumns('project', ['name', 'refProjects'])
    expect(issues.some((i) => i.key === 'refProjects' && i.reason === 'forbidden-column-rendered')).toBe(true)
  })

  it('反证 2：矩阵删掉一行（新增列未登记）→ 判红', () => {
    const broken = ASSET_WORKSPACE_MATRIX.filter((row) => row.key !== 'col.authStatus')
    const issues = validateMatrixColumns('project', ['authStatus'], broken)
    expect(issues.some((i) => i.key === 'authStatus' && i.reason.includes('not-declared'))).toBe(true)
  })

  it('反证 3：isVisible 对未声明的 key 返回 false，不默认放行', () => {
    expect(isVisible('project', 'col.bogus')).toBe(false)
    expect(isVisible('global', 'action.importExport')).toBe(true)
  })

  it('反证 4：注入一个未声明的差异列 → 机检判红', () => {
    const issues = validateMatrixColumns('project', ['ghost'], ASSET_WORKSPACE_MATRIX,
      [...DIFF_KEYS, 'ghost'])
    expect(issues.some((i) => i.key === 'ghost')).toBe(true)
  })

  it('columnKeyOf 去掉 col. 前缀且不动其它前缀', () => {
    expect(columnKeyOf({ key: 'col.source' } as MatrixRow)).toBe('source')
    expect(columnKeyOf({ key: 'tab.devices' } as MatrixRow)).toBe('tab.devices')
  })
})
