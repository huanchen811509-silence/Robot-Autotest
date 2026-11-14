/**
 * GlobalCaseSetsView（W57-09）视图级单测：列表 / 搜索 / CRUD / 成员 / 重建样例集 / 空态 / 只读显隐。
 * 平台接口整模块 mock，不依赖真实网络。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import GlobalCaseSetsView from '@/views/global/GlobalCaseSetsView.vue'
import { platformCaseSetApi } from '@/api/platformCaseSets'

vi.mock('@/api/platformCaseSets', () => ({
  platformCaseSetApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    items: vi.fn(),
    addItem: vi.fn(),
    removeItem: vi.fn(),
    reorder: vi.fn(),
    seedSamples: vi.fn(),
  },
  globalGroupedApi: { grouped: vi.fn() },
}))
vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []) },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

const rows = [
  { id: 1, setCode: 'SMOKE-L1', name: '以太网冒烟', description: null, itemCount: 3 },
  { id: 2, setCode: 'RDMA-L1', name: 'RDMA 回归', description: '回归用', itemCount: 5 },
]
const itemRows = [
  { id: 11, caseCode: 'CLI-001', title: '链路连通性', categoryL1: '以太网' },
  { id: 12, caseCode: 'CLI-002', title: '时延抖动', categoryL1: '以太网' },
]

const api = vi.mocked(platformCaseSetApi)
let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(GlobalCaseSetsView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  return root
}

async function settle(): Promise<void> {
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

beforeEach(() => {
  api.list.mockReset()
  api.list.mockResolvedValue(rows as never)
  api.create.mockReset()
  api.create.mockResolvedValue(rows[0] as never)
  api.update.mockReset()
  api.update.mockResolvedValue(rows[0] as never)
  api.remove.mockReset()
  api.remove.mockResolvedValue(undefined as never)
  api.items.mockReset()
  api.items.mockResolvedValue(itemRows as never)
  api.addItem.mockReset()
  api.addItem.mockResolvedValue(undefined as never)
  api.removeItem.mockReset()
  api.removeItem.mockResolvedValue(undefined as never)
  api.reorder.mockReset()
  api.reorder.mockResolvedValue(undefined as never)
  api.seedSamples.mockReset()
  api.seedSamples.mockResolvedValue({ created: 2, rebuilt: true, l1Count: 2 } as never)

  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 1, username: 'admin', displayName: '管理员', roles: ['admin'] } as never
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('GlobalCaseSetsView 列表与权限', () => {
  it('挂载后拉取列表并渲染两行', async () => {
    const root = await mountView()
    expect(api.list).toHaveBeenCalledTimes(1)
    expect(root.querySelector('[data-testid="case-set-row-1"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="case-set-row-2"]')).not.toBeNull()
  })

  it('admin 渲染全部写按钮（新建 / 重建 / 改名 / 删除）', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="create-btn"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="seed-btn"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="edit-1"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="delete-1"]')).not.toBeNull()
  })

  it('只读用户不渲染写按钮，但列表与「成员」入口仍可见', async () => {
    const auth = useAuthStore()
    auth.user = { id: 2, username: 'viewer', displayName: '只读', roles: ['viewer'] } as never
    const root = await mountView()
    expect(root.querySelector('[data-testid="create-btn"]')).toBeNull()
    expect(root.querySelector('[data-testid="seed-btn"]')).toBeNull()
    expect(root.querySelector('[data-testid="edit-1"]')).toBeNull()
    expect(root.querySelector('[data-testid="delete-1"]')).toBeNull()
    expect(root.querySelector('[data-testid="case-set-row-1"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="manage-1"]')).not.toBeNull()
  })

  it('空态自解释：说明如何新建与按一级分类重建', async () => {
    api.list.mockResolvedValue([] as never)
    const root = await mountView()
    const empty = root.querySelector('[data-testid="empty-state"]')
    expect(empty).not.toBeNull()
    expect(empty?.textContent).toContain('按一级分类重建样例集')
  })
})

describe('GlobalCaseSetsView 搜索与 CRUD', () => {
  it('搜索按 keyword 走接口', async () => {
    const root = await mountView()
    const input = root.querySelector<HTMLInputElement>('[data-testid="search-input"]')!
    input.value = 'RDMA'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="search-btn"]')!.click()
    await settle()
    expect(api.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'RDMA' }),
    )
  })

  it('新建：填表单提交后调 create 并刷新', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="create-btn"]')!.click()
    await nextTick()
    const setCode = root.querySelector<HTMLInputElement>('[data-testid="input-set-code"]')!
    setCode.value = 'NEW-01'
    setCode.dispatchEvent(new Event('input'))
    const name = root.querySelector<HTMLInputElement>('[data-testid="input-set-name"]')!
    name.value = '新用例集'
    name.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="submit-create"]')!.click()
    await settle()

    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ setCode: 'NEW-01', name: '新用例集' }))
    expect(root.querySelector('[data-testid="message"]')?.textContent).toContain('已创建')
  })

  it('「按一级分类重建样例集」调 seedSamples(true)', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="seed-btn"]')!.click()
    await settle()
    expect(api.seedSamples).toHaveBeenCalledWith(true)
  })

  it('改名：编辑表单提交调 update', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="edit-1"]')!.click()
    await nextTick()
    const name = root.querySelector<HTMLInputElement>('[data-testid="input-set-name"]')!
    name.value = '冒烟集（改）'
    name.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="submit-create"]')!.click()
    await settle()
    expect(api.update).toHaveBeenCalledWith(1, expect.objectContaining({ name: '冒烟集（改）' }))
  })

  it('删除：调 remove(id)', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="delete-1"]')!.click()
    await settle()
    expect(api.remove).toHaveBeenCalledWith(1)
  })
})

describe('GlobalCaseSetsView 成员管理', () => {
  it('点「成员」拉取 items 并渲染成员行', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="manage-1"]')!.click()
    await settle()
    expect(api.items).toHaveBeenCalledWith(1)
    expect(root.querySelector('[data-testid="member-panel"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="member-row-11"]')).not.toBeNull()
  })

  it('添加成员：输入用例 ID 后调 addItem', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="manage-1"]')!.click()
    await settle()
    const input = root.querySelector<HTMLInputElement>('[data-testid="add-case-input"]')!
    input.value = '99'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="add-case-btn"]')!.click()
    await settle()
    expect(api.addItem).toHaveBeenCalledWith(1, 99)
  })

  it('移除成员：调 removeItem(setId, caseId)', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="manage-1"]')!.click()
    await settle()
    root.querySelector<HTMLButtonElement>('[data-testid="remove-item-11"]')!.click()
    await settle()
    expect(api.removeItem).toHaveBeenCalledWith(1, 11)
  })
})
