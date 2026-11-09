/**
 * `utils/runPicker` 纯逻辑回归（FR-v3.10-02 判据①②）。
 *
 * 这段逻辑决定「下拉里能看到哪几条 run」，选错就建错报告；但它埋在组件里时
 * 只能靠打开 naive-ui 下拉才能断言，而虚拟列表在 happy-dom 下量不出宽度、选项
 * 根本不渲染。抽出来之后这些边界才测得到：
 * ① 时间基准三级回退（运行中的 run 也要能排序）；
 * ② 「近 7 天」的边界必须用注入的 now 固定住，否则用例跨天自己变红；
 * ③ 关键词只认 runId —— 匹配到别处等于把用户没选的那条推到眼前。
 */
import { describe, expect, it } from 'vitest'
import type { ExecutionRun } from '@/api/execution'
import { filterRuns, runOptionLabel, runTimestamp } from './runPicker'

/** 固定「现在」：2026-11-09T12:00:00+08:00，所有断言都相对它算。 */
const NOW = Date.parse('2026-11-09T12:00:00+08:00')

function run(partial: Partial<ExecutionRun> & { runId: string }): ExecutionRun {
  return {
    id: 1,
    projectId: 29,
    testSetId: null,
    state: 'FINISHED',
    priority: 2,
    concurrency: 1,
    deviceScope: null,
    attemptNo: 1,
    totalTests: 0,
    passedTests: 0,
    failedTests: 0,
    failureClass: null,
    failureReason: null,
    failureMessage: null,
    artifactsDir: null,
    enqueuedAt: null,
    startedAt: null,
    finishedAt: null,
    ...partial,
  }
}

describe('runTimestamp', () => {
  it('按 结束 → 开始 → 入队 三级回退', () => {
    expect(
      runTimestamp(
        run({
          runId: 'A',
          finishedAt: '2026-11-08T10:00:00+08:00',
          startedAt: '2026-11-08T09:00:00+08:00',
          enqueuedAt: '2026-11-08T08:00:00+08:00',
        }),
      ),
    ).toBe(Date.parse('2026-11-08T10:00:00+08:00'))

    // 运行中的 run 没有 finishedAt，用 startedAt
    expect(
      runTimestamp(
        run({ runId: 'B', startedAt: '2026-11-08T09:00:00+08:00', enqueuedAt: '2026-11-08T08:00:00+08:00' }),
      ),
    ).toBe(Date.parse('2026-11-08T09:00:00+08:00'))

    // 排队中的 run 只有 enqueuedAt
    expect(runTimestamp(run({ runId: 'C', enqueuedAt: '2026-11-08T08:00:00+08:00' }))).toBe(
      Date.parse('2026-11-08T08:00:00+08:00'),
    )
  })

  it('三个时间都空 / 无法解析时返回 0，不返回 NaN', () => {
    // NaN 参与比较恒 false，sort 会保留原序，看起来「排序没生效」却又抓不到错
    expect(runTimestamp(run({ runId: 'D' }))).toBe(0)
    expect(runTimestamp(run({ runId: 'E', finishedAt: '不是时间' }))).toBe(0)
  })
})

describe('filterRuns', () => {
  const old = run({ runId: 'RUN-OLD', finishedAt: '2026-10-01T10:00:00+08:00', totalTests: 3 })
  const mid = run({ runId: 'RUN-MID', finishedAt: '2026-10-25T10:00:00+08:00', totalTests: 8 })
  const recent = run({ runId: 'RUN-RECENT', finishedAt: '2026-11-07T10:00:00+08:00', totalTests: 12 })
  const all = [old, mid, recent]

  it('按时间倒序，最近一次排第一', () => {
    expect(filterRuns(all, '', 'all', NOW).map((item) => item.runId)).toEqual([
      'RUN-RECENT',
      'RUN-MID',
      'RUN-OLD',
    ])
  })

  it('时间档：近 7 天只留最新那条，近 30 天放进来 10-25 那条', () => {
    expect(filterRuns(all, '', '7d', NOW).map((item) => item.runId)).toEqual(['RUN-RECENT'])
    expect(filterRuns(all, '', '30d', NOW).map((item) => item.runId)).toEqual(['RUN-RECENT', 'RUN-MID'])
    expect(filterRuns(all, '', 'all', NOW)).toHaveLength(3)
  })

  it('边界：恰好 7 天前的 run 保留，早 1 毫秒的剔除', () => {
    const onEdge = run({ runId: 'EDGE', finishedAt: new Date(NOW - 7 * 86_400_000).toISOString() })
    const beforeEdge = run({ runId: 'BEFORE', finishedAt: new Date(NOW - 7 * 86_400_000 - 1).toISOString() })
    expect(filterRuns([onEdge, beforeEdge], '', '7d', NOW).map((item) => item.runId)).toEqual(['EDGE'])
  })

  it('关键词只匹配 runId，大小写不敏感，前后空格忽略', () => {
    expect(filterRuns(all, 'recent', 'all', NOW).map((item) => item.runId)).toEqual(['RUN-RECENT'])
    expect(filterRuns(all, '  MID  ', 'all', NOW).map((item) => item.runId)).toEqual(['RUN-MID'])
    // 「OCT」只出现在时间上、不出现在 runId 里，不该命中任何一条
    expect(filterRuns(all, 'OCT', 'all', NOW)).toEqual([])
  })

  it('关键词与时间档叠加生效', () => {
    expect(filterRuns(all, 'OLD', '7d', NOW)).toEqual([])
    expect(filterRuns(all, 'OLD', 'all', NOW).map((item) => item.runId)).toEqual(['RUN-OLD'])
  })

  it('不修改入参数组', () => {
    const input = [old, mid, recent]
    filterRuns(input, '', 'all', NOW)
    expect(input.map((item) => item.runId)).toEqual(['RUN-OLD', 'RUN-MID', 'RUN-RECENT'])
  })
})

describe('runOptionLabel', () => {
  it('四段信息齐全：runId · 时间 · 结论 · 用例数', () => {
    const label = runOptionLabel(
      run({ runId: 'RUN-20261109120000-abc', finishedAt: '2026-11-09T12:00:00+08:00', totalTests: 12 }),
    )
    expect(label).toContain('RUN-20261109120000-abc')
    expect(label).toContain('已完成')
    expect(label).toContain('12 条用例')
    expect(label.split(' · ')).toHaveLength(4)
  })

  it('没有结束时间时用入队时间，不显示破折号占位', () => {
    const label = runOptionLabel(
      run({ runId: 'RUN-Q', state: 'QUEUED', enqueuedAt: '2026-11-09T11:00:00+08:00', totalTests: 0 }),
    )
    expect(label).toContain('排队中')
    expect(label).toContain('0 条用例')
    expect(label).not.toContain('—')
  })
})
