/**
 * RunsView W57 接入回归（只读页只接筛选、不接全选；B 类 CRUD 提示）。
 *
 * 复用 `RunsView.spec.ts` 的全套依赖桩（本页依赖较多），只补 W57 新增的
 * `@/api/listCrud`。断言刻意全部落在**静态模板**上：只读提示、URL 化搜索框、
 * 列筛选条、确认/编辑弹层默认不渲染。不依赖异步数据返回，视图回归不会退化成
 * 「后端 mock 是否齐备」的测试。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App, type Slots } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import RunsView from '@/views/RunsView.vue'

vi.mock('@/api/execution', () => ({
  runLogSocketUrl: (runId: string) => `ws://test/runs/${runId}/log`,
  executionApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 1,
          runId: 'RUN-AAA',
          projectId: 1,
          state: 'FINISHED',
          deviceScope: 'DUT-01/mlx5_0',
          attemptNo: 1,
          passedTests: 3,
          totalTests: 3,
          failureReason: null,
          startedAt: '2026-11-05T01:00:00Z',
          finishedAt: '2026-11-05T01:05:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    })),
    downloadLog: vi.fn(async () => new Blob(['line1'])),
    recentLog: vi.fn(async () => [
      { seq: 1, tsGen: '01:00:01', source: 'robot', level: 'info', text: 'started' },
    ]),
    cancel: vi.fn(),
    get: vi.fn(),
    pendingActivity: vi.fn(async (_projectId: number, runId: string) => ({
      runId,
      state: 'queued' as const,
      position: 0,
      waiting: 0,
      running: 0,
      maxParallel: 2,
    })),
    quickRun: vi.fn(async () => ({ runId: 'RUN-MANUAL-1', state: 'QUEUED' })),
    runTestSet: vi.fn(async () => ({ runId: 'RUN-SET-1', state: 'QUEUED' })),
    retryOrigin: vi.fn(async () => null),
    leaseWait: vi.fn(async () => ({ state: 'waiting' as const, elapsedMs: 0, totalMs: null })),
  },
}))

vi.mock('@/api/asset', () => ({
  deviceApi: {
    page: vi.fn(async () => ({ items: [{ id: 1, projectId: 1, name: 'DUT-01' }], total: 1, page: 1, size: 100 })),
  },
}))

vi.mock('@/api/cases', () => ({
  caseSetApi: {
    page: vi.fn(async () => ({
      items: [{ id: 7, projectId: 1, setCode: 'SMOKE', name: '冒烟集', description: null }],
      total: 1,
      page: 1,
      size: 100,
    })),
  },
}))

vi.mock('@/api/shards', () => ({
  shardApi: {
    view: vi.fn(async (_projectId: number, runId: string) => ({
      runId,
      shards: [],
      totalShards: 0,
      terminalShards: 0,
      testCount: 0,
      failCount: 0,
      complete: true,
      aggregateState: 'DONE' as const,
    })),
    plan: vi.fn(),
    aggregate: vi.fn(),
  },
}))

vi.mock('@/api/listCrud', () => ({
  listCrudApi: {
    patchRun: vi.fn(async () => ({})),
    deleteRun: vi.fn(async () => ({})),
    restoreRun: vi.fn(async () => ({})),
    runImpact: vi.fn(async () => ({ testReportExportCount: 0, runArtifactCount: 0 })),
  },
}))

vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

class FakeSocket {
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  close(): void {}
}

const RouterLinkStub = {
  name: 'RouterLink',
  props: { to: { type: [String, Object], default: '' } },
  setup(_props: unknown, { slots }: { slots: Slots }) {
    return () => h('a', slots.default?.({ navigate: () => {} }))
  },
}

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(RunsView)
  app.use(pinia!)
  app.use(i18n)
  app.component('RouterLink', RouterLinkStub)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

beforeEach(() => {
  vi.stubGlobal('WebSocket', FakeSocket)
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  app = null
  host = null
  pinia = null
})

describe('RunsView W57 只读语义', () => {
  it('渲染 B 类只读提示，写明来源与允许的编辑范围', async () => {
    const root = await mountView()
    const notice = root.querySelector('[data-testid="readonly-notice"]')
    expect(notice).not.toBeNull()
    expect(notice?.textContent).toContain('运行记录由执行链路生成，不支持新增；仅可编辑备注或软删')
    expect(notice?.textContent).toContain('B 类系统产物')
    expect(notice?.getAttribute('role')).toBe('note')
  })

  it('只读提示带语义标记，供体验机检扫描', async () => {
    const root = await mountView()
    const notice = root.querySelector('.runs-readonly')
    expect(notice).not.toBeNull()
    expect(notice?.hasAttribute('data-check-semantic')).toBe(true)
  })
})

describe('RunsView W57 只接筛选、不接全选', () => {
  it('渲染 URL 化全局搜索框', async () => {
    const root = await mountView()
    expect(
      root.querySelector('[aria-label="搜索运行记录"], [placeholder="搜索运行记录"]'),
    ).not.toBeNull()
  })

  it('渲染列筛选触发按钮（DataTable filterable + filterColumns）', async () => {
    const root = await mountView()
    expect(root.querySelectorAll('[data-testid^="column-filter-trigger-"]').length).toBeGreaterThan(0)
  })

  it('只读页不渲染批量条 / 表头全选，也不渲染「新增」按钮', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="list-batch"]')).toBeNull()
    const labels = [...root.querySelectorAll('button')].map((b) => b.textContent?.trim() ?? '')
    expect(labels).not.toContain('新增')
  })
})

describe('RunsView W57 CRUD 弹层默认不渲染', () => {
  it('编辑备注弹层与危险操作确认默认都不出现', async () => {
    const root = await mountView()
    expect(root.querySelector('.crud-dialog')).toBeNull()
    expect(root.querySelector('[data-testid="confirm-ok"]')).toBeNull()
  })
})
