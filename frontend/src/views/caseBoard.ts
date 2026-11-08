/**
 * MENU-03：测试总表看板的纯计数逻辑（不依赖组件挂载，便于单测）。
 * EVID-04：当前轮结论收口为四态枚举；新增原始证据流渲染 / JSON 导出纯函数。
 *
 * 历史（historical_status）与本轮当前（current_status）是两条互相独立的列：
 * 当前结论只从 current_status 取，绝不从历史列推导，也不得把 ED202 历史「通过 330」
 * 之类历史数值当成本平台当前通过率。本模块不含任何业务数据，只放纯函数。
 */

import zhCN from '@/locales/zh-CN'

/**
 * EVID-04 四态枚举：当前轮结论只允许这四个值，不再使用裸字符串。
 * missing_evidence 表示本轮无原始证据，对应 JSON 下载不可用。
 */
export const CURRENT_STATUSES = ['passed', 'failed', 'untested', 'missing_evidence'] as const
export type CurrentStatus = (typeof CURRENT_STATUSES)[number]

/** 看板当前轮结论类型：即四态枚举（EVID-04 起不再是裸 string）。 */
export type BoardStatus = CurrentStatus

/** 原始证据流五要素，与后端 EvidenceLogWriter.write 的输出字段一一对应。 */
export interface RawEvidence {
  hostname: string
  stepNo: number
  stepDescription: string
  command: string
  stdout: string
  stderr: string
  exitCode: number
}

export interface CaseBoardRow {
  caseKey: string
  /** 导入的历史结论（ED202 等），仅作对照；保留 string 以兼容未知导入值。 */
  historical_status: string | null
  /** 本平台当前结论；null 视为尚未测试。 */
  current_status: BoardStatus | null
  /** 原始证据流；缺省 / null 视为证据缺失，禁止查看与下载。 */
  evidence?: RawEvidence | null
}

export interface BoardSummary {
  total: number
  historicalPass: number
  currentPass: number
  currentUntested: number
}

/**
 * 结论归一化：英文枚举值与受控中文词表值都算「通过」/「未测试」。
 * 中文词表值取自语言包 boardStatus 段，这里不写中文字面量，仅为兼容导入数据。
 */
const PASS_VALUES: readonly string[] = ['passed', zhCN.boardStatus.pass]
const UNTESTED_VALUES: readonly string[] = ['untested', zhCN.boardStatus.untested]

const isPass = (value: string | null): boolean => value != null && PASS_VALUES.includes(value)
const isUntested = (value: string | null): boolean => value != null && UNTESTED_VALUES.includes(value)

/** 双计数：历史通过只数历史列，当前通过只数当前列，二者互不串用。 */
export function summarizeBoard(rows: CaseBoardRow[]): BoardSummary {
  return {
    total: rows.length,
    historicalPass: rows.filter((row) => isPass(row.historical_status)).length,
    currentPass: rows.filter((row) => isPass(row.current_status)).length,
    currentUntested: rows.filter(
      (row) => row.current_status == null || isUntested(row.current_status),
    ).length,
  }
}

/** 该行是否有可展示 / 可下载的原始证据。唯一真相来自 evidence 字段，不看状态字面量。 */
export function hasEvidence(row: CaseBoardRow): boolean {
  return row.evidence != null
}

/**
 * 渲染弹窗里的原始流文本，五要素口径与后端 EvidenceLogWriter.write 一致：
 * [hostname] / #步骤N说明 / 命令原文 / stdout / stderr / exit_code。
 * 含中文的步骤行由调用方经 t() 传入，本模块保持零中文字面量。
 */
export function renderEvidenceLog(evidence: RawEvidence, stepLine: string): string {
  return [
    `[${evidence.hostname}]`,
    stepLine,
    `$ ${evidence.command}`,
    '--- stdout ---',
    evidence.stdout,
    '--- stderr ---',
    evidence.stderr,
    `exit_code=${evidence.exitCode}`,
  ].join('\n')
}

/**
 * 生成可下载的 JSON 文本。无证据时返回 null —— 调用方据此禁用下载按钮，
 * 绝不用占位 / 空对象假装有数据。
 */
export function buildEvidenceJson(row: CaseBoardRow): string | null {
  if (!hasEvidence(row)) return null
  return JSON.stringify(
    {
      caseKey: row.caseKey,
      current_status: row.current_status,
      evidence: row.evidence,
    },
    null,
    2,
  )
}
