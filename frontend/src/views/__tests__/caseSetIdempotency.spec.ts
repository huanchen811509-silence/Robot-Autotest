/**
 * W62-03：成员列 ⊆ 成员详情 DTO。
 *
 * 与 5.11 `columnIdempotency.spec.ts` 同一口径：不是「测试里另写一份映射表」就算数，
 * 而是把视图源码里的列 key 现抽出来，跟配置常量三向对齐；并留 6 条注入反证。
 */
import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  CASE_SET_MEMBER_COLUMNS,
  CASE_SET_MEMBER_FIELDS,
  MEMBER_DETAIL_ROWS,
  validateMemberColumns,
  type MemberColumn,
} from '@/config/caseSetMemberColumns'

/** vitest 下 import.meta.url 不是 file:，按 5.11 的口径用 cwd + 候选路径。 */
function readSource(rel: string): string {
  for (const base of [process.cwd(), join(process.cwd(), '..')]) {
    const full = join(base, rel)
    if (existsSync(full)) return readFileSync(full, 'utf-8')
  }
  throw new Error(`找不到源码：${rel}`)
}

function interfaceFields(): string[] {
  const src = readSource('src/config/caseSetMemberColumns.ts')
  const block = /export type CaseSetMemberView = TestCase\b/.exec(src)
  if (!block) throw new Error('CaseSetMemberView 形态变了，本测试必须跟上')
  // 它继承 TestCase；字段清单在 cases.ts，逐字段抽出来比对
  const cases = readSource('src/api/cases.ts')
  const m = /export interface TestCase extends CaseScriptFields \{([\s\S]*?)\n\}/.exec(cases)
  const scripts = /export interface CaseScriptFields \{([\s\S]*?)\n\}/.exec(cases)
  if (!m || !scripts) throw new Error('TestCase / CaseScriptFields 接口没找到')
  const names = (body: string): string[] =>
    [...body.matchAll(/^\s*([A-Za-z]\w*)\??\s*:/gm)].map((x) => x[1])
  return [...names(m[1]), ...names(scripts[1])]
}

/** 现抽视图里真实渲染的成员列，配置写了但页面没渲染一样判红。 */
function templateColumnKeys(): string[] {
  const src = readSource('src/views/CaseSetsView.vue')
  return [...new Set([...src.matchAll(/class="set-item-([a-z]+)"/g)].map((x) => x[1]))]
}

describe('用例集成员信息幂等（W62-03）', () => {
  it('CASE_SET_MEMBER_FIELDS 与 DTO 接口逐字段一致', () => {
    expect([...CASE_SET_MEMBER_FIELDS].sort()).toEqual(interfaceFields().sort())
  })

  it('视图真实列 ↔ 列配置 ↔ 详情 DTO 三向对齐', () => {
    const issues = validateMemberColumns(
      CASE_SET_MEMBER_COLUMNS,
      CASE_SET_MEMBER_FIELDS,
      templateColumnKeys(),
    )
    expect(issues).toEqual([])
  })

  it('详情字段数 ≥ 表格列数', () => {
    expect(MEMBER_DETAIL_ROWS.length).toBeGreaterThanOrEqual(CASE_SET_MEMBER_COLUMNS.length)
  })

  it('反证 1：注入一个 DTO 里不存在的假列名 → 判红', () => {
    const bogus: MemberColumn = { key: 'bogus', detailField: 'noSuchField' as never, labelKey: 'x' }
    const issues = validateMemberColumns(
      [...CASE_SET_MEMBER_COLUMNS, bogus], CASE_SET_MEMBER_FIELDS, templateColumnKeys(),
    )
    expect(issues.some((i) => i.column === 'bogus' && i.reason.includes('unknown-detail-field'))).toBe(true)
  })

  it('反证 2：视图多一列而矩阵没登记 → 判红', () => {
    const issues = validateMemberColumns(
      CASE_SET_MEMBER_COLUMNS, CASE_SET_MEMBER_FIELDS, [...templateColumnKeys(), 'ghost'],
    )
    expect(issues.some((i) => i.column === 'ghost')).toBe(true)
  })

  it('反证 3：矩阵登记了列但视图删掉 → 判红', () => {
    const keys = templateColumnKeys().filter((k) => k !== 'script')
    const issues = validateMemberColumns(CASE_SET_MEMBER_COLUMNS, CASE_SET_MEMBER_FIELDS, keys)
    expect(issues.some((i) => i.column === 'script')).toBe(true)
  })

  it('反证 4：派生列不写理由 → 判红', () => {
    const bad = CASE_SET_MEMBER_COLUMNS.map((c) =>
      c.key === 'order' ? { ...c, derivedNote: undefined } : c)
    const issues = validateMemberColumns(bad, CASE_SET_MEMBER_FIELDS, templateColumnKeys())
    expect(issues.some((i) => i.column === 'order')).toBe(true)
  })

  it('反证 5：通配映射 → 判红', () => {
    const bad = CASE_SET_MEMBER_COLUMNS.map((c) =>
      c.key === 'title' ? { ...c, detailField: '*' as never } : c)
    const issues = validateMemberColumns(bad, CASE_SET_MEMBER_FIELDS, templateColumnKeys())
    expect(issues.some((i) => i.reason.includes('wildcard'))).toBe(true)
  })

  it('反证 6：重复列 key → 判红', () => {
    const issues = validateMemberColumns(
      [...CASE_SET_MEMBER_COLUMNS, CASE_SET_MEMBER_COLUMNS[1]],
      CASE_SET_MEMBER_FIELDS, templateColumnKeys(),
    )
    expect(issues.some((i) => i.reason.includes('duplicate'))).toBe(true)
  })

  it('脚本三处同源：详情区复用 CaseScriptLink 且前端不切路径', () => {
    const src = readSource('src/components/CaseSetMemberDetail.vue')
    expect(src).toContain('CaseScriptLink')
    expect(src).not.toContain("split('/')")
    expect(src).toContain('member.scriptName')
    const cases = readSource('src/api/cases.ts')
    expect(cases).toContain('interface CaseScriptFields')
  })
})
