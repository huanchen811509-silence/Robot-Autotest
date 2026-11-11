/**
 * `GlobalTemplatesView` 视图级回归（v4.11.0 / FR-v4.11-28）。
 *
 * <p>`?kind=builtin|custom` 是纯前端筛选（列表接口没有 kind 入参、也没有分页），所以这里
 * 要盯的只有三件事：
 * ① 裸路径 / 非法值进页面后 `router.replace` 到 `?kind=builtin`——侧栏两个叶子都带 query，
 *    URL 停在裸路径时 `isLeafActive` 严格比较会两个都不亮；
 * ② 切 kind 只改筛选、**不再请求**（否则每次都整表重拉，筛选变成假的分页）；
 * ③ 内置模板不可编辑/不可删除（`builtin` 不是装饰字段，它决定可写性）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import GlobalTemplatesView from '@/views/global/GlobalTemplatesView.vue'
import { templateApi, type TemplateRow } from '@/api/globalTemplates'

vi.mock('@/api/globalTemplates', () => ({
  templateApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    setStatus: vi.fn(),
    remove: vi.fn(),
    copy: vi.fn(),
    saveAsTemplate: vi.fn(),
    applyContent: vi.fn(),
  },
}))

vi.mock('@/api/templateProjects', () => ({
  templateProjectsApi: { list: vi.fn(async () => []), create: vi.fn(), remove: vi.fn() },
}))

vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: vi.fn(), update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

/**
 * `replace` 桩必须**真的改 URL**：本页的 kind 完全由 `route.query.kind` 推导，
 * 如果 stub 只记录调用，点页签后筛选不会变，测出来的「切了」是假的。
 */
const routerState = vi.hoisted(() => {
  const state = {
    route: undefined as unknown as { query: Record<string, unknown>; path: string },
    push: vi.fn(),
    replace: vi.fn(async (location: { query?: Record<string, unknown> }) => {
      if (location?.query) state.route.query = { ...location.query }
    }),
  }
  return state
})
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ push: routerState.push, replace: routerState.replace }),
}))

function row(id: number, name: string, builtin: boolean): TemplateRow {
  return {
    id,
    name,
    code: `TPL-${id}`,
    kernel: 'robot',
    visibility: 'public',
    builtin,
    status: 'active',
    ownerId: builtin ? null : 1,
    description: '',
    itemCount: 1,
    createdAt: '2026-11-01T00:00:00',
  }
}

const rows: TemplateRow[] = [row(1, '内置-基础链路', true), row(2, '内置-性能', true), row(3, '我的模板', false)]

const list = vi.mocked(templateApi.list)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(GlobalTemplatesView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function entries(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('.template-entry')]
}

beforeEach(() => {
  list.mockReset()
  list.mockResolvedValue(rows)
  routerState.route = reactive({ query: {}, path: '/g/templates' })
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

describe('GlobalTemplatesView kind 规范化', () => {
  it('裸路径 replace 到 ?kind=builtin，且只显示内置模板', async () => {
    const root = await mountView()

    expect(routerState.replace).toHaveBeenCalledTimes(1)
    expect(routerState.replace.mock.calls[0][0]).toMatchObject({ query: { kind: 'builtin' } })
    expect(entries(root)).toHaveLength(2)
    expect(root.textContent).not.toContain('我的模板')
  })

  it('非法 kind 值同样规范化为 builtin', async () => {
    routerState.route = reactive({ query: { kind: 'xx' }, path: '/g/templates' })
    await mountView()

    expect(routerState.replace).toHaveBeenCalledTimes(1)
    expect(routerState.replace.mock.calls[0][0]).toMatchObject({ query: { kind: 'builtin' } })
  })

  it('?kind=custom 直接采纳，只显示自定义模板且不产生多余 replace', async () => {
    routerState.route = reactive({ query: { kind: 'custom' }, path: '/g/templates' })
    const root = await mountView()

    expect(routerState.replace).not.toHaveBeenCalled()
    expect(entries(root)).toHaveLength(1)
    expect(root.textContent).toContain('我的模板')
  })
})

describe('GlobalTemplatesView 切换 kind', () => {
  it('点「自定义」：改 URL、换列表，且不再请求接口', async () => {
    const root = await mountView()
    expect(list).toHaveBeenCalledTimes(1)

    const tabs = root.querySelectorAll<HTMLButtonElement>('.tabs .tab')
    expect(tabs).toHaveLength(2)
    tabs[1].click()
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(routerState.replace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ kind: 'custom' }) }),
    )
    // 纯前端筛选：两个 kind 之和 = 总数，不该多打一次请求
    expect(list).toHaveBeenCalledTimes(1)
    expect(entries(root)).toHaveLength(1)
    expect(root.textContent).toContain('我的模板')
  })

  it('某一 kind 下没有数据时走空态，不渲染空白网格', async () => {
    list.mockResolvedValue([row(1, '内置-基础链路', true)])
    routerState.route = reactive({ query: { kind: 'custom' }, path: '/g/templates' })
    const root = await mountView()

    expect(entries(root)).toHaveLength(0)
    expect(root.querySelector('.empty')).not.toBeNull()
  })
})

describe('GlobalTemplatesView 内置模板不可写', () => {
  it('内置行只有「启停 / 复制」，没有编辑与删除', async () => {
    const root = await mountView()
    // 两个内置模板，每个 2 个按钮（启停 + 复制）
    const buttons = entries(root).map((el) => el.querySelectorAll('.template-entry__actions button').length)
    expect(buttons).toEqual([2, 2])
  })

  it('自定义行有 4 个按钮（编辑 / 启停 / 复制 / 删除）', async () => {
    routerState.route = reactive({ query: { kind: 'custom' }, path: '/g/templates' })
    const root = await mountView()

    expect(entries(root)).toHaveLength(1)
    expect(entries(root)[0].querySelectorAll('.template-entry__actions button')).toHaveLength(4)
  })
})
