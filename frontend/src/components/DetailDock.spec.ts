/**
 * `DetailDock` 行为（v5.11.0 / W61-01 / FR-v5.11-01）。
 *
 * 组件本身只负责四件事，且都能在这里钉住：
 * ① 收起时不渲染；② 展开时标题 + 插槽内容可见；③ 关闭按钮发事件；
 * ④ 展开时用 `block:'nearest'` 滚动（不是把页面拽到底部）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import DetailDock from './DetailDock.vue'

let app: App | null = null

function mount(props: { open: boolean; title?: string }, slot?: string): HTMLElement {
  const root = document.createElement('div')
  document.body.appendChild(root)
  i18n.global.locale.value = 'zh-CN'
  app = createApp({
    render: () =>
      h(
        DetailDock as never,
        { ...props, onClose: () => onClose() } as never,
        slot ? { default: () => h('p', { class: 'probe' }, slot) } : undefined,
      ),
  })
  app.use(i18n)
  app.mount(root)
  return root
}

const onClose = vi.fn()

afterEach(() => {
  app?.unmount()
  app = null
  document.body.innerHTML = ''
  onClose.mockReset()
})

describe('DetailDock', () => {
  it('收起状态不渲染任何节点', async () => {
    const root = mount({ open: false })
    await nextTick()
    expect(root.querySelector('[data-testid="detail-dock"]')).toBeNull()
  })

  it('展开后标题与插槽内容都可见，且只有一份', async () => {
    const root = mount({ open: true, title: 'CASE-1 标题' }, '内容')
    await nextTick()
    expect(root.querySelectorAll('[data-testid="detail-dock"]')).toHaveLength(1)
    expect(root.querySelector('[data-testid="detail-dock-title"]')?.textContent).toContain('CASE-1')
    expect(root.querySelector('.probe')?.textContent).toBe('内容')
  })

  it('关闭按钮发出 close 事件', async () => {
    const root = mount({ open: true })
    await nextTick()
    ;(root.querySelector('[data-testid="detail-dock-close"]') as HTMLButtonElement).click()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('展开时按 block:nearest 滚动（不滚到页面底部）', async () => {
    const spy = vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(() => {})
    mount({ open: true })
    await nextTick()
    await nextTick()
    expect(spy).toHaveBeenCalled()
    // scrollIntoView 只有一个参数：{ block, inline }
    expect(spy.mock.calls[0][0]).toMatchObject({ block: 'nearest' })
    spy.mockRestore()
  })
})
