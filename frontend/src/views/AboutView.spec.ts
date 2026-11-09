/**
 * `AboutView` 视图级回归（3.9.0 / FR-v3.9-07）。
 *
 * 关于页新增的「最近更新」区块有两个只在挂载后才暴露的缺陷：
 * ① 数据源接错（另建一份摘要而不是复用版本迭代页的 CHANGELOG）→ 两处版本对不上；
 * ② 变更记录拉取失败时把整页拖成错误页 → 版本号、自检这些来自后端的信息跟着消失。
 *
 * 这里只断言这两条契约，不测样式（样式由 `side-toggle-probe` 与视觉评审负责）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import AboutView from '@/views/AboutView.vue'

const settingsState = vi.hoisted(() => ({
  info: null as unknown,
  error: null as Error | null,
}))

vi.mock('@/api/settings', () => ({
  settingsApi: {
    buildInfo: async () => {
      if (settingsState.error) throw settingsState.error
      return settingsState.info
    },
  },
}))

// DataTable 内部依赖 naive-ui 的 provider；本用例只关心「最近更新」区块，替换成空壳。
vi.mock('@/components/DataTable.vue', () => ({
  default: { name: 'DataTable', props: ['columns', 'data', 'rowKey', 'maxHeight', 'emptyTitle'], template: '<div class="mock-table" />' },
}))

const BUILD_INFO = {
  app: 'robot-autotest',
  version: '3.4.0',
  versionSource: 'file',
  gitCommit: 'deadbee',
  buildTime: '2026-11-09T00:00:00Z',
  source: 'build',
  entryPort: '8001',
  backendPort: '8081',
  devPort: '5273',
  license: 'Internal',
  dependencies: [],
  selfCheck: { database: 'OK', executor: 'OK', kernel: 'OK', aiCli: 'MISSING' },
}

const CHANGELOG = `# CHANGELOG

## v3.4.0-test（2026-11-09） <!-- current -->

### 新增 <!-- added -->
- 条目一
- 条目二

### 变更 <!-- changed -->
- 条目三

### 修复 <!-- fixed -->
- 条目四

### 破坏性变更 <!-- breaking -->
- 条目五
`

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
  app = createApp(AboutView)
  app.use(i18n)
  // 真实应用里 RouterLink 由 vue-router 提供；这里只需要它渲染出可断言的 href。
  app.component('RouterLink', {
    props: { to: { type: [String, Object], required: true } },
    template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>',
  })
  app.mount(root)
  await flush()
  return root
}

function recentPanel(root: HTMLElement): HTMLElement | null {
  const panels = [...root.querySelectorAll<HTMLElement>('.panel')]
  return panels.find((panel) => (panel.querySelector('h2')?.textContent ?? '').includes('最近更新')) ?? null
}

beforeEach(() => {
  settingsState.info = BUILD_INFO
  settingsState.error = null
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, text: async () => CHANGELOG })))
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('AboutView 最近更新区块', () => {
  it('渲染版本号，并从 CHANGELOG 首条列出变更', async () => {
    const root = await mountView()
    expect(root.querySelector('.version-value')?.textContent?.trim()).toBe('3.4.0')

    const panel = recentPanel(root)
    expect(panel).not.toBeNull()
    expect(panel!.querySelectorAll('.changes li').length).toBe(4) // 最多 4 条
    expect(panel!.textContent).toContain('v3.4.0-test')
  })

  it('提供指向版本迭代页的入口', async () => {
    const root = await mountView()
    const panel = recentPanel(root)
    const link = panel!.querySelector('a')
    expect(link?.getAttribute('href')).toBe('/changelog')
  })

  it('变更记录拉取失败时只降级本区块，版本信息仍在', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 404, text: async () => '' })))
    const root = await mountView()
    expect(root.querySelector('.version-value')?.textContent?.trim()).toBe('3.4.0')
    const panel = recentPanel(root)
    expect(panel).not.toBeNull()
    expect(panel!.textContent).toContain('变更记录读取失败')
    expect(panel!.querySelectorAll('.changes li').length).toBe(0)
  })
})
