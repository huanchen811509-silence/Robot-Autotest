/**
 * v3.3.0 · 证据工作台（FR-3.3-01..08）：
 * 流式增量必须逐字上屏、引用 chip 可见、生成中可停止、草稿未勾「已审阅」不得保存。
 * 全部走 mock API，不连后端。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '@/locales'
import AiEvidencePanel from './AiEvidencePanel.vue'

const mocks = vi.hoisted(() => ({
  templates: vi.fn(),
  sessions: vi.fn(),
  quota: vi.fn(),
  messages: vi.fn(),
  stream: vi.fn(),
  drafts: vi.fn(),
  applyDrafts: vi.fn(),
  exportSession: vi.fn(),
}))

vi.mock('@/api/aiWorkbench', () => ({ aiWorkbenchApi: mocks }))

let app: App | null = null
let root: HTMLElement
let cancel: ReturnType<typeof vi.fn>

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountPanel(): Promise<void> {
  root = document.createElement('div')
  document.body.appendChild(root)
  const router = createRouter({ history: createMemoryHistory(), routes: [] })
  app = createApp({ render: () => h(AiEvidencePanel, { projectId: 5 }) })
  app.use(i18n)
  app.use(router)
  app.mount(root)
  await flush()
}

function byTestId(id: string): HTMLElement {
  const node = root.querySelector(`[data-testid="${id}"]`)
  if (!node) throw new Error(`missing ${id}`)
  return node as HTMLElement
}

async function click(node: HTMLElement): Promise<void> {
  node.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flush()
  await nextTick()
}

async function type(node: HTMLElement, value: string): Promise<void> {
  const input = node as HTMLTextAreaElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.templates.mockResolvedValue([
    { key: 'triage', name: '分诊模板', body: '请分诊 RUN-1', projectId: 5 },
  ])
  mocks.sessions.mockResolvedValue([])
  mocks.quota.mockResolvedValue({ dailyQuota: 50, used: 3, remaining: 47 })
  mocks.messages.mockResolvedValue([])
  mocks.drafts.mockResolvedValue([
    { title: '草稿 A', precondition: '已登录', steps: '点按钮', expected: '成功', source: 'E1' },
  ])
  mocks.applyDrafts.mockResolvedValue([1])
  cancel = vi.fn().mockResolvedValue(undefined)
})

afterEach(() => {
  app?.unmount()
  app = null
  root?.remove()
})

describe('证据工作台', () => {
  it('流式回答逐字上屏，引用 chip 可见', async () => {
    mocks.stream.mockImplementation((_payload, handlers) => {
      handlers.onMeta({ streamId: 'S-1' })
      handlers.onCitations([
        { id: 'E1', type: 'run', ref: 'RUN-1', label: '运行 RUN-1', path: '/runs/RUN-1' },
      ])
      handlers.onDelta('事实：')
      handlers.onDelta('失败 3')
      handlers.onDone({})
      return { streamId: 'S-1', cancel, done: Promise.resolve() }
    })
    await mountPanel()

    await type(byTestId('ai-evidence-input'), '请分诊 RUN-1')
    await click(byTestId('ai-evidence-send'))

    expect(byTestId('history').textContent).toContain('事实：失败 3')
    expect(root.querySelector('[data-testid="citation"]')?.textContent).toContain('E1')
    expect(mocks.stream).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: 5, prompt: '请分诊 RUN-1' }),
      expect.any(Object),
    )
  })

  it('生成中显示停止按钮，点击后调用取消接口', async () => {
    mocks.stream.mockImplementation(() => ({
      streamId: 'S-2',
      cancel,
      done: new Promise(() => {}),
    }))
    await mountPanel()

    await type(byTestId('ai-evidence-input'), '请解释')
    await click(byTestId('ai-evidence-send'))
    expect(root.textContent).toContain('正在生成')

    await click(byTestId('ai-evidence-stop'))
    expect(cancel).toHaveBeenCalledTimes(1)
  })

  it('草稿未勾「已逐条审阅」时保存被禁用', async () => {
    await mountPanel()
    await click(byTestId('tab-draft'))

    await click(byTestId('draft-generate'))
    expect(root.querySelector('[data-testid="drafts"]')?.textContent).toContain('草稿 A')

    const apply = byTestId('draft-apply') as HTMLButtonElement
    expect(apply.disabled).toBe(true)

    const reviewed = byTestId('draft-reviewed') as HTMLInputElement
    reviewed.checked = true
    reviewed.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(apply.disabled).toBe(false)

    await click(apply)
    expect(mocks.applyDrafts).toHaveBeenCalledWith(5, expect.any(Array), true)
  })
})
