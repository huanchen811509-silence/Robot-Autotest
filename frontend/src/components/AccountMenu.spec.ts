/**
 * 账户菜单回归（v4.0 / FR-v4.0-03）。
 *
 * 锁四件事：
 * ① 固定四分区且顺序不变：状态（只读）→ 外观 → 管理与帮助 → 会话；
 * ② 状态区不出现 input / select——「看着能改、其实不生效」是最坏的假象；
 * ③ 管理入口按能力显隐：admin 全见，engineer 看不到设置/审计/运维；
 * ④ 登出先确认，取消不得真的退出。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { useConfirmHost } from '@/composables/useConfirm'
import { settingsApi } from '@/api/settings'
import AccountMenu from '@/components/AccountMenu.vue'

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

const { current: pendingRequest, settle } = useConfirmHost()

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null
let emitted: string[] = []

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

function setRole(roles: string[]): void {
  const auth = useAuthStore()
  auth.user = {
    id: 1,
    username: 'qa-e2e',
    displayName: '测试用户',
    roles,
  } as unknown as typeof auth.user
  auth.projects = [
    { id: 1, code: 'P-1', name: '项目一', enabled: true, visibility: 'private', ownerId: 1 },
  ] as unknown as typeof auth.projects
  auth.projectId = 1
}

async function mountMenu(roles: string[] = ['admin']): Promise<HTMLElement> {
  setRole(roles)
  host = document.createElement('div')
  document.body.appendChild(host)
  emitted = []
  app = createApp({
    render: () => h(AccountMenu, {
      onLogout: () => emitted.push('logout'),
      onOpenShortcuts: () => emitted.push('shortcuts'),
    }),
  })
  app.use(pinia!)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return host
}

async function openPanel(root: HTMLElement): Promise<HTMLElement> {
  root.querySelector<HTMLButtonElement>('.account-trigger')!.click()
  await nextTick()
  await flush()
  await nextTick()
  return root.querySelector<HTMLElement>('.account-panel')!
}

function linkTexts(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLElement>('.account-link')]
    .map((el) => el.textContent?.trim() ?? '')
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
  if (pendingRequest.value) settle(false)
})

describe('菜单结构与状态区', () => {
  it('默认收起；点击后打开四分区，顺序为 状态 → 外观 → 管理与帮助 → 会话', async () => {
    const root = await mountMenu()
    expect(root.querySelector('.account-panel')).toBeNull()

    const panel = await openPanel(root)
    expect(panel.getAttribute('role')).toBe('menu')
    const sections = [...panel.querySelectorAll<HTMLElement>('.account-section')]
    expect(sections).toHaveLength(4)
    expect(sections[0].querySelector('.account-identity')).not.toBeNull()
    const titles = sections
      .map((section) => section.querySelector('.account-section__title')?.textContent?.trim())
      .filter(Boolean)
    expect(titles).toEqual([
      i18n.global.t('accountMenu.appearance'),
      i18n.global.t('accountMenu.admin'),
      i18n.global.t('accountMenu.session'),
    ])
  })

  it('状态区只读：没有任何 input / select / textarea', async () => {
    const root = await mountMenu()
    const panel = await openPanel(root)
    const status = panel.querySelectorAll('.account-section')[0]
    expect(status.querySelectorAll('input, select, textarea')).toHaveLength(0)
    expect(status.querySelectorAll('dl dt')).toHaveLength(3)
  })

  it('打开时拉取服务状态与版本：ok → 正常，版本来自 /_ping', async () => {
    const root = await mountMenu()
    const panel = await openPanel(root)
    expect(panel.querySelector('.account-health')!.getAttribute('data-state')).toBe('ok')
    expect(panel.querySelector('.account-version')?.textContent?.trim()).toBe('4.2.0')
  })

  it('后端不可达时降级为「降级」，菜单本身仍可用', async () => {
    vi.mocked(settingsApi.health).mockRejectedValueOnce(new Error('down'))
    const root = await mountMenu()
    const panel = await openPanel(root)
    expect(panel.querySelector('.account-health')!.getAttribute('data-state')).toBe('degraded')
    expect(panel.querySelector('.account-version')?.textContent?.trim()).toBe('4.2.0')
  })
})

describe('管理入口按能力显隐', () => {
  it('admin 能看到 设置 / 审计 / 运维 / 关于 / 快捷键 / 版本迭代', async () => {
    const root = await mountMenu(['admin'])
    const panel = await openPanel(root)
    const texts = linkTexts(panel)
    for (const key of [
      'accountMenu.settings',
      'accountMenu.audit',
      'accountMenu.ops',
      'about.open',
      'shortcuts.title',
      'accountMenu.changelog',
    ]) {
      expect(texts).toContain(i18n.global.t(key))
    }
  })

  it('engineer 看不到 设置 / 审计 / 运维，但保留 关于 / 快捷键 / 版本迭代', async () => {
    const root = await mountMenu(['engineer'])
    const panel = await openPanel(root)
    const texts = linkTexts(panel)
    expect(texts).not.toContain(i18n.global.t('accountMenu.settings'))
    expect(texts).not.toContain(i18n.global.t('accountMenu.audit'))
    expect(texts).not.toContain(i18n.global.t('accountMenu.ops'))
    expect(texts).toContain(i18n.global.t('about.open'))
    expect(texts).toContain(i18n.global.t('shortcuts.title'))
    expect(texts).toContain(i18n.global.t('accountMenu.changelog'))
  })
})

describe('外观分区', () => {
  it('主题三态 / 六强调色 / 双密度 / 语言选择都在菜单里', async () => {
    const root = await mountMenu()
    const panel = await openPanel(root)
    expect(panel.querySelectorAll('.appearance-modes .mode-btn')).toHaveLength(5)
    expect(panel.querySelectorAll('.swatch')).toHaveLength(6)
    expect(panel.querySelectorAll('.appearance-lang select')).toHaveLength(1)
  })
})

describe('关闭与登出', () => {
  it('Esc 关闭面板', async () => {
    const root = await mountMenu()
    const panel = await openPanel(root)
    panel.querySelector<HTMLElement>('.mode-btn')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    )
    await nextTick()
    expect(root.querySelector('.account-panel')).toBeNull()
  })

  it('点面板外关闭', async () => {
    const root = await mountMenu()
    await openPanel(root)
    document.body.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    await nextTick()
    expect(root.querySelector('.account-panel')).toBeNull()
  })

  it('登出先出确认；取消不发事件，确认才退出一次', async () => {
    const root = await mountMenu()
    const panel = await openPanel(root)
    const logout = panel.querySelector<HTMLButtonElement>('.account-link--danger')!

    logout.click()
    await nextTick()
    expect(pendingRequest.value).not.toBeNull()
    expect(emitted).toEqual([])

    settle(false)
    await nextTick()
    expect(emitted).toEqual([])

    logout.click()
    await nextTick()
    settle(true)
    await nextTick()
    expect(emitted).toEqual(['logout'])
  })

  it('快捷键入口只发事件、不跳路由', async () => {
    const root = await mountMenu()
    const panel = await openPanel(root)
    const shortcut = [...panel.querySelectorAll<HTMLButtonElement>('.account-link')]
      .find((el) => el.textContent?.trim() === i18n.global.t('shortcuts.title'))!
    shortcut.click()
    await nextTick()
    expect(emitted).toEqual(['shortcuts'])
  })
})
