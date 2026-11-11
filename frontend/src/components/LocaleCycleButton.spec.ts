/**
 * 顶栏语言循环快捷按钮（v4.9.2 / OPT-03 / FR-49-10~12）。
 *
 * 用 vi.mock 隔离 locale store / i18n / preferenceApi，不依赖真实 localStorage；
 * locale store 用 vi.hoisted 的可变状态替身，验证 2 次点击回初态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import LocaleCycleButton from '@/components/LocaleCycleButton.vue'

const { put, localeState } = vi.hoisted(() => ({
  put: vi.fn().mockResolvedValue({}),
  localeState: { value: 'zh-CN' as string },
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('@/api/preferences', () => ({
  preferenceApi: { get: vi.fn(), put },
}))
vi.mock('@/stores/locale', () => ({
  useLocaleStore: () => ({
    get locale() {
      return localeState.value
    },
    setLocale(next: string) {
      localeState.value = next
    },
  }),
}))

let app: App | null = null
let host: HTMLElement | null = null

async function mountButton(): Promise<HTMLButtonElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({ render: () => h(LocaleCycleButton) })
  app.mount(host)
  await nextTick()
  return host.querySelector('button')!
}

beforeEach(() => {
  localeState.value = 'zh-CN'
  put.mockClear()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('LocaleCycleButton', () => {
  it('zh-CN ↔ en-US，点击 2 次回初态', async () => {
    const button = await mountButton()
    button.click()
    await nextTick()
    expect(localeState.value).toBe('en-US')
    button.click()
    await nextTick()
    expect(localeState.value).toBe('zh-CN')
  })

  it('沿用 preferenceApi.put 把语言持久化到服务端', async () => {
    const button = await mountButton()
    button.click()
    await nextTick()
    expect(put).toHaveBeenCalledWith({ locale: 'en-US' })
  })

  it('有 aria-label（i18n 键）且天然可 Tab 到达', async () => {
    const button = await mountButton()
    expect(button.getAttribute('aria-label')).toBe('nav.localeCycle')
    expect(button.tabIndex).toBeGreaterThanOrEqual(0)
  })
})
