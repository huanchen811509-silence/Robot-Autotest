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
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '@/locales'
import PytestWorkbenchView from '@/views/PytestWorkbenchView.vue'

const state = vi.hoisted(() => ({
  health: null as Record<string, unknown> | null,
  bindings: null as Record<string, unknown> | null,
  settings: {} as Record<string, string>,
  syncReport: null as Record<string, unknown> | null,
  missing: [] as string[],
  ledger: null as Record<string, unknown> | null,
  admission: null as Record<string, unknown> | null,
  readiness: null as Record<string, unknown> | null,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ projectId: 7 }),
}))

vi.mock('@/api/pytestProject', () => ({
  pytestProjectApi: {
    health: async () => state.health,
    // v5.6.0 / FR-v5.6-04：台架就绪度是首屏第一块，必须一起桩掉，
    // 否则每个用例都会真发一次 HTTP，日志里全是 ECONNREFUSED。
    readiness: async () => state.readiness,
    bindings: async () => state.bindings,
    settings: async () => state.settings,
    // v5.2.0 / W52-02：保存返回体变成 { settings, missingForExecute, errors, health }
    saveSettings: async () => ({
      settings: state.settings,
      missingForExecute: state.missing,
      errors: [],
      health: state.health,
    }),
    validateSettings: async () => [],
    sync: async () => state.syncReport,
  },
}))

/**
 * 执行协调接口必须 mock 掉：不 mock 的话每个用例都会真发 HTTP 到 127.0.0.1:3000，
 * 测试虽然「通过」，日志里全是 ECONNREFUSED —— 那种噪音会掩盖真正的失败。
 */
vi.mock('@/api/executionCoordination', () => ({
  executionCoordinationApi: {
    ledger: async () => state.ledger,
    admission: async () => state.admission,
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
  // 就绪度与顶栏都用 RouterLink（v5.6.0 / FR-v5.6-04）：不装路由时链接没有 href，
  // 「点得去哪里」这件事就测不出来了。
  app.use(createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  }))
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

  it('执行协调区块显示窗口状态与今日台账（v5.2.0 / W52-04）', async () => {
    state.ledger = {
      runsToday: 3,
      finishedRuns: 2,
      failedRuns: 1,
      running: 1,
      queued: 2,
      totalTests: 120,
      passedTests: 108,
      failedTests: 12,
      successRatePercent: 90.0,
      avgDurationSeconds: 3725,
      windowDescription: '23:00–06:00（跨零点）',
      windowOpen: true,
      exclusive: false,
    }
    state.admission = {
      allowed: true,
      windowDescription: '23:00–06:00（跨零点）',
      exclusive: false,
      reason: null,
      hint: null,
    }
    const root = await mountView()
    const pick = (key: string) =>
      root.querySelector(`[data-test="${key}"]`)?.textContent?.trim() ?? ''
    expect(pick('ledger-runs')).toBe('3')
    expect(pick('ledger-success')).toBe('90%')
    // 3725 秒要显示成 1h 2m，不是「3725」也不是相对时间
    expect(root.textContent).toContain('1h 2m')
    expect(pick('window-state')).toContain('23:00–06:00')
    expect(root.querySelector('[data-test="admission"]')).toBeNull()
  })

  it('窗口关闭时提示会被拒绝，并给出下一次开启时点', async () => {
    state.ledger = {
      runsToday: 0, finishedRuns: 0, failedRuns: 0, running: 0, queued: 0,
      totalTests: 0, passedTests: 0, failedTests: 0, successRatePercent: 0,
      avgDurationSeconds: 0, windowDescription: '23:00–06:00', windowOpen: false,
      exclusive: true,
    }
    state.admission = {
      allowed: false,
      windowDescription: '23:00–06:00',
      exclusive: true,
      reason: '当前 14:20 不在执行窗口内（23:00–06:00）',
      hint: '下一次窗口开启：23:00',
    }
    const root = await mountView()
    const pick = (key: string) =>
      root.querySelector(`[data-test="${key}"]`)?.textContent?.trim() ?? ''
    expect(pick('window-state')).toContain('不在执行窗口内')
    expect(pick('admission')).toContain('下一次窗口开启')
  })
})

/**
 * 台架就绪度（v5.6.0 / FR-v5.6-04 / W56-06）。
 *
 * 判据是「页面不许把未就绪说成就绪」，以及「缺项要能一键跳到配置页」：
 * 这两件事在 v5.5.0 都做不到——那次是绕过界面调 API 登记的设备，
 * 界面既不知道缺什么，也没地方点。
 */
describe('PytestWorkbenchView 台架就绪度', () => {
  beforeEach(() => {
    state.health = health()
    state.readiness = {
      ready: false,
      items: [
        { key: 'devices', ready: true, count: 4 },
        { key: 'credentials', ready: false, count: 0 },
        { key: 'environment', ready: true, count: 3 },
        { key: 'bindings', ready: true, count: 61 },
      ],
      missing: ['credentials'],
    }
  })

  afterEach(() => {
    state.readiness = null
  })

  it('未就绪时显示「缺 1 项」，并把缺的那项标出来', async () => {
    const root = await mountView()
    const section = root.querySelector('[data-test="readiness"]')
    expect(section).toBeTruthy()
    expect(section?.getAttribute('class')).toContain('readiness-blocked')

    const badge = root.querySelector('[data-test="readiness-state"]')
    expect(badge?.textContent).toContain('1')

    const devices = root.querySelector('[data-test="readiness-devices"]')
    const credentials = root.querySelector('[data-test="readiness-credentials"]')
    expect(devices?.getAttribute('class')).toContain('ok')
    expect(credentials?.getAttribute('class')).toContain('missing')
  })

  it('缺凭据时给出直达「资产-凭证」的入口，而不是只说一句未就绪', async () => {
    const root = await mountView()
    const fix = root.querySelector('[data-test="readiness-fix-credentials"]') as HTMLAnchorElement | null
    expect(fix).toBeTruthy()
    expect(fix?.getAttribute('href')).toBe('/assets?tab=credentials')
    // 已就绪的项不该有「去补齐」
    expect(root.querySelector('[data-test="readiness-fix-devices"]')).toBeNull()
  })

  it('四项齐全时显示「就绪」，且不再显示任何补齐入口', async () => {
    state.readiness = {
      ready: true,
      items: [
        { key: 'devices', ready: true, count: 4 },
        { key: 'credentials', ready: true, count: 1 },
        { key: 'environment', ready: true, count: 3 },
        { key: 'bindings', ready: true, count: 61 },
      ],
      missing: [],
    }
    const root = await mountView()
    const section = root.querySelector('[data-test="readiness"]')
    expect(section?.getAttribute('class')).toContain('readiness-ok')
    expect(root.querySelectorAll('[data-test="readiness-fix-credentials"]')).toHaveLength(0)
  })
})
