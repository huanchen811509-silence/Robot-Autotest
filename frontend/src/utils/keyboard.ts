/**
 * 全局快捷键的输入控件判定。
 *
 * 解决什么问题：`?` 是普通字符，用户在搜索框里敲问号时不能把快捷键面板弹出来。
 * 这里统一判断 input / textarea / select / contenteditable，App 只用这一处。
 */
export function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || typeof el.tagName !== 'string') return false
  const tag = el.tagName.toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable
}
