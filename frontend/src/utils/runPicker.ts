/**
 * 执行记录选择器的纯逻辑（FR-v3.10-02）。
 *
 * 从 `RunPicker.vue` 里抽出来的原因：过滤与文案格式化是「选错就是建错报告」的部分，
 * 但它埋在组件内部、只能靠打开 naive-ui 下拉才能断言——而 naive-ui 的虚拟列表在
 * happy-dom 下量不出宽度，下拉根本不渲染选项。抽成纯函数后可以直接测。
 *
 * 三条口径写死在这里，组件不再各写一份：
 * ① 时间基准取「结束时间 → 开始时间 → 入队时间」，运行中的 run 也有时间可排；
 * ② 关键词只匹配 runId（用户手里就这一个可对照的标识，匹配标题反而误命中）；
 * ③ 结果按时间倒序，最近一次回归排第一。
 */
import type { ExecutionRun } from '@/api/execution'
import { i18n } from '@/locales'
import { formatDateTime } from './format'
import { runStatusLabel } from './statusLabels'

/** 时间范围档位：全部 / 近 7 天 / 近 30 天。 */
export type RunRange = 'all' | '7d' | '30d'

export const RUN_RANGES: RunRange[] = ['all', '7d', '30d']

const DAY_MS = 86_400_000

/**
 * 取一条 run 的时间基准（毫秒）。
 *
 * 返回 0 而不是 NaN：时间字段全空时排序会把这条沉到最后，而不是让整个比较失效
 * （`NaN` 参与比较恒为 false，`sort` 会保留原序，看起来「没排序」）。
 */
export function runTimestamp(run: ExecutionRun): number {
  const raw = run.finishedAt ?? run.startedAt ?? run.enqueuedAt
  const value = raw ? Date.parse(raw) : NaN
  return Number.isNaN(value) ? 0 : value
}

/**
 * 按关键词与时间范围过滤，并按时间倒序返回新数组。
 *
 * `now` 可注入：单测要断言「近 7 天」就必须固定当前时刻，否则用例在跨天时自己变红。
 */
export function filterRuns(
  runs: readonly ExecutionRun[],
  keyword: string,
  range: RunRange,
  now: number = Date.now(),
): ExecutionRun[] {
  const spanDays = range === '7d' ? 7 : range === '30d' ? 30 : 0
  const needle = keyword.trim().toLowerCase()
  return runs
    .filter((run) => {
      if (spanDays > 0 && runTimestamp(run) < now - spanDays * DAY_MS) return false
      if (!needle) return true
      return run.runId.toLowerCase().includes(needle)
    })
    .sort((a, b) => runTimestamp(b) - runTimestamp(a))
}

/**
 * 下拉选项文案：`runId · 时间 · 结论 · 用例数`。
 *
 * 结论与用例数都要，是因为「3 条用例全过」和「120 条用例全过」不是一个分量，
 * 只给时间的话用户还得回运行列表二次确认。
 *
 * 直接读 `i18n.global` 而不是接收 `t`：与 `statusLabels` 一致，避免每个调用方
 * 都包一层转发函数（转发层写错键名时，界面上只会少一段文案，不会报错）。
 */
export function runOptionLabel(run: ExecutionRun): string {
  const stamp = formatDateTime(run.finishedAt ?? run.startedAt ?? run.enqueuedAt ?? null)
  const cases = i18n.global.t('testReports.caseCount', { n: run.totalTests })
  return `${run.runId} · ${stamp} · ${runStatusLabel(run.state)} · ${cases}`
}
