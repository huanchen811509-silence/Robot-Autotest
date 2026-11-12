/*
 * v5.0 W50-06 · 结果六态单一真源。
 * 采集 / 报告 / 台账三处只准使用本文件的常量，不准各自写字符串字面量。
 * 标签走 i18n 键，不在此处写中文；状态集合固定为六项，不扩项。
 * 真实浏览器端到端读数依赖 CDP :9222（D1 裁定保持取消），引用迁移列入后续版本。
 */
export const RESULT_STATES = {
  PASS: 'PASS',
  PARTIAL: 'PARTIAL',
  NOT_RUN: 'NOT_RUN',
  FAIL: 'FAIL',
  BLOCKED: 'BLOCKED',
  DEGRADED: 'DEGRADED',
} as const

export type ResultState = keyof typeof RESULT_STATES
export const RESULT_STATE_VALUES: readonly string[] = Object.values(RESULT_STATES)
