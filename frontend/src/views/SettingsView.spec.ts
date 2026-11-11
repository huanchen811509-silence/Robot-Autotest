/**
 * `SettingsView` 视图级回归（v4.12.0 / FR-v4.12-18、FR-v4.12-19）。
 *
 * <p>同一个组件承担两种语义，靠 `route.meta.global` 区分。本 spec 钉住四条：
 * ① 全局语义只渲染平台段，**不出现**「默认执行内核 / 项目默认超时 / 默认标签集」三项
 *    （它们对平台没有意义，展示出来只会让人以为全局也能改项目内核）；
 * ② 全局语义不拉项目设置接口 —— 全局用户可能没有当前项目，拉一次就是一次 403 噪音；
 * ③ 项目语义仍是两段都在，项目段有 3 个控件（回归保护：别为了全局把项目侧也砍了）；
 * ④ 平台段恒为 4 个可编辑控件 + 1 个只读端口。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import SettingsView from '@/views/SettingsView.vue'
import { settingsApi } from '@/api/settings'

vi.mock('@/api/settings', () => ({
  settingsApi: {
    platform: vi.fn(),
    project: vi.fn(),
    putPlatform: vi.fn(),
    putProject: vi.fn(),
  },
}))

const routeState = vi.hoisted(() => ({
  route: undefined as unknown as { meta: Record<string, unknown>; path: string },
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState.route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

const platform = vi.mocked(settingsApi.platform)
const project = vi.mocked(settingsApi.project)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

/**
 * 未装真实 router：`RouterLink` 用最小桩代替。
 *
 * <p>必须实现 `custom` + `navigate` 插槽参数：本页用的是
 * `<RouterLink v-slot="{ navigate }" custom>` 包 `NButton` 的写法，
 * 桩里不传 `navigate` 会直接抛「Cannot destructure property 'navigate'」。
 */
const RouterLinkStub = {
  props: {
    to: { type: [String, Object], default: '' },
    custom: { type: Boolean, default: false },
  },
  setup(
    props: { to: string | object },
    // 插槽类型写清楚（不用 `Function`：eslint 的 no-unsafe-function-type 会拦），
    // 只声明本页用到的 default 一支，参数形状与 RouterLink 的插槽一致
    { slots }: { slots: Record<string, ((arg: { navigate: () => void; href: string }) => unknown) | undefined> },
  ) {
    return () =>
      h(
        'a',
        { class: 'router-link-stub', href: String(props.to) },
        slots.default?.({ navigate: () => undefined, href: String(props.to) }) as never,
      )
  },
} as unknown as Component

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(SettingsView)
  app.component('RouterLink', RouterLinkStub)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

beforeEach(() => {
  vi.clearAllMocks()
  platform.mockResolvedValue({
    'execution.maxParallel': '4',
    'execution.defaultTimeoutSeconds': '600',
    'report.retentionDays': '30',
    'ai.enabled': 'true',
    'entry.port': '8001',
  })
  project.mockResolvedValue({
    'kernel.default': 'robot',
    'execution.timeoutSeconds': '900',
    'case.tagSet': 'smoke',
  })
  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projectId = 1
  auth.user = { id: 1, username: 'admin', displayName: '管理员', roles: ['admin'] } as never
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

function fields(root: HTMLElement, testid: string): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(`[data-testid="${testid}"] .field`)]
}

describe('SettingsView 全局语义（/g/settings）', () => {
  beforeEach(() => {
    routeState.route = reactive({ meta: { global: true }, path: '/g/settings' })
  })

  it('渲染平台配置表单：标题换成平台设置，4 个可编辑控件 + 1 个只读端口', async () => {
    const root = await mountView()

    expect(root.querySelector('h1')?.textContent).toContain('平台设置')
    const platformPanel = root.querySelector('[data-testid="platform-settings-panel"]')
    expect(platformPanel).not.toBeNull()

    // 4 个可编辑 + 1 个只读端口 = 5 个 .field
    expect(fields(root, 'platform-settings-panel')).toHaveLength(5)
    expect(platformPanel!.querySelectorAll('input:not([readonly])')).toHaveLength(3)
    expect(platformPanel!.querySelectorAll('select')).toHaveLength(1)
    const port = platformPanel!.querySelector<HTMLInputElement>('input[readonly]')
    expect(port).not.toBeNull()
    expect(port!.value).toBe('8001')
  })

  it('不渲染项目级区块：三项项目设置一个都不出现', async () => {
    const root = await mountView()

    expect(root.querySelector('[data-testid="project-settings-panel"]')).toBeNull()
    for (const label of ['默认执行内核', '项目默认超时（秒）', '默认标签集']) {
      expect(root.textContent).not.toContain(label)
    }
    // 内核选择的取值不该以任何形式残留在页面上（连 option 也不行）
    expect(root.innerHTML).not.toContain('pytest')
  })

  it('不拉项目设置接口：全局用户可能没有当前项目', async () => {
    await mountView()

    expect(platform).toHaveBeenCalledTimes(1)
    expect(project).not.toHaveBeenCalled()
  })
})

describe('SettingsView 项目语义（/settings）', () => {
  beforeEach(() => {
    routeState.route = reactive({ meta: {}, path: '/settings' })
  })

  it('两段都在：平台段 5 项 + 项目段 3 项，且拉取项目设置', async () => {
    const root = await mountView()

    expect(root.querySelector('h1')?.textContent).toContain('系统设置')
    expect(fields(root, 'platform-settings-panel')).toHaveLength(5)
    expect(fields(root, 'project-settings-panel')).toHaveLength(3)
    expect(project).toHaveBeenCalledWith(1)
    expect(root.textContent).toContain('默认执行内核')
    expect(root.innerHTML).toContain('pytest')
  })

  it('项目段的内核下拉把当前值选中（不是只把选项列出来）', async () => {
    const root = await mountView()

    const select = root.querySelector<HTMLSelectElement>(
      '[data-testid="project-settings-panel"] select',
    )
    expect(select).not.toBeNull()
    expect(select!.value).toBe('robot')
  })
})
