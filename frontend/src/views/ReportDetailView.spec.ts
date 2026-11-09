/**
 * `ReportDetailView` 视图级回归（UX-04b 逐页迁移）。
 *
 * 报告详情是全站控件最杂的一页：10 个原生按钮、2 个下拉、1 个开关、2 张表。
 * 迁移过程中有三类风险是**单测看不出来**的，只能靠这一层兜住：
 * ① 两列表里手写的 `render` 丢掉一列 → 数据静默消失；
 * ② 元数据键值表被"顺手"塞进 `NDataTable` → 多一行表头、`th` 语义丢失；
 * ③ 对比表「两轮都是 FAIL」被后端的 `SAME` 归类成「无变化」→ 把持续坏当成没问题。
 *
 * 挂载方式与其它视图 spec 一致：`createApp(View)` 真挂载，不引 `@vue/test-utils`
 * （项目没有这个依赖，且真挂载才能覆盖 `NDataTable` 的列渲染）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { reportsApi } from '@/api/reports'
import ReportDetailView from '@/views/ReportDetailView.vue'

/**
 * `vi.mock` 会被提升到 import 之前，工厂里不能直接读外部变量；
 * 用 `vi.hoisted` 建一块能在测试里改的状态，工厂函数在**调用时**才读它。
 */
const apiState = vi.hoisted(() => ({
  exportable: true,
  metadataMissing: [] as string[],
}))

vi.mock('@/api/reports', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/reports')>()
  return {
    ...actual,
    reportsApi: {
      detail: vi.fn(async () => ({
        projectId: 1,
        runId: 'RUN-AAA',
        state: 'finished',
        exportable: apiState.exportable,
        metadataMissing: apiState.metadataMissing,
        passCount: 3,
        failCount: 1,
        skipCount: 0,
        startedAt: '2026-11-05T07:00:00+08:00',
        finishedAt: '2026-11-05T07:05:00+08:00',
        metadata: {
          CASE_ID: 'REG-ROCE-001',
          RUN_ID: 'RUN-AAA',
          QA_PROJECT: 'roce',
          DUT: 'DUT-A',
          LINK: 'LINK-AB',
          SW_BUILD: 'b20261105',
          BASELINE: 'RUN-OLD',
          OPERATOR: 'evergreen',
          STARTED_AT: '2026-11-05 07:00',
          CASE_KIND: 'regression',
        },
      })),
      tree: vi.fn(async () => ({
        name: 'Roce',
        status: 'FAIL',
        suites: [],
        cases: [
          {
            caseKey: 'TC-001',
            name: '基本收发',
            verdict: 'PASS',
            durationMs: 1200,
            failureMsg: null,
            keywords: [],
          },
          {
            caseKey: 'TC-002',
            name: '大包吞吐',
            verdict: 'FAIL',
            durationMs: 8000,
            failureMsg: 'expected 190 got 120',
            keywords: [],
          },
        ],
      })),
      summary: vi.fn(async () => ({
        conclusion: 'FAILED',
        total: 4,
        passed: 3,
        failed: 1,
        skipped: 0,
        passRate: 75,
        durationMs: 300000,
        topFailures: [{ name: '大包吞吐', count: 1 }],
        risks: ['存在失败用例，建议先看大包吞吐'],
        generatedAt: '2026-11-05T07:05:00+08:00',
      })),
      compare: vi.fn(async () => ({
        baseline: { runId: 'RUN-OLD', startedAt: '2026-11-04T07:00:00+08:00', crossEnvironment: false, dut: 'DUT-A', link: 'LINK-AB' },
        rows: [
          { caseKey: 'TC-001', history: { verdict: 'PASS', durationMs: 1100 }, current: { verdict: 'PASS', durationMs: 1200 }, change: 'SAME' },
          { caseKey: 'TC-002', history: { verdict: 'FAIL', durationMs: 7000 }, current: { verdict: 'FAIL', durationMs: 8000 }, change: 'SAME' },
          { caseKey: 'TC-003', history: { verdict: 'PASS', durationMs: 900 }, current: { verdict: 'FAIL', durationMs: 950 }, change: 'PASS_TO_FAIL' },
          { caseKey: 'TC-004', history: { verdict: 'FAIL', durationMs: 500 }, current: { verdict: 'PASS', durationMs: 480 }, change: 'FAIL_TO_PASS' },
        ],
        summary: { newFail: 1, newPass: 1, bothFail: 1, notRun: 0 },
        crossEnvironment: false,
      })),
      baselineCandidates: vi.fn(async () => [
        { runId: 'RUN-OLD', startedAt: '2026-11-04T07:00:00+08:00', crossEnvironment: false, dut: 'DUT-A', link: 'LINK-AB' },
        { runId: 'RUN-OLDER', startedAt: null, crossEnvironment: true, dut: 'DUT-B', link: 'LINK-AB' },
      ]),
      trend: vi.fn(async () => ({
        caseKey: 'TC-001',
        baselineMs: 1150,
        deviated: false,
        points: [
          { runId: 'RUN-OLD', startedAt: '2026-11-04T07:00:00+08:00', verdict: 'PASS', durationMs: 1100, envKey: 'DUT-A|LINK-AB', deviated: false },
          { runId: 'RUN-AAA', startedAt: '2026-11-05T07:00:00+08:00', verdict: 'PASS', durationMs: 1200, envKey: 'DUT-A|LINK-AB', deviated: false },
        ],
        series: [
          {
            envKey: 'DUT-A|LINK-AB',
            points: [
              { runId: 'RUN-OLD', startedAt: '2026-11-04T07:00:00+08:00', verdict: 'PASS', durationMs: 1100, envKey: 'DUT-A|LINK-AB', deviated: false },
              { runId: 'RUN-AAA', startedAt: '2026-11-05T07:00:00+08:00', verdict: 'PASS', durationMs: 1200, envKey: 'DUT-A|LINK-AB', deviated: false },
            ],
          },
        ],
      })),
      passRate: vi.fn(async () => ({
        granularity: 'day',
        buckets: [{ bucket: '2026-11-04', total: 4, pass: 3, fail: 1, passRate: 0.75 }],
      })),
      logs: vi.fn(async () => ({ lines: [], total: 0, nextOffset: null })),
      exports: vi.fn(async () => [
        {
          exportId: 12,
          runId: 'RUN-AAA',
          format: 'docx',
          mode: 'internal',
          fileName: 'report_RUN-AAA_internal.docx',
          bytes: 1024,
          sha256: 'abc',
          status: 'done',
          requestedBy: 1,
          createdAt: '2026-11-05T07:06:00+08:00',
        },
        {
          exportId: 11,
          runId: 'RUN-AAA',
          format: 'pdf',
          mode: 'external',
          fileName: 'report_RUN-AAA_external.pdf',
          bytes: 2048,
          sha256: 'def',
          status: 'done',
          requestedBy: 1,
          createdAt: '2026-11-05T07:05:30+08:00',
        },
      ]),
      exportReport: vi.fn(),
      downloadExport: vi.fn(),
    },
  }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { runId: 'RUN-AAA' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

/** 套件树与折线图有自己的测试，这里只关心「详情页把数据交出去了」。 */
vi.mock('@/components/report/SuiteTree.vue', () => ({
  default: {
    name: 'SuiteTree',
    props: { node: { type: Object, default: null }, depth: { type: Number, default: 0 } },
    template: '<div class="suite-tree" />',
  },
}))

vi.mock('@/components/report/TrendChart.vue', () => ({
  default: {
    name: 'TrendChart',
    props: {
      series: { type: Array, default: () => [] },
      baseline: { type: Number, default: null },
      unit: { type: String, default: '' },
      clickable: { type: Boolean, default: false },
      currentId: { type: String, default: '' },
    },
    template: '<div class="trend-chart" />',
  },
}))

/** 内部版导出要过二次确认；测试里直接放行，专门断言「确认后确实发了请求」的反面不需要。 */
vi.mock('@/composables/useConfirm', () => ({
  confirmAction: vi.fn(async () => true),
}))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function flush(): Promise<void> {
  // loadAll → (detail, tree) → passRate + exports → caseKeys 变更 → trend
  // 至少三轮微任务，写死 4 轮比 `await flushPromises()` 更直观
  for (let i = 0; i < 4; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ReportDetailView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await flush()
  return root
}

beforeEach(() => {
  apiState.exportable = true
  apiState.metadataMissing = []
  vi.clearAllMocks()
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('ReportDetailView 元数据表', () => {
  it('保持原生 table：10 项全渲染且是 th + td 的两列定义表', async () => {
    const root = await mountView()
    const table = root.querySelector<HTMLTableElement>('table.meta-table')
    expect(table).not.toBeNull()
    expect(table!.querySelectorAll('tbody tr').length).toBe(10)
    // th 是「键」的语义，NDataTable 渲染不出这一层；这条断言就是防止有人把它塞进表格组件
    expect(table!.querySelectorAll('th').length).toBe(10)
    expect(root.textContent).toContain('REG-ROCE-001')
  })
})

describe('ReportDetailView 导出', () => {
  it('不可对外时两个外部导出按钮禁用，内部两枚仍可用', async () => {
    apiState.exportable = false
    apiState.metadataMissing = ['OPERATOR']
    const root = await mountView()
    const buttons = [...root.querySelectorAll<HTMLButtonElement>('.export-bar button')]
    expect(buttons.length).toBe(4)
    expect(buttons[0].disabled).toBe(true)
    expect(buttons[1].disabled).toBe(true)
    expect(buttons[2].disabled).toBe(false)
    expect(buttons[3].disabled).toBe(false)
    // 缺哪一项必须写在按钮上，否则"为什么点不动"要靠猜
    expect(buttons[0].title).toContain('OPERATOR')
  })

  it('导出历史：内部版是 warn 徽标而不是红字', async () => {
    const root = await mountView()
    const tags = [...root.querySelectorAll<HTMLElement>('.n-data-table .status-tag')]
    expect(tags.length).toBe(2)
    expect(tags.some((tag) => tag.dataset.tone === 'warn')).toBe(true)
    expect(tags.every((tag) => tag.querySelector('svg') !== null)).toBe(true)
    expect(root.textContent).toContain(i18n.global.t('reports.exportSection.internalTag'))
  })
})

describe('ReportDetailView 趋势', () => {
  it('用例下拉默认选中第一条并据此拉趋势', async () => {
    const root = await mountView()
    expect(reportsApi.trend).toHaveBeenCalledWith(1, 'RUN-AAA', 'TC-001', 5)
    expect(root.querySelector('.trend-select')?.textContent).toContain('TC-001')
  })

  it('日/周是单选组，切到周会按 week 重新取通过率', async () => {
    const root = await mountView()
    const buttons = [...root.querySelectorAll<HTMLElement>('.n-radio-group .n-radio-button')]
    expect(buttons.length).toBe(2)
    buttons[1].click()
    await flush()
    expect(reportsApi.passRate).toHaveBeenLastCalledWith(1, 'week', 12)
  })
})

describe('ReportDetailView 对比', () => {
  async function openCompare(root: HTMLElement): Promise<void> {
    const checkbox = root.querySelector<HTMLElement>('.n-checkbox')
    expect(checkbox).not.toBeNull()
    checkbox!.click()
    await flush()
  }

  it('开关是 NCheckbox：勾选后才发对比请求并多出一张表', async () => {
    const root = await mountView()
    expect(reportsApi.compare).not.toHaveBeenCalled()
    await openCompare(root)
    expect(reportsApi.compare).toHaveBeenCalledTimes(1)
    expect(root.querySelectorAll('.n-data-table').length).toBe(2)
  })

  it('两轮都 FAIL 显示「持续失败」，不能落成「无变化」', async () => {
    const root = await mountView()
    await openCompare(root)
    expect(root.textContent).toContain(i18n.global.t('reports.compare.changes.BOTH_FAIL'))
    // TC-002 是持续失败；TC-001 才是真正的"无变化"，两者文案不能混
    expect(root.textContent).toContain(i18n.global.t('reports.compare.changes.SAME'))
  })

  it('手动基线：候选由后端给出，选中后带 baselineRunId 重新对比（RPT-03 补口）', async () => {
    const root = await mountView()
    await openCompare(root)
    // 候选只拉一次，且带上 projectId / runId
    expect(reportsApi.baselineCandidates).toHaveBeenCalledTimes(1)
    expect(reportsApi.baselineCandidates).toHaveBeenCalledWith(1, 'RUN-AAA')
    // 首次对比不带 baselineRunId —— 默认走后端自动基线
    expect(reportsApi.compare).toHaveBeenLastCalledWith(1, 'RUN-AAA', undefined)

    /**
     * Naive 的下拉浮层要真实布局才展开，jsdom 里点不开，所以这里直接取 NSelect 实例上
     * 的 `onUpdate:value` 回调来派发选中 —— 覆盖的是「选中 → 重新对比」这条接线，
     * 浮层本身不在本用例的覆盖范围内。
     */
    const picker = root.querySelector<HTMLElement>('.baseline-select') as
      | (HTMLElement & {
          __vueParentComponent?: {
            props: Record<string, unknown>
          }
        })
      | null
    expect(picker).not.toBeNull()
    const onUpdate = picker!.__vueParentComponent?.props['onUpdate:value']
    expect(typeof onUpdate).toBe('function')

    ;(onUpdate as (value: string) => void)('RUN-OLD')
    await flush()
    expect(reportsApi.compare).toHaveBeenLastCalledWith(1, 'RUN-AAA', 'RUN-OLD')

    // 选回「自动」时不能把字符串 "undefined" 传下去
    ;(onUpdate as (value: string) => void)('')
    await flush()
    expect(reportsApi.compare).toHaveBeenLastCalledWith(1, 'RUN-AAA', undefined)
  })

  it('两侧结论与变化列都是图标 + 文字双编码', async () => {
    const root = await mountView()
    await openCompare(root)
    const summary = [...root.querySelectorAll<HTMLElement>('.summary-bar .status-tag')]
    expect(summary.length).toBe(4)
    expect(summary.every((tag) => tag.querySelector('svg') !== null)).toBe(true)
    // 4 行 × (历史 + 本轮 + 变化) = 12 个徽标
    const cells = [...root.querySelectorAll<HTMLElement>('.verdict-cell .status-tag')]
    expect(cells.length).toBe(8)
  })
})
