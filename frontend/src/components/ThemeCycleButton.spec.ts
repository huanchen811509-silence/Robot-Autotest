/**
 * 顶栏主题循环快捷按钮（v4.9.2 / OPT-03 / FR-49-10~12）。
 *
 * 用 vi.mock 隔离持久化边界（preferenceApi）与 i18n；useTheme 用真实单例
 * （模块级纯状态机，配合 test-setup 的内存 localStorage 垫片，不依赖真实浏览器存储），
 * 以真正验证按钮复用的是 useTheme.cycle() 的三态循环。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { useTheme } from '@/composables/useTheme'
import ThemeCycleButton from '@/components/ThemeCycleButton.vue'

const { put } = vi.hoisted(() => ({ put: vi.fn().mockResolvedValue({}) }))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('@/api/preferences', () => ({
  preferenceApi: { get: vi.fn(), put },
}))

let app: App | null = null
let host: HTMLElement | null = null

async function mountButton(): Promise<HTMLButtonElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({ render: () => h(ThemeCycleButton) })
  app.mount(host)
  await nextTick()
  return host.querySelector('button')!
}

beforeEach(() => {
  // 三态循环是模块级单例，每个用例从 system 起算
  useTheme().setMode('system')
  put.mockClear()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('ThemeCycleButton', () => {
  it('点击 4 次状态序列严格 system → light → dark → system', async () => {
    const button = await mountButton()
    const theme = useTheme()
    const seen: string[] = []
    for (let i = 0; i < 4; i++) {
      seen.push(theme.mode.value)
      button.click()
      await nextTick()
    }
    expect(seen).toEqual(['system', 'light', 'dark', 'system'])
    // 三态循环闭合：第 4 次点击继续到 light
    expect(theme.mode.value).toBe('light')
  })

  it('3 次点击回到初态 system（system → light → dark → system）', async () => {
    const button = await mountButton()
    const theme = useTheme()
    button.click()
    await nextTick()
    button.click()
    await nextTick()
    button.click()
    await nextTick()
    expect(theme.mode.value).toBe('system')
  })

  it('沿用 preferenceApi.put 把主题持久化到服务端', async () => {
    const button = await mountButton()
    button.click()
    await nextTick()
    expect(put).toHaveBeenCalledWith({ theme: 'light' })
  })

  it('有 aria-label（i18n 键）且天然可 Tab 到达', async () => {
    const button = await mountButton()
    expect(button.getAttribute('aria-label')).toBe('nav.themeCycle')
    expect(button.tabIndex).toBeGreaterThanOrEqual(0)
  })
})
