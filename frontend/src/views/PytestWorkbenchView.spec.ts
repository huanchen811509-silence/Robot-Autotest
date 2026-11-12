/**
 * `PytestWorkbenchView` 覆盖率双口径回归（v5.1.2 / W51-11 / FR-v5.1-05 / D12）。
 *
 * 这一页最容易出错的不是样式，而是**口径**：
 *  ① 用 `bound / (total - 178)` 自己算排除待评审——分子没跟着缩，得数必然偏高；
 *  ② 把 178 写死在前端——用例库一升级就悄悄错，且没有任何报错；
 *  ③ 只显示一个百分比——对外口径不允许只挑好看的那个。
 *
 * 因此这里断言的是「页面显示的百分比等于后端给的百分比」，而不是「等于某个常数」：
 * 前端不该有能力改口径。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import PytestWorkbenchView from '@/views/PytestWorkbenchView.vue'

const state = vi.hoisted(() => ({
  health: null as Record<string, unknown> | null,
  bindings: null as Record<string, unknown> | null,
  settings: {} as Record<string, string>,
  syncReport: null as Record<string, unknown> | null,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ projectId: 7 }),
}))

vi.mock('@/api/pytestProject', () => ({
  pytestProjectApi: {
    health: async () => state.health,
    bindings: async () => state.bindings,
    settings: async () => state.settings,
    saveSettings: async () => state.settings,
    sync: async () => state.syncReport,
  },
}))

function health(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    healthy: true,
    pythonBin: '/opt/venv/bin/python',
    pythonVersion: '3.11.9',
    minPythonVersion: '3.11',
    pythonVersionProblem: null,
    pytestVersion: '9.2.0',
    foundOptions: [],
    contractMissing: [],
    contractFailure: null,
    repoConfigured: true,
    repoReachable: true,
    repoPath: '/opt/nic',
    modeDefault: 'fake',
    totalCases: 469,
    bound: 61,
    unbound: 408,
    pendingCases: 178,
    totalExcludingPending: 291,
    boundExcludingPending: 30,
    coveragePercent: 13.0,
    coverageExcludingPendingPercent: 10.3,
    hints: [],
    ...overrides,
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function flush(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(PytestWorkbenchView)
  app.use(i18n)
  app.mount(root)
  await flush()
  return root
}

beforeEach(() => {
  state.health = health()
  state.bindings = {
    totalCases: 469,
    bound: 61,
    unbound: 408,
    pendingCases: 178,
    totalExcludingPending: 291,
    boundExcludingPending: 30,
    orphanScripts: 0,
    duplicateRisk: 0,
    coveragePercent: 13.0,
    coverageExcludingPendingPercent: 10.3,
    items: [],
  }
  state.settings = { repo_path: '/opt/nic' }
  state.syncReport = {
    totalCases: 469,
    bound: 62,
    unbound: 407,
    pendingCases: 178,
    totalExcludingPending: 291,
    boundExcludingPending: 31,
    orphanScripts: 0,
    duplicateBindings: 0,
    renamedScripts: 1,
    scriptsScanned: 62,
    orphanCodes: [],
    duplicateCodes: [],
    unboundCodes: [],
    renamedPairs: ['IM-NET-131: tests/nic/test_im_net_131.py → tests/nic/test_im_net_132.py'],
    balanced: true,
    coveragePercent: 13.2,
    coverageExcludingPendingPercent: 10.7,
  }
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('PytestWorkbenchView 覆盖率双口径', () => {
  it('两个口径并列显示，且都用后端给的百分比', async () => {
    const root = await mountView()
    const a = root.querySelector('[data-test="caliber-A"]')?.textContent ?? ''
    const b = root.querySelector('[data-test="caliber-B"]')?.textContent ?? ''
    expect(a).toContain('13%')
    expect(a).toContain('61 / 469')
    expect(b).toContain('10.3%')
    expect(b).toContain('30 / 291')
  })

  it('口径 B 的分子跟着缩，不是拿 bound 直接除', async () => {
    // 后端说 30/291=10.3%；若前端自己算 bound/(total-pending) 会得 61/291=21%
    const root = await mountView()
    const b = root.querySelector('[data-test="caliber-B"]')?.textContent ?? ''
    expect(b).not.toContain('21')
    expect(b).toContain('30 / 291')
  })

  it('说明为什么排除后反而更低', async () => {
    const root = await mountView()
    const note = root.querySelector('[data-test="pending-note"]')?.textContent ?? ''
    expect(note).toContain('178')
    expect(note).toContain('291')
    expect(note).toContain('30')
  })

  it('待评审条数变化时页面跟着变（不写死 178）', async () => {
    state.health = health({ pendingCases: 200, totalExcludingPending: 269 })
    const root = await mountView()
    const note = root.querySelector('[data-test="pending-note"]')?.textContent ?? ''
    expect(note).toContain('200')
    expect(note).toContain('269')
    expect(note).not.toContain('178')
  })

  it('同步后展示四类计数与恒等式状态，并列出重命名跟随', async () => {
    const root = await mountView()
    const button = [...root.querySelectorAll('button')]
      .find((item) => item.textContent?.includes(i18n.global.t('pytestWorkbench.sync')))
    expect(button).toBeDefined()
    button!.click()
    await flush()

    const block = root.querySelector('[data-test="four-classes"]')
    expect(block).not.toBeNull()
    expect(block!.textContent).toContain(i18n.global.t('pytestWorkbench.balanced'))
    expect(root.querySelector('[data-test="renamed"]')?.textContent).toContain('1')
    expect(root.querySelector('[data-test="renamed-pairs"]')?.textContent).toContain('IM-NET-131')
  })

  it('恒等式被破坏时显式标红而不是静默', async () => {
    state.syncReport = { ...state.syncReport, balanced: false, bound: 1, unbound: 1 }
    const root = await mountView()
    const button = [...root.querySelectorAll('button')]
      .find((item) => item.textContent?.includes(i18n.global.t('pytestWorkbench.sync')))
    button!.click()
    await flush()
    expect(root.querySelector('[data-test="balanced"]')?.textContent)
      .toBe(i18n.global.t('pytestWorkbench.unbalanced'))
  })
})
