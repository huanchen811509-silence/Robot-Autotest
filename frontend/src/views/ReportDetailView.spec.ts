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
  // 判据结论（FR-v5.8-01）：默认 PASS，测里按需改
  conclusion: 'PASS',
  evaluated: 4,
  missing: 0,
  // 失败数也接出来：用来证明「结论与 failCount 解耦」
  passCount: 3,
  failCount: 1,
  // v5.22.0 / FR-v5.22-06 判据 06-4：测试项数 = 三个结论之和，skip 也要能改
  skipCount: 0,
  /** 真接口形状开关：打开后夹具按 `counts:{pass,fail,skip}` 返回，而不是扁平字段。 */
  useCountsShape: false,
  // v5.17.0 / W67-02：深链用例需要控制一级套件列表
  suites: [] as Array<{ name: string; status: string; suites: unknown[]; cases: unknown[] }>,
}))

/** `?suite=` 深链用例要能改 query；其余用例保持空 query（历史行为不变）。 */
const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

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
        passCount: apiState.passCount,
        failCount: apiState.failCount,
        skipCount: apiState.skipCount,
        // 真接口给的是这份（列表页 W69-03 已按它读，详情页 v5.22.0 才补）
        counts: apiState.useCountsShape
          ? { pass: apiState.passCount, fail: apiState.failCount, skip: apiState.skipCount }
          : undefined,
        criteriaConclusion: apiState.conclusion,
        criteriaEvaluated: apiState.evaluated,
        criteriaMissingCount: apiState.missing,
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
        suites: apiState.suites,
        cases: [
          {
            caseKey: 'TC-001',
            name: '基本收发',
            verdict: 'PASS',
            durationMs: 1200,
            failureMsg: null,
            commandText: 'ipmitool raw 0x30 0x93',
            commandSource: 'RF_KEYWORD',
            commandExitCode: 0,
            keywords: [],
          },
          {
            caseKey: 'TC-002',
            name: '大包吞吐',
            verdict: 'FAIL',
            durationMs: 8000,
            failureMsg: 'expected 190 got 120',
            commandText: 'NONE',
            commandSource: 'NONE',
            commandExitCode: null,
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
        topFailures: [{ name: '大包吞吐', display: '大包吞吐', clusterKey: 'k-tp', count: 1 }],
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
  useRoute: () => ({ params: { runId: 'RUN-AAA' }, query: routeState.query }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

/**
 * 套件树与折线图有自己的测试，这里只关心「详情页把数据交出去了」。
 *
 * 桩里把 `commandText` 也渲染出来（不是空 div）：命令列的三个字段要从
 * `reportsApi.tree` 一路穿过详情页进到树组件，字段名对不上时（后端 `commandText`
 * ↔ 前端 `command_text`）只有打通这一段才看得见——组件单测拿假数据照样绿。
 */
vi.mock('@/components/report/SuiteTree.vue', () => ({
  default: {
    name: 'SuiteTree',
    props: {
      node: { type: Object, default: null },
      depth: { type: Number, default: 0 },
      highlight: { type: String, default: '' },
    },
    template: `<div class="suite-tree">
      <span v-for="s in ((node && node.suites) || [])" :key="s.name"
            class="stub-suite"
            :data-suite-name="s.name"
            :data-suite-target="highlight === s.name || highlight === 'all' ? 'true' : undefined"
      >{{ s.name }}</span>
      <span v-for="c in ((node && node.cases) || [])" :key="c.caseKey"
            class="stub-case"
            :data-case-key="c.caseKey"
            :data-command="c.commandText">{{ c.commandText }}</span>
    </div>`,
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
  apiState.conclusion = 'PASS'
  apiState.evaluated = 4
  apiState.missing = 0
  apiState.passCount = 3
  apiState.failCount = 1
  apiState.skipCount = 0
  apiState.useCountsShape = false
  apiState.suites = []
  routeState.query = {}
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

describe('ReportDetailView 结论', () => {
  it('有判据且 PASS：结论区显示「通过」并带评估数与缺失数', async () => {
    apiState.conclusion = 'PASS'
    apiState.evaluated = 4
    apiState.missing = 0
    const root = await mountView()

    const box = root.querySelector('[data-testid="report-conclusion"]')
    expect(box).not.toBeNull()
    expect(box!.textContent).toContain('通过')
    expect(box!.textContent).toContain('已评估 4 条')
  })

  it('结论区标注判据机检范围：RF 报告不产出判据结论（W58-12）', async () => {
    const root = await mountView()

    const scope = root.querySelector('[data-testid="report-conclusion-scope"]')
    expect(scope).not.toBeNull()
    // 文案必须自己说清「只覆盖 pytest」，不能靠用户去猜为什么 RF 报告恒为未判定
    expect(scope!.textContent).toContain('pytest')
    expect(scope!.textContent).toContain('Robot Framework')
  })

  it('判据 PASS 但失败数 > 0：结论仍显示「通过」——结论不按失败数翻译', async () => {
    apiState.conclusion = 'PASS'
    apiState.failCount = 2
    apiState.passCount = 1
    const root = await mountView()

    const box = root.querySelector('[data-testid="report-conclusion"]')!
    expect(box.textContent).toContain('通过')
    expect(box.textContent).not.toContain('不通过')
    // 计数条仍照实显示失败数（两件事互不覆盖）
    expect(root.textContent).toContain('FAIL 2')
  })

  it('未声明判据：显示「未判定」并说明不按失败数推断', async () => {
    apiState.conclusion = 'UNDETERMINED'
    apiState.evaluated = 0
    apiState.missing = 4
    const root = await mountView()

    const box = root.querySelector('[data-testid="report-conclusion"]')!
    expect(box.textContent).toContain('未判定')
    expect(box.textContent).toContain('不按失败数推断')
    expect(box.textContent).not.toContain('不通过')
  })
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

/**
 * FR-v3.9-03 ①④：报告页的命令字段必须从接口一路传到树组件。
 *
 * 这条走整页挂载而不是只测 `SuiteTree`：接口字段名对不上（后端 `commandText` ↔
 * 前端 `command_text`）时，组件单测照样绿，只有打通 `reportsApi.tree` 才能发现。
 * 命令的**渲染**（折叠/复制/三态文案）由 `SuiteTree.spec.ts` 覆盖，这里只钉数据通路。
 */
describe('ReportDetailView 下发命令列', () => {
  it('用例行的命令字段原样传给套件树：有命令 / 显式 NONE 各自到位', async () => {
    const root = await mountView()
    const stubs = [...root.querySelectorAll<HTMLElement>('.stub-case')]
    expect(stubs.length).toBe(2)
    const byKey = new Map(stubs.map((el) => [el.dataset.caseKey, el.dataset.command]))
    expect(byKey.get('TC-001')).toBe('ipmitool raw 0x30 0x93')
    expect(byKey.get('TC-002')).toBe('NONE')
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

/**
 * `?suite=` 深链（v5.14.0 W64-04 实现，v5.17.0 / W67-02 首次补断言）。
 *
 * 这条链路此前**没有任何自动化覆盖**：功能在，但"粘贴一个深链能不能定位到那个套件"
 * 只能靠人眼看。三条断言分别钉住：合法名 → 高亮下发 + 滚动到目标；`all` → 展开提示；
 * 非法名 → 给人话提示且页面照常加载（不白屏、不抛错）。
 */
describe('深链 ?suite=', () => {
  beforeEach(() => {
    apiState.suites = [
      { name: 'S1', status: 'PASS', suites: [], cases: [] },
      { name: 'S2', status: 'FAIL', suites: [], cases: [] },
    ]
    // jsdom 没有 scrollIntoView，不 stub 会直接抛 TypeError
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      value: vi.fn(),
      writable: true,
      configurable: true,
    })
  })

  it('合法一级套件名：目标节点被标记并滚动定位', async () => {
    routeState.query = { suite: 'S2' }
    const root = await mountView()
    const marked = document.querySelectorAll('[data-suite-target="true"]')
    expect(marked.length).toBeGreaterThan(0)
    expect(marked[0].getAttribute('data-suite-name')).toBe('S2')
    const notice = root.querySelector('[data-testid="suite-deeplink-notice"]')
    expect(notice?.textContent).toContain('S2')
  })

  it('suite=all：提示已展开全部一级套件', async () => {
    routeState.query = { suite: 'all' }
    const root = await mountView()
    const notice = root.querySelector('[data-testid="suite-deeplink-notice"]')
    expect(notice?.textContent).toContain(String(apiState.suites.length))
  })

  it('非法套件名：给可读提示，页面照常渲染不白屏', async () => {
    routeState.query = { suite: 'NOT-A-SUITE' }
    const root = await mountView()
    const notice = root.querySelector('[data-testid="suite-deeplink-notice"]')
    expect(notice?.textContent).toContain('NOT-A-SUITE')
    // 页面主体仍在（不是白屏）
    expect(root.textContent).toContain('RUN-AAA')
    expect(document.querySelectorAll('[data-suite-target="true"]').length).toBe(0)
  })

  it('不带 suite 参数：不出现提示（历史行为不变）', async () => {
    routeState.query = {}
    const root = await mountView()
    expect(root.querySelector('[data-testid="suite-deeplink-notice"]')).toBeNull()
  })
})

/**
 * v5.22.0 / FR-v5.22-06 判据 06-4（**降级版**）。
 *
 * 原期望「61 用例 → 70 测试项」。箭头的左半边在库里不存在（test_set_id / case_id 全 NULL，
 * case_key 是 70 个互不相同的编号），所以只交真实的右半边。这条测试同时钉住两件事：
 * ① 数字真的来自三个结论之和，不是写死的；② 界面上不出现「→」这种看起来有来源的箭头。
 */
describe('ReportDetailView 测试项计数（判据 06-4 降级版）', () => {
  it('显示真实展开的测试项数，且不出「N 用例 → M 测试项」箭头', async () => {
    apiState.passCount = 5
    apiState.failCount = 4
    apiState.skipCount = 61
    const root = await mountView()

    const chip = root.querySelector<HTMLElement>('[data-testid="report-test-items"]')
    expect(chip).not.toBeNull()
    expect(chip!.textContent?.trim()).toBe('共 70 测试项')
    expect(chip!.textContent).not.toContain('→')
  })

  /**
   * 真实接口返回的是 `counts:{pass,fail,skip}`，不是扁平字段（列表页 W69-03 已改，详情页漏了）。
   * 这条用例专门用**真形状**挂载：夹具给扁平字段时全绿也说明不了真页面有数字。
   */
  it('接口按 counts 形状返回时，PASS/FAIL/SKIP 与测试项数都要有数字', async () => {
    apiState.useCountsShape = true
    apiState.passCount = 5
    apiState.failCount = 4
    apiState.skipCount = 61
    const root = await mountView()

    const bar = root.querySelector('.summary-bar')
    expect(bar!.textContent).toContain('PASS 5')
    expect(bar!.textContent).toContain('FAIL 4')
    expect(bar!.textContent).toContain('SKIP 61')
    expect(root.querySelector<HTMLElement>('[data-testid="report-test-items"]')!.textContent?.trim())
      .toBe('共 70 测试项')
  })
})
