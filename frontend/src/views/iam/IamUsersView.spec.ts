/**
 * `IamUsersView` 视图级回归（v5.9.0 / FR-v5.9-05 判据 4 / W59-15、17）。
 *
 * 锁死：用户详情里的「所属组 N 个 / 参与项目 M 个」必须来自关系聚合，
 * 且**点开后的列表条数等于这两个数字**——这正是「信息幂等」在 IAM 页的落地。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import IamUsersView from '@/views/iam/IamUsersView.vue'
import { userApi } from '@/api/iam'
import { iamRelationApi } from '@/api/iamRelations'

vi.mock('@/api/iam', () => ({
  userApi: { page: vi.fn(), update: vi.fn(), create: vi.fn(), remove: vi.fn() },
  roleApi: { list: vi.fn(async () => [{ id: 1, code: 'admin', name: '管理员', builtIn: true }]) },
}))

vi.mock('@/api/iamRelations', () => ({ iamRelationApi: { of: vi.fn() } }))
vi.mock('@/composables/useConfirm', () => ({ confirmAction: vi.fn(async () => true) }))

const user = {
  id: 5,
  username: 'zhangsan',
  displayName: '张三',
  email: 'z@example.com',
  orgId: null,
  enabled: true,
  passwordExpiresAt: null,
  lastLoginAt: null,
  roles: ['engineer'],
}

const relation = {
  dimension: 'user' as const,
  id: 5,
  groups: [{ id: 3, name: '交付一组' }],
  users: [],
  projects: [
    { id: 11, code: 'ALPHA', name: 'Alpha 项目', source: 'MEMBER', roleCodes: ['engineer'] },
    { id: 12, code: 'BETA', name: 'Beta 项目', source: 'GROUP', roleCodes: ['viewer'] },
  ],
  counts: { groups: 1, users: 0, projects: 2 },
}

let app: App | null = null
let root: HTMLElement

async function flush(): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

beforeEach(() => {
  vi.mocked(userApi.page).mockResolvedValue({ items: [user], total: 1, page: 1, size: 100 } as never)
  vi.mocked(iamRelationApi.of).mockResolvedValue(relation as never)
  root = document.createElement('div')
  document.body.appendChild(root)
  app = createApp(IamUsersView)
  app.use(i18n)
  app.mount(root)
})

afterEach(() => {
  app?.unmount()
  app = null
  root?.remove()
  vi.clearAllMocks()
})

describe('IamUsersView：关系聚合数字与列表条数一致', () => {
  it('列表显示用户名与全局角色', async () => {
    await flush()
    expect(root.textContent).toContain('张三')
    expect(root.textContent).toContain('engineer')
  })

  it('打开详情后计数与关系聚合一致，且列出的项目条数 = 计数', async () => {
    await flush()
    const exposed = app?._instance?.exposed as { openDetail: (row: typeof user) => Promise<void> }
    await exposed.openDetail(user)
    await flush()

    const dock = root.querySelector('[data-testid="iam-user-detail"]')
    expect(dock).toBeTruthy()
    const text = dock?.textContent ?? ''
    expect(text).toContain('所属组 1 个')
    expect(text).toContain('参与项目 2 个')
    // 两个项目都列出，且带来源标记（为什么这个人能看到这个项目）
    expect(text).toContain('ALPHA')
    expect(text).toContain('BETA')
    expect(text).toContain('MEMBER')
    expect(text).toContain('GROUP')
    expect(iamRelationApi.of).toHaveBeenCalledWith('user', 5)

    const dockDock = dock as HTMLElement
    // 列表条数 = 计数：li 元素里除「空态」外正好 3 条（1 组 + 2 项目）
    expect(dockDock.querySelectorAll('li').length).toBe(3)
  })

  it('删除用户必须过确认，且删除后重新拉列表', async () => {
    await flush()
    const { confirmAction } = await import('@/composables/useConfirm')
    const exposed = app?._instance?.exposed as { remove: (row: typeof user) => Promise<void> }
    await exposed.remove(user)
    await flush()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(userApi.remove).toHaveBeenCalledWith(5)
    expect(userApi.page).toHaveBeenCalledTimes(2)
  })
})
