/**
 * 应用骨架的无障碍入口（FR-3.1-10）。
 *
 * 跳过链接依赖「锚点 + 主内容 id」同时存在才有意义，少一个就失效。
 * 这里用 `?raw` 读模板做结构断言；视觉焦点样式由 main.css 的 .skip-link 负责。
 */
import { describe, expect, it } from 'vitest'
import appSource from '@/App.vue?raw'

describe('App 骨架', () => {
  it('提供跳到主内容的 skip link，且指向 <main id="main">', () => {
    expect(appSource).toContain('class="skip-link"')
    expect(appSource).toContain('href="#main"')
    expect(appSource).toMatch(/<main[^>]*id="main"/)
    expect(appSource).toContain("t('a11y.skipToMain')")
  })

  it('主内容可被程序聚焦（tabindex=-1），且快捷键面板挂在应用内', () => {
    expect(appSource).toMatch(/<main[^>]*tabindex="-1"/)
    expect(appSource).toContain('<ShortcutPanel')
  })
})
