/**
 * FR-v3.9-03 · 命令一键复制（v3.12-03「一键复跑」也会复用这一层）。
 *
 * 为什么不用 `navigator.clipboard.writeText` 一把梭：
 * ① 它只在安全上下文（https / localhost）可用，而现场常用 `http://<内网IP>:8001`
 *    访问，这种页面里 `navigator.clipboard` 是 undefined，直接调会抛错；
 * ② 无头浏览器做 E2E 时权限也常被拒。
 * 所以先试 Clipboard API，失败再退到「临时 textarea + execCommand('copy')」的老路，
 * 两条都不通就返回 false，由调用方给出可见提示——**静默失败比不给按钮更糟**。
 */

/** 复制文本。成功返回 true；两条通路都失败返回 false，调用方负责提示。 */
export async function copyText(text: string): Promise<boolean> {
  const value = text ?? ''
  if (!value) return false

  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch {
    // 权限被拒 / 非安全上下文：落到下面的兜底
  }

  return legacyCopy(value)
}

/** 兜底通路：隐藏 textarea 选中后 execCommand('copy')，用完立刻摘掉，不留 DOM 垃圾。 */
function legacyCopy(value: string): boolean {
  if (typeof document === 'undefined') return false
  const area = document.createElement('textarea')
  area.value = value
  // 不能 display:none，否则选不中；用固定定位挪出视口。
  area.setAttribute('readonly', 'readonly')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  try {
    area.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(area)
  }
}
