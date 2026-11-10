/**
 * 项目 / 模板内部编号生成（v4.4 需求 1+4）。
 *
 * 格式：`前缀-引擎-年月-三位序号`
 *   PRJ-RF-202611-001
 *   API-RF-202611-001
 *   PY-PYT-202611-001
 *
 * 设计取舍：
 * - **前缀**来自模板 `codePrefix`（PRJ/API/UI/PY），保留业务分类语义；
 * - **引擎**用缩写（Robot Framework→`RF`，Pytest→`PYT`），编码自解释，不用记映射表；
 * - **年月**取本机时钟的 `YYYYMM`，序号按月分组重置：一个月内的项目连续、跨月不背历史包袱；
 * - 序号只做**建议**，用户可改；唯一性最终由后端唯一键 + 409 兜底（并发与私有项目不可见）。
 */

export type EngineCode = 'RF' | 'PYT'

/** 模板/项目内核 → 编码里的引擎缩写。 */
export function engineCode(kernel: string | null | undefined): EngineCode {
  return (kernel ?? '').trim().toLowerCase() === 'pytest' ? 'PYT' : 'RF'
}

/** 本机时钟的 `YYYYMM`（编码用，不做时区换算：按使用者所在时区的自然月）。 */
export function yearMonth(now: Date = new Date()): string {
  return `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 允许作为前缀的字符；非法字符剔除后仍为空则退回 `PRJ`。 */
function normalizePrefix(prefix: string): string {
  const cleaned = (prefix || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
  return cleaned || 'PRJ'
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 生成建议编号：同「前缀+引擎+年月」下的最大序号 + 1。
 *
 * @param prefix   模板前缀，如 `API`
 * @param kernel   测试内核，`robot` | `pytest`
 * @param existing 已存在的编号集合（项目或模板）
 * @param now      生成时刻，默认当前时间；单测可注入固定日期
 */
export function suggestProjectCode(
  prefix: string,
  kernel: string | null | undefined,
  existing: readonly string[],
  now: Date = new Date(),
): string {
  const head = normalizePrefix(prefix)
  const engine = engineCode(kernel)
  const period = yearMonth(now)
  const pattern = new RegExp(`^${escapeRegExp(head)}-${engine}-${period}-(\\d{3,})$`, 'i')
  let max = 0
  for (const raw of existing) {
    const matched = pattern.exec((raw || '').trim())
    if (matched) max = Math.max(max, Number(matched[1]))
  }
  return `${head}-${engine}-${period}-${String(max + 1).padStart(3, '0')}`
}

/**
 * 用户点「重新生成」时的序号起点：把当前值也算作已用，
 * 否则连点两次会得到同一个建议值，看起来像按钮没反应。
 */
export function nextProjectCode(
  prefix: string,
  kernel: string | null | undefined,
  existing: readonly string[],
  current: string,
  now: Date = new Date(),
): string {
  const trimmed = (current || '').trim()
  return suggestProjectCode(prefix, kernel, trimmed ? [...existing, trimmed] : existing, now)
}
