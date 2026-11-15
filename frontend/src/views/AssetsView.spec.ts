/**
 * `AssetsView` 视图级回归（UX-04b 逐页迁移，第三批）。
 *
 * 这一页比前两页多两件事，都是「构建与类型检查发现不了」的接线：
 * ① 三个页签共用一个 path（`?tab=`），页签一换就要整表换数据源 + 清空选择；
 * ② 端口面板从行内展开改成表格下方的卡片，`端口` 按钮 → `portsDeviceId` → 卡片标题
 *    与端口列表，中间任何一环断了页面都只是「点了没反应」。
 *
 * 只验接线，不重复测 `useSelection` / `useBatchAction` 的内部逻辑。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { executionApi } from '@/api/execution'
import { useConfirmHost } from '@/composables/useConfirm'
import { deviceApi, linkApi, type Link } from '@/api/asset'
import AssetsView from '@/views/AssetsView.vue'

// 工厂内写死数据（vi.mock 提升到 import 之前，引用外部 const 会 TDZ）
vi.mock('@/api/collectProfiles', () => ({
  collectProfileApi: {
    list: vi.fn(async () => []),
    bindGlobal: vi.fn(async () => ({ deviceId: 0, managementCredentialId: null, bmcCredentialId: null })),
    bindProject: vi.fn(async () => ({ deviceId: 0, managementCredentialId: null, bmcCredentialId: null })),
    clearProject: vi.fn(async () => ({ deviceId: 0, managementCredentialId: null, bmcCredentialId: null })),
    history: vi.fn(async () => []),
  },
}))

vi.mock('@/api/asset', () => ({
  deviceApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 1,
          projectId: 1,
          name: 'DUT-A',
          deviceType: 'server',
          model: null,
          vendor: null,
          serialNo: null,
          managementIp: '10.0.0.1',
          location: null,
          rackNo: null,
          domainTag: 'roce',
          enabled: true,
          ownerId: null,
          lastCheckedAt: null,
        },
        {
          id: 2,
          projectId: 1,
          name: 'DUT-B',
          deviceType: 'switch',
          model: null,
          vendor: null,
          serialNo: null,
          managementIp: null,
          location: null,
          rackNo: null,
          domainTag: 'roce',
          enabled: true,
          ownerId: null,
          lastCheckedAt: null,
        },
      ],
      total: 2,
      page: 1,
      size: 10,
    })),
    domains: vi.fn(async () => ['roce']),
    ports: vi.fn(async () => [
      { id: 11, deviceId: 1, portName: 'eth0', portType: 'sfp28', speedGbps: 100 },
    ]),
    create: vi.fn(),
    remove: vi.fn(),
    update: vi.fn(),
    updatePort: vi.fn(),
    deletePort: vi.fn(),
    addPort: vi.fn(),
  },
  linkApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 21,
          projectId: 1,
          name: 'LINK-AB',
          linkType: 'ethernet',
          protocol: 'roce',
          speedGbps: 100,
          mtu: 9000,
          aDeviceId: 1,
          aDeviceName: 'DUT-A',
          aPortId: 11,
          aPortName: 'eth0',
          bDeviceId: 2,
          bDeviceName: 'DUT-B',
          bPortId: null,
          bPortName: null,
          description: null,
          enabled: true,
        },
        {
          // 两端都没登记设备的链路：拓扑里必须显式写「未连接」，
          // 直接留空会让这一格看起来像渲染丢了（AST-02）
          id: 22,
          projectId: 1,
          name: 'LINK-ORPHAN',
          linkType: 'ethernet',
          protocol: null,
          speedGbps: null,
          mtu: null,
          aDeviceId: null,
          aDeviceName: null,
          aPortId: null,
          aPortName: null,
          bDeviceId: null,
          bDeviceName: null,
          bPortId: null,
          bPortName: null,
          description: null,
          enabled: false,
        },
      ],
      total: 2,
      page: 1,
      size: 10,
    })),
    create: vi.fn(),
    remove: vi.fn(),
  },
  credentialApi: {
    list: vi.fn(async () => [
      {
        id: 31,
        projectId: 1,
        name: 'dut-root',
        credType: 'ssh',
        username: 'root',
        configured: true,
        keyVersion: 1,
        lastRotatedAt: null,
      },
    ]),
    create: vi.fn(),
    remove: vi.fn(),
  },
}))

vi.mock('@/api/batch', () => ({
  batchApi: { run: vi.fn(async () => ({ items: [] })) },
}))

// 租约页签（FR-v0.4-09）与强制释放（FR-v0.4-10）：只验接线，不重复后端校验逻辑
vi.mock('@/api/execution', () => ({
  executionApi: {
    listLeases: vi.fn(async () => [
      {
        resourceKey: 'port:DUT-A:eth0',
        runId: 'RUN-LEASE',
        jobId: 7,
        projectId: 1,
        acquiredAt: '2026-11-05T08:00:00Z',
        expiresAt: '2026-11-05T09:00:00Z',
        remainingSeconds: 125,
        state: 'held',
      },
    ]),
    forceReleaseLease: vi.fn(async () => ({
      runId: 'RUN-LEASE',
      runState: 'FAILED',
      releasedLeases: 1,
    })),
  },
}))

// 项目选择器依赖 auth.projects，与本测试无关
vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

// 本页用 useRoute 读 `?tab=`、useRouter 回写；只做最小桩，不装整个 vue-router
const routerMock = vi.hoisted(() => ({
  replace: vi.fn(async () => {}),
  route: { query: { tab: 'devices' } as Record<string, string> },
}))

vi.mock('vue-router', () => ({
  useRoute: () => routerMock.route,
  useRouter: () => ({ replace: routerMock.replace }),
}))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

/** 挂载并等首屏请求落定（load() 是 async，且加载分支里有 Promise.all）。 */
async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(AssetsView)
  // 必须复用 beforeEach 里那个 pinia：重新 createPinia() 会让 projectId 回 0，load() 短路
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await flush()
  return root
}

/** 让 watch → 异步 load → nextTick 的整条链走完。 */
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

function buttonsByText(root: HTMLElement, text: string): HTMLButtonElement[] {
  return [...root.querySelectorAll<HTMLButtonElement>('button')].filter(
    (btn) => btn.textContent?.trim() === text,
  )
}

function clickByText(root: HTMLElement, text: string): void {
  const [first] = buttonsByText(root, text)
  if (!first) throw new Error(`button not found: ${text}`)
  first.click()
}

/** 页签是 `.n-tabs-tab`（不是 button），按标签文字点。 */
function clickTab(
  root: HTMLElement,
  tabKey: 'devices' | 'links' | 'credentials' | 'leases',
): void {
  const label = i18n.global.t(`assets.tabs.${tabKey}`)
  const tab = [...root.querySelectorAll<HTMLElement>('.n-tabs-tab')].find(
    (item) => item.textContent?.trim() === label,
  )
  if (!tab) throw new Error(`tab not found: ${label}`)
  tab.click()
}

beforeEach(() => {
  routerMock.route.query = { tab: 'devices' }
  routerMock.replace.mockClear()
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  // 调用记录跨用例累积会让「调用几次」的断言全部错位（租约强制释放就踩过）
  vi.clearAllMocks()
  app = null
  host = null
  pinia = null
})

describe('AssetsView 设备页', () => {
  it('渲染设备行，空值列显示统一占位而不是空白', async () => {
    const root = await mountView()
    expect(root.textContent).toContain('DUT-A')
    expect(root.textContent).toContain('DUT-B')
    // model 为 null：必须有占位符，否则整列看起来像渲染失败
    expect(root.textContent).toContain('—')
  })

  it('勾一行出现批量条，计数为 1', async () => {
    const root = await mountView()
    const boxes = [...root.querySelectorAll<HTMLElement>('.n-checkbox')]
    // 1 个表头 + 2 行
    expect(boxes).toHaveLength(3)

    boxes[1].click()
    await nextTick()
    expect(root.textContent).toContain(i18n.global.t('cases.batch.selected', { count: 1 }))
  })

  it('端口按钮打开下方卡片，含设备名与端口行', async () => {
    const root = await mountView()
    // 未点击前没有端口卡片
    expect(root.querySelector('.port-card')).toBeNull()

    clickByText(root, i18n.global.t('assets.device.ports'))
    await flush()

    const card = root.querySelector('.port-card')
    expect(card).not.toBeNull()
    expect(card?.textContent).toContain('DUT-A')
    expect(card?.textContent).toContain('eth0')
    expect(card?.textContent).toContain('100')
  })

  it('再点一次收起端口卡片', async () => {
    const root = await mountView()
    clickByText(root, i18n.global.t('assets.device.ports'))
    await flush()
    expect(root.querySelector('.port-card')).not.toBeNull()

    clickByText(root, i18n.global.t('assets.device.hidePorts'))
    await flush()
    expect(root.querySelector('.port-card')).toBeNull()
  })
})

describe('AssetsView 页签切换', () => {
  it('切到链路页签后换数据源，且设备表不再渲染', async () => {
    const root = await mountView()
    expect(root.textContent).toContain('DUT-A')

    clickTab(root, 'links')
    await flush()

    expect(root.textContent).toContain('LINK-AB')    // A 端拼装成「设备 / 端口」
    expect(root.textContent).toContain('DUT-A / eth0')
    // B 端只有设备名，不能拼出悬空的「 / 」
    expect(root.textContent).toContain('DUT-B')
    expect(root.textContent).not.toContain('DUT-B /')
  })

  it('切页签会清空上一页的勾选', async () => {
    const root = await mountView()
    const boxes = [...root.querySelectorAll<HTMLElement>('.n-checkbox')]
    boxes[1].click()
    await nextTick()
    expect(root.textContent).toContain(i18n.global.t('cases.batch.selected', { count: 1 }))

    clickTab(root, 'credentials')
    await flush()

    // 凭据 id(31) 若没清掉，会带着旧 id 去发表单外的批量删除请求
    expect(root.textContent).not.toContain(i18n.global.t('cases.batch.selected', { count: 1 }))
  })
})

describe('AssetsView 链路拓扑（AST-02）', () => {
  it('拓扑按 A 端 → 链路 → B 端渲染，表格让位给拓扑', async () => {
    const root = await mountView()
    clickTab(root, 'links')
    await flush()
    expect(root.querySelector('.n-data-table')).not.toBeNull()

    clickByText(root, i18n.global.t('assets.link.topologyView'))
    await flush()

    const links = [...root.querySelectorAll<HTMLElement>('.topo-link')]
    expect(links).toHaveLength(2)

    const [first] = links
    expect(first.textContent).toContain('DUT-A')
    expect(first.textContent).toContain('eth0')
    expect(first.textContent).toContain('LINK-AB')
    expect(first.textContent).toContain('DUT-B')
    // B 端没有端口，不能渲染出一个空的行高
    const nodes = [...first.querySelectorAll<HTMLElement>('.topo-node')]
    expect(nodes).toHaveLength(2)
    expect(nodes[0].querySelector('.topo-node-port')).not.toBeNull()
    expect(nodes[1].querySelector('.topo-node-port')).toBeNull()

    // 同一份数据不能同时以表格和拓扑出现两遍
    expect(root.querySelector('.n-data-table')).toBeNull()
  })

  it('两端都没登记设备的链路显示「未连接」，切回表格可复原', async () => {
    const root = await mountView()
    clickTab(root, 'links')
    await flush()
    clickByText(root, i18n.global.t('assets.link.topologyView'))
    await flush()

    const orphan = root.querySelector<HTMLElement>('[data-link="LINK-ORPHAN"]')
    expect(orphan).not.toBeNull()
    // 两端各一处占位：留空会让这一格看起来像渲染失败
    expect(orphan?.textContent?.split(i18n.global.t('assets.link.unconnected')).length).toBe(3)

    clickByText(root, i18n.global.t('assets.link.tableView'))
    await flush()
    expect(root.querySelector('.topo-link')).toBeNull()
    expect(root.querySelector('.n-data-table')).not.toBeNull()
  })

  /** 造一条两端都登记了设备的链路（拓扑用例只关心两端设备 id / 名称）。 */
  function hop(id: number, name: string, a: [number, string], b: [number, string]): Link {
    return {
      id,
      projectId: 1,
      name,
      linkType: 'ethernet',
      protocol: 'roce',
      speedGbps: 100,
      mtu: 9000,
      aDeviceId: a[0],
      aDeviceName: a[1],
      aPortId: null,
      aPortName: null,
      bDeviceId: b[0],
      bDeviceName: b[1],
      bPortId: null,
      bPortName: null,
      description: null,
      enabled: true,
    }
  }

  it('三跳链路串成一条链，节点按 A→B→C→D 排开且边不丢', async () => {
    vi.mocked(linkApi.page).mockResolvedValueOnce({
      items: [
        hop(41, 'L1', [1, 'DUT-A'], [2, 'SW-01']),
        hop(42, 'L2', [2, 'SW-01'], [3, 'SW-02']),
        hop(43, 'L3', [3, 'SW-02'], [4, 'HOST-01']),
      ],
      total: 3,
      page: 1,
      size: 10,
    })
    const root = await mountView()
    clickTab(root, 'links')
    await flush()
    clickByText(root, i18n.global.t('assets.link.topologyView'))
    await flush()

    // 三条链路共用中间设备，必须合成一行；按链路逐行画等于把一条链拆成三张断图
    const chains = [...root.querySelectorAll<HTMLElement>('.topo-link')]
    expect(chains).toHaveLength(1)
    expect(chains[0].dataset.link).toBe('L1 → L2 → L3')

    const names = [...chains[0].querySelectorAll<HTMLElement>('.topo-node-name')].map(
      (node) => node.textContent?.trim(),
    )
    expect(names).toEqual(['DUT-A', 'SW-01', 'SW-02', 'HOST-01'])
    // 4 个节点之间恰好 3 条边
    expect(chains[0].querySelectorAll('.topo-edge')).toHaveLength(3)
  })

  it('分叉时每条链路仍各渲染一次，不会被主干吞掉', async () => {
    vi.mocked(linkApi.page).mockResolvedValueOnce({
      items: [
        hop(51, 'AB', [1, 'DUT-A'], [2, 'HOST-B']),
        hop(52, 'AC', [1, 'DUT-A'], [3, 'HOST-C']),
        hop(53, 'AD', [1, 'DUT-A'], [4, 'HOST-D']),
      ],
      total: 3,
      page: 1,
      size: 10,
    })
    const root = await mountView()
    clickTab(root, 'links')
    await flush()
    clickByText(root, i18n.global.t('assets.link.topologyView'))
    await flush()

    // 从 A 出发一次只能走一条路：剩下的支路另起一行，合计边数必须等于链路数
    const edges = [...root.querySelectorAll<HTMLElement>('.topo-edge-name')].map(
      (edge) => edge.textContent?.trim(),
    )
    expect(edges.sort()).toEqual(['AB', 'AC', 'AD'])
    // 每条链的节点数 = 边数 + 1，没有节点凭空多出来或漏掉
    for (const chain of root.querySelectorAll<HTMLElement>('.topo-link')) {
      expect(chain.querySelectorAll('.topo-node')).toHaveLength(
        chain.querySelectorAll('.topo-edge').length + 1,
      )
    }
  })
})

describe('AssetsView 租约页', () => {
  it('切到租约页签后列出占用资源与剩余时间', async () => {
    const root = await mountView()
    clickTab(root, 'leases')
    await flush()

    expect(root.textContent).toContain('port:DUT-A:eth0')
    expect(root.textContent).toContain('RUN-LEASE')
    // 125 秒要说人话：2m 5s，而不是裸露的 125
    expect(root.textContent).toContain('2m 5s')
    // 只读视图：没有「新建」，也没有搜索框
    expect(buttonsByText(root, i18n.global.t('common.create'))).toHaveLength(0)
    expect(root.querySelector('input.n-input__input-el')).toBeNull()
  })

  it('非管理员看不到强制释放按钮', async () => {
    const root = await mountView()
    clickTab(root, 'leases')
    await flush()
    expect(buttonsByText(root, i18n.global.t('assets.lease.forceRelease'))).toHaveLength(0)
  })

  it('管理员强制释放以 runId 作二次确认，成功后刷新列表', async () => {
    useAuthStore().user = {
      id: 1,
      username: 'admin',
      displayName: 'admin',
      email: null,
      orgId: 1,
      enabled: true,
      passwordExpiresAt: null,
      lastLoginAt: null,
      roles: ['admin'],
    }
    const root = await mountView()
    clickTab(root, 'leases')
    await flush()

    clickByText(root, i18n.global.t('assets.lease.forceRelease'))
    await nextTick()
    // confirmAction 是模块级单例：直接结算「确定」，不等 Modal 挂载
    useConfirmHost().settle(true)
    await flush()

    expect(executionApi.forceReleaseLease).toHaveBeenCalledWith(
      1,
      'port:DUT-A:eth0',
      'RUN-LEASE',
    )
    // 释放后必须重新拉列表：留着已释放的行等于让操作员以为没生效
    expect(executionApi.listLeases).toHaveBeenCalledTimes(2)
  })

  it('取消二次确认时一个请求都不发', async () => {
    useAuthStore().user = {
      id: 1,
      username: 'admin',
      displayName: 'admin',
      email: null,
      orgId: 1,
      enabled: true,
      passwordExpiresAt: null,
      lastLoginAt: null,
      roles: ['admin'],
    }
    const root = await mountView()
    clickTab(root, 'leases')
    await flush()

    clickByText(root, i18n.global.t('assets.lease.forceRelease'))
    await nextTick()
    useConfirmHost().settle(false)
    await flush()

    expect(executionApi.forceReleaseLease).not.toHaveBeenCalled()
  })
})

describe('AssetsView 凭据页', () => {
  it('「已配置」走 StatusTag，图标与文字双编码', async () => {
    const root = await mountView()
    clickTab(root, 'credentials')
    await flush()

    const tags = [...root.querySelectorAll<HTMLElement>('.status-tag')]
    expect(tags).toHaveLength(1)
    expect(tags[0].textContent).toContain(i18n.global.t('common.yes'))
    expect(tags[0].querySelector('svg')).not.toBeNull()
  })

  it('搜索在前端过滤（后端 /credentials 不支持关键词）', async () => {
    const root = await mountView()
    clickTab(root, 'credentials')
    await flush()
    expect(root.textContent).toContain('dut-root')

    const input = root.querySelector<HTMLInputElement>('input.n-input__input-el')
    if (!input) throw new Error('search input not found')
    input.value = 'zzz'
    input.dispatchEvent(new Event('input'))
    await flush()

    // 搜不到就必须真的空掉：留着旧行等于「输入了但没反应」
    expect(root.textContent).not.toContain('dut-root')
  })
})

describe('AssetsView W62-11/12/13 授权来源与端口 CRUD', () => {
  function submit(form: HTMLFormElement): void {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  }

  it('私有设备「来源」列标注本项目', async () => {
    const root = await mountView()
    expect(root.textContent).toContain(i18n.global.t('w62.sourceProject'))
  })

  it('平台共享资产标注「公共资产（已授权）」', async () => {
    vi.mocked(deviceApi.page).mockResolvedValueOnce({
      items: [{ id: 9, projectId: 99, name: 'SHARED-1' }],
      total: 1,
      page: 1,
      size: 10,
    } as never)
    const root = await mountView()
    expect(root.textContent).toContain('SHARED-1')
    expect(root.textContent).toContain(i18n.global.t('w62.sourcePlatform'))
  })

  it('点设备编辑 → 提交走 update 而不是 create', async () => {
    const root = await mountView()
    root.querySelector<HTMLButtonElement>('[data-testid="device-edit-1"]')!.click()
    await nextTick()
    const form = root.querySelector<HTMLFormElement>('[data-testid="device-form"]')!
    expect(form.textContent).toContain(i18n.global.t('w62.saveDevice'))
    submit(form)
    await flush()

    expect(deviceApi.update).toHaveBeenCalledWith(
      1,
      1,
      expect.objectContaining({ name: 'DUT-A', managementIp: '10.0.0.1' }),
    )
    expect(deviceApi.create).not.toHaveBeenCalled()
  })

  it('点端口编辑 → 表单带出原值 → 提交走 updatePort', async () => {
    const root = await mountView()
    clickByText(root, i18n.global.t('assets.device.ports'))
    await flush()
    root.querySelector<HTMLButtonElement>('[data-testid="port-edit-11"]')!.click()
    await nextTick()
    const form = root.querySelector<HTMLFormElement>('form.port-form')!
    submit(form)
    await flush()

    expect(deviceApi.updatePort).toHaveBeenCalledWith(
      1,
      11,
      expect.objectContaining({ portName: 'eth0' }),
    )
    expect(deviceApi.addPort).not.toHaveBeenCalled()
  })

  it('删被引用端口：409 文案点名链路，不是笼统的「删除失败」', async () => {
    vi.mocked(deviceApi.deletePort).mockRejectedValueOnce(
      new Error('端口被链路引用，不能删除：上联-A；请先解除引用'),
    )
    const root = await mountView()
    clickByText(root, i18n.global.t('assets.device.ports'))
    await flush()
    root.querySelector<HTMLButtonElement>('[data-testid="port-delete-11"]')!.click()
    await nextTick()
    useConfirmHost().settle(true)
    await flush()

    const line = root.querySelector('[data-testid="port-delete-error"]')!.textContent!
    expect(line).toContain('上联-A')
    expect(line).toContain('请先解除引用')
  })

  it('取消删除确认：不调用删除接口', async () => {
    const root = await mountView()
    clickByText(root, i18n.global.t('assets.device.ports'))
    await flush()
    root.querySelector<HTMLButtonElement>('[data-testid="port-delete-11"]')!.click()
    await nextTick()
    useConfirmHost().settle(false)
    await flush()
    expect(deviceApi.deletePort).not.toHaveBeenCalled()
  })
})
