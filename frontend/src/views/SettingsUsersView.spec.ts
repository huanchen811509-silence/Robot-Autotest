/**
 * `SettingsUsersView` 视图级回归（v4.11.0 / FR-v4.11-30、FR-v4.11-08）。
 *
 * <p>这一页最容易「看起来对、其实答错问题」的地方有三处，本 spec 逐条锁死：
 * ① **关联项目的判据是 `own || joined`，不是 `myRole`**——全局 admin 对无成员关系的
 *    项目也会拿到 `myRole = 'admin'`（A14），用 `myRole` 判会把别人的项目列进来；
 * ② 页面是**只读**的：除「打开项目」链接外不得出现任何写控件（新增 / 停用 / 改角色）；
 * ③ 取数失败时不能把上一轮的项目列表留在页面上，也不能显示成「还没有关联项目」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import SettingsUsersView from '@/views/SettingsUsersView.vue'
import { projectCardsApi, type ProjectCard } from '@/api/projectCards'

vi.mock('@/api/projectCards', () => ({
  projectCardsApi: { list: vi.fn() },
}))

/** `auth` store 间接引用 `authApi`，一并给空实现，避免 mock 模块缺导出。 */
vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: vi.fn(), update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

/**
 * 四张卡，覆盖「列 / 不列」全部分支：
 * - 我创建的（own=true，无成员关系，myRole=admin）→ 列
 * - 我加入的（joined=true，myRole=engineer）→ 列
 * - 别人的（own/joined 都 false，但 myRole=admin）→ **不列**（这条是 A14 的回归点）
 * - 已归档（enabled=false）但确实是我加入的 → 仍列（归档不影响归属）
 */
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
    caseCount: 3,
    runCount: 1,
    passRateBp: 9000,
    lastRunAt: null,
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
    lastRunAt: null,
    deviceCount: 0,
    memberCount: 4,
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
    // 关键：全局 admin 看到的 myRole 就是 admin，但这个人并没有加入
    myRole: 'admin',
    testKernel: 'robot',
    own: false,
    joined: false,
  },
  {
    id: 4,
    code: 'OFF-01',
    name: '归档项目',
    description: null,
    enabled: false,
    visibility: 'private',
    ownerId: 9,
    isCurrent: false,
    caseCount: 0,
    runCount: 0,
    passRateBp: null,
    lastRunAt: null,
    deviceCount: 0,
    memberCount: 3,
    myRole: 'viewer',
    testKernel: 'robot',
    own: false,
    joined: true,
  },
]

const list = vi.mocked(projectCardsApi.list)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

/** 未装真实 router：`RouterLink` 用最小桩代替，只保留「可点击」与默认插槽。 */
const RouterLinkStub = {
  props: { to: { type: [String, Object], default: '' } },
  setup(_props: unknown, { slots }: { slots: Record<string, (() => unknown) | undefined> }) {
    return () => h('a', { class: 'router-link-stub' }, slots.default?.() as never)
  },
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(SettingsUsersView)
  app.component('RouterLink', RouterLinkStub)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function text(root: HTMLElement, testid: string): string {
  return root.querySelector<HTMLElement>(`[data-testid="${testid}"]`)?.textContent?.trim() ?? ''
}

function linkedNames(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLElement>('[data-testid="su-linked-projects"] li')].map(
    (li) => li.querySelector('a')?.textContent?.trim() ?? '',
  )
}

beforeEach(() => {
  list.mockReset()
  list.mockResolvedValue(cards)
  push.mockReset()
  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projectId = 1
  // 全局 admin：既是最需要「我参与的」这个筛子的人，也是最容易被 myRole 骗到的人
  auth.user = {
    id: 1,
    username: 'qa-e2e-admin',
    displayName: '质量管理员',
    roles: ['admin', 'pm'],
  } as never
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('SettingsUsersView 当前用户', () => {
  it('四项身份信息全部来自 auth store，不额外打接口', async () => {
    const root = await mountView()

    expect(text(root, 'su-username')).toBe('qa-e2e-admin')
    expect(text(root, 'su-display-name')).toBe('质量管理员')
    expect(text(root, 'su-roles')).toBe('admin · pm')
    // 只发一次卡牌请求，且参数是「7d + 当前项目」
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenCalledWith('7d', 1)
  })

  it('没有用户信息时全部回落到占位符，不渲染 undefined', async () => {
    const auth = useAuthStore()
    auth.user = null
    const root = await mountView()

    expect(text(root, 'su-username')).toBe('—')
    expect(text(root, 'su-display-name')).toBe('—')
    expect(text(root, 'su-roles')).toBe('—')
    expect(root.textContent).not.toContain('undefined')
  })
})

describe('SettingsUsersView 关联项目', () => {
  it('只列 own || joined：别人的项目即便 myRole=admin 也不出现', async () => {
    const root = await mountView()
    const names = linkedNames(root)

    expect(names).toEqual(['我创建的项目', '我加入的项目', '归档项目'])
    expect(names).not.toContain('别人的项目')
    expect(text(root, 'su-project-count')).toBe('3')
  })

  it('角色徽标走 i18n，缺键时回退成原始角色码', async () => {
    const root = await mountView()
    const badges = [...root.querySelectorAll<HTMLElement>('[data-testid="su-linked-projects"] .badge')]
      .map((el) => el.textContent?.trim())

    // 三条都能渲染出非空徽标；engineer 在 zh-CN 里没有 memberRole 键时退回角色码，
    // 两种结果都不该是空串（空徽标等于信息丢失）。
    expect(badges).toHaveLength(3)
    for (const badge of badges) expect(badge).toBeTruthy()
  })

  it('一个关联项目都没有时走空态，不留空白列表', async () => {
    list.mockResolvedValue([])
    const root = await mountView()

    expect(root.querySelector('[data-testid="su-linked-empty"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="su-linked-projects"]')).toBeNull()
    expect(text(root, 'su-project-count')).toBe('0')
  })

  it('点关联项目先切当前项目再跳看板', async () => {
    const root = await mountView()
    // 点第二行（id=2）：第一行恰好就是当前项目，点它证明不了「切换」真的发生了
    root.querySelectorAll<HTMLAnchorElement>('[data-testid="su-linked-projects"] li a')[1].click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(useAuthStore().projectId).toBe(2)
    expect(push).toHaveBeenCalledWith('/overview')
  })
})

describe('SettingsUsersView 边界', () => {
  it('取数失败只出错误行，不残留旧列表、也不谎报「没有项目」', async () => {
    list.mockRejectedValue(new Error('boom'))
    const root = await mountView()

    expect(root.textContent).toContain('加载失败')
    expect(root.querySelector('[data-testid="su-linked-projects"]')).toBeNull()
    expect(root.querySelector('[data-testid="su-linked-empty"]')).toBeNull()
    expect(text(root, 'su-project-count')).toBe('0')
  })

  it('页面没有任何写控件：没有表单、输入框与增删改按钮', async () => {
    const root = await mountView()

    expect(root.querySelectorAll('form')).toHaveLength(0)
    expect(root.querySelectorAll('input')).toHaveLength(0)
    expect(root.querySelectorAll('select')).toHaveLength(0)
    // 唯一的可点击元素是关联项目链接，不允许出现 button
    expect(root.querySelectorAll('button')).toHaveLength(0)
    for (const label of ['新增', '停用', '删除', '改角色']) {
      expect(root.textContent).not.toContain(label)
    }
  })
})
