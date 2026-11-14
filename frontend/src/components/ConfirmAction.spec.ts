/**
 * W57-36 · 危险操作统一确认单测。
 *
 * 页面里的软删确认必须把「影响面写清楚」：具体条数来自后端（count / impacts），
 * 前端不臆造。本组件不依赖 naive-ui（纯原生 button + mask），直接挂载即可测。
 * 覆盖：条数文案、impacts 求和与明细、confirm/cancel emit 纪律、遮罩 self 点击、
 * loading 禁用、自定义文案。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import ConfirmAction from './ConfirmAction.vue'

function makeSpies() {
  return {
    show: vi.fn(),
    confirm: vi.fn(),
    cancel: vi.fn(),
  }
}
type Spies = ReturnType<typeof makeSpies>

function handlers(s: Spies): Record<string, unknown> {
  return {
    'onUpdate:show': s.show,
    onConfirm: s.confirm,
    onCancel: s.cancel,
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function mountConfirm(
  props: Record<string, unknown> = {},
  s: Spies = makeSpies(),
): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () =>
      h(ConfirmAction as never, { show: true, count: 3, ...props, ...handlers(s) } as never),
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('ConfirmAction 影响面文案', () => {
  it('show=false 不渲染任何确认层', async () => {
    const root = await mountConfirm({ show: false })
    expect(root.querySelector('.confirm-mask')).toBeNull()
  })

  it('文案写明具体条数（count 来自后端）', async () => {
    const root = await mountConfirm({ count: 7 })
    expect(root.querySelector('.confirm-count')?.textContent).toBe('7')
    expect(root.querySelector('.confirm-lead')?.textContent).toContain('7 条记录')
    expect(root.textContent).toContain('删除后不可撤销')
  })

  it('count=0 时按 impacts 明细求和', async () => {
    const root = await mountConfirm({
      count: 0,
      impacts: [
        { label: '关联测试报告导出', count: 3 },
        { label: '运行产物', count: 2 },
      ],
    })
    expect(root.querySelector('.confirm-count')?.textContent).toBe('5')
  })

  it('count>0 时优先用 count，不被 impacts 覆盖', async () => {
    const root = await mountConfirm({ count: 9, impacts: [{ label: '明细', count: 1 }] })
    expect(root.querySelector('.confirm-count')?.textContent).toBe('9')
  })

  it('impacts 逐项展开，避免用户以为只删了一行', async () => {
    const root = await mountConfirm({
      impacts: [
        { label: '导出记录', count: 4 },
        { label: '产物', count: 1 },
      ],
    })
    const items = root.querySelectorAll('.confirm-impacts li')
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toContain('导出记录')
    expect(items[0].textContent).toContain('4')
  })

  it('description 补充说明渲染在面板里', async () => {
    const root = await mountConfirm({ description: '软删后导出链接不再可用' })
    expect(root.querySelector('.confirm-desc')?.textContent).toContain('软删后导出链接不再可用')
  })
})

describe('ConfirmAction emit 纪律与状态', () => {
  it('确认只发 confirm，不自动关闭（关闭由宿主按请求结果决定）', async () => {
    const s = makeSpies()
    const root = await mountConfirm({}, s)
    root.querySelector<HTMLElement>('[data-testid="confirm-ok"]')!.click()
    await nextTick()
    expect(s.confirm).toHaveBeenCalledTimes(1)
    expect(s.show).not.toHaveBeenCalled()
  })

  it('取消发 cancel 且请求 update:show=false', async () => {
    const s = makeSpies()
    const root = await mountConfirm({}, s)
    root.querySelector<HTMLElement>('[data-testid="confirm-cancel"]')!.click()
    await nextTick()
    expect(s.cancel).toHaveBeenCalledTimes(1)
    expect(s.show).toHaveBeenCalledWith(false)
  })

  it('点击遮罩自身（click.self）等同取消', async () => {
    const s = makeSpies()
    const root = await mountConfirm({}, s)
    root.querySelector('.confirm-mask')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(s.cancel).toHaveBeenCalledTimes(1)
    expect(s.show).toHaveBeenCalledWith(false)
  })

  it('loading 时确认按钮禁用并显示「处理中…」', async () => {
    const root = await mountConfirm({ loading: true })
    const ok = root.querySelector<HTMLButtonElement>('[data-testid="confirm-ok"]')!
    expect(ok.disabled).toBe(true)
    expect(ok.textContent).toContain('处理中')
  })

  it('自定义标题 / 按钮文案生效', async () => {
    const root = await mountConfirm({ title: '确认归档', confirmText: '归档', cancelText: '返回' })
    expect(root.querySelector('.confirm-title')?.textContent).toBe('确认归档')
    expect(root.querySelector('[data-testid="confirm-ok"]')?.textContent).toContain('归档')
    expect(root.querySelector('[data-testid="confirm-cancel"]')?.textContent).toContain('返回')
  })

  it('无障碍语义：dialog / aria-modal / aria-label', async () => {
    const root = await mountConfirm()
    const dialog = root.querySelector('.confirm-dialog')!
    expect(dialog.getAttribute('role')).toBe('dialog')
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expect(dialog.getAttribute('aria-label')).toBe('危险操作确认')
  })
})
