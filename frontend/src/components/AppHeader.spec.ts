/**
 * 顶栏结构回归（v2.3 / UI-04，FR-UI-15..19 / V-UI-06）。
 *
 * 盯的是「顶栏看起来对、其实已经退化」的四件事：
 * ① 四段顺序与 3 条分隔线——少了就是又回到「10 个控件等距平铺」；
 * ② 项目选择器有自己的造型，不再借用语言下拉的 class；
 * ③ 主题按钮是二态（light ↔ dark），点一次必须翻转，绝不能落回 system；
 * ④ 登出走 danger + 二次确认，取消时**不得**真的退出。
 *
 * 28px 栅格的**像素读数**不在这里断（jsdom 无布局，`getBoundingClientRect()` 恒为 0），
 * 由 `scripts/browser-regression.mjs` 在真实 Chromium 里量；这里只锁结构与类名契约。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { useConfirmHost } from '@/composables/useConfirm'
import AppHeader from '@/components/AppHeader.vue'

vi.mock('@/api/preferences', () => ({
  preferenceApi: { get: vi.fn(), put: vi.fn().mockResolvedValue(undefined) },
}))

const { current: pendingRequest, settle } = useConfirmHost()

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null
let emitted: string[] = []

async function mountHeader(aiEnabled = true): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  emitted = []
  app = createApp({
    render: () => h(AppHeader, { aiEnabled, onLogout: () => emitted.push('logout') }),
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
  // 主题状态是模块级单例：每个用例从亮色起步，避免上一个用例留下的 dark 影响断言
  useTheme().setMode('light')
  localStorage.removeItem('robot.nav.expanded')
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
  // 确认层也是模块级单例：不结算会把 promise 悬在那儿污染下一个用例
  if (pendingRequest.value) settle(false)
})

describe('顶栏四段排版（FR-UI-18）', () => {
  it('四段按「上下文 → 工具 → 偏好 → 账户」顺序存在，且恰好 3 条分隔线', async () => {
    const root = await mountHeader()
    expect(segs(root).map((el) => el.dataset.seg)).toEqual([
      'context', 'tools', 'prefs', 'account',
    ])
    expect(root.querySelectorAll('.app-sep')).toHaveLength(3)
  })

  it('「关于」按钮已移除，侧栏折叠按钮不在顶栏（各自有明确去处）', async () => {
    const root = await mountHeader()
    const labels = [...root.querySelectorAll('[aria-label]')]
      .map((el) => el.getAttribute('aria-label'))
    expect(labels).not.toContain(i18n.global.t('about.open'))
    expect(labels).not.toContain(i18n.global.t('layout.toggleSide'))
  })

  it('Tab 顺序与视觉顺序一致（DOM 顺序即焦点顺序）', async () => {
    const root = await mountHeader()
    const order = interactive(root).map((el) => el.tagName.toLowerCase())
    // 项目下拉 → 检索 → AI → 外观 → 主题 → 语言 → 登出
    expect(order).toEqual(['select', 'button', 'button', 'button', 'button', 'select', 'button'])
  })

  it('AI 未启用时不渲染 AI 按钮，控件数相应少 1（不占位、不留空壳）', async () => {
    const withAi = await mountHeader(true)
    expect(interactive(withAi)).toHaveLength(7)
    app?.unmount()
    host?.remove()

    const withoutAi = await mountHeader(false)
    expect(interactive(withoutAi)).toHaveLength(6)
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

describe('主题快捷按钮（FR-UI-16）', () => {
  it('二态切换：每次点击都在 light / dark 之间翻转，绝不出现 system', async () => {
    const root = await mountHeader()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')

    const btn = root.querySelector<HTMLButtonElement>('.app-theme-btn')!
    btn.click()
    await nextTick()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    btn.click()
    await nextTick()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(useTheme().mode.value).not.toBe('system')
  })

  it('按钮的可读名称指向「目标」而不是「当前」，避免点了没反应的错觉', async () => {
    const root = await mountHeader()
    const btn = root.querySelector<HTMLButtonElement>('.app-theme-btn')!
    expect(btn.getAttribute('aria-label')).toBe(
      i18n.global.t('theme.switchTo', { mode: i18n.global.t('theme.dark') }),
    )
  })
})

describe('登出（FR-UI-17）', () => {
  it('登出按钮带 danger 视觉，点击先出确认层而不是直接退出', async () => {
    const root = await mountHeader()
    const logout = root.querySelector<HTMLButtonElement>('.app-logout')!
    expect(logout.classList.contains('danger')).toBe(true)

    logout.click()
    await nextTick()
    expect(pendingRequest.value).not.toBeNull()
    expect(emitted).toEqual([])
  })

  it('取消确认 → 不发出 logout 事件；确认 → 发出一次', async () => {
    const root = await mountHeader()

    root.querySelector<HTMLButtonElement>('.app-logout')!.click()
    await nextTick()
    settle(false)
    await nextTick()
    expect(emitted).toEqual([])

    root.querySelector<HTMLButtonElement>('.app-logout')!.click()
    await nextTick()
    settle(true)
    await nextTick()
    expect(emitted).toEqual(['logout'])
  })
})
