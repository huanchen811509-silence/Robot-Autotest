/**
 * 焦点陷阱（FR-3.1-10）。
 *
 * 解决什么问题：命令面板、右侧抽屉各自写一套 Esc 与焦点归还，容易漏掉移除监听；
 * 打开后若不用 Tab 循环，键盘焦点会跑到遮罩后的页面。这里统一成一份：
 * 打开时记住触发元素并把焦点送进容器，Tab/Shift+Tab 在容器内首尾循环，
 * 关闭或卸载时把焦点还给触发元素。
 */
import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue'

export interface FocusTrapOptions {
  /** 容器内按 Esc 时回调（通常是关闭弹层）。 */
  onEscape?: () => void
}

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

export function getFocusableElements(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    (el) =>
      !el.hasAttribute('hidden') &&
      el.getAttribute('aria-hidden') !== 'true' &&
      !el.closest('[inert]'),
  )
}

export function useFocusTrap(
  containerRef: Ref<HTMLElement | null>,
  active: Readonly<Ref<boolean>>,
  options: FocusTrapOptions = {},
): void {
  let trigger: HTMLElement | null = null
  let listening = false

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      options.onEscape?.()
      return
    }
    if (event.key !== 'Tab') return

    const container = containerRef.value
    if (!container) return
    const focusable = getFocusableElements(container)
    if (focusable.length === 0) {
      event.preventDefault()
      container.focus()
      return
    }

    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    const current = document.activeElement as HTMLElement | null
    const outside = !current || !container.contains(current)

    if (event.shiftKey) {
      if (outside || current === first) {
        event.preventDefault()
        last.focus()
      }
      return
    }
    if (outside || current === last) {
      event.preventDefault()
      first.focus()
    }
  }

  async function activate(): Promise<void> {
    trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.addEventListener('keydown', onKeydown, true)
    listening = true
    await nextTick()
    const container = containerRef.value
    if (!container) return
    const first = getFocusableElements(container)[0]
    ;(first ?? container).focus()
  }

  function deactivate(): void {
    if (listening) {
      document.removeEventListener('keydown', onKeydown, true)
      listening = false
    }
    if (trigger?.isConnected) trigger.focus()
    trigger = null
  }

  watch(active, (open) => (open ? void activate() : deactivate()), { immediate: true })

  onBeforeUnmount(deactivate)
}
