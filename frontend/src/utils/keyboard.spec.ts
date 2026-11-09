/**
 * `?` 快捷键的输入框过滤（FR-3.1-09）。
 *
 * 用户在搜索框里打问号必须得到问号，而不是被快捷键面板抢走。
 */
import { describe, expect, it } from 'vitest'
import { isEditableTarget } from '@/utils/keyboard'

function el(tag: string, contentEditable = false): HTMLElement {
  const node = document.createElement(tag)
  node.contentEditable = contentEditable ? 'true' : 'false'
  return node
}

describe('isEditableTarget', () => {
  it.each(['input', 'textarea', 'select'])('把 <%s> 视为输入控件', (tag) => {
    expect(isEditableTarget(el(tag))).toBe(true)
  })

  it('把 contenteditable 区域视为输入控件', () => {
    expect(isEditableTarget(el('div', true))).toBe(true)
  })

  it('普通元素与 null 不拦截快捷键', () => {
    expect(isEditableTarget(el('button'))).toBe(false)
    expect(isEditableTarget(el('div'))).toBe(false)
    expect(isEditableTarget(null)).toBe(false)
  })
})
