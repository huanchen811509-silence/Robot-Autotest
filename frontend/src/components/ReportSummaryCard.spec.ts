/**
 * 报告速览结论口径（FR-3.1-07）。
 *
 * 锁三件事：失败结论必须带失败数与通过率；有跳过时不能简化成「全部通过」；
 * 空数据要给出「暂无可统计结果」而不是 0% 通过。数字直接来自后端 summary，
 * 组件只负责文案，所以这里喂的是接口返回形状。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import ReportSummaryCard from '@/components/ReportSummaryCard.vue'
import type { ReportSummary } from '@/api/reports'

let app: App | null = null
let host: HTMLElement | null = null

function base(overrides: Partial<ReportSummary>): ReportSummary {
  return {
    conclusion: 'PASSED',
    total: 10,
    passed: 10,
    failed: 0,
    skipped: 0,
    passRate: 100,
    durationMs: 65000,
    topFailures: [],
    risks: [],
    generatedAt: '2026-11-08T10:00:00+08:00',
    flaky: 0,
    ...overrides,
  }
}

async function mount(summary: ReportSummary): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({
    render: () => h(ReportSummaryCard, { summary }),
  })
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return host
}

beforeEach(() => {
  i18n.global.locale.value = 'zh-CN'
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('报告速览结论', () => {
  it('失败时写明失败数与通过率，并只展示前 3 条 Top 失败', async () => {
    const root = await mount(
      base({
        conclusion: 'FAILED',
        passed: 7,
        failed: 3,
        skipped: 0,
        passRate: 70,
        topFailures: [
          { name: 'A', display: 'A', clusterKey: 'k-a', count: 2 },
          { name: 'B', display: 'B', clusterKey: 'k-b', count: 1 },
          { name: 'C', display: 'C', clusterKey: 'k-c', count: 1 },
          { name: 'D', display: 'D', clusterKey: 'k-d', count: 1 },
        ],
        risks: ['建议先回归 A'],
      }),
    )

    const conclusion = root.querySelector('[data-test="report-summary-conclusion"]')
    expect(conclusion?.textContent).toContain('本轮 3 条失败')
    expect(conclusion?.textContent).toContain('70.0%')
    expect(root.querySelectorAll('.report-summary__failure')).toHaveLength(3)
    expect(root.textContent).not.toContain('D')
    expect(root.querySelector('.report-summary__risks')?.textContent).toContain('建议先回归 A')
    expect(root.querySelector('.report-summary__jump')).not.toBeNull()
  })

  it('无失败但有跳过时，结论显式写明跳过数，不写成「全部通过」', async () => {
    const root = await mount(
      base({ failed: 0, passed: 8, skipped: 2, passRate: 80 }),
    )

    const conclusion = root.querySelector('[data-test="report-summary-conclusion"]')
    expect(conclusion?.textContent?.trim()).toBe('全部通过（其中 2 条跳过）')
    expect(root.querySelector('.report-summary__jump')).toBeNull()
    expect(root.querySelectorAll('.report-summary__failure')).toHaveLength(0)
  })

  it('抖动条数单列，0 条不显示（FR-v5.6-24）', async () => {
    const root = await mount(
      base({ conclusion: 'PASSED', total: 12, passed: 12, skipped: 0, flaky: 2 }),
    )
    const line = root.querySelector('[data-test="summary-flaky"]')
    expect(line).not.toBeNull()
    expect(line?.textContent).toContain('2')
    // 抖动不计入失败：结论仍是全部通过
    expect(root.querySelector('[data-test="report-summary-conclusion"]')?.textContent?.trim())
      .toBe('全部通过')
  })

  it('没有抖动时不占位显示「抖动 0 条」', async () => {
    const root = await mount(base({ conclusion: 'PASSED', total: 5, passed: 5 }))
    expect(root.querySelector('[data-test="summary-flaky"]')).toBeNull()
  })

  it('空数据给出「暂无可统计结果」，不伪装成 0% 通过', async () => {
    const root = await mount(
      base({ conclusion: 'NO_DATA', total: 0, passed: 0, failed: 0, skipped: 0, passRate: 0 }),
    )

    const conclusion = root.querySelector('[data-test="report-summary-conclusion"]')
    expect(conclusion?.textContent?.trim()).toBe('本轮暂无可统计的用例结果')
    expect(root.textContent).toContain('无数据')
    expect(root.textContent).not.toContain('全部通过')
  })
})
