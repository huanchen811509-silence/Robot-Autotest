import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import NotificationCenter from '@/components/NotificationCenter.vue'
import { useAuthStore } from '@/stores/auth'
import zhCN from '@/locales/zh-CN'

const api = vi.hoisted(() => ({
  page: vi.fn(),
  unreadCount: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
  preferences: vi.fn(),
  savePreferences: vi.fn(),
}))

vi.mock('@/api/notifications', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/notifications')>()
  return { ...actual, notificationApi: api }
})

const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

function item(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    projectId: 1,
    runKey: null,
    type: 'RUN_COMPLETED',
    status: 'unread',
    message: '运行完成',
    readAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function mountCenter(): HTMLElement {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({ render: () => h(NotificationCenter) })
  app.use(pinia!)
  app.use(i18n)
  app.mount(host)
  return host
}

async function settle(): Promise<void> {
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

function buttonByText(root: ParentNode, label: string): HTMLButtonElement | undefined {
  return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (button) => button.textContent?.trim() === label,
  )
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.token = 'token'
  auth.projectId = 1

  api.page.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  api.unreadCount.mockResolvedValue(0)
  api.markRead.mockResolvedValue(undefined)
  api.markAllRead.mockResolvedValue(undefined)
  api.preferences.mockResolvedValue([])
  api.savePreferences.mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  document.body.innerHTML = ''
  app = null
  host = null
  localStorage.clear()
  vi.clearAllMocks()
})

describe('NotificationCenter', () => {
  it('未读数超过 99 显示 99+', async () => {
    api.unreadCount.mockResolvedValue(120)
    const root = mountCenter()
    await settle()
    expect(root.querySelector('.notification-badge')?.textContent).toBe('99+')
    expect(api.unreadCount).toHaveBeenCalledWith(1)
  })

  it('打开列表后按类型分组，点击未读条目调用已读接口并移除', async () => {
    api.unreadCount.mockResolvedValue(3)
    api.page.mockResolvedValue({
      items: [
        item({ id: 1, type: 'RUN_COMPLETED', message: '运行完成' }),
        item({ id: 2, type: 'NODE_OFFLINE', message: '节点离线' }),
      ],
      total: 2,
      page: 1,
      size: 20,
    })
    const root = mountCenter()
    await settle()
    root.querySelector<HTMLButtonElement>('.notification-bell')?.click()
    await settle()

    const panel = document.body.querySelector('.notification-groups')
    expect(panel?.textContent).toContain('运行完成')
    expect(panel?.textContent).toContain('节点离线')

    document.body.querySelectorAll<HTMLButtonElement>('.notification-item')[0]?.click()
    await settle()
    expect(api.markRead).toHaveBeenCalledWith(1, 1)
    expect(document.body.querySelectorAll('.notification-item')).toHaveLength(1)
  })

  it('全部已读调用批量接口并清零徽标', async () => {
    api.unreadCount.mockResolvedValue(2)
    const root = mountCenter()
    await settle()
    root.querySelector<HTMLButtonElement>('.notification-bell')?.click()
    await settle()

    buttonByText(document.body, '全部已读')?.click()
    await settle()
    expect(api.markAllRead).toHaveBeenCalledWith(1)
    expect(root.querySelector('.notification-badge')).toBeNull()
    expect(document.body.textContent).toContain('已全部标记为已读')
  })

  it('偏好保存失败时回滚开关并提示，不留下假开关', async () => {
    const root = mountCenter()
    await settle()
    root.querySelector<HTMLButtonElement>('.notification-bell')?.click()
    await settle()
    document.body.querySelector<HTMLButtonElement>('.notification-prefs')?.click()
    await settle()

    const switches = [...document.body.querySelectorAll<HTMLElement>('.n-switch')]
    expect(switches.length).toBeGreaterThan(0)
    switches[0].click()
    await settle()
    expect(switches[0].getAttribute('aria-checked')).toBe('false')

    api.savePreferences.mockRejectedValueOnce(new Error('boom'))
    buttonByText(document.body, '保存')?.click()
    await settle()

    expect(document.body.querySelector('.notification-prefs-error')?.textContent).toContain(
      '偏好保存失败，已恢复原设置',
    )
    expect(
      document.body.querySelectorAll<HTMLElement>('.n-switch')[0].getAttribute('aria-checked'),
    ).toBe('true')
  })

  it('偏好保存成功后提示，并按四类开关提交', async () => {
    const root = mountCenter()
    await settle()
    root.querySelector<HTMLButtonElement>('.notification-bell')?.click()
    await settle()
    document.body.querySelector<HTMLButtonElement>('.notification-prefs')?.click()
    await settle()

    buttonByText(document.body, '保存')?.click()
    await settle()

    expect(api.savePreferences).toHaveBeenCalledWith(
      expect.arrayContaining([
        { type: 'RUN_COMPLETED', enabled: true },
        { type: 'RUN_FAILED', enabled: true },
        { type: 'NODE_OFFLINE', enabled: true },
        { type: 'PLAN_CONFLICT', enabled: true },
      ]),
    )
    expect(document.body.querySelector('.notification-prefs-success')?.textContent).toContain(
      '偏好已保存',
    )
  })
})
