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

  it('AI 开启时 7 枚控件，关闭时 6 枚（AI 整项不渲染）', async () => {
    const withAi = await mountHeader(true)
    expect(interactive(withAi)).toHaveLength(7)
    app?.unmount()
    host?.remove()

    const withoutAi = await mountHeader(false)
    expect(interactive(withoutAi)).toHaveLength(6)
    expect(withoutAi.querySelector('[aria-label="' + i18n.global.t('aiPanel.toggle') + '"]')).toBeNull()
  })

  it('外观 / 主题 / 语言 / 关于 / 登出不再占用顶栏', async () => {
    const root = await mountHeader()
    const labels = [...root.querySelectorAll('[aria-label]')]
      .map((el) => el.getAttribute('aria-label'))
    expect(labels).not.toContain(i18n.global.t('about.open'))
    expect(labels).not.toContain(i18n.global.t('layout.toggleSide'))
    expect(root.querySelector('.app-theme-btn')).toBeNull()
    expect(root.querySelector('.app-logout')).toBeNull()
  })

  it('Tab 顺序与视觉顺序一致（模式 → 项目 → 检索 → 通知 → AI → 账户）', async () => {
    const root = await mountHeader()
    const order = interactive(root).map((el) => el.tagName.toLowerCase())
    expect(order).toEqual(['button', 'button', 'select', 'button', 'button', 'button', 'button'])
  })

  it('账户入口是菜单触发器：aria-haspopup=menu 且默认收起', async () => {
    const root = await mountHeader()
    const trigger = root.querySelector<HTMLButtonElement>('.account-trigger')!
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
  })
})

describe('项目选择器（FR-UI-19）', () => {
  it('有独立造型：带 app-project 类但不与 app-lang 共用选择器', async () => {
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
