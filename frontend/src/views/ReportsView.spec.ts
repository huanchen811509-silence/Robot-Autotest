/**
 * `ReportsView` 行内 CRUD 接线（W58-08）。
 *
 * 这一页此前只有「点进详情」和「清理产物」两条路：`listCrudApi.updateReport/deleteReport`
 * 早就封装好了，但页面上没有任何入口——等于后端能力空转。本组只断言**接线**：
 * 编辑框回填 → 提交打到 PUT；删除先确认、再打到 DELETE、回执带真实影响面。
 *
 * 不重复测 `listCrudApi` 的请求拼装（那是 api 层的契约，由 RunsView/TestReports 的用例覆盖）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App, type Slots } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { reportsApi } from '@/api/reports'
import { listCrudApi } from '@/api/listCrud'
import { confirmAction } from '@/composables/useConfirm'
import ReportsView from '@/views/ReportsView.vue'

const routerMock = { replace: vi.fn(), push: vi.fn() }

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, params: {} }),
  useRouter: () => routerMock,
}))

const rows = [
  {
    projectId: 1,
    runId: 'RUN-001',
    state: 'PASS',
    exportable: true,
    metadataMissing: [],
    passCount: 3,
    failCount: 0,
    skipCount: 0,
    startedAt: '2026-11-14 10:00:00',
    finishedAt: '2026-11-14 10:01:00',
    suiteCount: 3,
  },
  {
    projectId: 1,
    runId: 'RUN-002',
    state: 'FAIL',
    exportable: false,
    metadataMissing: ['DUT'],
    passCount: 1,
    failCount: 2,
    skipCount: 0,
    startedAt: '2026-11-14 11:00:00',
    finishedAt: null,
    suiteCount: null,
  },
]

vi.mock('@/api/reports', () => ({
  reportsApi: {
    page: vi.fn(async () => ({ items: rows, total: 2, page: 1, size: 20 })),
    retention: vi.fn(),
    detail: vi.fn(),
  },
}))

vi.mock('@/api/listCrud', () => ({
  listCrudApi: {
    getRun: vi.fn(async () => ({ runId: 'RUN-001', displayName: '夜间回归', remark: 'r', tags: 't' })),
    updateReport: vi.fn(async () => ({ runId: 'RUN-001', displayName: 'x', remark: '', tags: '' })),
    deleteReport: vi.fn(async () => ({
      deleted: true,
      impact: { testReportExportCount: 2, runArtifactCount: 5 },
    })),
    runImpact: vi.fn(),
    batchDeleteReports: vi.fn(async () => ({
      deleted: 2,
      failed: 0,
      impact: { testReportExportCount: 4, runArtifactCount: 7 },
      failures: [],
    })),
    patchRun: vi.fn(),
    deleteRun: vi.fn(),
    restoreRun: vi.fn(),
  },
}))

vi.mock('@/composables/useConfirm', () => ({ confirmAction: vi.fn(async () => true) }))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ReportsView)
  app.use(pinia!)
  app.use(i18n)
  app.component('RouterLink', {
    name: 'RouterLink',
    props: { to: { type: [String, Object], default: '' } },
    setup(_props: unknown, { slots }: { slots: Slots }) {
      return () => slots.default?.({ navigate: () => {} }) as never
    },
  })
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function byTestId(root: HTMLElement, id: string): HTMLElement | null {
  return root.querySelector<HTMLElement>(`[data-testid="${id}"]`)
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
  vi.mocked(reportsApi.page).mockClear()
  vi.mocked(listCrudApi.getRun).mockClear()
  vi.mocked(listCrudApi.updateReport).mockClear()
  vi.mocked(listCrudApi.deleteReport).mockClear()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
  vi.clearAllMocks()
})

describe('ReportsView 行内编辑（W58-08）', () => {
  it('回填来自 getRun，提交打到 updateReport', async () => {
    const root = await mountView()
    byTestId(root, 'edit-RUN-001')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    // 回填必须查后端：列表项里没有展示层三件套
    expect(listCrudApi.getRun).toHaveBeenCalledWith(1, 'RUN-001')
    const input = byTestId(root, 'edit-display-name')!.querySelector('input')!
    expect(input.value).toBe('夜间回归')

    byTestId(root, 'edit-submit')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(listCrudApi.updateReport).toHaveBeenCalledWith(1, 'RUN-001', {
      displayName: '夜间回归',
      remark: 'r',
      tags: 't',
    })
    expect(reportsApi.page).toHaveBeenCalledTimes(2)
  })
})

describe('ReportsView 软删（W58-08）', () => {
  it('先确认再删，回执展示后端返回的真实影响面', async () => {
    const root = await mountView()
    byTestId(root, 'delete-RUN-001')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(confirmAction).toHaveBeenCalled()
    expect(listCrudApi.deleteReport).toHaveBeenCalledWith(1, 'RUN-001')
    const impact = byTestId(root, 'report-delete-impact')
    expect(impact).not.toBeNull()
    // 影响面来自响应，不是前端编的
    expect(impact!.textContent).toContain(
      i18n.global.t('reports.crud.deleted', { deleted: 1, exports: 2, artifacts: 5 }),
    )
  })

  it('取消确认时不发删除请求', async () => {
    vi.mocked(confirmAction).mockResolvedValueOnce(false)
    const root = await mountView()
    byTestId(root, 'delete-RUN-001')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(listCrudApi.deleteReport).not.toHaveBeenCalled()
    expect(byTestId(root, 'report-delete-impact')).toBeNull()
  })
})

describe('ReportsView 批量软删（W59-20）', () => {
  it('勾选两条 → 批量删：确认框带条数，请求带两个 runId，回执报真删条数', async () => {
    const root = await mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const boxes = root.querySelectorAll<HTMLElement>('.n-checkbox')
    // 第 0 个是表头全选；点它等于勾上当前页两行
    boxes[0].click()
    await nextTick()
    await nextTick()

    const batchButton = root.querySelector<HTMLButtonElement>('[data-testid="batch-delete-reports"]')
    expect(batchButton).not.toBeNull()
    batchButton!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(vi.mocked(confirmAction)).toHaveBeenCalled()
    expect(String(vi.mocked(confirmAction).mock.calls[0][0])).toContain('2')
    expect(vi.mocked(listCrudApi.batchDeleteReports)).toHaveBeenCalledTimes(1)
    const [pid, ids] = vi.mocked(listCrudApi.batchDeleteReports).mock.calls[0]
    expect(pid).toBe(1)
    expect(ids).toEqual(['RUN-001', 'RUN-002'])
    expect(root.querySelector('[data-testid="report-batch-delete-result"]')?.textContent).toContain('7')
  })

  it('取消确认时不发批量请求', async () => {
    vi.mocked(confirmAction).mockResolvedValueOnce(false)
    const root = await mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    root.querySelectorAll<HTMLElement>('.n-checkbox')[0].click()
    await nextTick()
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="batch-delete-reports"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(vi.mocked(listCrudApi.batchDeleteReports)).not.toHaveBeenCalled()
  })
})


describe('ReportsView 套件数列（v5.14.0 / W64-03）', () => {
  it('有值显示数字；历史 run 的 NULL 显示「—」而不是 0', async () => {
    const root = await mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(root.querySelector('[data-testid="suite-count-RUN-001"]')?.textContent).toBe('3')
    expect(root.querySelector('[data-testid="suite-unknown-RUN-002"]')?.textContent).toBe('—')
    expect(root.querySelector('[data-testid="suite-count-RUN-002"]')).toBeNull()
  })
})
