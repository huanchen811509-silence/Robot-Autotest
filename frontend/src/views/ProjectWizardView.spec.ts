/**
 * ProjectWizardView 视图级回归（W57-12/13：全局向导 + 内容步四类来源）。
 *
 * <p>盯的是这几件与 W57 直接相关的事：
 * - 全局入口（`?from=global` 或 `/g/*`）创建成功后的返回落点回 `/g/projects`；
 * - 向导 content 步复用 `WizardContentPicker`，选择状态放在顶层、返回上一步不丢；
 * - 提交时把 `caseSetIds` 交给 `templateApi.applyContent` 且去重（字段名与后端
 *   `ProjectTemplateService.ContentRequest` 同名，见复核 P0-2）；
 * - 集合已删的并发错误按名称 / id 指名，提示用户返回内容步移除。
 *
 * <p>`WizardContentPicker` 被打桩成一个可点击发 `update:modelValue` 的假组件：
 * 这样既能驱动向导顶层的 `contentSelection`（v-model），又不必把它内部依赖的
 * `CaseCategoryTree` / 一堆 global API 都拖进测试。所有会打网络的 API 模块整模块 mock，
 * vue-router 只给 `useRoute/useRouter`；i18n、pinia、config 用真实实现（不打网络）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { projectApi, userApi, authApi } from '@/api/iam'
import { projectCardsApi } from '@/api/projectCards'
import { templateApi } from '@/api/globalTemplates'
import { groupsApi } from '@/api/groups'
import { credentialOptionsApi } from '@/api/credentialOptions'
import ProjectWizardView from '@/views/ProjectWizardView.vue'

vi.mock('@/api/iam', () => ({
  projectApi: {
    list: vi.fn(),
    create: vi.fn(),
    composite: vi.fn(),
    nextCode: vi.fn(),
    addMembers: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    capabilities: vi.fn(),
  },
  userApi: { page: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn(), renew: vi.fn(), logout: vi.fn() },
}))

vi.mock('@/api/projectCards', () => ({
  projectCardsApi: { list: vi.fn() },
}))

vi.mock('@/api/globalTemplates', () => ({
  templateApi: {
    applyContent: vi.fn(),
    saveAsTemplate: vi.fn(),
    list: vi.fn(),
    // v5.17.0 / W67-05：选中后要拉详情带出内核与四类计数
    get: vi.fn(),
  },
}))

vi.mock('@/api/groups', () => ({
  groupsApi: { groups: vi.fn(), grant: vi.fn() },
  roleRank: () => 0,
}))

vi.mock('@/api/credentialOptions', () => ({
  credentialOptionsApi: { options: vi.fn(), collectManual: vi.fn() },
}))

const routerState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown>, path: '/' as string },
  push: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => routerState.route,
  useRouter: () => ({ push: routerState.push }),
}))

/** 假内容选择器：点击按钮把当前 payload 通过 v-model 回写给向导顶层 ref。 */
const picker = vi.hoisted(() => ({
  payload: {
    templateKeys: [] as string[],
    setIds: [] as number[],
    sets: [] as { id: number; name: string }[],
    caseIds: [] as number[],
    assetIds: [] as number[],
    scenarioIds: [] as number[],
  },
}))
vi.mock('@/components/WizardContentPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  type PropType<T> = import('vue').PropType<T>
  interface Conflict {
    id: number
    name: string
  }
  interface Selection {
    templateKeys: string[]
    setIds: number[]
    sets: { id: number; name: string }[]
    caseIds: number[]
    assetIds: number[]
    scenarioIds?: number[]
  }
  return {
    default: defineComponent({
      name: 'WizardContentPickerStub',
      props: {
        modelValue: { type: Object as PropType<Selection>, default: undefined },
        conflicts: { type: Array as PropType<Conflict[]>, default: () => [] },
      },
      emits: ['update:modelValue'],
      setup(props, { emit }) {
        return () =>
          h('div', { 'data-testid': 'content-picker' }, [
            h(
              'button',
              {
                type: 'button',
                'data-testid': 'picker-emit',
                onClick: () => emit('update:modelValue', { ...picker.payload }),
              },
              'emit',
            ),
            h(
              'span',
              { 'data-testid': 'picker-conflicts' },
              props.conflicts.map((c) => `${c.name}#${c.id}`).join('|'),
            ),
            h(
              'span',
              { 'data-testid': 'picker-model' },
              JSON.stringify({
                cases: props.modelValue?.caseIds ?? [],
                sets: props.modelValue?.setIds ?? [],
              }),
            ),
          ])
      },
    }),
  }
})

function emptyPayload(): typeof picker.payload {
  return { templateKeys: [], setIds: [], sets: [], caseIds: [], assetIds: [], scenarioIds: [] }
}

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function tick(): Promise<void> {
  await nextTick()
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ProjectWizardView)
  app.use(pinia!)
  app.use(i18n)
  // 这里没有装真 router（vue-router 被 mock 掉了），但模板里用到 RouterLink：
  // 补一个最小桩，避免 Vue warn 把「组件未解析」混进测试输出。
  app.component('RouterLink', { props: ['to'], template: '<a><slot /></a>' })
  app.mount(root)
  await tick()
  return root
}

function click(root: HTMLElement, selector: string): void {
  root.querySelector<HTMLButtonElement>(selector)?.click()
}

function clickNext(root: HTMLElement): void {
  click(root, '[data-testid="wizard-next"]')
}

function fill(root: HTMLElement, selector: string, value: string): void {
  const el = root.querySelector<HTMLInputElement>(selector)
  if (!el) return
  el.value = value
  el.dispatchEvent(new Event('input'))
}

function currentStep(root: HTMLElement): string {
  if (root.querySelector('[data-testid="wizard-create"]')) return 'review'
  if (root.querySelector('[data-testid="content-picker"]')) return 'content'
  if (root.querySelector('[data-testid="wizard-name"]')) return 'basics'
  if (root.querySelector('.template-card')) return 'template'
  return 'runtime-or-members'
}

/** 只推进到 basics 步：用于校验基本信息步的必填拦截。 */
async function advanceToBasics(root: HTMLElement): Promise<void> {
  root.querySelector<HTMLElement>('.template-card')?.click()
  await tick()
  clickNext(root)
  await tick()
}

/** 走完 template→basics→runtime(→members) 到 content 步，并触发一次 v-model 回写。 */
async function advanceToContent(root: HTMLElement): Promise<void> {
  root.querySelector<HTMLElement>('.template-card')?.click()
  await tick()
  clickNext(root)
  await tick()
  fill(root, '[data-testid="wizard-code"]', 'QA-2026-001')
  fill(root, '[data-testid="wizard-name"]', '冒烟项目')
  await tick()
  clickNext(root)
  await tick()
  for (let i = 0; i < 6 && !root.querySelector('[data-testid="content-picker"]'); i += 1) {
    clickNext(root)
    await tick()
  }
  root.querySelector<HTMLButtonElement>('[data-testid="picker-emit"]')?.click()
  await tick()
}

/** content 步再点一次 next 到 review（创建按钮所在步）。 */
async function toReview(root: HTMLElement): Promise<void> {
  await advanceToContent(root)
  clickNext(root)
  await tick()
}

beforeEach(() => {
  vi.clearAllMocks()

  vi.mocked(projectApi.list).mockResolvedValue([] as never)
  vi.mocked(projectApi.create).mockResolvedValue({ id: 42 } as never)
  vi.mocked(projectApi.nextCode).mockResolvedValue({ code: 'QA-RF-202611-001' } as never)
  vi.mocked(projectApi.addMembers).mockResolvedValue({ added: 0, failed: 0, failures: [] } as never)
  vi.mocked(projectApi.composite).mockResolvedValue({
    projectId: 42,
    code: 'QA-RF-202611-001',
    members: 0,
    groups: 0,
    assets: 0,
    cases: 0,
    warnings: [],
  } as never)
  vi.mocked(userApi.page).mockResolvedValue({ items: [], total: 0 } as never)
  vi.mocked(authApi.login).mockResolvedValue({} as never)
  vi.mocked(authApi.me).mockResolvedValue({} as never)
  vi.mocked(authApi.renew).mockResolvedValue({} as never)
  vi.mocked(authApi.logout).mockResolvedValue({} as never)
  vi.mocked(projectCardsApi.list).mockResolvedValue([] as never)
  vi.mocked(templateApi.applyContent).mockResolvedValue({} as never)
  vi.mocked(templateApi.saveAsTemplate).mockResolvedValue({ name: '冒烟模板' } as never)
  // W67-04：第 1 步改读真模板列表，给一条可选的模板（否则页面落在空态，后续用例无卡可点）
  vi.mocked(templateApi.list).mockResolvedValue([
    {
      id: 501, name: '接口回归模板', code: 'API-REG', kernel: 'robot', visibility: 'private',
      builtin: false, status: 'ACTIVE', ownerId: 1, description: '接口回归默认值',
      itemCount: 5, createdAt: '2026-11-01T00:00:00+08:00',
    },
  ] as never)
  // W67-05：详情四类计数（2 集 + 1 例 + 1 场景 + 1 资产 = 5 项）
  vi.mocked(templateApi.get).mockResolvedValue({
    template: {
      id: 501, name: '接口回归模板', code: 'API-REG', kernel: 'robot', visibility: 'private',
      builtin: false, status: 'ACTIVE', ownerId: 1, description: '接口回归默认值',
      itemCount: 5, createdAt: '2026-11-01T00:00:00+08:00',
    },
    items: [
      { itemType: 'CASE_SET', refId: 11, mode: 'REF', name: 'S1' },
      { itemType: 'CASE_SET', refId: 12, mode: 'REF', name: 'S2' },
      { itemType: 'CASE', refId: 21, mode: 'REF', name: 'C1' },
      { itemType: 'SCENARIO', refId: 31, mode: 'REF', name: 'SC1' },
      { itemType: 'DEVICE', refId: 41, mode: 'REF', name: 'D1' },
    ],
  } as never)
  vi.mocked(groupsApi.groups).mockResolvedValue([
    { id: 5, name: '网络组', memberCount: 3 },
    { id: 6, name: '存储组', memberCount: 2 },
  ] as never)
  vi.mocked(credentialOptionsApi.options).mockResolvedValue({
    items: [
      { credentialId: 100, name: 'root@mgmt', credType: 'SSH', username: 'root', tier: 0,
        lastResult: 'SUCCESS', lastVerifiedAt: '', boundDevices: 1, configured: true },
      { credentialId: 101, name: 'BMC 只读', credType: 'BMC', username: 'admin', tier: 2,
        lastResult: '', lastVerifiedAt: '', boundDevices: 0, configured: true },
    ],
    total: 2,
    truncated: false,
  } as never)

  routerState.route = { query: {}, path: '/' }
  picker.payload = emptyPayload()

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

describe('W57-12 全局入口与返回落点', () => {
  it('?from=global 时页头「返回」跳 /g/projects', async () => {
    routerState.route = { query: { from: 'global' }, path: '/projects/new' }
    const root = await mountView()

    click(root, '[data-testid="wizard-back"]')
    await tick()

    expect(routerState.push).toHaveBeenCalledWith('/g/projects')
  })

  it('路由 path 以 /g/ 开头也判定为全局，返回跳 /g/projects', async () => {
    routerState.route = { query: {}, path: '/g/projects/new' }
    const root = await mountView()

    click(root, '[data-testid="wizard-back"]')
    await tick()

    expect(routerState.push).toHaveBeenCalledWith('/g/projects')
  })

  it('非全局入口（/projects/new，无 from）返回跳 /workbench', async () => {
    routerState.route = { query: {}, path: '/projects/new' }
    const root = await mountView()

    click(root, '[data-testid="wizard-back"]')
    await tick()

    expect(routerState.push).toHaveBeenCalledWith('/workbench')
  })

  it('全局模式页头用中文「返回项目列表」，非全局不含该串', async () => {
    routerState.route = { query: { from: 'global' }, path: '/' }
    const globalRoot = await mountView()
    expect(globalRoot.querySelector('[data-testid="wizard-back"]')?.textContent).toContain('返回项目列表')

    app?.unmount()
    host?.remove()
    routerState.route = { query: {}, path: '/projects/new' }
    const localRoot = await mountView()
    expect(localRoot.querySelector('[data-testid="wizard-back"]')?.textContent).not.toContain('返回项目列表')
  })
})

describe('W57-12 向导步骤与 content 步复用', () => {
  it('初始停在 template 步：渲染模板卡与「下一步」', async () => {
    const root = await mountView()

    expect(currentStep(root)).toBe('template')
    expect(root.querySelectorAll('.template-card').length).toBeGreaterThan(0)
    expect(root.querySelector('[data-testid="wizard-next"]')).not.toBeNull()
  })

  it('未选模板点「下一步」被拦在 template 步并给出 message', async () => {
    const root = await mountView()

    clickNext(root)
    await tick()

    expect(currentStep(root)).toBe('template')
    expect(root.querySelector('.wizard-message')).not.toBeNull()
  })

  it('basics 步 name 为空点「下一步」不推进', async () => {
    const root = await mountView()
    await advanceToBasics(root)

    expect(currentStep(root)).toBe('basics')
    clickNext(root)
    await tick()

    expect(currentStep(root)).toBe('basics')
    expect(root.querySelector('.wizard-message')).not.toBeNull()
  })

  it('content 步渲染 WizardContentPicker（复用四类来源选择器）', async () => {
    const root = await mountView()
    await advanceToContent(root)

    expect(currentStep(root)).toBe('content')
    expect(root.querySelector('[data-testid="content-picker"]')).not.toBeNull()
  })
})

describe('v5.10.0 一次提交（composite）：字段名 / 去重 / 可跳过', () => {
  function compositeBody(): Record<string, unknown> {
    const calls = vi.mocked(projectApi.composite).mock.calls
    return (calls[calls.length - 1]?.[0] ?? {}) as Record<string, unknown>
  }

  it('六类来源一次提交：caseIds / assets / caseSetIds 同请求，且不带后端不认的字段', async () => {
    picker.payload = {
      ...emptyPayload(),
      caseIds: [11, 12],
      assetIds: [9],
      setIds: [5, 6],
      sets: [
        { id: 5, name: '冒烟集' },
        { id: 6, name: '回归集' },
      ],
      templateKeys: ['tpl-x'],
    }
    const root = await mountView()
    await toReview(root)

    expect(currentStep(root)).toBe('review')
    click(root, '[data-testid="wizard-create"]')
    await tick()

    // 只发一次请求：项目 + 成员 + 组 + 内容 + 资产 + 凭证在服务端一个事务里
    expect(projectApi.composite).toHaveBeenCalledTimes(1)
    expect(projectApi.create).not.toHaveBeenCalled()
    expect(templateApi.applyContent).not.toHaveBeenCalled()

    const body = compositeBody()
    expect(body.caseIds).toEqual([11, 12])
    expect(body.caseSetIds).toEqual([5, 6])
    // 后端 record 是 caseSetIds；写 setIds 会被 Jackson 忽略，建出的项目里集合为空
    expect(body).not.toHaveProperty('setIds')
    expect(body).not.toHaveProperty('templateKeys')
    expect(body.assets).toEqual([
      { assetType: 'DEVICE', assetId: 9, managementCredentialId: null, bmcCredentialId: null },
    ])
  })

  it('提交去重：重复 caseIds / caseSetIds 只留一份', async () => {
    picker.payload = {
      ...emptyPayload(),
      caseIds: [11, 11, 12],
      setIds: [5, 5],
      sets: [{ id: 5, name: '冒烟集' }],
    }
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    const body = compositeBody()
    expect(body.caseIds).toEqual([11, 12])
    expect(body.caseSetIds).toEqual([5])
  })

  it('内容全空也创建项目：composite 照样调一次，内容数组为空', async () => {
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    expect(projectApi.composite).toHaveBeenCalledTimes(1)
    const body = compositeBody()
    expect(body.caseIds).toEqual([])
    expect(body.assets).toEqual([])
    expect(root.querySelector('.wizard-result')).not.toBeNull()
  })

  it('只选用例集（跳过用例）也带上 caseSetIds', async () => {
    picker.payload = {
      ...emptyPayload(),
      setIds: [5],
      sets: [{ id: 5, name: '冒烟集' }],
    }
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    const body = compositeBody()
    expect(body.caseIds).toEqual([])
    expect(body.caseSetIds).toEqual([5])
  })

  it('负责人默认当前用户，随请求一起提交（FR-v5.10-04）', async () => {
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    expect(compositeBody().ownerId).toBe(1)
    expect(compositeBody().demo).toBe(false)
  })

  it('勾选演示项目 → demo=true；不勾 → false（FR-v5.10-04）', async () => {
    const root = await mountView()
    await advanceToBasics(root)
    fill(root, '[data-testid="wizard-code"]', 'QA-2026-009')
    fill(root, '[data-testid="wizard-name"]', '演示项目')
    await tick()
    clickNext(root)
    await tick()

    const demoBox = root.querySelector<HTMLInputElement>('[data-testid="wizard-demo"]')
    expect(demoBox).not.toBeNull()
    demoBox!.checked = true
    demoBox!.dispatchEvent(new Event('change'))
    await tick()

    for (let i = 0; i < 8 && !root.querySelector('[data-testid="wizard-create"]'); i += 1) {
      clickNext(root)
      await tick()
    }
    click(root, '[data-testid="wizard-create"]')
    await tick()

    expect(compositeBody().demo).toBe(true)
  })

  it('用户组步：勾选组并选角色 → 组授权随请求提交（FR-v5.10-02）', async () => {
    const root = await mountView()
    click(root, '.template-card')
    await tick()
    clickNext(root)
    await tick()
    fill(root, '[data-testid="wizard-code"]', 'QA-2026-010')
    fill(root, '[data-testid="wizard-name"]', '组授权项目')
    await tick()
    // 走到 groups 步
    for (let i = 0; i < 4 && !root.querySelector('[data-testid="wizard-group-5"]'); i += 1) {
      clickNext(root)
      await tick()
    }
    const box = root.querySelector<HTMLInputElement>('[data-testid="wizard-group-5"]')
    expect(box).not.toBeNull()
    box!.checked = true
    box!.dispatchEvent(new Event('change'))
    await tick()
    // 步内显示该组成员数
    expect(root.textContent).toContain('3')

    for (let i = 0; i < 4 && !root.querySelector('[data-testid="wizard-create"]'); i += 1) {
      clickNext(root)
      await tick()
    }
    click(root, '[data-testid="wizard-create"]')
    await tick()

    expect(compositeBody().groups).toEqual([{ groupId: 5, roleCode: 'viewer' }])
  })

  it('资产凭证内联：选中设备后出现两个选择器，可留空（FR-v5.10-03）', async () => {
    picker.payload = { ...emptyPayload(), assetIds: [9] }
    const root = await mountView()
    await advanceToContent(root)

    expect(root.querySelector('[data-testid="wizard-cred-mgmt-9"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="wizard-cred-bmc-9"]')).not.toBeNull()
    expect(credentialOptionsApi.options).toHaveBeenCalledWith(9)

    clickNext(root)
    await tick()
    click(root, '[data-testid="wizard-create"]')
    await tick()

    // 留空 → 两面都是 null（后端把 null 当「不动」，不会清掉既有绑定）
    expect(compositeBody().assets).toEqual([
      { assetType: 'DEVICE', assetId: 9, managementCredentialId: null, bmcCredentialId: null },
    ])
  })
})

describe('W57-13 并发冲突指名 / 返回不丢选择 / 存为模板', () => {
  it('集合已删的并发错误按名称指名，提示返回内容步移除', async () => {
    picker.payload = {
      ...emptyPayload(),
      setIds: [5],
      sets: [{ id: 5, name: '以太网冒烟' }],
    }
    vi.mocked(projectApi.composite).mockRejectedValueOnce(
      new Error('用例集「以太网冒烟」已被删除'),
    )
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    const warnings = root.textContent ?? ''
    expect(warnings).toContain('以太网冒烟')
  })

  it('泛化错误不含集合名 / id 时走普通失败文案，不误报「已被删除」', async () => {
    picker.payload = {
      ...emptyPayload(),
      setIds: [7],
      sets: [{ id: 7, name: '回归集' }],
    }
    vi.mocked(projectApi.composite).mockRejectedValueOnce(new Error('网络连接超时'))
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    const warnings = root.textContent ?? ''
    expect(warnings).not.toContain('以下用例集已被删除')
    // 顶部错误行要给出原因，不能静默吞掉
    expect(warnings).toContain('网络连接超时')
    expect(root.querySelector('.wizard-result')).toBeNull()
  })

  it('提交失败不落在半成品上：结果区不出现已创建卡片', async () => {
    vi.mocked(projectApi.composite).mockRejectedValueOnce(new Error('编号生成冲突，请重试'))
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    expect(root.querySelector('[data-testid="wizard-create"]')).not.toBeNull()
  })

  it('content 步返回再进入不丢选择：caseIds / setIds 经 v-model 保持', async () => {
    picker.payload = {
      ...emptyPayload(),
      caseIds: [11, 12],
      setIds: [5],
      sets: [{ id: 5, name: '冒烟集' }],
    }
    const root = await mountView()
    await advanceToContent(root)

    const before = root.querySelector('[data-testid="picker-model"]')?.textContent ?? ''
    expect(before).toContain('11')
    expect(before).toContain('12')

    // 从 content 返回上一步（销毁该步），再前进回 content（重建该步）
    click(root, '[data-testid="wizard-back-step"]')
    await tick()
    expect(root.querySelector('[data-testid="content-picker"]')).toBeNull()
    clickNext(root)
    await tick()

    const after = root.querySelector('[data-testid="picker-model"]')?.textContent ?? ''
    expect(after).toBe(before)
  })

  it('创建成功后「存为模板」入口调用 saveAsTemplate', async () => {
    const root = await mountView()
    await toReview(root)
    click(root, '[data-testid="wizard-create"]')
    await tick()

    click(root, '[data-testid="open-save-template"]')
    await tick()
    expect(root.querySelector('[data-testid="save-template-modal"]')).not.toBeNull()
    fill(root, '[data-testid="save-template-name"]', '冒烟模板')
    await tick()
    click(root, '[data-testid="save-template-confirm"]')
    await tick()

    expect(templateApi.saveAsTemplate).toHaveBeenCalledWith(42, '冒烟模板')
  })
})

/**
 * 第 1 步接真模板（v5.17.0 / W67-04 判据 1·4·5、W67-05 判据 2·3）。
 *
 * 这一组钉的是「数据从哪来」：改版前卡片读的是前端静态 `PROJECT_TEMPLATES`，
 * 库里新建的模板永远不出现在向导里；改版后必须来自 `GET /api/global/templates`，
 * 且后端不可用时回退静态卡要**显式标离线**，不能假装是真模板。
 */
describe('第 1 步接真模板', () => {
  it('卡片来自 GET /api/global/templates，不再渲染前端静态模板', async () => {
    const root = await mountView()
    expect(vi.mocked(templateApi.list)).toHaveBeenCalled()
    expect(root.textContent).toContain('接口回归模板')
    // 静态配置里的「空白项目」不应再出现
    expect(root.textContent).not.toContain('空白项目')
  })

  it('pytest 模板 → 第 3 步默认内核为 pytest', async () => {
    vi.mocked(templateApi.list).mockResolvedValue([
      {
        id: 502, name: 'pytest 服务模板', code: 'PY-SVC', kernel: 'pytest', visibility: 'private',
        builtin: false, status: 'ACTIVE', ownerId: 1, description: '', itemCount: 0,
        createdAt: '2026-11-01T00:00:00+08:00',
      },
    ] as never)
    vi.mocked(templateApi.get).mockResolvedValue({
      template: {
        id: 502, name: 'pytest 服务模板', code: 'PY-SVC', kernel: 'pytest', visibility: 'private',
        builtin: false, status: 'ACTIVE', ownerId: 1, description: '', itemCount: 0,
        createdAt: '2026-11-01T00:00:00+08:00',
      },
      items: [],
    } as never)
    const root = await mountView()
    root.querySelector<HTMLElement>('[data-testid="template-card-tpl-502"]')?.click()
    await tick()
    clickNext(root)
    await tick()
    fill(root, '[data-testid="wizard-code"]', 'PY-2026-001')
    fill(root, '[data-testid="wizard-name"]', 'pytest 项目')
    await tick()
    clickNext(root)
    await tick()
    const radio = root.querySelector<HTMLInputElement>('input[type="radio"][value="pytest"]')
    expect(radio).toBeTruthy()
    expect(radio?.checked).toBe(true)
  })

  it('卡片计数与详情四类之和一致，且摘要逐类可读', async () => {
    const root = await mountView()
    root.querySelector<HTMLElement>('[data-testid="template-card-tpl-501"]')?.click()
    await tick()
    // 卡片 meta 里的「内容 N 项」来自列表 itemCount
    const card = root.querySelector('[data-testid="template-card-tpl-501"]')
    expect(card?.textContent).toContain('内容 5 项')
    // 详情摘要：2 集 + 1 例 + 1 场景 + 1 资产 = 5
    const counts = root.querySelector('[data-testid="template-counts"]')?.textContent ?? ''
    expect(counts).toContain('用例集 2')
    expect(counts).toContain('用例 1')
    expect(counts).toContain('场景 1')
    expect(counts).toContain('资产 1')
  })

  it('模板列表为空：给空态与「去新建模板」入口，流程不崩', async () => {
    vi.mocked(templateApi.list).mockResolvedValue([] as never)
    const root = await mountView()
    expect(root.querySelector('[data-testid="templates-empty"]')).toBeTruthy()
    expect(root.querySelector('[data-testid="templates-empty-create"]')).toBeTruthy()
    expect(root.querySelector('.template-card')).toBeNull()
  })

  it('后端不可用：回退静态卡并标「离线模板」', async () => {
    vi.mocked(templateApi.list).mockRejectedValue(new Error('backend down') as never)
    const root = await mountView()
    expect(root.querySelector('[data-testid="templates-offline"]')).toBeTruthy()
    const cards = root.querySelectorAll('.template-card')
    expect(cards.length).toBeGreaterThan(0)
    expect(cards[0].getAttribute('data-offline')).toBe('true')
    expect(root.textContent).toContain('离线模板')
  })
})

/**
 * 复核步内容合计（v5.17.0 / W67-06 判据 2）。
 *
 * 场景是第五个维度：`2 集 + 5 例 + 2 场景` 必须显示「合计 9 项」。
 * 这条断言的价值在于**计数不能靠"看起来差不多"**——之前复核步根本没有内容合计，
 * 用户点完三步不知道自己到底带进来多少东西。
 */
describe('复核步内容合计', () => {
  it('2 集 + 5 例 + 2 场景 → 合计 9 项', async () => {
    const root = await mountView()
    await advanceToContent(root)
    picker.payload = {
      templateKeys: [],
      setIds: [11, 12],
      sets: [
        { id: 11, name: '用例集 A' },
        { id: 12, name: '用例集 B' },
      ],
      caseIds: [1, 2, 3, 4, 5],
      assetIds: [],
      scenarioIds: [31, 32],
    }
    root.querySelector<HTMLButtonElement>('[data-testid="picker-emit"]')?.click()
    await tick()
    for (let i = 0; i < 8 && !root.querySelector('[data-testid="wizard-create"]'); i += 1) {
      clickNext(root)
      await tick()
    }
    const total = root.querySelector('[data-testid="review-content-total"]')?.textContent ?? ''
    expect(total).toContain('9')
    expect(total).toContain('用例集 2')
    expect(total).toContain('场景 2')
  })
})
