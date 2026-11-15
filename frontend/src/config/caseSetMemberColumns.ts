/**
 * 用例集成员：列配置 ↔ 详情 DTO 的唯一真值表（v5.12 / W62-03 / FR-v5.12-02）。
 *
 * 不变量：成员表格的每一列，要么是「详情 DTO 里存在的字段」，要么必须写明
 * 它是怎么算出来的（derivedNote）。写成通配映射等于没验，机检专门为此判红。
 *
 * 与 5.11 的 `CaseListView` / `CaseDetailView` 是同一套事实：
 * 同一条用例在「用例清单 / 用例详情 / 用例集成员」三处看到的值必须逐字一致。
 */
import type { TestCase } from '@/api/cases'

/** 成员详情 DTO：视图里能显示的字段全集。加字段必须同步 `CASE_SET_MEMBER_FIELDS`。 */
export interface CaseSetMemberView extends TestCase {}

/** 接口字段清单（与上面的 interface 逐字段由单测核对，防止「加了字段忘了登记」）。 */
export const CASE_SET_MEMBER_FIELDS = [
  'id',
  'projectId',
  'caseCode',
  'title',
  'category',
  'priority',
  'safetyLevel',
  'precondition',
  'expected',
  'domainTag',
  'reviewStatus',
  'source',
  'capability',
  'description',
  'scriptPath',
  'scriptName',
  'scriptNodeId',
  'bindingState',
  'scriptKernel',
  'otherScriptCount',
  'scriptAbsoluteSource',
] as const

export type CaseSetMemberField = (typeof CASE_SET_MEMBER_FIELDS)[number]

export interface MemberColumn {
  /** 与模板 `set-item-<key>` 一一对应，也是机检抓取的锚点。 */
  key: string
  /** 值来自详情 DTO 的哪个字段。 */
  detailField?: CaseSetMemberField
  /** 不是库字段时必须写清算法，禁止留空绕过校验。 */
  derivedNote?: string
  labelKey: string
}

/** 详情区逐行展示的字段（顺序即界面顺序）。 */
export const MEMBER_DETAIL_ROWS = [
  { field: 'caseCode', labelKey: 'w62.field.caseCode' },
  { field: 'title', labelKey: 'w62.field.title' },
  { field: 'category', labelKey: 'w62.field.category' },
  { field: 'priority', labelKey: 'w62.field.priority' },
  { field: 'safetyLevel', labelKey: 'w62.field.safetyLevel' },
  { field: 'precondition', labelKey: 'w62.field.precondition' },
  { field: 'expected', labelKey: 'w62.field.expected' },
  { field: 'scriptPath', labelKey: 'w62.field.scriptPath' },
  { field: 'scriptNodeId', labelKey: 'w62.field.scriptNodeId' },
  { field: 'bindingState', labelKey: 'w62.field.bindingState' },
] as const satisfies readonly { field: CaseSetMemberField; labelKey: string }[]

/** 成员表格列：新增一列必须先在这里登记，否则机检判红。 */
export const CASE_SET_MEMBER_COLUMNS: readonly MemberColumn[] = [
  {
    key: 'order',
    derivedNote: 'Row number = index of the backend ORDER BY i.seq_no array + 1; not a DB column',
    labelKey: 'w62.field.order',
  },
  { key: 'code', detailField: 'caseCode', labelKey: 'w62.field.caseCode' },
  { key: 'title', detailField: 'title', labelKey: 'w62.field.title' },
  { key: 'category', detailField: 'category', labelKey: 'w62.field.category' },
  { key: 'priority', detailField: 'priority', labelKey: 'w62.field.priority' },
  { key: 'script', detailField: 'scriptPath', labelKey: 'w62.memberScript' },
]

export interface ValidationIssue {
  column: string
  reason: string
}

/**
 * 机检内核：列 ↔ DTO ↔ 模板三向对齐。
 * 单测、后续门禁脚本都调它，避免「测试里另写一份规则」。
 */
export function validateMemberColumns(
  columns: readonly MemberColumn[],
  fields: readonly string[],
  templateKeys: readonly string[],
): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const known = new Set(fields)
  const declared = new Set<string>()
  for (const column of columns) {
    if (declared.has(column.key)) {
      issues.push({ column: column.key, reason: 'duplicate-column-key' })
    }
    declared.add(column.key)
    if (column.detailField && column.derivedNote) {
      issues.push({ column: column.key, reason: 'column-cannot-be-both-field-and-derived' })
    }
    if (!column.detailField && !column.derivedNote) {
      issues.push({ column: column.key, reason: 'missing-field-and-derived-note' })
    }
    if (column.detailField && !known.has(column.detailField)) {
      issues.push({ column: column.key, reason: `unknown-detail-field:${column.detailField}` })
    }
    if (String(column.detailField ?? '') === '*') {
      issues.push({ column: column.key, reason: 'wildcard-mapping-forbidden' })
    }
  }
  const rendered = new Set(templateKeys)
  for (const key of rendered) {
    if (!declared.has(key)) {
      issues.push({ column: key, reason: 'column-rendered-but-not-declared' })
    }
  }
  for (const key of declared) {
    if (!rendered.has(key)) {
      issues.push({ column: key, reason: 'column-declared-but-not-rendered' })
    }
  }
  return issues
}
