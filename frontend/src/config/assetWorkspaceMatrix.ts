/**
 * 资产工作台差异矩阵（v5.12 / W62-07 / FR-v5.12-06~08）。
 *
 * 唯一真值表：哪些页签 / 列 / 动作在哪种 scope 下出现，只在这里写一次。
 * 逐行对应 `docs/release/_draft/v5.12.0/PRD.md` §3 的 16 行表格（机检会数行数）。
 *
 * 硬约束：组件里不许另写 `v-if="scope === ..."` 的整块模板，也不许把同一张
 * 设备表在两个页面各写一遍。可见性一律走 `isVisible` / `filterColumnsByMatrix`。
 */
export type AssetScope = 'project' | 'global'
export type MatrixKind = 'tab' | 'column' | 'action'

export interface MatrixRow {
  /** 稳定标识：tab.* / col.* / action.*，与 i18n 键 w62.matrix.* 对应。 */
  key: string
  kind: MatrixKind
  projectScope: boolean
  globalScope: boolean
  labelKey: string
  /** 为什么有这个差异（英文，避免 i18n 机检把说明当界面文案）。 */
  note: string
}

export const ASSET_WORKSPACE_MATRIX: readonly MatrixRow[] = [
  { key: 'tab.devices', kind: 'tab', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.tabDevices', note: 'Shared device table' },
  { key: 'tab.links', kind: 'tab', projectScope: true, globalScope: false,
    labelKey: 'w62.matrix.tabLinks', note: 'Links belong to one project topology' },
  { key: 'tab.credentials', kind: 'tab', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.tabCredentials', note: 'Shared credential table' },
  { key: 'tab.leases', kind: 'tab', projectScope: true, globalScope: false,
    labelKey: 'w62.matrix.tabLeases', note: 'Leases are project-scoped' },
  { key: 'col.ownerScope', kind: 'column', projectScope: false, globalScope: true,
    labelKey: 'w62.matrix.colOwnerScope', note: 'Global list must distinguish platform vs private' },
  { key: 'col.source', kind: 'column', projectScope: true, globalScope: false,
    labelKey: 'w62.matrix.colSource', note: 'Project list must show borrowed platform assets' },
  { key: 'col.refProjects', kind: 'column', projectScope: false, globalScope: true,
    labelKey: 'w62.matrix.colRefProjects', note: 'Global delete needs reference count' },
  { key: 'col.authStatus', kind: 'column', projectScope: true, globalScope: false,
    labelKey: 'w62.matrix.colAuthStatus', note: 'Project list shows authorization state' },
  { key: 'action.create', kind: 'action', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.actionCreate', note: 'Both scopes can create' },
  { key: 'action.edit', kind: 'action', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.actionEdit', note: 'Global PUT is added in W62-12' },
  { key: 'action.delete', kind: 'action', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.actionDelete', note: 'Copy differs: unlink vs delete' },
  { key: 'action.batchDelete', kind: 'action', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.actionBatchDelete', note: 'Global batch delete is admin only' },
  { key: 'action.importExport', kind: 'action', projectScope: false, globalScope: true,
    labelKey: 'w62.matrix.actionImportExport', note: 'Platform library maintenance only' },
  { key: 'action.collect', kind: 'action', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.actionCollect', note: 'Behaviour unchanged in 5.12; wired in 5.13' },
  { key: 'action.bindCredential', kind: 'action', projectScope: true, globalScope: true,
    labelKey: 'w62.matrix.actionBindCredential', note: 'Project side is read-only in 5.12' },
  { key: 'action.referencePlatform', kind: 'action', projectScope: true, globalScope: false,
    labelKey: 'w62.matrix.actionReferencePlatform', note: 'Project-only reference / unreference' },
]

/** PRD §3 的数据行数；文档或代码任一侧改了行数，机检必须判红。 */
export const PRD_MATRIX_ROW_COUNT = 16

/** 差异行数（两 scope 取值不同的行）：W62-08 用它约束 `v-if="scope"` 整块数。 */
export function differenceCount(rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX): number {
  return rows.filter((row) => row.projectScope !== row.globalScope).length
}

export function rowsOf(
  scope: AssetScope,
  kind: MatrixKind,
  rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX,
): MatrixRow[] {
  return rows.filter((row) => row.kind === kind && row[scope === 'project' ? 'projectScope' : 'globalScope'])
}

export function visibleTabs(scope: AssetScope, rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX): MatrixRow[] {
  return rowsOf(scope, 'tab', rows)
}

export function visibleActions(scope: AssetScope, rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX): MatrixRow[] {
  return rowsOf(scope, 'action', rows)
}

export function isVisible(scope: AssetScope, key: string, rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX): boolean {
  const row = rows.find((item) => item.key === key)
  return row ? row[scope === 'project' ? 'projectScope' : 'globalScope'] : false
}

/** 矩阵列名 → 表格列 key（去掉 `col.` 前缀）。 */
export function columnKeyOf(row: MatrixRow): string {
  return row.key.replace(/^col\./, '')
}

/**
 * 唯一的列过滤入口：矩阵里标 ❌ 的差异列，在对应 scope 的渲染结果里必须消失。
 * 共享列（name / managementIp ...）不在矩阵里，一律保留。
 */
export function filterColumnsByMatrix<T extends { key: string }>(
  scope: AssetScope,
  columns: readonly T[],
  rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX,
): T[] {
  const forbidden = new Set(
    rows.filter((row) => row.kind === 'column' && !row[scope === 'project' ? 'projectScope' : 'globalScope'])
        .map(columnKeyOf),
  )
  return columns.filter((column) => !forbidden.has(column.key))
}

export interface MatrixIssue {
  key: string
  reason: string
}

/**
 * W62-15 机检内核：① ❌ 的列不得出现在渲染结果；② 新增差异列未登记 → 判红。
 * `knownDifferenceKeys` 是「已知的差异列名单」，注入一个未声明的列时靠它识别。
 */
export function validateMatrixColumns(
  scope: AssetScope,
  renderedKeys: readonly string[],
  rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX,
  knownDifferenceKeys: readonly string[] = ['ownerScope', 'source', 'refProjects', 'authStatus'],
): MatrixIssue[] {
  const issues: MatrixIssue[] = []
  const declared = new Set(rows.filter((row) => row.kind === 'column').map(columnKeyOf))
  for (const key of knownDifferenceKeys) {
    if (!declared.has(key)) {
      issues.push({ key, reason: 'known-difference-column-not-declared-in-matrix' })
    }
  }
  for (const row of rows.filter((item) => item.kind === 'column')) {
    const key = columnKeyOf(row)
    const visible = row[scope === 'project' ? 'projectScope' : 'globalScope']
    if (!visible && renderedKeys.includes(key)) {
      issues.push({ key, reason: 'forbidden-column-rendered' })
    }
  }
  return issues
}

/** 自检用：投影出该 scope 下真正可见的矩阵条目（列 / 动作 / 页签）。 */
export function matrixProjection(scope: AssetScope, rows: readonly MatrixRow[] = ASSET_WORKSPACE_MATRIX): string[] {
  return rows.filter((row) => row[scope === 'project' ? 'projectScope' : 'globalScope'])
      .map((row) => row.key)
}
