/** 执行前预检的纯逻辑：分组、阻断判定、是否需要弹窗。 */
export type PrecheckLevel = 'BLOCK' | 'WARN' | 'OK'

export const PRECHECK_LEVELS: PrecheckLevel[] = ['BLOCK', 'WARN', 'OK']

export interface PrecheckCheckLike {
  key: string
  level: PrecheckLevel
  title: string
  detail: string
  hint: string | null
}

export interface PrecheckLike {
  verdict: PrecheckLevel
  checks: PrecheckCheckLike[]
  estimatedTotal: number
  estimatedSeconds: number
  sampleSize: number
}

export function groupPrechecks<T extends PrecheckCheckLike>(
  checks: T[],
): Array<{ level: PrecheckLevel; items: T[] }> {
  return PRECHECK_LEVELS.map((level) => ({
    level,
    items: checks.filter((check) => check.level === level),
  })).filter((group) => group.items.length > 0)
}

export function isPrecheckBlocked(result: PrecheckLike | null | undefined): boolean {
  return result?.verdict === 'BLOCK'
}

/** 全通过且没有任何检查项/估算时直接执行，避免为了一个空弹窗多点一次。 */
export function needsPrecheck(result: PrecheckLike | null | undefined): boolean {
  if (!result) return false
  return result.verdict !== 'OK' || result.checks.length > 0 || result.estimatedTotal > 0
}
