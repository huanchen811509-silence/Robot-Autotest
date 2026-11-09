import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import SelectionBar from '@/components/SelectionBar.vue'
import zhCN from '@/locales/zh-CN'
import type { BatchResult } from '@/composables/useSelection'

vi.mock('@/api/undo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/undo')>()
  return { ...actual, undoBatch: vi.fn() }
})

import { undoBatch } from '@/api/undo'

const undoBatchMock = vi.mocked(undoBatch)
const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })

let app: App | null = null
let host: HTMLElement | null = null

function mountBar(props: Record<string, unknown>, onUndone = vi.fn()): HTMLElement {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({
    render: () =>
      h(SelectionBar, { count: 0, crossPage: 0, result: null, projectId: 7, ...props, onUndone }, {
        default: () => h('span', 'actions'),
      }),
  })
  app.use(i18n)
  app.mount(host)
  return host
}

async function settle(): Promise<void> {
  await nextTick()
  await nextTick()
  await Promise.resolve()
}

function reversibleResult(): BatchResult & Record<string, unknown> {
  return {
    total: 3,
    succeeded: 3,
    failed: 0,
    items: [],
    undoToken: 'tok-1',
    undoUntil: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
  }
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('SelectionBar 批量撤销', () => {
  it('可逆动作成功后展示「已影响 N 项」与倒计时', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const root = mountBar({ count: 3, result: reversibleResult(), action: 'setPriority' })
    await settle()
    expect(root.textContent).toContain('已影响 3 项')
    expect(root.textContent).toContain('05:00')
  })

  it('删除等不可逆动作不显示撤销，明确提示删除不可撤销', async () => {
    const root = mountBar({ count: 0, result: reversibleResult(), action: 'delete' })
    await settle()
    expect(root.textContent).toContain('删除不可撤销')
    expect(root.textContent).not.toContain('5 分钟内可撤销')
  })

  it('倒计时结束自动隐藏撤销条', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const root = mountBar({ count: 0, result: reversibleResult(), action: 'approve' })
    await settle()
    expect(root.querySelector('.selection-undo')).not.toBeNull()

    vi.advanceTimersByTime(5 * 60 * 1000 + 1000)
    await settle()
    expect(root.querySelector('.selection-undo')).toBeNull()
  })

  it('撤销部分失败时展示恢复数与最多 3 条失败原因', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const root = mountBar({ count: 0, result: reversibleResult(), action: 'submit' })
    await settle()

    undoBatchMock.mockResolvedValue({
      action: 'submit',
      restored: 2,
      failed: 1,
      items: [
        { id: 4, ok: true, message: null },
        { id: 9, ok: false, message: '已被他人修改' },
      ],
    })
    root.querySelector<HTMLButtonElement>('.selection-undo__btn')?.click()
    await settle()

    expect(root.textContent).toContain('已恢复 2 项，1 项失败')
    expect(root.textContent).toContain('#9：已被他人修改')
    expect(undoBatchMock).toHaveBeenCalledWith(7, 'tok-1')
  })

  it('撤销请求失败时提示重试，且不误报已恢复', async () => {
    const root = mountBar({ count: 0, result: reversibleResult(), action: 'approve' })
    await settle()
    undoBatchMock.mockRejectedValueOnce(new Error('boom'))
    root.querySelector<HTMLButtonElement>('.selection-undo__btn')?.click()
    await settle()
    expect(root.textContent).toContain('撤销失败，请重试')
    expect(root.textContent).not.toContain('已恢复')
  })
})
