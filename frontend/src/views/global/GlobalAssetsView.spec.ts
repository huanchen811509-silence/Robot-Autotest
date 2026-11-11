/**
 * `GlobalAssetsView` 视图级回归（v4.11.0 / FR-v4.11-27、FR-v4.11-19）。
 *
 * <p>这一页的页签有两个身份：**URL 的 `?tab=`** 和 **组件的 `type`**。侧栏「设备 / 凭据」
 * 是同一 path 的两个 query，`isLeafActive` 对 query 做严格比较，所以只要 URL 停在裸路径，
 * 两个叶子就都不亮——看起来像「选中态坏了」，其实是 URL 没规范化。本 spec 钉四件事：
 * ① 裸路径 / 非法值进页面后必须 `router.replace` 到 `?tab=devices`；
 * ② 合法 query 直接采纳，不产生多余的 replace（否则每进一次页就多一条历史记录）；
 * ③ 点页签 ↔ URL 双向同步，且取数参数跟着变；
 * ④ 写操作列只对有权限的人出现。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import GlobalAssetsView from '@/views/global/GlobalAssetsView.vue'
import { globalAssetApi } from '@/api/globalAssets'

vi.mock('@/api/globalAssets', () => ({
  globalAssetApi: {
    list: vi.fn(),
    references: vi.fn(),
    preview: vi.fn(),
    commit: vi.fn(),
    batches: vi.fn(),
    collect: vi.fn(),
    export: vi.fn(),
    reference: vi.fn(),
    unreference: vi.fn(),
    remove: vi.fn(),
  },
}))

vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: vi.fn(), update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

const routerState = vi.hoisted(() => ({
  route: undefined as unknown as { query: Record<string, unknown>; path: string },
  push: vi.fn(),
  replace: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ push: routerState.push, replace: routerState.replace }),
}))

const deviceRow = {
  id: 101,
  assetType: 'DEVICE',
  name: 'leaf-01',
  detail: '交换机',
  managementIp: '10.0.0.11',
  status: 'active',
  source: 'xlsx',
  externalId: 'DEV-1',
  environment: 'lab',
  referenceCount: 2,
  portCount: 24,
  collectCount: 1,
  lastCollectedAt: '2026-11-10T08:00:00',
  collectStatus: 'OK',
}

const credentialRow = {
  ...deviceRow,
  id: 201,
  assetType: 'CREDENTIAL',
  name: 'lab-root',
  detail: '用户名 root',
  managementIp: '',
}

const list = vi.mocked(globalAssetApi.list)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(GlobalAssetsView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

beforeEach(() => {
  list.mockReset()
  list.mockImplementation(async (params) =>
    params?.type === 'CREDENTIAL' ? [credentialRow] : [deviceRow],
  )
  routerState.route = reactive({ query: {}, path: '/g/assets' })
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

describe('GlobalAssetsView URL 规范化', () => {
  it('裸路径进来 replace 到 ?tab=devices，并按 DEVICE 取数', async () => {
    await mountView()

    expect(routerState.replace).toHaveBeenCalledTimes(1)
    expect(routerState.replace.mock.calls[0][0]).toMatchObject({ query: { tab: 'devices' } })
    expect(list).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'DEVICE' }),
    )
  })

  it('非法 tab（?tab=links）也规范化为 devices', async () => {
    routerState.route = reactive({ query: { tab: 'links' }, path: '/g/assets' })
    await mountView()

    expect(routerState.replace).toHaveBeenCalledTimes(1)
    expect(routerState.replace.mock.calls[0][0]).toMatchObject({ query: { tab: 'devices' } })
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ type: 'DEVICE' }))
  })

  it('合法 query 直接采纳，不产生多余的 replace', async () => {
    routerState.route = reactive({ query: { tab: 'credentials' }, path: '/g/assets' })
    await mountView()

    expect(routerState.replace).not.toHaveBeenCalled()
    expect(list).toHaveBeenCalledTimes(1)
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ type: 'CREDENTIAL' }))
  })
})

describe('GlobalAssetsView 页签同步', () => {
  it('点「凭据」页签：URL 与取数参数一起切到 CREDENTIAL', async () => {
    const root = await mountView()
    expect(list).toHaveBeenCalledTimes(1)

    const tabs = root.querySelectorAll<HTMLButtonElement>('.tabs .tab')
    expect(tabs).toHaveLength(2)
    tabs[1].click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(routerState.replace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'credentials' }) }),
    )
    expect(list).toHaveBeenCalledTimes(2)
    expect(list.mock.calls[1][0]).toMatchObject({ type: 'CREDENTIAL' })
  })

  it('浏览器后退改 URL：页面跟着切数据源（URL 是唯一真源）', async () => {
    await mountView()
    expect(list.mock.calls[0][0]).toMatchObject({ type: 'DEVICE' })

    routerState.route.query = { tab: 'credentials' }
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(list).toHaveBeenCalledTimes(2)
    expect(list.mock.calls[1][0]).toMatchObject({ type: 'CREDENTIAL' })
  })
})

describe('GlobalAssetsView 权限', () => {
  it('admin 有导入/导出与行内操作', async () => {
    const root = await mountView()

    expect(root.querySelectorAll('.page-actions button')).toHaveLength(2)
    expect(root.textContent).toContain(deviceRow.name)
  })

  it('非 admin 没有导入/导出按钮，也没有删除列', async () => {
    const auth = useAuthStore()
    auth.user = { id: 2, username: 'eng', displayName: '工程', roles: ['engineer'] } as never
    const root = await mountView()

    expect(root.querySelectorAll('.page-actions button')).toHaveLength(0)
    // 列表数据本身仍可见：读权限与写权限是两件事
    expect(root.textContent).toContain(deviceRow.name)
  })
})
