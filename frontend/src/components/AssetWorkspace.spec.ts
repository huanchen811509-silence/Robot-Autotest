/**
 * W62-08：工作台本身不堆叠 scope 分支；矩阵标 ❌ 的列在真实渲染里必须消失。
 * DataTable 用桩替换，只把列 key 铺成 DOM，便于逐列断言。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { i18n } from '@/locales'
import AssetWorkspace from '@/components/AssetWorkspace.vue'

vi.mock('@/components/DataTable.vue', () => ({
  default: {
    name: 'DataTable',
    props: ['columns', 'data', 'rowKey', 'loading', 'error', 'emptyTitle', 'emptyDescription',
      'emptyIcon', 'emptyIllustration', 'maxHeight', 'flexHeight'],
    template: `<div class="n-data-table" data-testid="asset-table"><span v-for="c in columns" :key="c.key" :data-col="c.key">{{ c.key }}</span></div>`,
  },
}))

let app: App | null = null
let host: HTMLElement | null = null

const cols = [
  { key: 'name' }, { key: 'managementIp' },
  { key: 'ownerScope' }, { key: 'source' },
  { key: 'refProjects' }, { key: 'authStatus' },
]

async function mount(scope: 'project' | 'global'): Promise<{ root: HTMLElement; actions: string[] }> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  let actions: string[] = []
  app = createApp({
    render: () => h(AssetWorkspace, {
      scope,
      columns: cols,
      rows: [],
      rowKey: (row: object) => (row as { id: number }).id,
    }, {
      actions: (slotProps: { actions: { key: string }[] }) => {
        actions = slotProps.actions.map((a) => a.key)
        return h('div', { 'data-testid': 'actions-slot' }, actions.join(','))
      },
      panels: () => h('div', { 'data-testid': 'panels-slot' }, 'extra panel'),
    }),
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return { root, actions }
}

function tableKeys(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLElement>('[data-col]')].map((el) => el.dataset.col ?? '')
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AssetWorkspace（W62-08）', () => {
  it('project：不渲染「归属 / 引用项目数」，保留「来源 / 授权状态」与共享列', async () => {
    const { root } = await mount('project')
    const keys = tableKeys(root)
    expect(root.querySelector('[data-testid="asset-workspace"]')?.getAttribute('data-scope')).toBe('project')
    expect(keys).not.toContain('ownerScope')
    expect(keys).not.toContain('refProjects')
    expect(keys).toContain('source')
    expect(keys).toContain('authStatus')
    expect(keys).toContain('name')
  })

  it('global：不渲染「来源 / 授权状态」，保留「归属 / 引用项目数」', async () => {
    const { root } = await mount('global')
    const keys = tableKeys(root)
    expect(keys).not.toContain('source')
    expect(keys).not.toContain('authStatus')
    expect(keys).toContain('ownerScope')
    expect(keys).toContain('refProjects')
  })

  it('动作条从矩阵取：project 无导入导出，global 无引用平台资产', async () => {
    const p = await mount('project')
    expect(p.actions).not.toContain('action.importExport')
    expect(p.actions).toContain('action.referencePlatform')
    p.root.remove()
    const g = await mount('global')
    expect(g.actions).toContain('action.importExport')
    expect(g.actions).not.toContain('action.referencePlatform')
  })

  it('额外面板仍由同一容器渲染（端口 / 拓扑 / 采集弹窗不另起页面）', async () => {
    const { root } = await mount('project')
    expect(root.querySelector('[data-testid="panels-slot"]')?.textContent).toContain('extra panel')
    expect(root.querySelector('[data-testid="assets-grid"]')).not.toBeNull()
  })

  it('模板里没有 `v-if="scope"` 整块堆叠（判据 3）', () => {
    for (const base of [process.cwd(), join(process.cwd(), '..')]) {
      const full = join(base, 'src/components/AssetWorkspace.vue')
      if (!existsSync(full)) continue
      const src = readFileSync(full, 'utf-8')
      expect(src).not.toContain('v-if="scope')
      expect(src).toContain('filterColumnsByMatrix')
      return
    }
    throw new Error('AssetWorkspace.vue not found')
  })
})
