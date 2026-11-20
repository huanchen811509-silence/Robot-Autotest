/**
 * `TopologyGraph` 回归（v5.34.0 / FR-v5.34-03）。
 *
 * 这张图的判据全部落在「DOM 里真有什么」上，因为它的前身正是一个**只有图例、没有图**的页面：
 * 文案写「图上 N 条」，DOM 里却一个 `<svg>` 都没有。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import TopologyGraph from '@/components/TopologyGraph.vue'
import type { TopoNode, TopologyEdge } from '@/api/environment'

const nodes: TopoNode[] = [
  { id: 1, name: 'core-sw-01', deviceType: 'switch' },
  { id: 2, name: 'gpu-node-01', deviceType: 'GPU服务器' },
  { id: 3, name: 'gpu-node-02', deviceType: 'gpu' },
  { id: 4, name: 'nas-01', deviceType: 'storage' },
]

const edges: TopologyEdge[] = [
  {
    id: 11, name: 'sw01-gpu01', aDeviceId: 1, aDeviceName: 'core-sw-01',
    bDeviceId: 2, bDeviceName: 'gpu-node-01', kind: 'MEASURED', source: 'LLDP',
  },
  {
    id: 12, name: 'sw01-gpu02', aDeviceId: 1, aDeviceName: 'core-sw-01',
    bDeviceId: 3, bDeviceName: 'gpu-node-02', kind: 'MANUAL', source: null,
  },
  {
    id: 13, name: 'gpu01-nas', aDeviceId: 2, aDeviceName: 'gpu-node-01',
    bDeviceId: 4, bDeviceName: 'nas-01', kind: 'PLANNED', source: null,
  },
]

let app: App | null = null
let host: HTMLElement | null = null

async function mount(props: { edges?: TopologyEdge[]; nodes?: TopoNode[] } = {}): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(TopologyGraph, {
    edges: props.edges ?? edges,
    nodes: props.nodes ?? nodes,
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

beforeEach(() => {
  document.body.innerHTML = ''
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('TopologyGraph', () => {
  it('真的画出 svg：节点数与输入一致', async () => {
    const root = await mount()
    expect(root.querySelector('svg.topo__canvas')).not.toBeNull()
    const rendered = root.querySelectorAll('[data-testid^="topo-node-"]')
    expect(rendered).toHaveLength(nodes.length)
  })

  it('三类边各自带 kind class，线型可区分', async () => {
    const root = await mount()
    expect(root.querySelector('[data-testid="topo-edge-11"]')?.getAttribute('class'))
      .toContain('is-measured')
    expect(root.querySelector('[data-testid="topo-edge-12"]')?.getAttribute('class'))
      .toContain('is-manual')
    expect(root.querySelector('[data-testid="topo-edge-13"]')?.getAttribute('class'))
      .toContain('is-planned')
  })

  it('同一对节点之间的两条边不重叠（path 不相同）', async () => {
    const dup = [
      { ...edges[0]!, id: 21, kind: 'MEASURED' as const },
      { ...edges[0]!, id: 22, kind: 'MANUAL' as const },
    ]
    const root = await mount({ edges: dup })
    const d1 = root.querySelector('[data-testid="topo-edge-21"]')?.getAttribute('d')
    const d2 = root.querySelector('[data-testid="topo-edge-22"]')?.getAttribute('d')
    expect(d1).toBeTruthy()
    expect(d2).toBeTruthy()
    expect(d1).not.toBe(d2)
  })

  it('分层：交换机/计算/存储在纵向不同行', async () => {
    const root = await mount()
    const y = (id: number): number => {
      const g = root.querySelector(`[data-testid="topo-node-${id}"]`)
      const transform = g?.getAttribute('transform') ?? ''
      const match = /translate\([^,]+,\s*([\d.]+)\)/.exec(transform)
      return Number(match?.[1] ?? -1)
    }
    // 交换机在第 2 行、计算节点第 3 行、存储第 4 行（出口层为空不占位）
    expect(y(1)).toBeLessThan(y(2))
    expect(y(2)).toBeLessThan(y(4))
  })

  it('对端未登记的边不画线，但给出计数提示', async () => {
    const danglingEdges: TopologyEdge[] = [
      {
        id: 31, name: 'half', aDeviceId: 1, aDeviceName: 'core-sw-01',
        bDeviceId: null, bDeviceName: null, kind: 'MANUAL', source: null,
      },
    ]
    const root = await mount({ edges: danglingEdges })
    expect(root.querySelector('[data-testid="topo-edge-31"]')).toBeNull()
    expect(root.textContent).toContain(i18n.global.t('env.topology.dangling', { count: 1 }))
  })

  it('缩放按钮改变 viewBox，复位回到 1:1', async () => {
    const root = await mount()
    const svg = root.querySelector('svg.topo__canvas')!
    const initial = svg.getAttribute('viewBox')
    ;(root.querySelector('[data-testid="topo-zoom-in"]') as HTMLElement).click()
    await nextTick()
    const zoomed = svg.getAttribute('viewBox')
    expect(zoomed).not.toBe(initial)
    ;(root.querySelector('[data-testid="topo-reset"]') as HTMLElement).click()
    await nextTick()
    expect(svg.getAttribute('viewBox')).toBe(initial)
  })
})
