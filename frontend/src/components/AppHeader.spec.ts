/**
 * 顶栏结构回归（v4.0 / FR-v4.0-03）。
 *
 * 盯的是「顶栏看起来对、其实已经退化」的四件事：
 * ① 三段顺序（上下文 → 工具 → 账户）与 2 条分隔线；
 * ② 控件计数只有 5 枚（AI 关闭时 4 枚）——外观/主题/语言/关于/登出全部收进账户菜单；
 * ③ 项目选择器保留独立造型（不借用语言下拉的 class）；
 * ④ AI 未启用时整项不渲染，不占位、不留空壳。
 *
 * 像素读数不在这里断（jsdom 无布局，`getBoundingClientRect()` 恒为 0），
 * 由 `scripts/browser-regression.mjs` 在真实 Chromium 里量；这里只锁结构契约。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import AppHeader from '@/components/AppHeader.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/preferences', () => ({
  preferenceApi: { get: vi.fn(), put: vi.fn().mockResolvedValue({}) },
}))

vi.mock('@/api/settings', () => ({
  settingsApi: { health: vi.fn().mockResolvedValue({ status: 'ok' }) },
}))

vi.mock('@/api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/http')>()
  return {
    ...actual,
    healthPing: vi.fn().mockResolvedValue({ status: 'ok', app: 'x', version: '4.2.0' }),
  }
})

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountHeader(aiEnabled = true): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({
    render: () => h(AppHeader, { aiEnabled }),
  })
  app.use(pinia!)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return host
}

function segs(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('[data-seg]')]
}

function interactive(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(
    '[data-testid="header-actions"] button, '
    + '[data-testid="header-actions"] select, '
    + '[data-testid="header-actions"] a[href]',
  )]
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    id: 1,
    username: 'qa-e2e-admin',
    displayName: 'QA 管理员',
    roles: ['admin'],
  } as unknown as typeof auth.user
  auth.projects = [
    { id: 1, code: 'P-1', name: '项目一', enabled: true, visibility: 'private', ownerId: 1 },
  ] as unknown as typeof auth.projects
  auth.projectId = 1
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('顶栏三段排版（FR-v4.0-03 / FR-v4.5-04）', () => {
  it('管理员按「模式 → 上下文 → 工具 → 账户」排布，且恰好 2 条分隔线', async () => {
    const root = await mountHeader()
    expect(segs(root).map((el) => el.dataset.seg)).toEqual([
      'mode',
      'context',
      'tools',
      'account',
    ])
    expect(root.querySelectorAll('.app-sep')).toHaveLength(2)
  })

  it('工程师没有全局模式，因此不渲染模式段（三段）', async () => {
    useAuthStore().user = {
      id: 2,
      username: 'qa-engineer',
      displayName: '工程师',
      roles: ['engineer'],
    } as unknown as ReturnType<typeof useAuthStore>['user']
    const root = await mountHeader()
    expect(segs(root).map((el) => el.dataset.seg)).toEqual(['context', 'tools', 'account'])
    expect(root.querySelector('.app-mode-switch')).toBeNull()
  })

  // v4.12.0 / FR-v4.12-25：全局在左、项目在右（原来的顺序是反的）。
  it('模式段顺序为「全局 → 项目」，初始激活项跟随当前模式', async () => {
    const root = await mountHeader()
    const buttons = [...root.querySelectorAll<HTMLButtonElement>('[data-seg="mode"] button')]
    expect(buttons).toHaveLength(2)
    expect(buttons[0].textContent?.trim()).toBe(i18n.global.t('mode.global'))
    expect(buttons[1].textContent?.trim()).toBe(i18n.global.t('mode.project'))
    // 默认是项目模式（localStorage 无值 → project），所以右侧按钮激活
    expect(buttons[0].getAttribute('aria-pressed')).toBe('false')
    expect(buttons[1].getAttribute('aria-pressed')).toBe('true')
  })

  // v4.11.0 / FR-v4.11-09：主题 / 语言并入工具段，可聚焦控件 7→9（AI 关闭 8）。
  it('AI 开启时 9 枚控件，关闭时 8 枚（AI 整项不渲染）', async () => {
    const withAi = await mountHeader(true)
    expect(interactive(withAi)).toHaveLength(9)
    app?.unmount()
    host?.remove()

    const withoutAi = await mountHeader(false)
    expect(interactive(withoutAi)).toHaveLength(8)
    expect(withoutAi.querySelector('[aria-label="' + i18n.global.t('aiPanel.toggle') + '"]')).toBeNull()
  })

  it('主题 / 语言在工具段内（不再挂在账户之后），关于与登出仍不占顶栏', async () => {
    const root = await mountHeader()
    const labels = [...root.querySelectorAll('[aria-label]')]
      .map((el) => el.getAttribute('aria-label'))
    expect(labels).not.toContain(i18n.global.t('about.open'))
    expect(labels).not.toContain(i18n.global.t('layout.toggleSide'))
    expect(root.querySelector('.app-logout')).toBeNull()
    // v4.11.0：主题 / 语言必须在 `[data-seg="tools"]` 里，否则账户就不在最右
    const tools = root.querySelector('[data-seg="tools"]')!
    expect(tools.querySelectorAll('button').length).toBeGreaterThanOrEqual(4)
  })

  it('Tab 顺序与视觉顺序一致（模式 → 项目 → 检索 → 通知 → AI → 主题 → 语言 → 账户）', async () => {
    const root = await mountHeader()
    const order = interactive(root).map((el) => el.tagName.toLowerCase())
    expect(order).toEqual([
      'button', 'button', 'select',
      'button', 'button', 'button', 'button', 'button',
      'button',
    ])
  })

  it('账户段是 header-actions 内最后一个 data-seg，且 header-quick 容器已移除（FR-v4.11-10）', async () => {
    const root = await mountHeader()
    const segs = [...root.querySelectorAll('[data-testid="header-actions"] > [data-seg]')]
    expect(segs.at(-1)?.getAttribute('data-seg')).toBe('account')
    // 分隔线仍是 2 条：三段两线，不因重排多画一条
    expect(root.querySelectorAll('.app-sep')).toHaveLength(2)
    expect(root.querySelector('[data-testid="header-quick"]')).toBeNull()
  })

  it('账户入口是菜单触发器：aria-haspopup=menu 且默认收起', async () => {
    const root = await mountHeader()
    const trigger = root.querySelector<HTMLButtonElement>('.account-trigger')!
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
  })
})

describe('项目选择器（FR-UI-19）', () => {  it('有独立造型：带 app-project 类但不与 app-lang 共用选择器', async () => {
    const root = await mountHeader()
    const project = root.querySelector('.app-project')
    expect(project).not.toBeNull()
    expect(project!.classList.contains('app-lang')).toBe(false)
    expect(project!.querySelector('.app-project-dot')).not.toBeNull()
  })

  it('有项目时显示当前项目名，状态点亮起', async () => {
    const root = await mountHeader()
    const select = root.querySelector<HTMLSelectElement>('.app-project-select')!
    expect(select.value).toBe('1')
    expect(select.selectedOptions[0].textContent?.trim()).toBe('项目一')
    expect(root.querySelector('.app-project-dot')!.classList.contains('is-on')).toBe(true)
    expect(root.querySelector('.app-project')!.classList.contains('is-empty')).toBe(false)
  })

  it('未选项目时显示引导态（虚线边，而不是空白下拉）', async () => {
    useAuthStore().projectId = null
    const root = await mountHeader()
    expect(root.querySelector('.app-project')!.classList.contains('is-empty')).toBe(true)
    expect(root.querySelector<HTMLSelectElement>('.app-project-select')!.value).toBe('')
  })
})

/**
 * v4.11.0 / FR-v4.11-11：760px 下顶栏不横向溢出。
 *
 * <p>**为什么不直接断 `scrollWidth <= clientWidth`**：jsdom 不做布局，两个值恒为 0，
 * 那样的断言永远为真，等于没有断言（本项目已经吃过「写了探针但探针恒绿」的亏）。
 * 像素读数由 `scripts/browser-regression.mjs` 在真实 Chromium 里量；这里锁的是
 * **让它不会溢出的两条契约**：
 * ① 顶栏与右侧容器 `flex-wrap: wrap`（空间不足换行，而不是把内容挤出视口）；
 * ② 主题/语言并入工具段后，窄屏断点仍存在且收紧段内间距（否则 9 枚控件在 760px 硬挤）。
 *
 * <p>注意样式必须用 `fs` 直读：vitest 的 CSS 插件会把 `*.css` 模块短路成空串，
 * `?raw` 也不例外（见 `styles/select-theme.spec.ts` 的踩坑记录）。
 */
describe('760px 窄屏不横向溢出（FR-v4.11-11）', () => {
  // vitest 里 `import.meta.url` 是 http:// 而非 file://，不能拿来喂 readFileSync；
  // 与 `styles/select-theme.spec.ts` 一致：以 vitest 的 cwd（frontend/）为基准直读源文件。
  const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')
  const mainCss = read('src/styles/main.css').replace(/\/\*[\s\S]*?\*\//g, '')

  it('顶栏与右侧容器在空间不足时换行，而不是横向溢出', () => {
    // 同一对选择器在断点里还出现过一次（只改 gap），所以要找「含 flex-wrap 的那条」，
    // 而不是第一处命中——第一处是 @media 里的间距微调，断它等于断错了规则。
    const blocks = [
      ...mainCss.matchAll(/\.app-header,\s*\.app-header-right\s*\{([^}]*)\}/g),
    ].map((match) => match[1])
    expect(blocks.length, '缺少 .app-header / .app-header-right 规则').toBeGreaterThan(0)
    expect(
      blocks.some((body) => body.includes('flex-wrap: wrap')),
      `没有任何一条 .app-header 规则声明 flex-wrap: wrap（实际：${JSON.stringify(blocks)}）`,
    ).toBe(true)
  })

  it('760px 断点存在，且工具段在窄屏收紧间距', () => {
    const source = read('src/components/AppHeader.vue')
    // 断点块内部还有嵌套规则，`[^}]*` 会停在第一个内层 `}`，改用定长窗口取整段
    const narrow = /@media \(max-width: 760px\)\s*\{([\s\S]{0,240})/.exec(source)
    expect(narrow, 'AppHeader 缺少 760px 断点').not.toBeNull()
    expect(narrow![1]).toContain('.app-tools')
    expect(narrow![1]).toMatch(/gap:\s*\d+px/)
  })

  it('探针：挂载后 scrollWidth 不超过 clientWidth（真布局下才有读数，jsdom 恒 0，不作唯一判据）', async () => {
    const root = await mountHeader()
    const header = root.querySelector<HTMLElement>('.app-header')!
    expect(header.scrollWidth).toBeLessThanOrEqual(header.clientWidth)
  })
})
