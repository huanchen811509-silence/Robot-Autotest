/**
 * `AppLogo` 组件级回归（v4.5.0 / UX-05 品牌）。
 *
 * v4.5 起标记唯一来源是 `assets/brand/logo-mark.svg`（白泽），组件按 `?raw` 内联，
 * 不再手写几何。这里固定四件事：形状来自真实资产、无文字、颜色走 token、尺寸落到盒子。
 */
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import AppLogo from './AppLogo.vue'

let app: App | null = null
let host: HTMLElement | null = null

async function mount(props: Record<string, unknown>): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({ render: () => h(AppLogo, props) })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AppLogo', () => {
  it('几何标记来自品牌资产：多段路径且无文字', async () => {
    const root = await mount({})
    const svg = root.querySelector('.app-logo__mark svg')
    expect(svg).not.toBeNull()
    expect(svg!.querySelectorAll('path').length).toBeGreaterThanOrEqual(1)
    expect(svg!.querySelectorAll('text')).toHaveLength(0)
    expect(svg!.querySelectorAll('image')).toHaveLength(0)
  })

  it('viewBox 与资产一致（0 38 1254 1040），换形时两边才对照得上', async () => {
    const root = await mount({})
    expect(root.querySelector('svg')!.getAttribute('viewBox')).toBe('0 38 1254 1040')
  })

  it('size 落到盒子尺寸上，不靠外层 padding 撑', async () => {
    const root = await mount({ size: 40 })
    const box = root.querySelector<HTMLElement>('.app-logo__mark')!
    expect(box.style.width).toBe('40px')
    expect(box.style.height).toBe('40px')
    // 尺寸由内联样式驱动，SVG 填满该盒子（样式由 scoped CSS 给出，jsdom 不做布局）
    expect(box.querySelector('svg')).not.toBeNull()
  })

  it('withWordmark 才渲染字标', async () => {
    const without = await mount({})
    expect(without.querySelector('.app-logo__word')).toBeNull()
    app?.unmount()
    host?.remove()

    const withWord = await mount({ withWordmark: true })
    expect(withWord.querySelector('.app-logo__word')?.textContent).toContain(i18n.global.t('app.title'))
  })

  it('颜色走 token 而不是硬编码（换强调色时标记必须跟着变）', async () => {
    const root = await mount({})
    const html = root.innerHTML
    expect(html).not.toMatch(/#[0-9a-f]{3,6}/i)
    // 颜色由父级 `--logo-fg` 决定，路径本身只声明 currentColor
    const group = root.querySelector('.app-logo__mark svg g')!
    expect(group.getAttribute('fill')).toBe('currentColor')
  })
})
