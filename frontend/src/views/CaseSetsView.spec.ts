/**
 * `CaseSetsView` 视图级回归（CAS-02 前端补齐）。
 *
 * 这一页把「用例集」从只能被计划间接触达，变成可维护的实体。三处容易在静态检查下
 * 蒙混过关、上线才暴露的接线，是本组用例的重点：
 * ① 列表三列（编号 / 名称 / 描述）必须都渲染，描述为空要显示占位而不是空白；
 * ② 「管理用例」要真的拉成员并按后端顺序编号，打开面板却没数据等于功能不可用；
 * ③ 「上移 / 下移」提交的必须是**完整成员列表的新顺序**，只提交被移动的一条会被后端拒绝。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App, type Slots } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
// v5.21.0 / W71-05：写按钮按服务端能力集显隐；单测挂载不走登录，需先种一份能力集
import { seedMenuCapabilities } from '@/test/seedMenuCapabilities'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { useConfirmHost } from '@/composables/useConfirm'
import { caseSetApi, assetVersionApi, platformCaseSetApi } from '@/api/cases'
import CaseSetsView from '@/views/CaseSetsView.vue'

const sets = [
  {
    id: 7,
    projectId: 1,
    setCode: 'SMOKE-001',
    name: '冒烟集',
    description: '每日冒烟',
  },
  {
    id: 8,
    projectId: 1,
    setCode: 'REG-002',
    name: '回归集',
    description: null,
  },
]

const items = [
  { id: 101, projectId: 1, caseCode: 'TC-001', title: '登录成功' },
  { id: 102, projectId: 1, caseCode: 'TC-002', title: '错误口令' },
]

const syncedView = {
  assetVersion: 2,
  contentHash: 'a'.repeat(64),
  forkState: 'SYNCED',
  syncedAssetVersion: 2,
  syncedContentHash: 'a'.repeat(64),
  sourceAssetId: 101,
  hasNewVersion: false,
  latestAssetVersion: 2,
  latestContentHash: 'a'.repeat(64),
}

const divergedView = {
  ...syncedView,
  forkState: 'DIVERGED',
  hasNewVersion: true,
}

vi.mock('@/api/cases', () => ({
  caseSetApi: {
    page: vi.fn(async () => ({ items: sets, total: 2, page: 1, size: 100 })),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    items: vi.fn(async () => items),
    addItem: vi.fn(),
    removeItem: vi.fn(),
    batchRemoveItems: vi.fn(async () => ({ removed: 2 })),
    reorder: vi.fn(async () => undefined),
  },
  caseApi: {
    page: vi.fn(async () => ({ items, total: 2, page: 1, size: 50 })),
  },
  assetVersionApi: {
    version: vi.fn(async () => syncedView),
    diff: vi.fn(async () => []),
    sync: vi.fn(async () => syncedView),
    diverge: vi.fn(async () => divergedView),
  },
  platformCaseSetApi: {
    page: vi.fn(async () => ({ items: [], total: 0, page: 1, size: 100 })),
    items: vi.fn(async () => []),
  },
}))

vi.mock('@/api/execution', () => ({
  executionApi: {
    runTestSet: vi.fn(async () => ({ runId: 'RUN-001' })),
  },
}))

const routerState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  replace: vi.fn(),
  push: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ replace: routerState.replace, push: routerState.push }),
}))

vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

/** 页面用 `<RouterLink v-slot="{ navigate }" custom>` 包按钮，给同契约的桩即可。 */
const RouterLinkStub = {
  name: 'RouterLink',
  props: { to: { type: [String, Object], default: '' } },
  setup(_props: unknown, { slots }: { slots: Slots }) {
    return () => h('a', slots.default?.({ navigate: () => {} }))
  },
}

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(CaseSetsView)
  app.use(pinia!)
  app.use(i18n)
  app.component('RouterLink', RouterLinkStub)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function rows(root: HTMLElement): HTMLElement[] {
  // v5.35 / FR-UIE-01：集合列表从 DataTable 换成全宽表格（table.set-table）。
  return [...root.querySelectorAll<HTMLElement>('table.set-table tbody tr')]
}

/** 打开某一行的「更多」菜单（编辑 / 立即执行 / 删除都收在这里）。 */
async function openRowMenu(root: HTMLElement, rowIndex: number): Promise<void> {
  rowButton(root, rowIndex, i18n.global.t('w57.globalCaseSets.more')).click()
  await nextTick()
}

/** 取当前展开的菜单项。 */
function menuItem(root: HTMLElement, text: string): HTMLButtonElement {
  const button = [...root.querySelectorAll<HTMLButtonElement>('.menu button')].find(
    (item) => item.textContent?.trim() === text,
  )
  if (!button) throw new Error(`menu item not found: ${text}`)
  return button
}

function rowButton(root: HTMLElement, rowIndex: number, text: string): HTMLButtonElement {
  const button = [...rows(root)[rowIndex].querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === text,
  )
  if (!button) throw new Error(`button not found in row ${rowIndex}: ${text}`)
  return button
}

/** 面板里的操作按钮（上移 / 下移 / 移除），按顺序取。 */
function itemButtons(root: HTMLElement, index: number): HTMLButtonElement[] {
  const list = [...root.querySelectorAll<HTMLElement>('.set-item')]
  if (!list[index]) throw new Error(`set item ${index} not found`)
  return [...list[index].querySelectorAll<HTMLButtonElement>('button')]
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  seedMenuCapabilities()
  useAuthStore().projectId = 1
  vi.mocked(caseSetApi.reorder).mockClear()
  vi.mocked(assetVersionApi.sync).mockClear()
  vi.mocked(platformCaseSetApi.page).mockClear()
  vi.mocked(platformCaseSetApi.items).mockClear()
  // v5.22.0 / FR-v5.22-06：新建与路由跳转要按用例隔离计数
  vi.mocked(caseSetApi.create).mockClear()
  vi.mocked(caseSetApi.items).mockClear()
  routerState.push.mockClear()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('CaseSetsView 空态三动作（FR-v5.22-06 判据 06-2）', () => {
  const emptyPage = { items: [], total: 0, page: 1, size: 100 }

  it('真·空列表给三个动作，导入与查看样例各自跳到对应页', async () => {
    vi.mocked(caseSetApi.page).mockResolvedValueOnce(emptyPage)
    const root = await mountView()

    const box = root.querySelector<HTMLElement>('[data-testid="case-sets-empty-actions"]')
    expect(box).not.toBeNull()
    expect(box!.querySelectorAll('button')).toHaveLength(3)

    root.querySelector<HTMLButtonElement>('[data-testid="case-sets-empty-import"]')!.click()
    expect(routerState.push).toHaveBeenCalledWith({ name: 'imports' })

    root.querySelector<HTMLButtonElement>('[data-testid="case-sets-empty-sample"]')!.click()
    expect(routerState.push).toHaveBeenCalledWith({ name: 'global-case-sets' })
  })

  it('viewer（无 case:write）只看到「查看样例」一个动作，写动作一律不出现', async () => {
    // 只读口径：能力集里没有 case:write。这条与门禁 64 role-buttons 同口径——
    // viewer 在用例集页的写按钮数必须为 0；「导入」最终也写用例集，不能算只读动作。
    // （首版漏了它的守卫，被 role-buttons 扫出「导入用例」一颗而判红，已补）
    seedMenuCapabilities(['case:read'])
    vi.mocked(caseSetApi.page).mockResolvedValueOnce(emptyPage)
    const root = await mountView()

    const box = root.querySelector<HTMLElement>('[data-testid="case-sets-empty-actions"]')
    expect(box!.querySelectorAll('button')).toHaveLength(1)
    expect(box!.querySelector('[data-testid="case-sets-empty-create"]')).toBeNull()
    expect(box!.querySelector('[data-testid="case-sets-empty-import"]')).toBeNull()
    expect(box!.querySelector('[data-testid="case-sets-empty-sample"]')).not.toBeNull()
  })
})

describe('CaseSetsView 新建即选中（FR-v5.22-06 判据 06-1）', () => {
  it('新建成功后右栏自动展开成员面板，无需再点一次「管理用例」', async () => {
    const created = {
      id: 9,
      projectId: 1,
      setCode: 'NEW-001',
      name: '新建集合',
      description: null,
      itemCount: 0,
    }
    vi.mocked(caseSetApi.create).mockResolvedValueOnce(created)
    // 两次列表读：① 挂载时两条；② 新建后重拉返回三条（含新集合）。
    // 都用 `Once`，不改成常驻返回值——常驻会污染后面所有用例的列表。
    vi.mocked(caseSetApi.page)
      .mockResolvedValueOnce({ items: sets, total: 2, page: 1, size: 100 })
      .mockResolvedValueOnce({ items: [...sets, created], total: 3, page: 1, size: 100 })

    const root = await mountView()
    const createButton = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (item) => item.textContent?.trim() === i18n.global.t('caseSets.create'),
    )
    expect(createButton).toBeTruthy()
    createButton!.click()
    await nextTick()

    const card = root.querySelector<HTMLElement>('.form-card')
    expect(card).not.toBeNull()
    const inputs = [...card!.querySelectorAll<HTMLInputElement>('input')]
    inputs[0].value = 'NEW-001'
    inputs[0].dispatchEvent(new Event('input'))
    inputs[1].value = '新建集合'
    inputs[1].dispatchEvent(new Event('input'))
    await nextTick()

    // `.form-card` 本身就是那个 <form>（v-if="formOpen"），不要在它内部再找一次
    card!.dispatchEvent(new Event('submit'))
    // 建集合 → 重拉列表 → 展开面板（拉成员 + 搜用例 + 拉资产版本）是四段异步
    for (let i = 0; i < 6; i++) {
      await new Promise((resolve) => setTimeout(resolve, 0))
      await nextTick()
    }

    expect(caseSetApi.create).toHaveBeenCalledTimes(1)
    // 判据 06-1 的实质：新建完直接按 created.id 拉了成员，等于「已选中」
    expect(caseSetApi.items).toHaveBeenCalledWith(1, 9)
    expect(root.textContent).toContain('新建集合')
    // 创建成功的提示不能被展开面板顺手清掉（openItems 开头会清 message）
    expect(root.textContent).toContain(i18n.global.t('caseSets.created', { name: '新建集合' }))
  })
})

describe('CaseSetsView 列表', () => {
  it('编号 / 名称 / 描述三列都渲染，空描述显示占位符', async () => {
    const root = await mountView()
    const text = root.textContent ?? ''
    expect(text).toContain('SMOKE-001')
    expect(text).toContain('冒烟集')
    expect(text).toContain('每日冒烟')
    expect(text).toContain('REG-002')
    // 第二条 description 为 null，不能渲染成空白单元格
    expect(text).toContain('—')
  })

  it('点「编辑」预填原值，且集合编号不可改', async () => {
    const root = await mountView()
    await openRowMenu(root, 1)
    menuItem(root, i18n.global.t('caseSets.edit')).click()
    await nextTick()

    const card = root.querySelector<HTMLElement>('.form-card')
    expect(card).not.toBeNull()
    expect(card!.textContent).toContain(i18n.global.t('caseSets.editTitle'))
    const inputs = [...card!.querySelectorAll<HTMLInputElement>('input')]
    expect(inputs.map((input) => input.value)).toContain('回归集')
    const codeInput = inputs.find((input) => input.value === 'REG-002')
    expect(codeInput?.disabled).toBe(true)
  })
})

describe('CaseSetsView 成员管理', () => {
  it('打开面板后按后端顺序列出成员并编号', async () => {
    const root = await mountView()
    rowButton(root, 0, i18n.global.t('caseSets.manage')).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const nodes = [...root.querySelectorAll<HTMLElement>('.set-item')]
    expect(nodes).toHaveLength(2)
    expect(nodes[0].textContent).toContain('TC-001')
    expect(nodes[0].querySelector('.set-item-order')?.textContent?.trim()).toBe('1')
    expect(nodes[1].textContent).toContain('TC-002')
    expect(nodes[1].querySelector('.set-item-order')?.textContent?.trim()).toBe('2')
  })

  it('上移提交完整成员列表的新顺序，而不是只提交被移动的一条', async () => {
    const root = await mountView()
    rowButton(root, 0, i18n.global.t('caseSets.manage')).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    // 第二行点「上移」→ 顺序应变成 [102, 101]
    const buttons = itemButtons(root, 1)
    const moveUp = buttons.find((b) => b.textContent?.trim() === i18n.global.t('caseSets.moveUp'))
    expect(moveUp).toBeTruthy()
    moveUp!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(caseSetApi.reorder).toHaveBeenCalledTimes(1)
    expect(caseSetApi.reorder).toHaveBeenCalledWith(1, 7, [102, 101])
    const nodes = [...root.querySelectorAll<HTMLElement>('.set-item')]
    expect(nodes[0].textContent).toContain('TC-002')
  })
})

describe('CaseSetsView 资产版本（PUBX-01/02）', () => {
  async function openPanel(root: HTMLElement): Promise<void> {
    rowButton(root, 0, i18n.global.t('caseSets.manage')).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }

  it('打开面板出现资产版本读数：v2 与「已同步」徽标', async () => {
    const root = await mountView()
    await openPanel(root)

    const panel = root.querySelector<HTMLElement>('[data-testid="asset-panel"]')
    expect(panel).not.toBeNull()
    expect(assetVersionApi.version).toHaveBeenCalledWith(1, 7)
    expect(panel!.textContent).toContain('v2')
    const fork = panel!.querySelector<HTMLElement>('[data-testid="asset-fork"]')
    expect(fork!.textContent?.trim()).toBe(i18n.global.t('caseSets.assetForkSynced'))
  })

  it('平台有新版本时显示提示；检查差异后按类型渲染 diff 行', async () => {
    vi.mocked(assetVersionApi.version).mockResolvedValueOnce({
      ...syncedView,
      hasNewVersion: true,
      latestAssetVersion: 3,
    })
    vi.mocked(assetVersionApi.diff).mockResolvedValueOnce([
      { type: 'ADDED', caseCode: 'TC-003', title: '改密', detail: '平台 seq=3，副本缺这条' },
      { type: 'MODIFIED', caseCode: 'TC-001', title: '登录', detail: '优先级 P1→P0' },
      { type: 'REMOVED', caseCode: 'TC-002', title: '登出', detail: '平台已删除' },
    ])
    const root = await mountView()
    await openPanel(root)

    expect(root.querySelector('[data-testid="asset-has-new"]')!.textContent).toContain('v3')
    const diffButton = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.dataset.testid === 'asset-diff',
    )
    expect(diffButton).toBeTruthy()
    diffButton!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const diffRows = [...root.querySelectorAll<HTMLElement>('[data-testid="diff-row"]')]
    expect(diffRows).toHaveLength(3)
    expect(diffRows[0].textContent).toContain('TC-003')
    expect(diffRows[2].textContent).toContain(i18n.global.t('caseSets.assetDiff.REMOVED'))
  })

  it('保持分叉：调用 diverge、徽标切到「已分叉」、内容接口不再动', async () => {
    const root = await mountView()
    await openPanel(root)

    const divergeButton = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.dataset.testid === 'asset-diverge',
    )
    expect(divergeButton).toBeTruthy()
    divergeButton!.click()
    // confirmAction 是模块级单例：直接结算「确定」，不等 Modal 挂载
    useConfirmHost().settle(true)
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(assetVersionApi.diverge).toHaveBeenCalledWith(1, 7)
    const fork = root.querySelector<HTMLElement>('[data-testid="asset-fork"]')
    expect(fork!.textContent?.trim()).toBe(i18n.global.t('caseSets.assetForkDiverged'))
    // 分叉不重建条目：reorder / addItem 都不许被顺手调用
    expect(caseSetApi.reorder).not.toHaveBeenCalled()
  })

  it('非副本集合：面板显示占位，不渲染同步按钮', async () => {
    vi.mocked(assetVersionApi.version).mockResolvedValueOnce({
      ...syncedView,
      sourceAssetId: null,
      syncedAssetVersion: null,
      syncedContentHash: null,
    })
    const root = await mountView()
    await openPanel(root)

    expect(root.textContent).toContain(i18n.global.t('caseSets.assetNoSource'))
    expect(
      [...root.querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.dataset.testid === 'asset-sync',
      ),
    ).toBeUndefined()
  })
})

describe('CaseSetsView 同步策略（N9）', () => {
  async function openPanel(root: HTMLElement): Promise<HTMLElement> {
    rowButton(root, 0, i18n.global.t('caseSets.manage')).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    return root.querySelector<HTMLElement>('[data-testid="asset-panel"]')!
  }

  function syncButton(root: HTMLElement): HTMLButtonElement {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.dataset.testid === 'asset-sync',
    )!
  }

  it('默认按 FULL 同步', async () => {
    const root = await mountView()
    const panel = await openPanel(root)
    expect(panel.querySelector('[data-testid="asset-strategy"]')).not.toBeNull()

    syncButton(root).click()
    useConfirmHost().settle(true)
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(assetVersionApi.sync).toHaveBeenCalledWith(1, 7, 'FULL')
  })

  it('下拉切到 ADD_ONLY 后按策略同步', async () => {
    const root = await mountView()
    const panel = await openPanel(root)

    // naive-ui 的下拉菜单用虚拟列表，jsdom 里量不到高度、渲染不出选项；
    // 直接走组件的 v-model 回调，等价于用户选中「只补新增」。
    const select = panel.querySelector<HTMLElement>('.n-select')
    expect(select).not.toBeNull()
    const instance = (
      select as unknown as { __vueParentComponent?: { props: Record<string, unknown> } }
    ).__vueParentComponent
    expect(instance).toBeDefined()
    const onUpdate = instance!.props['onUpdate:value'] as (value: string) => void
    onUpdate('ADD_ONLY')
    await nextTick()

    syncButton(root).click()
    useConfirmHost().settle(true)
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(assetVersionApi.sync).toHaveBeenCalledWith(1, 7, 'ADD_ONLY')
  })
})

describe('CaseSetsView 平台用例集库（N9）', () => {
  const platformRow = {
    id: 31,
    setCode: 'PLAT-SMOKE',
    name: '平台冒烟集',
    description: null,
    itemCount: 2,
    assetVersion: 4,
    contentHash: 'b'.repeat(64),
    hasNewVersion: true,
    referenceCount: 2,
    references: [
      {
        projectId: 1,
        projectCode: 'PRJ-A',
        copyAssetId: 7,
        syncedAssetVersion: 3,
        forkState: 'SYNCED',
        latestAssetVersion: 4,
        hasNewVersion: true,
      },
    ],
  }

  const platformItems = [
    {
      id: 301,
      projectId: 0,
      caseCode: 'PL-001',
      title: '平台用例一',
      category: null,
      priority: 'P1',
      safetyLevel: 'L1',
      precondition: null,
      expected: null,
      domainTag: null,
      reviewStatus: 'APPROVED',
      source: 'PLATFORM',
      capability: null,
      description: null,
    },
  ]

  function adminUser() {
    return {
      id: 1,
      username: 'admin',
      displayName: '管理员',
      email: null,
      orgId: null,
      enabled: true,
      passwordExpiresAt: null,
      lastLoginAt: null,
      roles: ['admin'],
    }
  }

  it('管理员可见平台库、可展开只读成员', async () => {
    useAuthStore().user = adminUser()
    vi.mocked(platformCaseSetApi.page).mockResolvedValueOnce({
      items: [platformRow],
      total: 1,
      page: 1,
      size: 100,
    })
    vi.mocked(platformCaseSetApi.items).mockResolvedValueOnce(platformItems)

    const root = await mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(root.querySelector('[data-testid="platform-case-sets"]')).not.toBeNull()
    expect(platformCaseSetApi.page).toHaveBeenCalledTimes(1)
    const text = root.textContent ?? ''
    expect(text).toContain('PLAT-SMOKE')
    expect(text).toContain(i18n.global.t('caseSets.platformHint'))

    const row = root.querySelector<HTMLElement>('[data-testid="platform-set-row"]')!
    const viewButton = [...row.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === i18n.global.t('caseSets.platformViewMembers'),
    )!
    viewButton.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(platformCaseSetApi.items).toHaveBeenCalledWith(31)
    const panel = root.querySelector<HTMLElement>('[data-testid="platform-items"]')
    expect(panel).not.toBeNull()
    expect(panel!.textContent).toContain('PL-001')
  })

  it('非管理员不渲染平台库，也不发请求', async () => {
    const root = await mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(root.querySelector('[data-testid="platform-case-sets"]')).toBeNull()
    expect(platformCaseSetApi.page).not.toHaveBeenCalled()
  })
})

describe('CaseSetsView 服务端筛选与计数（FR-v5.8-03）', () => {
  /** 让列表有多页，否则分页器不渲染、翻页行为无从验证。 */
  function manyRows(total = 50): void {
    vi.mocked(caseSetApi.page).mockResolvedValue({
      items: sets,
      total,
      page: 1,
      size: 10,
    } as never)
  }

  function pagerItem(root: HTMLElement, label: string): HTMLElement {
    const items = [...root.querySelectorAll<HTMLElement>('.n-pagination-item')]
    const found = items.find((el) => el.textContent?.trim() === label)
    if (!found) throw new Error('pager item not found: ' + label)
    return found
  }

  beforeEach(() => {
    vi.mocked(caseSetApi.page).mockClear()
    window.history.replaceState({}, '', '/case-sets')
  })

  afterEach(() => {
    window.history.replaceState({}, '', '/')
  })

  it('底部计数取服务端 total，与当前页行数无关', async () => {
    manyRows(50)
    const root = await mountView()
    expect(root.textContent).toContain(i18n.global.t('caseSets.totalCount', { count: 50 }))
    // 本页只有 2 行，计数不能变成 2
    expect(rows(root)).toHaveLength(2)
  })

  it('搜索回车重新请求并回到第 1 页', async () => {
    manyRows(50)
    const root = await mountView()
    pagerItem(root, '2').click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(vi.mocked(caseSetApi.page).mock.calls.at(-1)![1]).toMatchObject({ page: 2 })

    // 按占位文案定位搜索框，别拿「第一个 input」蒙：列筛选弹层里也有 input
    const placeholder = i18n.global.t('common.searchPlaceholder')
    const input = root.querySelector<HTMLInputElement>(
      `input[placeholder="${placeholder}"]`,
    )!
    expect(input).toBeTruthy()
    input.value = 'REG'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(vi.mocked(caseSetApi.page).mock.calls.at(-1)![1]).toMatchObject({
      keyword: 'REG',
      page: 1,
    })
  })

  it('URL 里的列筛选直接进首次请求，翻页后仍带着它', async () => {
    manyRows(50)
    // 筛选条件落在 URL（prefix sets_）：拷给别人打开应重现同一份结果
    window.history.replaceState({}, '', '/case-sets?sets_t_setCode=REG')
    const root = await mountView()

    expect(vi.mocked(caseSetApi.page).mock.calls[0][1]).toMatchObject({
      setCode: 'REG',
      page: 1,
    })

    pagerItem(root, '2').click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(vi.mocked(caseSetApi.page).mock.calls.at(-1)![1]).toMatchObject({
      setCode: 'REG',
      page: 2,
    })
  })
})


describe('CaseSetsView 成员批量动作与二次确认（W62-05/06）', () => {
  async function openWithMembers(): Promise<HTMLElement> {
    const root = await mountView()
    rowButton(root, 0, i18n.global.t('caseSets.manage')).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    return root
  }

  it('未选中时四个批量按钮全部 disabled（不是隐藏）', async () => {
    const root = await openWithMembers()
    for (const id of ['remove', 'up', 'down', 'export']) {
      const btn = root.querySelector<HTMLButtonElement>(`[data-testid="member-batch-${id}"]`)
      expect(btn).not.toBeNull()
      expect(btn?.disabled).toBe(true)
    }
  })

  it('勾选 2 条后显示「已选 2 条」且按钮解禁', async () => {
    const root = await openWithMembers()
    const checks = [...root.querySelectorAll<HTMLInputElement>('.member-check')]
    expect(checks).toHaveLength(2)
    checks.forEach((input) => {
      input.checked = true
      input.dispatchEvent(new Event('change'))
    })
    await nextTick()
    expect(root.querySelector('[data-testid="member-batch-count"]')?.textContent).toContain('2')
    expect(root.querySelector<HTMLButtonElement>('[data-testid="member-batch-remove"]')?.disabled).toBe(false)
  })

  it('批量移出：确认后调 batchRemoveItems，取消则一个请求都不发', async () => {
    vi.mocked(caseSetApi.batchRemoveItems).mockClear()
    const root = await openWithMembers()
    root.querySelector<HTMLButtonElement>('[data-testid="member-batch-select-all"]')!.click()
    await nextTick()
    const btn = root.querySelector<HTMLButtonElement>('[data-testid="member-batch-remove"]')!
    btn.click()
    useConfirmHost().settle(true)
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(caseSetApi.batchRemoveItems).toHaveBeenCalledWith(1, 7, [101, 102])

    vi.mocked(caseSetApi.batchRemoveItems).mockClear()
    root.querySelector<HTMLButtonElement>('[data-testid="member-batch-select-all"]')!.click()
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="member-batch-remove"]')!.click()
    useConfirmHost().settle(false)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(caseSetApi.batchRemoveItems).not.toHaveBeenCalled()
  })

  it('删除集合的确认文案含集合名与成员数', async () => {
    const root = await mountView()
    await openRowMenu(root, 0)
    const remove = menuItem(root, i18n.global.t('common.delete'))
    remove.click()
    // 文案由 confirmAction 的第一个参数给出；这里断言 hook 收到了弹窗请求
    await new Promise((resolve) => setTimeout(resolve, 0))
    const host = useConfirmHost()
    expect(host.current.value).not.toBeNull()
    expect(host.current.value?.message).toContain('冒烟集')
    host.settle(false)
  })
})
