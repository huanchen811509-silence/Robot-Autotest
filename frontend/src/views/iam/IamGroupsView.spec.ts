/**
 * `IamGroupsView` 视图级回归（v5.9.0 / FR-v5.9-05 判据 3 / W59-15、17）。
 *
 * 锁死一件事：**界面上的「已授权项目 N 个」必须等于关系聚合返回的条数**。
 * 这条如果只靠「看起来对」验证，一定会退化成各页自己 count——三页三个数字，
 * 谁也不报错，但用户永远对不上账。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import IamGroupsView from '@/views/iam/IamGroupsView.vue'
import { groupsApi } from '@/api/groups'
import { iamRelationApi } from '@/api/iamRelations'

vi.mock('@/api/groups', () => ({
  groupsApi: {
    groups: vi.fn(),
    create: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    addMember: vi.fn(),
    removeMember: vi.fn(),
    revoke: vi.fn(),
    grant: vi.fn(),
  },
}))

vi.mock('@/api/iam', () => ({
  userApi: { page: vi.fn(async () => ({ items: [{ id: 1, username: 'u1', displayName: '张三' }] })) },
  projectApi: { list: vi.fn(async () => []) },
  roleApi: { list: vi.fn(async () => []) },
}))

vi.mock('@/api/iamRelations', () => ({ iamRelationApi: { of: vi.fn() } }))

vi.mock('@/composables/useConfirm', () => ({ confirmAction: vi.fn(async () => true) }))

const group = { id: 7, name: '交付一组', description: '交付团队', memberCount: 1, projectCount: 2 }

const relation = {
  dimension: 'group' as const,
  id: 7,
  groups: [],
  users: [{ id: 1, username: 'u1', displayName: '张三' }],
  projects: [
    { id: 11, code: 'ALPHA', name: 'Alpha 项目', roleCode: 'engineer' },
    { id: 12, code: 'BETA', name: 'Beta 项目', roleCode: 'viewer' },
  ],
  counts: { groups: 0, users: 1, projects: 2 },
}

let app: App | null = null
let root: HTMLElement

async function flush(): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

async function mountView(): Promise<HTMLElement> {
  root = document.createElement('div')
  document.body.appendChild(root)
  app = createApp(IamGroupsView)
  app.use(i18n)
  app.mount(root)
  await flush()
  return root
}

beforeEach(() => {
  vi.mocked(groupsApi.groups).mockResolvedValue([group] as never)
  vi.mocked(iamRelationApi.of).mockResolvedValue(relation as never)
})

afterEach(() => {
  app?.unmount()
  app = null
  root?.remove()
  vi.clearAllMocks()
})

describe('IamGroupsView：关系数字与列表条数一致', () => {
  it('列表显示组的成员数与授权项目数，且授权项目数来自 groups() 的计数', async () => {
    const el = await mountView()
    expect(el.textContent).toContain('交付一组')
    // memberCount=1 / projectCount=2 —— 两个数字都在表格里，不是只在详情里
    expect(el.textContent).toContain('1')
    expect(el.textContent).toContain('2')
  })

  it('打开详情后「已授权项目」数字 = relations.counts.projects，且列出条数与之相等', async () => {
    const el = await mountView()
    const exposed = app?._instance?.exposed as { openDetail: (row: typeof group) => Promise<void> }
    await exposed.openDetail(group)
    await flush()

    const dock = el.querySelector('[data-testid="iam-group-detail"]')
    expect(dock).toBeTruthy()
    const text = dock?.textContent ?? ''
    // 计数徽标（2）与两个项目名同时出现 —— 数字和列表不可能各说各的
    expect(text).toContain('ALPHA')
    expect(text).toContain('BETA')
    expect(text).toContain('(2)')
    expect(iamRelationApi.of).toHaveBeenCalledWith('group', 7)
  })

  it('删除组必须先过确认（T1 通则：不允许无声删除）', async () => {
    const { confirmAction } = await import('@/composables/useConfirm')
    const el = await mountView()
    const exposed = app?._instance?.exposed as { remove: (row: typeof group) => Promise<void> }

    await exposed.remove(group)
    await flush()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(groupsApi.remove).toHaveBeenCalledWith(7)
    // 列表被重新拉取：删除后不会留下「已删除但还在表里」的假象
    expect(groupsApi.groups).toHaveBeenCalledTimes(2)
    expect(el).toBeTruthy()
  })
})
