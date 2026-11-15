/**
 * `CaseScriptLink` 四态与复制（v5.11.0 / W61-12 / FR-v5.11-05 判据 1/3/4）。
 *
 * 四种状态各锁一条：已校验、未校验、未绑定、不适用。
 * 另两条只在这里能测：tooltip 带完整相对路径（表格里只显示文件名）、点击复制的是**相对路径**。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import CaseScriptLink from './CaseScriptLink.vue'

let app: App | null = null

async function mount(props: Record<string, unknown>): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  i18n.global.locale.value = 'zh-CN'
  app = createApp({ render: () => h(CaseScriptLink as never, props as never) })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

afterEach(() => {
  app?.unmount()
  app = null
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

const BOUND = {
  scriptPath: 'cases/net/roce_bw.py',
  scriptName: 'roce_bw.py',
  bindingState: 'BOUND_VERIFIED',
}

describe('CaseScriptLink', () => {
  it('已校验：显示文件名（不是完整路径），tooltip 里是完整相对路径', async () => {
    const root = await mount(BOUND)
    expect(root.querySelector('[data-testid="case-script-name"]')?.textContent).toBe('roce_bw.py')
    expect(root.querySelector('[data-testid="case-script-path"]')).toBeNull()
    const btn = root.querySelector('[data-testid="case-script-copy"]') as HTMLElement
    expect(btn.getAttribute('title')).toContain('cases/net/roce_bw.py')
    expect(root.querySelector('[data-testid="case-script-state"]')?.textContent).toContain('已校验')
  })

  it('未校验：状态文案不同（不能混成「已校验」）', async () => {
    const root = await mount({ ...BOUND, bindingState: 'BOUND_UNVERIFIED' })
    expect(root.querySelector('[data-testid="case-script-state"]')?.textContent).toContain('未校验')
  })

  it('未绑定：显示灰标签而不是空单元格', async () => {
    const root = await mount({ bindingState: 'UNBOUND' })
    expect(root.querySelector('[data-testid="case-script-unbound"]')?.textContent).toContain(
      '未绑定脚本',
    )
    expect(root.querySelector('[data-testid="case-script-copy"]')).toBeNull()
  })

  it('不适用（RF）：只给「不适用」+ 原因，不出现任何路径', async () => {
    const root = await mount({ bindingState: 'NOT_APPLICABLE' })
    const tag = root.querySelector('[data-testid="case-script-na"]') as HTMLElement
    expect(tag.textContent).toContain('不适用')
    expect(tag.getAttribute('title')).toContain('RF')
    expect(root.textContent).not.toMatch(/\.py|\.robot/)
  })

  it('多内核绑定：显示 +N 且在 tooltip 里说明', async () => {
    const root = await mount({ ...BOUND, otherScriptCount: 2 })
    expect(root.querySelector('[data-testid="case-script-more"]')?.textContent).toBe('+2')
    const btn = root.querySelector('[data-testid="case-script-copy"]') as HTMLElement
    expect(btn.getAttribute('title')).toContain('2')
  })

  it('点击复制的是相对路径，成功后有反馈', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const root = await mount(BOUND)

    ;(root.querySelector('[data-testid="case-script-copy"]') as HTMLButtonElement).click()
    await nextTick()
    await nextTick()

    expect(writeText).toHaveBeenCalledWith('cases/net/roce_bw.py')
    expect(root.querySelector('[data-testid="case-script-copied"]')).not.toBeNull()
  })

  it('绝对路径脏数据：截断后的路径 + tooltip 注明原始值为绝对路径', async () => {
    const root = await mount({ ...BOUND, absoluteSource: true })
    const btn = root.querySelector('[data-testid="case-script-copy"]') as HTMLElement
    expect(btn.getAttribute('title')).toContain('绝对路径')
    expect(btn.getAttribute('title')).not.toContain('/home/')
  })
})
