/**
 * 执行发起台（v5.6.0 / FR-v5.6-11、12、14 / W56-15）。
 *
 * 三条判据对应三件最容易做错的事：
 * ① 预检没过却让提交 —— 用户拿到一个看不懂的失败；
 * ② real 模式不打勾就能提交 —— 这是「点一下就把命令发到真机上」。
 * ③ 提交后不轮询 —— 页面停在「已提交」，用户不知道跑没跑完。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '@/locales'
import PytestRunLaunch from '@/views/pytest/PytestRunLaunch.vue'

const state = vi.hoisted(() => ({
  precheck: null as Record<string, unknown> | null,
  progress: [] as Record<string, unknown>[],
  accepted: null as Record<string, unknown> | null,
  submitted: [] as Record<string, unknown>[],
  bindings: [] as Record<string, unknown>[],
  canceled: [] as string[],
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ projectId: 95 }),
}))

vi.mock('@/api/pytestProject', () => ({
  pytestProjectApi: {
    bindings: async () => ({ items: state.bindings }),
  },
}))

vi.mock('@/api/pytestRuns', () => ({
  pytestRunApi: {
    precheck: async () => state.precheck,
    startAsync: async (_projectId: number, body: Record<string, unknown>) => {
      state.submitted.push(body)
      return state.accepted
    },
    progress: async () => {
      const next = state.progress.shift()
      if (!next) throw new Error('no more progress')
      return next
    },
    cancel: async (_projectId: number, runId: string) => {
      state.canceled.push(runId)
      return { runId, canceled: true }
    },
  },
}))

function precheck(ready: boolean): Record<string, unknown> {
  return {
    mode: 'fake',
    ready,
    blocking: ready ? 0 : 1,
    checks: [
      {
        key: 'bench',
        required: true,
        ok: ready,
        detail: ready ? '四项齐备' : '缺：credentials',
        items: [],
        action: '/assets?tab=credentials',
      },
      {
        key: 'config',
        required: true,
        ok: true,
        detail: '配置齐备',
        items: [],
        action: '/pytest-workbench',
      },
      {
        key: 'devices',
        required: true,
        ok: true,
        detail: '已登记 4 台设备',
        items: {},
        action: '/assets?tab=devices',
      },
      {
        key: 'safety',
        required: false,
        ok: true,
        detail: '可执行',
        items: [],
        action: '/cases',
      },
    ],
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function flush(times = 6): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(PytestRunLaunch)
  app.use(i18n)
  app.use(createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  }))
  app.mount(root)
  await flush()
  return root
}

function submitButton(root: HTMLElement): HTMLButtonElement {
  return root.querySelector('[data-test="submit"]') as HTMLButtonElement
}

beforeEach(() => {
  state.precheck = precheck(true)
  state.progress = []
  state.accepted = { runId: 'EHT-UI-1', mode: 'fake', total: 2, accepted: true, progressUrl: '' }
  state.submitted = []
  state.canceled = []
  state.bindings = [
    { id: 1, nodeId: 'tests/a.py::test_a', scriptPath: 'tests/a.py', projectId: 95,
      caseId: 1, kernel: 'pytest', scriptSha256: 'x', resolveSource: 'docstring', verified: true },
  ]
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('PytestRunLaunch 预检', () => {
  it('预检未通过时提交按钮禁用，并列出缺项与补齐入口', async () => {
    state.precheck = precheck(false)
    const root = await mountView()

    expect(submitButton(root).disabled).toBe(true)
    const bench = root.querySelector('[data-test="precheck-bench"]')
    expect(bench?.getAttribute('class')).toContain('blocked')
    expect(root.querySelector('[data-test="blocked-hint"]')).toBeTruthy()
  })

  it('预检通过时按钮可用，安全检查标为「本模式不拦」', async () => {
    const root = await mountView()
    expect(submitButton(root).disabled).toBe(false)
    const safety = root.querySelector('[data-test="precheck-safety"]')
    expect(safety?.textContent).toContain('本模式不拦')
  })
})

describe('PytestRunLaunch 提交', () => {
  it('fake 模式直接提交，nodeIds 为空表示整库', async () => {
    state.progress = [
      { runId: 'EHT-UI-1', stage: 'FINISHED', total: 2, finished: 2, passed: 2, failed: 0,
        elapsedMs: 1200, cancelRequested: false, terminal: true, source: 'live' },
    ]
    const root = await mountView()
    submitButton(root).click()
    await flush(10)

    expect(state.submitted).toHaveLength(1)
    expect(state.submitted[0].mode).toBe('fake')
    expect(state.submitted[0].nodeIds).toEqual([])
    expect(state.submitted[0].confirmReal).toBe(false)
  })

  it('real 模式不勾确认不能提交，勾了才发送 confirmReal=true', async () => {
    state.progress = [
      { runId: 'EHT-UI-1', stage: 'FAILED', total: 1, finished: 1, passed: 0, failed: 1,
        elapsedMs: 800, cancelRequested: false, terminal: true, source: 'live' },
    ]
    const root = await mountView()
    const select = root.querySelector('[data-test="mode"]') as HTMLSelectElement
    select.value = 'real'
    select.dispatchEvent(new Event('change'))
    await flush()

    expect(submitButton(root).disabled).toBe(true)

    const confirm = root.querySelector('[data-test="confirm-real"] input') as HTMLInputElement
    confirm.click()
    await flush()
    expect(submitButton(root).disabled).toBe(false)

    submitButton(root).click()
    await flush(10)
    expect(state.submitted[0].confirmReal).toBe(true)
    expect(state.submitted[0].mode).toBe('real')
  })
})

describe('PytestRunLaunch 进度', () => {
  it('提交后轮询到终态，并展示已完成/总数与报告入口', async () => {
    state.progress = [
      { runId: 'EHT-UI-1', stage: 'RUNNING', total: 2, finished: 1, passed: 1, failed: 0,
        elapsedMs: 400, cancelRequested: false, terminal: false, source: 'live' },
      { runId: 'EHT-UI-1', stage: 'FINISHED', total: 2, finished: 2, passed: 1, failed: 1,
        elapsedMs: 900, cancelRequested: false, terminal: true, source: 'live' },
    ]
    const root = await mountView()
    submitButton(root).click()
    // 轮询间隔 1s、首次 0.8s：等够两轮（0.8s + 1s）再断言，否则只能看到第一轮
    await new Promise((resolve) => setTimeout(resolve, 2400))
    await flush(10)

    const stage = root.querySelector('[data-test="progress-stage"]')
    expect(stage?.textContent).toContain('已完成')
    const terminal = root.querySelector('[data-test="progress-terminal"]')
    expect(terminal).toBeTruthy()
    // 终态后不再有取消按钮
    expect(root.querySelector('[data-test="cancel"]')).toBeNull()
  })
})
