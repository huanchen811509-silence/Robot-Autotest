/**
 * `GlobalCasesView` 视图级回归（v4.11.0 / FR-v4.11-26、FR-v4.11-19、FR-v4.11-18）。
 *
 * <p>本版把「导入记录」从页内折叠块提级为**独立分支**（`?view=imports`），与本 spec 盯的
 * 三件事直接相关：
 * ① 两个分支互斥——看着像小事，但两个 `v-if` 同时成立时用户会看到「用例清单下面挂着
 *    一张批次表」，而侧栏只亮一个叶子，读起来像页面坏了；
 * ② 来回切分支**不能重复请求**（`watch` 只在数据为空时补拉），这是提级后最容易漏的一条；
 * ③ `force=1` 只在勾选时透传，默认必须保持「回放」的幂等语义。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import GlobalCasesView from '@/views/global/GlobalCasesView.vue'
import { globalCaseApi } from '@/api/globalCases'

vi.mock('@/api/globalCases', () => ({
  globalCaseApi: {
    page: vi.fn(),
    categories: vi.fn(),
    batches: vi.fn(),
    previewImport: vi.fn(),
    commitImport: vi.fn(),
    export: vi.fn(),
    errorReport: vi.fn(),
    deprecate: vi.fn(),
    restore: vi.fn(),
  },
}))

vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: vi.fn(), update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

/**
 * 路由必须是 **reactive** 的：`view` 是 `computed(() => route.query.view ...)`，
 * 后续用例要靠改 query 触发 `watch(view)`。普通对象不会建立依赖，watch 永不触发，
 * 那样「切分支不重复请求」这条会假绿。
 */
const routerState = vi.hoisted(() => ({
  route: undefined as unknown as { query: Record<string, unknown>; path: string },
  push: vi.fn(),
  replace: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ push: routerState.push, replace: routerState.replace }),
}))

const caseRows = [
  {
    id: 11,
    caseKey: 'CLI-001',
    title: '链路连通性',
    categoryL1: '以太网',
    categoryL2: '链路',
    priority: 'P1',
    lifecycleStatus: 'active',
    latestResult: 'PASS',
  },
  {
    id: 12,
    caseKey: 'CLI-002',
    title: '时延抖动',
    categoryL1: '以太网',
    categoryL2: '性能',
    priority: 'P2',
    lifecycleStatus: 'active',
    latestResult: null,
  },
]

const batchRows = [
  {
    id: 5,
    kind: 'CASE_LEDGER',
    checksum: 'a'.repeat(64),
    status: 'COMMITTED',
    fileName: 'ledger.xlsx',
    inserted: 10,
    updated: 2,
    unchanged: 3,
    deprecated: 1,
    errorCount: 0,
    createdAt: '2026-11-10T09:00:00',
  },
]

const page = vi.mocked(globalCaseApi.page)
const categories = vi.mocked(globalCaseApi.categories)
const batches = vi.mocked(globalCaseApi.batches)
const previewImport = vi.mocked(globalCaseApi.previewImport)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(GlobalCasesView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

beforeEach(() => {
  page.mockReset()
  page.mockResolvedValue({ items: caseRows, total: 2 } as never)
  categories.mockReset()
  categories.mockResolvedValue([
    { id: 1, name: '以太网', level: 1, parentId: null },
    { id: 2, name: '链路', level: 2, parentId: 1 },
  ] as never)
  batches.mockReset()
  batches.mockResolvedValue({ items: batchRows, total: 1 } as never)
  previewImport.mockReset()
  previewImport.mockResolvedValue({
    batchId: 9,
    sha256: 'b'.repeat(64),
    newCount: 2,
    updateCount: 0,
    unchangedCount: 0,
    deprecateCount: 0,
    errorCount: 0,
    replayed: false,
    skippedSheets: [],
    rows: [],
  } as never)

  routerState.route = reactive({ query: {}, path: '/g/cases' })
  routerState.push.mockReset()
  routerState.replace.mockReset()

  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projectId = 1
  auth.user = {
    id: 1,
    username: 'qa-e2e-admin',
    displayName: '质量管理员',
    roles: ['admin'],
  } as never
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('GlobalCasesView 分支互斥', () => {
  it('裸路径进用例清单：只渲染用例网格，不请求批次', async () => {
    const root = await mountView()

    expect(root.querySelector('[data-testid="cases-grid"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="imports-grid"]')).toBeNull()
    expect(page).toHaveBeenCalledTimes(1)
    expect(batches).not.toHaveBeenCalled()
  })

  it('?view=imports 进导入记录：只渲染批次网格，不请求用例列表', async () => {
    routerState.route = reactive({ query: { view: 'imports' }, path: '/g/cases' })
    const root = await mountView()

    expect(root.querySelector('[data-testid="imports-grid"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="cases-grid"]')).toBeNull()
    expect(batches).toHaveBeenCalledTimes(1)
    expect(page).not.toHaveBeenCalled()
  })

  it('非法 view 值按用例清单处理，不渲染空白页', async () => {
    routerState.route = reactive({ query: { view: 'xx' }, path: '/g/cases' })
    const root = await mountView()

    expect(root.querySelector('[data-testid="cases-grid"]')).not.toBeNull()
    expect(page).toHaveBeenCalledTimes(1)
  })
})

describe('GlobalCasesView 分支切换', () => {
  it('已有数据时来回切不重复请求（提级后最容易漏的一条）', async () => {
    const root = await mountView()
    expect(page).toHaveBeenCalledTimes(1)

    // 切到导入记录：批次为空 → 补拉一次
    const route = routerState.route
    route.query = { view: 'imports' }
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(batches).toHaveBeenCalledTimes(1)

    // 切回用例清单：用例非空 → 不再请求
    route.query = {}
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(page).toHaveBeenCalledTimes(1)
    expect(batches).toHaveBeenCalledTimes(1)
    expect(root.querySelector('[data-testid="cases-grid"]')).not.toBeNull()
  })
})

describe('GlobalCasesView 导入预览的 force 语义', () => {
  async function pickFile(root: HTMLElement): Promise<void> {
    const input = root.querySelector<HTMLInputElement>('input[type="file"]')!
    const file = new File(['x'], 'ledger.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    input.dispatchEvent(new Event('change'))
    await new Promise((resolve) => setTimeout(resolve, 0))
  }

  it('默认不勾选：第二参 force = false，保持回放幂等', async () => {
    const root = await mountView()
    root.querySelectorAll<HTMLButtonElement>('.page-actions button')[1].click()
    await nextTick()

    await pickFile(root)

    expect(previewImport).toHaveBeenCalledTimes(1)
    expect(previewImport.mock.calls[0][1]).toBe(false)
  })

  it('勾选「强制重算」后第二参 force = true', async () => {
    const root = await mountView()
    root.querySelectorAll<HTMLButtonElement>('.page-actions button')[1].click()
    await nextTick()

    const checkbox = root.querySelector<HTMLInputElement>('[data-testid="force-recalc"]')!
    checkbox.checked = true
    checkbox.dispatchEvent(new Event('change'))
    await nextTick()

    await pickFile(root)

    expect(previewImport).toHaveBeenCalledTimes(1)
    expect(previewImport.mock.calls[0][1]).toBe(true)
  })
})

describe('GlobalCasesView 权限与四态', () => {
  it('非 admin 看不到导入/导出按钮，但列表照常取数', async () => {
    const auth = useAuthStore()
    auth.user = { id: 2, username: 'eng', displayName: '工程', roles: ['engineer'] } as never
    const root = await mountView()

    expect(root.querySelectorAll('.page-actions button')).toHaveLength(0)
    expect(page).toHaveBeenCalledTimes(1)
  })

  it('取数失败出错误行，且不渲染残留行', async () => {
    page.mockRejectedValue(new Error('boom'))
    const root = await mountView()

    expect(root.textContent).toContain('加载失败')
    // 不能用 `.n-data-table-tr` 计数判「没有行」：naive-ui 把空态本身也渲染在行里，
    // 恒为 1 条。真正的判据是「一条用例数据都没有漏出来」。
    expect(root.textContent).not.toContain('链路连通性')
    expect(root.textContent).not.toContain('CLI-001')
  })
})
