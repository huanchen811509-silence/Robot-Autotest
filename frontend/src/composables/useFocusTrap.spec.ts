/**
 * 焦点陷阱（FR-3.1-10）。
 *
 * 命令面板 / 侧栏 / AI 抽屉共用这一个实现，所以这里测的是契约：
 * 打开聚焦首个可聚焦元素、Tab 与 Shift+Tab 首尾循环、
 * 关闭后焦点回到打开前的触发元素。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { useFocusTrap } from '@/composables/useFocusTrap'

let app: App | null = null
let host: HTMLElement | null = null
let trigger: HTMLButtonElement | null = null

afterEach(() => {
  app?.unmount()
  host?.remove()
  trigger?.remove()
  app = null
  host = null
  trigger = null
})

describe('useFocusTrap', () => {
  it('打开时聚焦首个元素，Tab / Shift+Tab 在容器内循环，Esc 回调', async () => {
    const active = ref(false)
    const container = ref<HTMLElement | null>(null)
    const onEscape = vi.fn(() => {
      active.value = false
    })

    const Host = defineComponent({
      setup() {
        useFocusTrap(container, active, { onEscape })
        return () =>
          h('div', { ref: container, 'data-test': 'trap' }, [
            h('button', { id: 'first' }, 'first'),
            h('button', { id: 'last' }, 'last'),
          ])
      },
    })

    trigger = document.createElement('button')
    trigger.textContent = 'trigger'
    document.body.appendChild(trigger)
    trigger.focus()

    host = document.createElement('div')
    document.body.appendChild(host)
    app = createApp(Host)
    app.mount(host)
    await nextTick()

    active.value = true
    await nextTick()
    await nextTick()

    expect(document.activeElement?.id).toBe('first')

    const first = host.querySelector<HTMLButtonElement>('#first')!
    const last = host.querySelector<HTMLButtonElement>('#last')!

    last.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    expect(document.activeElement).toBe(first)

    first.focus()
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }),
    )
    expect(document.activeElement).toBe(last)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(onEscape).toHaveBeenCalledTimes(1)

    active.value = false
    await nextTick()
    expect(document.activeElement).toBe(trigger)
  })

  it('容器没有可聚焦元素时焦点落在容器本身', async () => {
    const active = ref(false)
    const container = ref<HTMLElement | null>(null)

    const Host = defineComponent({
      setup() {
        useFocusTrap(container, active)
        return () => h('div', { ref: container, tabindex: -1, 'data-test': 'empty' })
      },
    })

    host = document.createElement('div')
    document.body.appendChild(host)
    app = createApp(Host)
    app.mount(host)
    await nextTick()

    active.value = true
    await nextTick()
    await nextTick()

    expect(document.activeElement).toBe(host.querySelector('[data-test="empty"]'))
  })
})
