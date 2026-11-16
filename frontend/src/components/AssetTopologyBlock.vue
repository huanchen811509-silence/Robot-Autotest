<script setup lang="ts">
/**
 * 链路拓扑视图 —— v5.18.0 / FR-v5.18-02 / W68-04 从 `AssetsView.vue` 抽出。
 *
 * <p>它回答的问题与表格不同：表格答「有几条链路」，拓扑答「谁连到谁」。为此这里用
 * 链路自带的 A/B 端建邻接表，把「一条链路一行」重排成「一条链一行」——同一台设备
 * 会在多条链路里出现，按链路逐行画看到的是三张互不相干的图，排障时还得自己在脑子
 * 里接起来。分叉与环都不特殊处理：走到不能再走就收尾，剩下的边另起一条链，
 * 保证「每条链路恰好渲染一次」。
 *
 * <p>抽成组件的收益：这份图算法与页面的取数、表单、分页零耦合，独立后可以单独
 * 喂假链路测「多跳串链」与「孤儿端点」这两种最容易画错的形态。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Link } from '@/api/asset'
import { EMPTY } from '@/utils/format'

const props = defineProps<{
  /** 当前页链路；拓扑按这些链路连线，不做额外取数。 */
  links: Link[]
}>()

const { t } = useI18n()

function endpointName(link: Link, side: 'a' | 'b'): string {
  const name = side === 'a' ? link.aDeviceName : link.bDeviceName
  return name || t('assets.link.unconnected')
}

function endpointPort(link: Link, side: 'a' | 'b'): string {
  return (side === 'a' ? link.aPortName : link.bPortName) || ''
}

/**
 * 多跳拓扑：把「一条链路一行」重排成「一条链一行」。
 *
 * 数据模型只有两端链路，但同一台设备会出现在多条链路里（DUT → SW → HOST）。
 * 按链路逐行画，看到的是三张互不相干的图，排障时还得自己在脑子里接起来；
 * 这里用链路两端的设备 id 建邻接表，从任意一条没走过的边出发向两端各走到底，
 * 串成节点与边交替的链。分叉与环都不特殊处理——走到不能再走就收尾，
 * 剩下的边由外层循环另起一条链，从而保证「每条链路恰好渲染一次」。
 */
type TopoNode = { key: string; name: string; ports: string[] }
type TopoChain = { key: string; label: string; nodes: TopoNode[]; edges: Link[] }

function sideKey(link: Link, side: 'a' | 'b'): string {
  const id = side === 'a' ? link.aDeviceId : link.bDeviceId
  // 没登记设备时用「链路 + 侧」做键：否则两条孤儿链路的端点会被并成同一个「未连接」节点
  return id ? `d:${id}` : `u:${link.id}:${side}`
}

function otherKey(link: Link, side: 'a' | 'b'): string {
  return sideKey(link, side === 'a' ? 'b' : 'a')
}

const linkChains = computed<TopoChain[]>(() => {
  const nodes = new Map<string, TopoNode>()
  const adjacency = new Map<string, { link: Link; side: 'a' | 'b' }[]>()

  const ensure = (key: string, name: string): TopoNode => {
    const existing = nodes.get(key)
    if (existing) return existing
    const created: TopoNode = { key, name, ports: [] }
    nodes.set(key, created)
    return created
  }

  for (const link of props.links) {
    for (const side of ['a', 'b'] as const) {
      const key = sideKey(link, side)
      const node = ensure(key, endpointName(link, side))
      const port = endpointPort(link, side)
      if (port && !node.ports.includes(port)) node.ports.push(port)
      const list = adjacency.get(key) ?? []
      list.push({ link, side })
      adjacency.set(key, list)
    }
  }

  const used = new Set<Link>()

  /** 从 startKey 顺着「不是刚走过的那条、且还没用过」的边一直走到头。 */
  const extend = (startKey: string, via: Link): { keys: string[]; edges: Link[] } => {
    const keys: string[] = []
    const edges: Link[] = []
    let cursor = startKey
    let previous: Link = via
    for (;;) {
      const next = (adjacency.get(cursor) ?? []).find(
        (item) => item.link !== previous && !used.has(item.link),
      )
      if (!next) break
      used.add(next.link)
      cursor = otherKey(next.link, next.side)
      previous = next.link
      keys.push(cursor)
      edges.push(next.link)
    }
    return { keys, edges }
  }

  const chains: TopoChain[] = []
  for (const link of props.links) {
    if (used.has(link)) continue
    used.add(link)
    const aKey = sideKey(link, 'a')
    const bKey = sideKey(link, 'b')
    // 先往 A 端外侧走，再往 B 端外侧走；左侧是倒序长出来的，拼回去时要翻一次
    const left = extend(aKey, link)
    const right = extend(bKey, link)
    const keys = [...left.keys.slice().reverse(), aKey, bKey, ...right.keys]
    const edges = [...left.edges.slice().reverse(), link, ...right.edges]
    chains.push({
      key: `chain:${link.id}`,
      // 单跳链的标签就是链路名本身，多跳才用箭头连起来
      label: edges.map((item) => item.name).join(' → '),
      nodes: keys.map((key) => nodes.get(key) ?? { key, name: '', ports: [] }),
      edges,
    })
  }
  return chains
})

function edgeMeta(link: Link): string {
  return (
    [link.protocol, link.speedGbps ? `${link.speedGbps} Gbps` : '']
      .filter(Boolean)
      .join(' · ') || EMPTY
  )
}

/** 链有多长栅格就多宽：节点拉伸，边固定 180px，否则长链会把节点压成一条缝。 */
function chainColumns(chain: TopoChain): string {
  return chain.nodes
    .map((_, index) => (index < chain.edges.length ? 'minmax(0, 1fr) 180px' : 'minmax(0, 1fr)'))
    .join(' ')
}
</script>

<template>
<div class="topology">
      <p v-if="links.length === 0" class="muted">{{ t('assets.link.topologyEmpty') }}</p>
      <!-- 一条链渲染成一行：节点与边交替，链断了自然折到下一行，多跳才看得出「谁串到谁」 -->
      <div
        v-for="chain in linkChains"
        :key="chain.key"
        class="topo-link"
        :data-link="chain.label"
        :style="{ gridTemplateColumns: chainColumns(chain) }"
      >
        <template v-for="(node, index) in chain.nodes" :key="`${chain.key}:${index}`">
          <div class="topo-node">
            <span class="topo-node-name">{{ node.name }}</span>
            <span v-if="node.ports.length" class="topo-node-port">
              {{ node.ports.join(' / ') }}
            </span>
          </div>
          <div v-if="chain.edges[index]" class="topo-edge">
            <span class="topo-edge-name">{{ chain.edges[index].name }}</span>
            <span class="muted small">{{ edgeMeta(chain.edges[index]) }}</span>
          </div>
        </template>
      </div>
    </div>
</template>
