/**
 * `GlobalProjectsView` 视图级回归（v4.11.0 / FR-v4.11-29）。
 *
 * <p>本页只做一件事：读 `?scope` → 交给 `WorkbenchView`。所以 spec 盯的是**这个转换**：
 * - 裸路径 `/g/projects`（该组默认态）= 全部项目；
 * - `?scope=mine` = 只列「我创建或我加入」；
 * - 非法值（`?scope=xx`）必须按全部处理，不能渲染成空网格。
 *
 * <p>过滤本身在 `WorkbenchView` 里，判据是 `own || joined`。第三张卡专门用来钉住 A14：
 * 全局 admin 对**没有成员关系**的项目也会拿到 `myRole = 'admin'`，用 `myRole` 判会把它列进来。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import GlobalProjectsView from '@/views/global/GlobalProjectsView.vue'
import { projectCardsApi, type ProjectCard } from '@/api/projectCards'

vi.mock('@/api/projectCards', () => ({
  projectCardsApi: { list: vi.fn() },
}))

vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: vi.fn(), update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

const routerState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  push: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ push: routerState.push }),
}))

const cards: ProjectCard[] = [
  {
    id: 1,
    code: 'OWN-01',
    name: '我创建的项目',
    description: null,
    enabled: true,
    visibility: 'private',
    ownerId: 1,
    isCurrent: true,
    caseCount: 1,
    runCount: 1,
    passRateBp: 9000,
    lastRunAt: '2026-11-10T10:00:00',
    deviceCount: 0,
    memberCount: 1,
    myRole: 'admin',
    testKernel: 'robot',
    own: true,
    joined: false,
  },
  {
    id: 2,
    code: 'JOIN-01',
    name: '我加入的项目',
    description: null,
    enabled: true,
    visibility: 'public',
    ownerId: 9,
    isCurrent: false,
    caseCount: 0,
    runCount: 0,
    passRateBp: null,
    lastRunAt: '2026-11-09T10:00:00',
    deviceCount: 0,
    memberCount: 3,
    myRole: 'engineer',
    testKernel: 'pytest',
    own: false,
    joined: true,
  },
  {
    id: 3,
    code: 'OTHER-01',
    name: '别人的项目',
    description: null,
    enabled: true,
    visibility: 'public',
    ownerId: 9,
    isCurrent: false,
    caseCount: 0,
    runCount: 0,
    passRateBp: null,
    lastRunAt: null,
    deviceCount: 0,
    memberCount: 2,
    // A14：admin 兜底让这个值看起来「像是我的」，但 own/joined 都是 false
    myRole: 'admin',
    testKernel: 'robot',
    own: false,
    joined: false,
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
  app = createApp(GlobalProjectsView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function cardNames(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLElement>('[data-testid="project-card"] .project-card__name')].map(
    (el) => el.textContent?.trim() ?? '',
  )
}

beforeEach(() => {
  list.mockReset()
  list.mockResolvedValue(cards)
  routerState.route = { query: {} }
  routerState.push.mockReset()
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

describe('GlobalProjectsView 范围切换', () => {
  it('裸路径 = 全部项目，三张卡都在', async () => {
    const root = await mountView()

    expect(cardNames(root)).toHaveLength(3)
    expect(root.textContent).toContain('别人的项目')
    expect(list).toHaveBeenCalledTimes(1)
  })

  it('?scope=mine 只留 own || joined，别人的项目即便 myRole=admin 也不出现', async () => {
    routerState.route = { query: { scope: 'mine' } }
    const root = await mountView()

    const names = cardNames(root)
    expect(names).toHaveLength(2)
    expect(names.join(' ')).toContain('我创建的项目')
    expect(names.join(' ')).toContain('我加入的项目')
    expect(root.textContent).not.toContain('别人的项目')
  })

  it('非法 scope（?scope=xx）按「全部」处理，不退化成空网格', async () => {
    routerState.route = { query: { scope: 'xx' } }
    const root = await mountView()

    expect(cardNames(root)).toHaveLength(3)
  })

  it('mine 下没有任何关联项目时走空态而不是空白网格', async () => {
    routerState.route = { query: { scope: 'mine' } }
    list.mockResolvedValue([cards[2]])
    const root = await mountView()

    expect(cardNames(root)).toHaveLength(0)
    expect(root.querySelector('[data-testid="workbench-empty"]')).not.toBeNull()
  })

  it('切范围不额外打接口：仍只有一次卡牌请求', async () => {
    routerState.route = { query: { scope: 'mine' } }
    await mountView()

    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenCalledWith('7d', 1)
  })
})
