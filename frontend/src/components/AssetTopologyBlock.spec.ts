/**
 * 链路拓扑块（v5.18.0 / FR-v5.18-02 / W68-04 抽取后首次建立）。
 *
 * 这组钉的是「多跳能不能串成一条链」与「孤儿端点会不会被并成一个节点」。
 * 这两件事都只在**链路形状不对**时才出错，而错误形态看起来很像正常渲染
 * （节点都在、边都在，只是被拆成了几张互不相干的图），靠人眼看截图抓不到。
 */
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, h, type App } from 'vue'
import { i18n } from '@/locales'
import AssetTopologyBlock from '@/components/AssetTopologyBlock.vue'
import type { Link } from '@/api/asset'

let app: App | null = null
let host: HTMLElement | null = null

function link(id: number, name: string, aId: number | null, aName: string,
              bId: number | null, bName: string): Link {
  return {
    id,
    name,
    linkType: 'ethernet',
    protocol: 'roce',
    speedGbps: 100,
    aDeviceId: aId,
    aDeviceName: aName,
    aPortId: null,
    aPortName: 'p1',
    bDeviceId: bId,
    bDeviceName: bName,
    bPortId: null,
    bPortName: 'p2',
    description: '',
    enabled: true,
    projectId: 1,
  } as unknown as Link
}

async function mount(links: Link[]): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({ setup: () => () => h(AssetTopologyBlock, { links }) })
  app.use(i18n)
  app.mount(host)
  return host
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AssetTopologyBlock', () => {
  it('单跳链路渲染成一行：两个节点 + 一条边', async () => {
    const root = await mount([link(1, 'L1', 10, 'dut-1', 20, 'sw-1')])
    expect(root.querySelectorAll('.topo-link').length).toBe(1)
    expect(root.querySelectorAll('.topo-node').length).toBe(2)
    expect(root.querySelectorAll('.topo-edge').length).toBe(1)
    expect(root.textContent).toContain('dut-1')
    expect(root.textContent).toContain('sw-1')
  })

  it('两跳串成一条链：dut → sw → host 一行渲染 3 个节点 / 2 条边', async () => {
    const root = await mount([
      link(1, 'L1', 10, 'dut-1', 20, 'sw-1'),
      link(2, 'L2', 20, 'sw-1', 30, 'host-1'),
    ])
    // 关键：不是两行，而是一行三节点——按链路逐行画会把人逼着自己接线
    expect(root.querySelectorAll('.topo-link').length).toBe(1)
    expect(root.querySelectorAll('.topo-node').length).toBe(3)
    expect(root.querySelectorAll('.topo-edge').length).toBe(2)
  })

  it('两条互不相连的链路各占一行，不会并成一条', async () => {
    const root = await mount([
      link(1, 'L1', 10, 'dut-1', 20, 'sw-1'),
      link(2, 'L2', 30, 'dut-2', 40, 'sw-2'),
    ])
    expect(root.querySelectorAll('.topo-link').length).toBe(2)
    expect(root.querySelectorAll('.topo-node').length).toBe(4)
  })

  it('两端都没登记设备的链路显示「未连接」，两条孤儿链路不共用一个节点', async () => {
    const root = await mount([
      link(1, 'L1', null, '', null, ''),
      link(2, 'L2', null, '', null, ''),
    ])
    // 两个链路各 2 个孤儿端点 → 4 个节点；若键只用「未连接」会被并成 1 个
    expect(root.querySelectorAll('.topo-node').length).toBe(4)
    expect(root.textContent).toContain('未连接')
  })

  it('没有链路时给空态文案，不渲染空容器', async () => {
    const root = await mount([])
    expect(root.querySelectorAll('.topo-link').length).toBe(0)
    expect(root.textContent?.trim().length).toBeGreaterThan(0)
  })
})
