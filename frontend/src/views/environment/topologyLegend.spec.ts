/**
 * 拓扑图例与「仅实测」过滤（v5.18.0 / FR-v5.18-03 / W68-07 首次建立）。
 *
 * 这一组钉三件事，都属于「看着对、数不对」的类型：
 * ① 图例四类的计数必须与接口逐数一致；
 * ② 「仅实测」后的条数必须等于接口的 MEASURED 数（不是前端自己另算一遍）；
 * ③ 过滤态必须落 URL —— 排障时把链接发给别人，对方看到的得是同一张图。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import TopologySnapshotView from '@/views/environment/TopologySnapshotView.vue'

const state = vi.hoisted(() => ({
  route: { query: {} as Record<string, string> },
  replaced: [] as Record<string, unknown>[],
  edgesView: null as unknown,
  edgesReject: false,
}))

vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => ({
    replace: (arg: { query: Record<string, unknown> }) => {
      state.replaced.push(arg)
      state.route.query = (arg.query ?? {}) as Record<string, string>
    },
  }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ projectId: 1 }),
}))

vi.mock('@/api/environment', () => ({
  envApi: {
    profiles: vi.fn(async () => []),
    snapshots: vi.fn(async () => []),
  },
  deviceSnapshotApi: { list: vi.fn(async () => []) },
  topologyTrustApi: {
    edges: vi.fn(async () => {
      if (state.edgesReject) throw new Error('boom')
      return state.edgesView
    }),
  },
  parseSpec: () => null,
}))

vi.mock('@/components/DataTable.vue', () => ({
  default: {
    name: 'DataTable',
    props: ['columns', 'data'],
    template: '<div class="dt-stub" :data-count="(data || []).length"><slot name="empty" /></div>',
  },
}))
vi.mock('@/components/EmptyState.vue', () => ({
  default: { name: 'EmptyState', template: '<div class="empty-stub" />' },
}))
vi.mock('@/components/EmptyGuide.vue', () => ({
  default: { name: 'EmptyGuide', template: '<span class="guide-stub" />' },
}))

let app: App | null = null
let host: HTMLElement | null = null

function view(edges: unknown[], counts: Record<string, number>,
              overridden = 0, plannedNodes: unknown[] = []) {
  return {
    edges,
    kindCounts: { MEASURED: counts.MEASURED ?? 0, MANUAL: counts.MANUAL ?? 0, PLANNED: counts.PLANNED ?? 0 },
    counts,
    total: edges.length,
    overridden,
    plannedNodes,
  }
}

function edge(id: number, kind: string) {
  return {
    id, name: `edge-${id}`, aDeviceId: 1, aDeviceName: 'sw-1',
    bDeviceId: 2, bDeviceName: 'dut-1', kind, source: kind === 'MEASURED' ? 'LLDP' : 'MANUAL',
  }
}

async function mount(): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp(TopologySnapshotView)
  app.use(i18n)
  app.mount(host)
  // onMounted 里是「四段串行 await」，只 tick 两次不够——必须把 microtask 队列刷干净，
  // 否则断言读到的是 loading 中的 0，会误判成「接口没接上」
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return host
}

function edgeCount(root: HTMLElement): string | null {
  // data-testid 透传到 DataTable 桩的根元素上，直接读它的 data-count
  const node = root.querySelector('[data-testid="topology-edges"]')
  return node?.getAttribute('data-count') ?? null
}

beforeEach(() => {
  state.route = { query: {} }
  state.replaced = []
  state.edgesReject = false
  state.edgesView = view(
    [edge(1, 'MEASURED'), edge(2, 'MEASURED'), edge(3, 'MANUAL'), edge(4, 'PLANNED')],
    { MEASURED: 2, MANUAL: 1, PLANNED: 1, CONFLICT: 1 },
    1,
  )
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('拓扑图例', () => {
  it('四类图例都在，计数与接口逐数一致', async () => {
    const root = await mount()
    expect(root.querySelector('[data-testid="legend-measured-count"]')?.textContent).toBe('2')
    expect(root.querySelector('[data-testid="legend-manual-count"]')?.textContent).toBe('1')
    expect(root.querySelector('[data-testid="legend-planned-count"]')?.textContent).toBe('1')
    expect(root.querySelector('[data-testid="legend-conflict-count"]')?.textContent).toBe('1')
  })

  it('默认显示全部边，条数等于 total', async () => {
    const root = await mount()
    expect(edgeCount(root)).toBe('4')
  })

  it('勾选「仅实测」后 URL 落 topo=measured，且按该 URL 重开只剩实测边', async () => {
    const root = await mount()
    const box = root.querySelector<HTMLInputElement>('[data-testid="topology-only-measured"]')!
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await nextTick()

    expect(state.replaced.length).toBe(1)
    expect((state.replaced[0].query as Record<string, unknown>).topo).toBe('measured')

    // 判据 4「URL 可复现」：按写出去的 query 重新挂载，必须得到同一张图
    app?.unmount()
    host?.remove()
    state.route = { query: { topo: 'measured' } }
    const reopened = await mount()
    expect(edgeCount(reopened)).toBe('2')
  })

  it('URL 自带 topo=measured 时首屏就只显示实测边（可复现）', async () => {
    state.route = { query: { topo: 'measured' } }
    const root = await mount()
    expect(edgeCount(root)).toBe('2')
  })

  it('有规划被覆盖时给出说明，不把冲突藏进图例数字里', async () => {
    const root = await mount()
    expect(root.querySelector('[data-testid="topology-conflict-hint"]')).toBeTruthy()
  })

  it('没有边时给自解释空态，不是一片空白', async () => {
    state.edgesView = view([], { MEASURED: 0, MANUAL: 0, PLANNED: 0, CONFLICT: 0 })
    const root = await mount()
    expect(edgeCount(root)).toBe('0')
    expect(root.querySelector('[data-testid="topology-edges-empty"]')).toBeTruthy()
  })

  it('接口失败时给错误行而不是静默空表', async () => {
    state.edgesReject = true
    const root = await mount()
    expect(root.querySelector('[data-testid="topology-edges-failed"]')).toBeTruthy()
  })

  it('规划节点单列（尚未连边），不与边混在一起', async () => {
    state.edgesView = view(
      [edge(1, 'MEASURED')],
      { MEASURED: 1, MANUAL: 0, PLANNED: 0, CONFLICT: 0 },
      0,
      [{ id: 9, name: 'planned-sw', nodeType: 'SWITCH', refDeviceId: null, parentId: null }],
    )
    const root = await mount()
    const nodes = root.querySelector('[data-testid="topology-planned-nodes"]')
    expect(nodes?.textContent).toContain('planned-sw')
  })
})
