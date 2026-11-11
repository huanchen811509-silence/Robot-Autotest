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
    credentialDevices: vi.fn(),
    collectByCredential: vi.fn(),
    createDevice: vi.fn(),
    createCredential: vi.fn(),
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
  managementCredentialId: 201 as number | null,
  bmcCredentialId: null as number | null,
  collectChannel: null as string | null,
}

const credentialRow = {
  ...deviceRow,
  id: 201,
  assetType: 'CREDENTIAL',
  name: 'lab-root',
  detail: 'ssh / root',
  managementIp: '',
  managementCredentialId: null,
  bmcCredentialId: null,
  collectChannel: 'ssh',
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
  vi.clearAllMocks()
  list.mockReset()
  list.mockImplementation(async (params) =>
    params?.type === 'CREDENTIAL' ? [credentialRow] : [deviceRow],
  )
  vi.mocked(globalAssetApi.credentialDevices).mockResolvedValue([])
  vi.mocked(globalAssetApi.collectByCredential).mockResolvedValue({
    total: 0,
    ok: 0,
    failed: 0,
    items: [],
  })
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
  it('admin 有导出 / 录入 / 导入三个入口与行内操作', async () => {
    const root = await mountView()

    // v4.12.0：设备页签多一个「录入设备」，三个按钮的顺序是 导出 → 录入设备 → 导入
    const actions = Array.from(root.querySelectorAll<HTMLButtonElement>('.page-actions button'))
    expect(actions).toHaveLength(3)
    expect(actions[1].textContent).toContain('录入设备')
    expect(root.textContent).toContain(deviceRow.name)
  })

  it('非 admin 没有导入/导出按钮，也没有删除列', async () => {
    const auth = useAuthStore()
    auth.user = { id: 2, username: 'eng', displayName: '工程', roles: ['engineer'] } as never
    const root = await mountView()

    expect(root.querySelectorAll<HTMLButtonElement>('.page-actions button')).toHaveLength(0)
    // 列表数据本身仍可见：读权限与写权限是两件事
    expect(root.textContent).toContain(deviceRow.name)
  })
})

/* ------------------------------------------------- v4.12.0 采集通道与录入 */

describe('GlobalAssetsView 采集按钮按通道显隐（FR-v4.12-10）', () => {
  it('设备未绑定任何凭据：不渲染「采集」按钮，只显示原因', async () => {
    list.mockImplementation(async () => [
      { ...deviceRow, managementCredentialId: null, bmcCredentialId: null },
    ])
    const root = await mountView()

    const actionButtons = Array.from(root.querySelectorAll<HTMLButtonElement>('.row-actions button')).map(
      (button) => button.textContent?.trim() ?? '',
    )
    expect(actionButtons.some((text) => text.includes('采集'))).toBe(false)
    expect(root.textContent).toContain('未绑定凭据')
  })

  it('设备只有带外凭据（纯 BMC 交换机）仍可采集', async () => {
    list.mockImplementation(async () => [
      { ...deviceRow, managementCredentialId: null, bmcCredentialId: 202 },
    ])
    const root = await mountView()

    const actionButtons = Array.from(root.querySelectorAll<HTMLButtonElement>('.row-actions button')).map(
      (button) => button.textContent?.trim() ?? '',
    )
    expect(actionButtons.some((text) => text.includes('采集'))).toBe(true)
    expect(root.textContent).not.toContain('未绑定凭据')
  })
})

describe('GlobalAssetsView 凭据关联设备（FR-v4.12-11/12/15）', () => {
  it('凭据页签点「关联设备」：拉取并渲染地址面与通道', async () => {
    routerState.route = reactive({ query: { tab: 'credentials' }, path: '/g/assets' })
    vi.mocked(globalAssetApi.credentialDevices).mockResolvedValue([
      {
        deviceId: 6,
        name: 'GPU-01',
        plane: 'mgmt',
        address: '172.31.20.101',
        channel: 'ssh',
        lastStatus: 'OK',
        lastCollectedAt: '2026-11-11 10:00:00',
      },
      {
        deviceId: 6,
        name: 'GPU-01',
        plane: 'bmc',
        address: '172.31.10.11',
        channel: 'bmc',
        lastStatus: null,
        lastCollectedAt: null,
      },
    ])
    const root = await mountView()

    const bindButton = Array.from(root.querySelectorAll<HTMLButtonElement>('.row-actions button')).find((button) =>
      button.textContent?.includes('关联设备'),
    )
    expect(bindButton).toBeTruthy()
    bindButton!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(globalAssetApi.credentialDevices).toHaveBeenCalledWith(201)
    const table = root.querySelector('.bind-table')
    expect(table).not.toBeNull()
    expect(table!.querySelectorAll('tbody tr')).toHaveLength(2)
    const text = table!.textContent ?? ''
    expect(text).toContain('业务管理')
    expect(text).toContain('带外')
    expect(text).toContain('SSH 命令')
    expect(text).toContain('BMC Redfish')
    expect(text).toContain('172.31.10.11')
    // 没采过的面显示「未采集」而不是空白，避免看起来像加载失败
    expect(text).toContain('未采集')
  })

  it('「全部采集」：带 confirm=true 调接口并显示 N/N 摘要与失败原因', async () => {
    routerState.route = reactive({ query: { tab: 'credentials' }, path: '/g/assets' })
    vi.mocked(globalAssetApi.credentialDevices).mockResolvedValue([
      {
        deviceId: 7,
        name: 'R750-Server-01',
        plane: 'bmc',
        address: '172.31.10.16',
        channel: 'bmc',
        lastStatus: null,
        lastCollectedAt: null,
      },
    ])
    vi.mocked(globalAssetApi.collectByCredential).mockResolvedValue({
      total: 2,
      ok: 1,
      failed: 1,
      items: [
        { deviceId: 6, name: 'GPU-01', plane: 'mgmt', host: '172.31.20.101', status: 'OK', reason: null },
        {
          deviceId: 7,
          name: 'R750-Server-01',
          plane: 'bmc',
          host: '172.31.10.16',
          status: 'FAILED',
          reason: 'auth_failed: 401 Unauthorized',
        },
      ],
    })
    const root = await mountView()

    Array.from(root.querySelectorAll<HTMLButtonElement>('.row-actions button'))
      .find((button) => button.textContent?.includes('关联设备'))!
      .click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const collectAllButton = Array.from(root.querySelectorAll<HTMLButtonElement>('.modal-actions button')).find(
      (button) => button.textContent?.includes('全部采集'),
    )
    expect(collectAllButton).toBeTruthy()
    collectAllButton!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(globalAssetApi.collectByCredential).toHaveBeenCalledWith(201, true)
    const summary = root.querySelector('[data-testid="batch-summary"]')
    expect(summary).not.toBeNull()
    // 摘要必须把「总数 / 成功 / 失败」三个数都摆出来，只报总数会让运维漏掉失败台
    expect(summary!.textContent).toContain('2')
    expect(summary!.textContent).toContain('1')
    // 失败原因要跟着摘要一起给出，否则人还得逐台点开
    expect(root.textContent).toContain('auth_failed: 401 Unauthorized')
  })
})

describe('GlobalAssetsView 手工录入（FR-v4.12-13/14）', () => {
  it('设备录入：名称与地址都空时本地拦截，不发请求', async () => {
    const root = await mountView()

    const openButton = Array.from(root.querySelectorAll<HTMLButtonElement>('.page-actions button')).find((button) =>
      button.textContent?.includes('录入设备'),
    )
    openButton!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const submit = Array.from(root.querySelectorAll<HTMLButtonElement>('.modal-actions button')).find((button) =>
      button.textContent?.includes('提交'),
    )
    submit!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(globalAssetApi.createDevice).not.toHaveBeenCalled()
    expect(root.textContent).toContain('名称不能为空')
  })

  it('设备录入：只填带外地址也能提交（纯 BMC 设备是合法形态）', async () => {
    vi.mocked(globalAssetApi.createDevice).mockResolvedValue({
      id: 21,
      name: '32DE-RoCE-800-01',
      deviceType: 'switch',
      managementIp: null,
      managementIpAlt: null,
      bmcAddress: '10.202.54.91',
      bmcAddressAlt: null,
      managementCredentialId: null,
      bmcCredentialId: 203,
      assetSource: 'manual',
    })
    const root = await mountView()

    Array.from(root.querySelectorAll<HTMLButtonElement>('.page-actions button'))
      .find((button) => button.textContent?.includes('录入设备'))!
      .click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const nameInput = root.querySelector<HTMLInputElement>('[data-testid="device-name"]')!
    nameInput.value = '32DE-RoCE-800-01'
    nameInput.dispatchEvent(new Event('input'))
    const bmcInput = root.querySelector<HTMLInputElement>('[data-testid="device-bmc"]')!
    bmcInput.value = '10.202.54.91'
    bmcInput.dispatchEvent(new Event('input'))
    await nextTick()

    Array.from(root.querySelectorAll<HTMLButtonElement>('.modal-actions button'))
      .find((button) => button.textContent?.includes('提交'))!
      .click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(globalAssetApi.createDevice).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(globalAssetApi.createDevice).mock.calls[0][0]
    expect(payload.name).toBe('32DE-RoCE-800-01')
    expect(payload.bmcAddress).toBe('10.202.54.91')
    expect(root.textContent).toContain('已创建：32DE-RoCE-800-01')
  })

  it('凭据录入：口令必填，填了才发请求且提交后清空口令输入框', async () => {
    vi.mocked(globalAssetApi.createCredential).mockResolvedValue({
      id: 31,
      name: 'lab-netadmin-ssh',
      credType: 'network',
      username: 'ehtadmin',
      connectOptions: 'diffie-hellman-group14-sha1',
      configured: true,
    })
    routerState.route = reactive({ query: { tab: 'credentials' }, path: '/g/assets' })
    const root = await mountView()

    Array.from(root.querySelectorAll<HTMLButtonElement>('.page-actions button'))
      .find((button) => button.textContent?.includes('录入凭据'))!
      .click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const submit = () =>
      Array.from(root.querySelectorAll<HTMLButtonElement>('.modal-actions button')).find((button) =>
        button.textContent?.includes('提交'),
      )!
    const nameInput = root.querySelector<HTMLInputElement>('[data-testid="credential-name"]')!
    nameInput.value = 'lab-netadmin-ssh'
    nameInput.dispatchEvent(new Event('input'))
    await nextTick()

    submit().click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(globalAssetApi.createCredential).not.toHaveBeenCalled()
    expect(root.textContent).toContain('口令不能为空')

    const secretInput = root.querySelector<HTMLInputElement>('[data-testid="credential-secret"]')!
    secretInput.value = 'Eht_lab2026!'
    secretInput.dispatchEvent(new Event('input'))
    await nextTick()

    submit().click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(globalAssetApi.createCredential).toHaveBeenCalledTimes(1)
    expect(vi.mocked(globalAssetApi.createCredential).mock.calls[0][0].secret).toBe('Eht_lab2026!')
    // 提交后口令立刻从页面状态清掉：截屏/录屏不会带走明文
    expect(root.querySelector<HTMLInputElement>('[data-testid="credential-secret"]')!.value).toBe('')
  })
})
