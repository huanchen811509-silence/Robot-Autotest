import { readonly, ref } from 'vue'

/**
 * UX-04 步骤 5：把 `nativeConfirm` 换成统一 Modal（Promise 化）。
 *
 * 原生 confirm 有三个问题：亮/暗主题下长得像浏览器弹窗、无法双语、阻塞 JS 线程
 * （测试里也没法断言）。这里用模块级单例队列 + Promise，调用点只要把
 * `if (!nativeConfirm(msg)) return` 改成 `if (!(await confirmAction(msg))) return`，
 * 行为完全等价，且 Esc / 遮罩点击都等价于「取消」。
 *
 * 同时只显示一个：新请求到达时把上一个按 false 结算，避免旧的 Promise 永远 pending。
 */

type ConfirmTone = 'default' | 'danger'

type ConfirmRequest = {
  message: string
  tone: ConfirmTone
  resolve: (value: boolean) => void
}

const current = ref<ConfirmRequest | null>(null)

export function confirmAction(
  message: string,
  options: { tone?: ConfirmTone } = {},
): Promise<boolean> {
  if (current.value) current.value.resolve(false)
  return new Promise<boolean>((resolve) => {
    current.value = { message, tone: options.tone ?? 'default', resolve }
  })
}

function settle(value: boolean): void {
  const request = current.value
  current.value = null
  request?.resolve(value)
}

export function useConfirmHost() {
  return {
    current: readonly(current),
    settle,
  }
}
