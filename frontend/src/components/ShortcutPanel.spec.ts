/**
 * 快捷键面板（FR-3.1-09）。
 *
 * 面板复用 SidePanel，所以这里同时验证两件事：五条快捷键都列出来；
 * Esc 会被焦点陷阱接住并发出关闭事件（键盘用户不需要去找关闭按钮）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import ShortcutPanel from '@/components/ShortcutPanel.vue'

let app: App | null = null

beforeEach(() => {
  i18n.global.locale.value = 'zh-CN'
})

afterEach(() => {
  app?.unmount()
  app = null
})

describe('ShortcutPanel', () => {
  it('列出 Ctrl/Cmd+K、?、Esc、↑↓、Enter，并支持 Esc 关闭', async () => {
    const onUpdate = vi.fn()
    app = createApp({
      render: () => h(ShortcutPanel, { open: true, 'onUpdate:open': onUpdate }),
    })
    app.use(i18n)
    app.mount(document.createElement('div'))
    await nextTick()

    expect(document.querySelectorAll('.shortcut-item')).toHaveLength(5)
    expect(document.body.textContent).toContain('Ctrl / ⌘')
    expect(document.body.textContent).toContain('全局搜索')
    expect(document.body.textContent).toContain('关闭当前弹层')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(onUpdate).toHaveBeenCalledWith(false)
  })
})
