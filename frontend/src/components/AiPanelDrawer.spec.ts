/**
 * TC-AI-05：抽屉文本输入 -> 提交 -> 模型回答 -> 可滚动多轮历史；
 * 加载 / 错误 / DEGRADED / 禁用态均可见。全程 mock API，不发网络请求。
 *
 * v2.6 增补（FR-AI-14..16）：宽度拖拽与键盘调宽、Agent/模型选择、
 * 多行输入回车语义、输出区撑满高度。全部断言只看行为，不看具体像素。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import AiPanelDrawer from './AiPanelDrawer.vue'
import { useAiDrawer, AI_DRAWER_MAX } from '@/composables/useAiDrawer'

const mocks = vi.hoisted(() => ({
  state: vi.fn(),
  open: vi.fn(),
  suggest: vi.fn(),
  execute: vi.fn(),
  terminate: vi.fn(),
  chat: vi.fn(),
  agents: vi.fn(),
  chatWith: vi.fn(),
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
      agents: mocks.agents,
      chatWith: mocks.chatWith,
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

function chatReply(overrides: Record<string, unknown> = {}): Record<string, unknown> {
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
    ...overrides,
  }
}

function agentsPayload(): Record<string, unknown> {
  return {
    defaultKey: 'hermes',
    realCliEnabled: true,
    items: [
      {
        key: 'hermes', name: 'Hermes Agent', models: [], defaultModel: '',
        modelFlag: null, injectable: false, configured: false,
      },
      {
        key: 'pi', name: 'pi coding agent', models: ['deepseek-v4-flash'],
        defaultModel: 'ark/deepseek-v4-flash', modelFlag: '--model',
        injectable: true, configured: true,
      },
    ],
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

interface Mounted {
  root: HTMLElement
  widths: number[]
}

async function mount(width = 340): Promise<Mounted> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  const widths: number[] = []
  app = createApp({
    render: () => h(AiPanelDrawer, {
      open: true,
      width,
      projectId: 1,
      'onUpdate:width': (value: number) => widths.push(value),
    }),
  })
  app.use(i18n)
  app.mount(root)
  await flush()
  return { root, widths }
}

async function type(root: HTMLElement, text: string): Promise<void> {
  const input = root.querySelector<HTMLTextAreaElement>('[data-testid="ai-input"]')!
  input.value = text
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

async function send(root: HTMLElement, text: string): Promise<void> {
  await type(root, text)
  root.querySelector<HTMLButtonElement>('[data-testid="ai-send"]')!.click()
  await flush()
}

beforeEach(() => {
  for (const key of Object.keys(mocks) as Array<keyof typeof mocks>) {
    mocks[key].mockReset()
  }
  mocks.state.mockResolvedValue(baseState())
  mocks.open.mockResolvedValue(1001)
  mocks.suggest.mockResolvedValue({})
  mocks.execute.mockResolvedValue({})
  mocks.terminate.mockResolvedValue({})
  mocks.chat.mockResolvedValue(chatReply())
  mocks.agents.mockResolvedValue(agentsPayload())
  mocks.chatWith.mockResolvedValue(chatReply())
  // 单例状态跨用例共享：每个用例从「自动 / 默认模型」起步
  const drawer = useAiDrawer()
  drawer.setAgent('')
  drawer.setModel('')
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AiPanelDrawer', () => {
  it('有提示词输入框与发送按钮', async () => {
    const { root } = await mount()
    expect(root.querySelector('[data-testid="ai-input"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="ai-send"]')).not.toBeNull()
  })

  it('提交后显示模型回答，历史可滚动且保留多轮消息', async () => {
    const { root } = await mount()
    await send(root, '第一轮问题')
    expect(mocks.chatWith).toHaveBeenCalledTimes(1)
    expect(root.textContent).toContain('FAKE_CLI_OK:ui-echo')

    await send(root, '第二轮问题')
    expect(mocks.chatWith).toHaveBeenCalledTimes(2)
    const history = root.querySelector<HTMLElement>('[data-testid="ai-history"]')!
    // v2.6：滚动由样式类负责（输出区撑满剩余高度），不再写 inline max-height
    expect(history.className).toContain('ai-history')
    expect(history.querySelectorAll('.ai-msg')).toHaveLength(4)
    // 第二轮 prompt 携带了第一轮的多轮上下文
    const secondCall = mocks.chatWith.mock.calls[1] as unknown[]
    expect(String((secondCall[0] as { prompt: string }).prompt)).toContain('第一轮问题')
  })

  it('加载态：请求未返回时显示等待文案且发送按钮禁用', async () => {
    let resolveChat!: (value: unknown) => void
    mocks.chatWith.mockReturnValueOnce(new Promise((resolve) => { resolveChat = resolve }))
    const { root } = await mount()
    await type(root, '挂起的问题')
    root.querySelector<HTMLButtonElement>('[data-testid="ai-send"]')!.click()
    await flush()
    expect(root.querySelector('[data-testid="ai-loading"]')).not.toBeNull()
    expect(root.querySelector<HTMLButtonElement>('[data-testid="ai-send"]')!.disabled).toBe(true)
    resolveChat(chatReply())
    await flush()
    expect(root.querySelector('[data-testid="ai-loading"]')).toBeNull()
  })

  it('错误态：chat 拒绝时显示错误提示', async () => {
    mocks.chatWith.mockRejectedValueOnce(new Error('boom'))
    const { root } = await mount()
    await send(root, '会失败的问题')
    expect(root.querySelector('[data-testid="ai-error"]')).not.toBeNull()
  })

  it('DEGRADED 态显式可见', async () => {
    mocks.state.mockResolvedValue(baseState({ mode: 'DEGRADED', anyAvailable: false }))
    const { root } = await mount()
    expect(root.querySelector('[data-testid="ai-degraded"]')).not.toBeNull()
  })

  it('enabled=false 时不开会话且禁用输入', async () => {
    mocks.state.mockResolvedValue(baseState({ enabled: false, mode: 'DISABLED' }))
    const { root } = await mount()
    expect(mocks.open).not.toHaveBeenCalled()
    expect(root.querySelector('[data-testid="ai-disabled"]')).not.toBeNull()
    expect(root.querySelector<HTMLTextAreaElement>('[data-testid="ai-input"]')!.disabled).toBe(true)
  })
})

describe('AiPanelDrawer v2.6 宽度可调', () => {
  it('拖拽手柄存在且可聚焦（键盘可达）', async () => {
    const { root } = await mount()
    const handle = root.querySelector<HTMLElement>('[data-testid="ai-resizer"]')
    expect(handle).not.toBeNull()
    expect(handle!.getAttribute('tabindex')).toBe('0')
    expect(handle!.getAttribute('role')).toBe('separator')
  })

  it('左方向键变宽 20、右方向键变窄 20，Home/End 直达两端', async () => {
    const { root, widths } = await mount(500)
    const handle = root.querySelector<HTMLElement>('[data-testid="ai-resizer"]')!

    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }))
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }))
    await nextTick()

    expect(widths).toEqual([520, 480, 340, AI_DRAWER_MAX])
  })

  it('档位按钮按 340→420→520→760 循环，超出上限不再增长', async () => {
    const { root, widths } = await mount(340)
    const button = root.querySelector<HTMLButtonElement>('[data-testid="ai-width"]')!
    button.click()
    await nextTick()
    expect(widths).toEqual([420])
  })
})

describe('AiPanelDrawer v2.6 Agent 与模型选择', () => {
  it('下拉里列出后端返回的 agent，并带上可选的模型', async () => {
    const { root } = await mount()
    const agent = root.querySelector<HTMLSelectElement>('[data-testid="ai-agent"]')!
    expect([...agent.options].map((o) => o.value)).toEqual(['', 'hermes', 'pi'])

    agent.value = 'pi'
    agent.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()

    const model = root.querySelector<HTMLSelectElement>('[data-testid="ai-model"]')!
    // 目录建议值 + 已保存模型（去重后共两项），外加「默认模型」空选项
    expect([...model.options].map((o) => o.value)).toEqual(
      ['', 'ark/deepseek-v4-flash', 'deepseek-v4-flash'])
  })

  it('提交时把选中的 agent 与模型带进请求体', async () => {
    const { root } = await mount()
    const agent = root.querySelector<HTMLSelectElement>('[data-testid="ai-agent"]')!
    agent.value = 'pi'
    agent.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    const model = root.querySelector<HTMLSelectElement>('[data-testid="ai-model"]')!
    model.value = 'ark/deepseek-v4-flash'
    model.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()

    await send(root, '用 pi 回答')

    const payload = mocks.chatWith.mock.calls[0][0] as Record<string, unknown>
    expect(payload.cli).toBe('pi')
    expect(payload.model).toBe('ark/deepseek-v4-flash')
  })

  it('没选 agent 时不传 cli/model（与老行为一致）', async () => {
    const { root } = await mount()
    await send(root, '默认回答')
    const payload = mocks.chatWith.mock.calls[0][0] as Record<string, unknown>
    expect(payload.cli).toBeUndefined()
    expect(payload.model).toBeUndefined()
  })

  it('后端回落时明确提示，不静默换成别的 agent', async () => {
    mocks.chatWith.mockResolvedValue(chatReply({ cli: 'pi', requestedCli: 'claude', cliFallback: true }))
    const { root } = await mount()
    const agent = root.querySelector<HTMLSelectElement>('[data-testid="ai-agent"]')!
    agent.value = 'claude'
    agent.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    await send(root, '回落到谁')
    expect(root.querySelector('[data-testid="ai-fallback"]')).not.toBeNull()
  })

  it('本机没有可用 agent 时给出说明，不显示空下拉', async () => {
    mocks.agents.mockResolvedValue({ defaultKey: '', realCliEnabled: false, items: [] })
    const { root } = await mount()
    expect(root.querySelector('[data-testid="ai-no-agents"]')).not.toBeNull()
    expect(root.querySelector<HTMLSelectElement>('[data-testid="ai-agent"]')!.disabled).toBe(true)
  })
})

describe('AiPanelDrawer v2.6 多行输入', () => {
  it('是 textarea（可多行），不是单行 input', async () => {
    const { root } = await mount()
    const input = root.querySelector('[data-testid="ai-input"]')!
    expect(input.tagName).toBe('TEXTAREA')
  })

  it('Enter 直接发送，Shift+Enter 不发送（留给换行）', async () => {
    const { root } = await mount()
    await type(root, '回车发送')

    const input = root.querySelector<HTMLTextAreaElement>('[data-testid="ai-input"]')!
    input.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Enter', shiftKey: true, bubbles: true,
    }))
    await flush()
    expect(mocks.chatWith).not.toHaveBeenCalled()

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flush()
    expect(mocks.chatWith).toHaveBeenCalledTimes(1)
  })
})
