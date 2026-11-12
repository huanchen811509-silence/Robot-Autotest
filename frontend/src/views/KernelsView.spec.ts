/**
 * `KernelsView` /kernels 转正回归（v5.1.1 / W51-10 / FR-v5.1-12 / 验收 A28/A29）。
 *
 * 页面要回答四件事，每件事都必须能被断言，否则「转正」只是加了个标题：
 *   ① 解释器版本达标与否（3.10 必须显式标不达标）；
 *   ② 契约探测缺哪个参数（要能念出参数名，不能只说「不健康」）；
 *   ③ 仓库可达性与绑定覆盖率（数字来自后端，不在前端算）；
 *   ④ 上次失败原因与修复建议（hints 必须渲染，否则用户只知道坏了不知道为什么）。
 *
 * 另有一条降级契约：健康检查失败时，内核能力矩阵必须照常显示——
 * 探不到 pytest 工程不等于「内核页挂了」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import KernelsView from '@/views/KernelsView.vue'

const state = vi.hoisted(() => ({
  health: null as unknown,
  healthError: null as Error | null,
  healthCalls: [] as boolean[],
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ projectId: 7 }),
}))

vi.mock('@/api/kernels', () => ({
  kernelsApi: {
    capabilities: async () => ({
      robot: true,
      pabot: true,
      pytest: true,
      python3: true,
      kernels: ['robot', 'pytest'],
      timeoutSeconds: 1800,
      maxRetries: 2,
      sameRunMixedKernels: false,
    }),
    select: async () => ({ kernel: 'pytest', source: 'project', overridden: false }),
  },
}))

vi.mock('@/api/pytestProject', () => ({
  pytestProjectApi: {
    health: async (_projectId: number, refresh = false) => {
      state.healthCalls.push(refresh)
      if (state.healthError) throw state.healthError
      return state.health
    },
  },
}))

function health(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    healthy: true,
    pythonBin: '/home/qa/venvs/nic/bin/python',
    pythonVersion: '3.11.9',
    minPythonVersion: '3.11',
    pythonVersionProblem: null,
    pytestVersion: '9.2.0',
    foundOptions: ['--mode', '--env_config', '--junitxml'],
    contractMissing: [],
    contractFailure: null,
    repoConfigured: true,
    repoReachable: true,
    repoPath: '/opt/nic-test-platform',
    modeDefault: 'fake',
    totalCases: 469,
    bound: 61,
    unbound: 408,
    coveragePercent: 13.0,
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
  app = createApp(KernelsView)
  app.use(i18n)
  app.mount(root)
  await flush()
  return root
}

function text(root: HTMLElement, test: string): string {
  return root.querySelector(`[data-test="${test}"]`)?.textContent?.trim() ?? ''
}

beforeEach(() => {
  state.health = health()
  state.healthError = null
  state.healthCalls = []
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('KernelsView pytest 工程健康', () => {
  it('健康时给出达标与覆盖率数字', async () => {
    const root = await mountView()
    expect(text(root, 'health-state')).toBe(i18n.global.t('kernels.healthy'))
    expect(text(root, 'version-ok')).toBe(i18n.global.t('kernels.versionOk'))
    expect(root.querySelector('[data-test="version-too-old"]')).toBeNull()
    expect(text(root, 'coverage')).toContain('61 / 469')
    expect(text(root, 'coverage')).toContain('13')
  })

  it('解释器 3.10 显式标不达标，并渲染修复建议', async () => {
    state.health = health({
      healthy: false,
      pythonVersion: '3.10.12',
      pythonVersionProblem: '解释器版本 3.10.12 低于用例库要求的最低版本 3.11',
      hints: ['解释器版本 3.10.12 低于用例库要求的最低版本 3.11'],
    })
    const root = await mountView()
    expect(root.querySelector('[data-test="version-too-old"]')).not.toBeNull()
    expect(root.querySelector('[data-test="version-ok"]')).toBeNull()
    expect(text(root, 'health-state')).toBe(i18n.global.t('kernels.unhealthy'))
    expect(text(root, 'hints')).toContain('3.10.12')
  })

  it('契约缺参数时念出参数名而不是只说坏了', async () => {
    state.health = health({ healthy: false, contractMissing: ['--mode', '--env_config'] })
    const root = await mountView()
    const missing = text(root, 'contract-missing')
    expect(missing).toContain('--mode')
    expect(missing).toContain('--env_config')
  })

  it('仓库未配置与路径不可达要分开显示', async () => {
    state.health = health({ healthy: false, repoConfigured: false, repoReachable: false, repoPath: null })
    let root = await mountView()
    expect(text(root, 'repo-bad')).toBe(i18n.global.t('kernels.repoMissing'))
    app?.unmount()
    host?.remove()

    state.health = health({ healthy: false, repoReachable: false, repoPath: '/nope/x' })
    root = await mountView()
    expect(text(root, 'repo-bad')).toBe(i18n.global.t('kernels.repoUnreachable'))
  })

  it('上次失败原因单独成行', async () => {
    state.health = health({ healthy: false, contractFailure: 'pytest --help 退出码 1：No module named nic_platform' })
    const root = await mountView()
    expect(text(root, 'last-failure')).toContain('No module named nic_platform')
  })

  it('点「重新探测」带 refresh=true，首次加载不带', async () => {
    const root = await mountView()
    expect(state.healthCalls).toEqual([false])
    const button = root.querySelector<HTMLButtonElement>('[data-test="reprobe"]')
    expect(button).not.toBeNull()
    button!.click()
    await flush()
    expect(state.healthCalls).toEqual([false, true])
  })

  it('健康检查失败只降级本区块，内核能力矩阵照常显示', async () => {
    state.healthError = new Error('boom')
    const root = await mountView()
    expect(root.querySelector('[data-test="health-failed"]')).not.toBeNull()
    expect(root.querySelector('[data-test="health-state"]')).toBeNull()
    // 能力矩阵仍在：四个二进制条目 + 混跑说明 + 内核选择/覆盖两行
    expect(root.querySelectorAll('.facts .notice').length).toBeGreaterThanOrEqual(6)
    expect(root.textContent).toContain('false')
  })
})
