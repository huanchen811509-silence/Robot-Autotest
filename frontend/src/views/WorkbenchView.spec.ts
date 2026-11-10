/**
 * `WorkbenchView` 视图级回归（v2.3 / UI-03，FR-UI-10..14）。
 *
 * <p>盯住四件「页面能打开但实际已经坏了」的事：
 * ① 一次请求：整页只允许调一次 `projectCardsApi.list`，为每张卡再打 overview 是性能事故；
 * ② 搜索 / 排序必须是本地计算，且结果顺序真的变化；
 * ③ 空数组走空态而不是渲染空白网格；
 * ④ 接口失败只出错误行，**不得**残留任何卡牌或旧数据。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import WorkbenchView from '@/views/WorkbenchView.vue'
import { projectCardsApi, type ProjectCard } from '@/api/projectCards'

vi.mock('@/api/projectCards', () => ({
  projectCardsApi: { list: vi.fn() },
}))

/**
 * `projectApi.remove` 必须打桩：删除弹层点确认后会真发请求，
 * 不打桩等于让单测打网络（失败也看不出来）。`auth` 里同样引用了 `authApi`，
 * 一并给空实现，避免 mock 模块缺导出。
 */
const removeProject = vi.hoisted(() => vi.fn())
vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: removeProject, update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

/**
 * 三张卡刻意让「最近活跃」和「名称排序」的首项不同：
 * 按时间 Zeta 最前，按名称 Alpha 最前 —— 否则排序断言会假通过。
 */
const cards: ProjectCard[] = [
  {
    id: 1,
    code: 'Z-01',
    name: 'Zeta 项目',
    description: '最近跑过',
    enabled: true,
    visibility: 'private',
    ownerId: 1,
    isCurrent: true,
    caseCount: 10,
    runCount: 3,
    passRateBp: 9700,
    lastRunAt: '2026-11-08T08:48:58',
    deviceCount: 0,
    memberCount: 2,
    myRole: 'admin',
    testKernel: 'robot',
  },
  {
    id: 2,
    code: 'A-01',
    name: 'Alpha 项目',
    description: null,
    enabled: true,
    visibility: 'public',
    ownerId: 1,
    isCurrent: false,
    caseCount: 0,
    runCount: 0,
    passRateBp: null,
    lastRunAt: '2026-11-01T10:00:00',
    deviceCount: 1,
    memberCount: 1,
    myRole: null,
    testKernel: 'robot',
  },
  {
    id: 3,
    code: 'B-01',
    name: 'Beta 项目',
    description: null,
    enabled: false,
    visibility: 'private',
    ownerId: 2,
    isCurrent: false,
    caseCount: 5,
    runCount: 1,
    passRateBp: 5000,
    lastRunAt: null,
    deviceCount: 0,
    memberCount: 0,
    myRole: 'viewer',
    testKernel: 'pytest',
  },
]

const list = vi.mocked(projectCardsApi.list)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(WorkbenchView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function cardEls(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('[data-testid="project-card"]')]
}

beforeEach(() => {
  list.mockReset()
  list.mockResolvedValue(cards)
  push.mockReset()
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

describe('WorkbenchView 卡牌页', () => {
  it('正常渲染 N 张卡，且只发一次卡牌请求', async () => {
    const root = await mountView()
    expect(cardEls(root)).toHaveLength(3)
    expect(root.textContent).toContain('Zeta 项目')
    expect(root.textContent).toContain('共 3 个项目')
    // FR-UI-14：整页一次请求，参数是窗口 + 当前项目
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenCalledWith('7d', 1)
  })

  it('搜索框按名称本地过滤，数量随之变化', async () => {
    const root = await mountView()
    const search = root.querySelector<HTMLInputElement>('[data-testid="workbench-search"]')!
    search.value = 'beta'
    search.dispatchEvent(new Event('input'))
    await nextTick()

    expect(cardEls(root)).toHaveLength(1)
    expect(root.textContent).toContain('Beta 项目')
    expect(root.textContent).toContain('共 1 个项目')
    // 本地过滤不触发第二次请求
    expect(list).toHaveBeenCalledTimes(1)
  })

  it('两种排序的首项不同：默认最近活跃，切到名称后按字母序', async () => {
    const root = await mountView()
    expect(cardEls(root)[0].textContent).toContain('Zeta 项目')

    root.querySelector<HTMLButtonElement>('[data-testid="sort-name"]')!.click()
    await nextTick()

    expect(cardEls(root)[0].textContent).toContain('Alpha 项目')
    expect(list).toHaveBeenCalledTimes(1)
  })

  it('空数组显示空态，不渲染空白网格', async () => {
    list.mockResolvedValue([])
    const root = await mountView()

    expect(root.querySelector('[data-testid="workbench-empty"]')).not.toBeNull()
    expect(cardEls(root)).toHaveLength(0)
  })

  it('接口失败显示错误行，且卡牌容器为空', async () => {
    list.mockRejectedValue(new Error('network down'))
    const root = await mountView()

    expect(root.querySelector('[data-testid="workbench-error"]')).not.toBeNull()
    expect(root.textContent).toContain('项目卡牌加载失败')
    expect(cardEls(root)).toHaveLength(0)
    expect(root.querySelector('[data-testid="project-cards"]')).toBeNull()
  })
})

/**
 * v4.4 缺陷回归：删除弹层的编码输入框曾经「点得进去、打不出字」。
 *
 * 两条断言缺一不可：
 * ① 打开后焦点必须落在输入框（被别处抢焦点就会出现打不出字）；
 * ② 逐字输入后 model 真的变、编码匹配后确认按钮由禁用转为可用。
 */
describe('WorkbenchView 删除确认弹层', () => {
  const emptyCard: ProjectCard = {
    ...cards[1],
    id: 9,
    code: 'EMPTY-01',
    name: '空项目',
    caseCount: 0,
    runCount: 0,
    deviceCount: 0,
  }

  async function openDeleteDialog(): Promise<HTMLInputElement> {
    list.mockResolvedValue([emptyCard])
    useAuthStore().user = { id: 1, username: 'admin', displayName: '管理员', roles: ['admin'] } as never
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="card-delete"]')!.click()
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    return document.querySelector<HTMLInputElement>('[data-testid="delete-code"]')!
  }

  afterEach(() => {
    removeProject.mockReset()
    document.querySelector('[data-testid="delete-mask"]')?.remove()
  })

  it('打开后输入框拿到焦点，可以逐字输入并匹配编码', async () => {
    const input = await openDeleteDialog()

    expect(document.activeElement).toBe(input)
    input.value = 'EMPTY-01'
    input.dispatchEvent(new Event('input'))
    await nextTick()

    expect(input.value).toBe('EMPTY-01')
    const confirm = document.querySelector<HTMLButtonElement>('[data-testid="delete-confirm"]')!
    expect(confirm.disabled).toBe(false)
  })

  it('编码不匹配时确认按钮保持禁用，不调删除接口', async () => {
    const input = await openDeleteDialog()
    input.value = 'EMPTY-0'
    input.dispatchEvent(new Event('input'))
    await nextTick()

    const confirm = document.querySelector<HTMLButtonElement>('[data-testid="delete-confirm"]')!
    expect(confirm.disabled).toBe(true)
    expect(removeProject).not.toHaveBeenCalled()
  })

  it('编码匹配后点确认按项目 id 调删除接口', async () => {
    const input = await openDeleteDialog()
    input.value = 'EMPTY-01'
    input.dispatchEvent(new Event('input'))
    await nextTick()

    document.querySelector<HTMLButtonElement>('[data-testid="delete-confirm"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(removeProject).toHaveBeenCalledWith(9)
  })
})
