/**
 * TC-AI-05：抽屉文本输入 -> 提交 -> 模型回答 -> 可滚动多轮历史；
 * 加载 / 错误 / DEGRADED / 禁用态均可见。全程 mock API，不发网络请求。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import AiPanelDrawer from './AiPanelDrawer.vue'

const mocks = vi.hoisted(() => ({
  state: vi.fn(),
  open: vi.fn(),
  suggest: vi.fn(),
  execute: vi.fn(),
  terminate: vi.fn(),
  chat: vi.fn(),
}))

vi.mock('@/api/aiPanel', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/aiPanel')>()
  return {
    ...actual,
    aiPanelApi: {
      state: mocks.state,
      open: mocks.open,
      suggest: mocks.suggest,
      execute: mocks.execute,
      terminate: mocks.terminate,
      chat: mocks.chat,
    },
  }
})

function baseState(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    anyAvailable: true,
    maxLevel: 'L3',
    mode: 'NORMAL',
    nonInteractiveOnly: true,
    projectId: 1,
    timeoutSeconds: 60,
    encoding: 'UTF-8',
    contextMaxChars: 8000,
    executeRequiresConfirmation: true,
    enabled: true,
    realCliEnabled: true,
    ...overrides,
  }
}

function chatReply(): Record<string, unknown> {
  return {
    sessionId: 1001,
    answer: 'FAKE_CLI_OK:ui-echo',
    stdout: 'FAKE_CLI_OK:ui-echo',
    stderr: '',
    exitCode: 0,
    durationMs: 12,
    degraded: false,
    mode: 'NORMAL',
    cli: 'claude',
    timedOut: false,
    audited: true,
  }
}

let app: App | null = null
let host: HTMLElement | null = null

/** 多次 flush：load()/submitChat() 都是 async，mocked promise 需要几轮微任务才落地。 */
async function flush(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await Promise.resolve()
    await nextTick()
  }
}

async function mount(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () => h(AiPanelDrawer, { open: true, width: 340, projectId: 1 }),
  })
  app.use(i18n)
  app.mount(root)
  await flush()
  return root
}

async function send(root: HTMLElement, text: string): Promise<void> {
  const input = root.querySelector<HTMLInputElement>('[data-testid="ai-input"]')!
  input.value = text
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  root.querySelector<HTMLButtonElement>('[data-testid="ai-send"]')!.click()
  await flush()
}

beforeEach(() => {
  mocks.state.mockReset()
  mocks.state.mockResolvedValue(baseState())
  mocks.open.mockReset()
  mocks.open.mockResolvedValue(1001)
  mocks.suggest.mockReset()
  mocks.suggest.mockResolvedValue({})
  mocks.execute.mockReset()
  mocks.execute.mockResolvedValue({})
  mocks.terminate.mockReset()
  mocks.terminate.mockResolvedValue({})
  mocks.chat.mockReset()
  mocks.chat.mockResolvedValue(chatReply())
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AiPanelDrawer', () => {
  it('有提示词输入框与发送按钮', async () => {
    const root = await mount()
    expect(root.querySelector('[data-testid="ai-input"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="ai-send"]')).not.toBeNull()
  })

  it('提交后显示模型回答，历史可滚动且保留多轮消息', async () => {
    const root = await mount()
    await send(root, '第一轮问题')
    expect(mocks.chat).toHaveBeenCalledTimes(1)
    expect(root.textContent).toContain('FAKE_CLI_OK:ui-echo')

    await send(root, '第二轮问题')
    expect(mocks.chat).toHaveBeenCalledTimes(2)
    const history = root.querySelector<HTMLElement>('[data-testid="ai-history"]')!
    expect(history.getAttribute('style')).toContain('overflow-y')
    expect(history.querySelectorAll('.ai-msg')).toHaveLength(4)
    // 第二轮 prompt 携带了第一轮的多轮上下文
    const secondCall = mocks.chat.mock.calls[1] as unknown[]
    expect(String(secondCall[2])).toContain('第一轮问题')
  })

  it('加载态：请求未返回时显示等待文案且发送按钮禁用', async () => {
    let resolveChat!: (value: unknown) => void
    mocks.chat.mockReturnValueOnce(new Promise((resolve) => { resolveChat = resolve }))
    const root = await mount()
    const input = root.querySelector<HTMLInputElement>('[data-testid="ai-input"]')!
    input.value = '挂起的问题'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="ai-send"]')!.click()
    await flush()
    expect(root.querySelector('[data-testid="ai-loading"]')).not.toBeNull()
    expect(root.querySelector<HTMLButtonElement>('[data-testid="ai-send"]')!.disabled).toBe(true)
    resolveChat(chatReply())
    await flush()
    expect(root.querySelector('[data-testid="ai-loading"]')).toBeNull()
  })

  it('错误态：chat 拒绝时显示错误提示', async () => {
    mocks.chat.mockRejectedValueOnce(new Error('boom'))
    const root = await mount()
    await send(root, '会失败的问题')
    expect(root.querySelector('[data-testid="ai-error"]')).not.toBeNull()
  })

  it('DEGRADED 态显式可见', async () => {
    mocks.state.mockResolvedValue(baseState({ mode: 'DEGRADED', anyAvailable: false }))
    const root = await mount()
    expect(root.querySelector('[data-testid="ai-degraded"]')).not.toBeNull()
  })

  it('enabled=false 时不开会话且禁用输入', async () => {
    mocks.state.mockResolvedValue(baseState({ enabled: false, mode: 'DISABLED' }))
    const root = await mount()
    expect(mocks.open).not.toHaveBeenCalled()
    expect(root.querySelector('[data-testid="ai-disabled"]')).not.toBeNull()
    expect(root.querySelector<HTMLInputElement>('[data-testid="ai-input"]')!.disabled).toBe(true)
  })
})
