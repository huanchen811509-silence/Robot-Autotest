/**
 * 信息幂等不变量（v5.11.0 / FR-v5.11-04 / W61-09）。
 *
 * 不变量：**表格列配置的每个 `key` 必须能在详情 DTO 里找到同名字段，或在本文件的
 * 映射表里显式声明来源**。它防的是「表格上新加一列，详情里查不到」这类静默漂移——
 * 用户点了行、看到详情，却发现表格上有这个字段、详情里没有，而构建和单测都不会红。
 *
 * 三道锁，缺一道这条不变量就是口号：
 * 1. `DETAIL_FIELDS` 与 `CaseDetailView` 接口**逐字段**对齐（接口加字段忘了同步 → 红）；
 * 2. 两页的列 `key` 从源码现场抽出，必须与映射表**逐个一致**（视图加列忘了登记 → 红）；
 * 3. 映射表禁止通配：没有 `dtoField` 就必须给 `computedFrom` 或 `reason`（写不出理由 → 红）。
 *
 * 第 3 道锁配了反证用例：注入假列名 / 漏登记 / 不存在的 DTO 字段 / 无来源计算列，
 * 全部必须抛错。没有这几条反证，`validateColumns` 写错成「什么都不检查」也没人发现。
 */
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 读源码：cwd 可能是仓库根，也可能是 `frontend/`（两种跑法都要支持）。
 * 不用 `import.meta.url` —— vitest 下它不保证是 `file:` 协议。
 */
function readSource(relative: string): string {
  const candidates = [
    resolve(process.cwd(), relative),
    resolve(process.cwd(), 'frontend', relative),
  ]
  const hit = candidates.find((candidate) => existsSync(candidate))
  if (!hit) {
    throw new Error(`找不到 ${relative}，试过：${candidates.join(' / ')}`)
  }
  return readFileSync(hit, 'utf8')
}

const API_SRC = readSource('src/api/caseDetail.ts')
const CASES_VIEW_SRC = readSource('src/views/CasesView.vue')
const GLOBAL_VIEW_SRC = readSource('src/views/global/GlobalCasesView.vue')

/** 详情 DTO 字段全集：与 `CaseDetailView` 接口逐字段对齐（对齐关系由第 1 条用例兜底）。 */
const DETAIL_FIELDS = [
  'id',
  'caseCode',
  'externalId',
  'title',
  'categoryL1',
  'categoryL2',
  'priority',
  'safetyLevel',
  'lifecycleStatus',
  'versionNo',
  'sourceSheet',
  'contentSha',
  'aSideCommand',
  'bSideCommand',
  'switchConfig',
  'testResult',
  'testResource',
  'syncJobId',
  'localOverride',
  'sourceBadge',
  'keyMaskedCredential',
  'caseKind',
  'precondition',
  'expected',
  'steps',
  'scriptPath',
  'scriptName',
  'scriptNodeId',
  'bindingState',
  'domainTag',
  'reviewStatus',
  'capability',
  // FR-UIE-08：内容来源三件套（接口加字段必须在这里登记，否则列/详情会悄悄脱节）
  'inheritedFrom',
  'inheritedFromCode',
  'sourceHasContent',
] as const

type DetailField = (typeof DETAIL_FIELDS)[number]

/**
 * 一行列 → 详情字段的映射声明。
 *
 * - `dtoField`：同名字段。同名映射也要逐条写出来，不靠「自动同名」通配兜底。
 * - `computedFrom`：计算列，必须声明它读的是哪几个详情字段（PRD 降级预案）。
 * - `alsoReads`：列渲染时额外读的字段（如编号列回退到外部编号），不改变主映射。
 * - `reason`：确实没有数据来源的列（目前只有操作列），必须写明为什么。
 */
interface ColumnSource {
  key: string
  dtoField: DetailField | null
  computedFrom?: DetailField[]
  alsoReads?: DetailField[]
  reason?: string
}

/** 全局「公共用例」列表列（`GlobalCasesView.vue` 的 `columns`）。 */
const GLOBAL_CASE_COLUMNS: ColumnSource[] = [
  { key: 'caseCode', dtoField: 'caseCode', alsoReads: ['externalId'] },
  { key: 'title', dtoField: 'title' },
  {
    // 脚本列：文件名与状态都由后端算好（三处同源），前端不切片、不猜路径
    key: 'script',
    dtoField: null,
    computedFrom: ['scriptPath', 'scriptName', 'scriptNodeId', 'bindingState'],
  },
  { key: 'categoryL1', dtoField: 'categoryL1', alsoReads: ['categoryL2'] },
  { key: 'priority', dtoField: 'priority' },
  { key: 'lifecycleStatus', dtoField: 'lifecycleStatus' },
  { key: 'versionNo', dtoField: 'versionNo' },
  { key: 'actions', dtoField: null, reason: '操作列：只放「停用 / 恢复」按钮，不承载数据' },
]

/** 项目「用例」列表列（`CasesView.vue` 的 `CASE_COLUMN_KEYS`）。 */
const PROJECT_CASE_COLUMNS: ColumnSource[] = [
  { key: 'caseCode', dtoField: 'caseCode' },
  { key: 'title', dtoField: 'title' },
  {
    key: 'script',
    dtoField: null,
    computedFrom: ['scriptPath', 'scriptName', 'scriptNodeId', 'bindingState'],
  },
  { key: 'priority', dtoField: 'priority' },
  { key: 'safetyLevel', dtoField: 'safetyLevel' },
  { key: 'reviewStatus', dtoField: 'reviewStatus' },
  { key: 'domainTag', dtoField: 'domainTag' },
  { key: 'capability', dtoField: 'capability' },
]

function interfaceFields(src: string, name: string): string[] {
  const match = new RegExp(`export interface ${name} \\{([\\s\\S]*?)\\n\\}`).exec(src)
  if (!match) throw new Error(`找不到接口 ${name}（契约文件结构变了，测试要同步）`)
  return match[1]
    .split('\n')
    .map((line) => /^ {2}([A-Za-z][A-Za-z0-9]*)\??:/.exec(line)?.[1])
    .filter((field): field is string => Boolean(field))
}

/** 取源码区间：找不到就抛错——视图改了结构而测试没跟上，必须红。 */
function slice(src: string, start: string, end: string): string {
  const from = src.indexOf(start)
  const to = src.indexOf(end, from + start.length)
  if (from < 0 || to < 0) {
    throw new Error(`列定义区间没找到：${start} … ${end}（视图结构变了，测试要同步）`)
  }
  return src.slice(from, to)
}

/** 对象形态的列定义（`{ title, key: 'xxx', ... }`，单行与多行写法都有）。 */
function columnKeys(src: string, start: string, end: string): string[] {
  const found = [...slice(src, start, end).matchAll(/\bkey: '([A-Za-z][A-Za-z0-9]*)'/g)].map(
    (match) => match[1],
  )
  return [...new Set(found)]
}

/** 字符串数组形态的列定义（`const CASE_COLUMN_KEYS = ['a', 'b'] as const`）。 */
function arrayKeys(src: string, start: string, end: string): string[] {
  return [...slice(src, start, end).matchAll(/^\s*'([A-Za-z][A-Za-z0-9]*)',$/gm)].map(
    (match) => match[1],
  )
}

/** 锁 2：视图真实列 key 必须与映射表逐个一致（多一个、少一个都红）。 */
function assertSameKeys(label: string, viewKeys: string[], columns: ColumnSource[]): void {
  const actual = [...viewKeys].sort()
  const declared = columns.map((column) => column.key).sort()
  expect(actual, `${label} 视图列与映射表不一致`).toEqual(declared)
}

/** 锁 3：每条映射都得说清数据从哪来。 */
function validateColumns(label: string, columns: ColumnSource[]): void {
  const fields = new Set<string>(DETAIL_FIELDS)
  const seen = new Set<string>()
  for (const column of columns) {
    if (seen.has(column.key)) throw new Error(`${label}：列 ${column.key} 重复声明`)
    seen.add(column.key)
    for (const extra of column.alsoReads ?? []) {
      if (!fields.has(extra)) {
        throw new Error(`${label}：列 ${column.key} 额外读取的 ${extra} 不在详情 DTO 里`)
      }
    }
    if (column.dtoField !== null) {
      if (!fields.has(column.dtoField)) {
        throw new Error(`${label}：列 ${column.key} 映射到详情 DTO 里不存在的 ${column.dtoField}`)
      }
      continue
    }
    if (column.computedFrom) {
      if (column.computedFrom.length === 0) {
        throw new Error(`${label}：计算列 ${column.key} 没有声明来源字段`)
      }
      for (const source of column.computedFrom) {
        if (!fields.has(source)) {
          throw new Error(`${label}：计算列 ${column.key} 的来源 ${source} 不在详情 DTO 里`)
        }
      }
      continue
    }
    if (!column.reason) {
      throw new Error(`${label}：列 ${column.key} 既没有 DTO 字段，也没有来源声明或理由`)
    }
  }
}

const GLOBAL_KEYS = columnKeys(
  GLOBAL_VIEW_SRC,
  'const columns = computed<DataTableColumns<GlobalCaseRow>>',
  'const batchColumns',
)
const PROJECT_KEYS = arrayKeys(CASES_VIEW_SRC, 'const CASE_COLUMN_KEYS = [', '] as const')

describe('信息幂等：表格列 ⊆ 详情字段（FR-v5.11-04）', () => {
  it('DETAIL_FIELDS 与 CaseDetailView 接口逐字段一致（接口改了这里必须跟）', () => {
    expect([...DETAIL_FIELDS].sort()).toEqual(
      interfaceFields(API_SRC, 'CaseDetailView').sort(),
    )
  })

  it('两页的列配置都能通过幂等校验', () => {
    expect(() => validateColumns('GlobalCasesView', GLOBAL_CASE_COLUMNS)).not.toThrow()
    expect(() => validateColumns('CasesView', PROJECT_CASE_COLUMNS)).not.toThrow()
  })

  it('映射表与视图真实列 key 逐个一致（视图加列忘了登记就会红）', () => {
    expect(GLOBAL_KEYS.length).toBeGreaterThan(0)
    expect(PROJECT_KEYS.length).toBeGreaterThan(0)
    assertSameKeys('GlobalCasesView', GLOBAL_KEYS, GLOBAL_CASE_COLUMNS)
    assertSameKeys('CasesView', PROJECT_KEYS, PROJECT_CASE_COLUMNS)
  })

  it('反证：注入一个假列名必须判红', () => {
    const injected = [...PROJECT_CASE_COLUMNS, { key: 'fakeColumn', dtoField: 'title' as const }]
    expect(() => assertSameKeys('CasesView', PROJECT_KEYS, injected)).toThrow(/不一致/)
  })

  it('反证：漏登记一个真实列同样判红（少也要报）', () => {
    const dropped = PROJECT_CASE_COLUMNS.filter((column) => column.key !== 'domainTag')
    expect(() => assertSameKeys('CasesView', PROJECT_KEYS, dropped)).toThrow(/不一致/)
  })

  it('反证：映射到不存在的详情字段必须判红', () => {
    expect(() =>
      validateColumns('假 DTO 字段', [{ key: 'title', dtoField: 'noSuchField' as never }]),
    ).toThrow(/noSuchField/)
  })

  it('反证：计算列不声明来源必须判红（禁止「算出来的」当万能免责理由）', () => {
    expect(() => validateColumns('无来源计算列', [{ key: 'script', dtoField: null }])).toThrow(
      /来源声明|来源字段/,
    )
  })

  it('反证：操作列不写理由必须判红', () => {
    expect(() => validateColumns('无理由操作列', [{ key: 'actions', dtoField: null }])).toThrow(
      /来源声明或理由/,
    )
  })

  it('反证：重复声明同一列必须判红', () => {
    expect(() =>
      validateColumns('重复列', [
        { key: 'title', dtoField: 'title' },
        { key: 'title', dtoField: 'title' },
      ]),
    ).toThrow(/重复声明/)
  })
})
