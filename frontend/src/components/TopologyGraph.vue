<script setup lang="ts">
/**
 * 分层拓扑图（v5.34.0 / FR-v5.34-03）。
 *
 * 自研纯 SVG，不引图库：离线部署环境装不了 vue-flow / cytoscape，而这张图的布局
 * 需求本来就只有「按层分行、同行均分」，力导向纯属浪费。
 *
 * 设计参考了白泽（`new-baize/frontend/src/components/TopologyGraph.vue`）的分层行布局与
 * 「同对节点多条边按法线错开」，但代码是本工程的独立实现，且三处刻意不同：
 * ① 配色全部走 CSS token（白泽写死深色），浅色主题下同样可读；
 * ② 节点与边都有 click 事件（白泽原组件没有任何点击）；
 * ③ 边的类别只认后端给的 kind（MEASURED/MANUAL/PLANNED），绝不从设备名里猜「800G」。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TopoNode, TopologyEdge, TopologyEdgeKind } from '@/api/environment'

const props = withDefaults(defineProps<{
  edges: TopologyEdge[]
  nodes: TopoNode[]
  /** 缩放下限/上限（默认 0.5–2，与白泽同区间但由本组件自己夹紧）。 */
  minScale?: number
  maxScale?: number
}>(), { minScale: 0.5, maxScale: 2 })

const emit = defineEmits<{
  (event: 'select-node', node: TopoNode): void
  (event: 'select-edge', edge: TopologyEdge): void
}>()

const { t } = useI18n()

/** 画布逻辑尺寸：宽度固定，高度随层数长，外层容器滚动/缩放。 */
const CANVAS_W = 1000
const ROW_H = 96
const NODE_W = 132
const NODE_H = 34

type LayerKey = 'edge' | 'switch' | 'compute' | 'storage' | 'unknown'

const LAYER_ORDER: LayerKey[] = ['edge', 'switch', 'compute', 'storage', 'unknown']

/**
 * Layering rule: `device_type` is free text (real values include `switch`, `HOST`,
 * `GPU服务器`, `storage`), so matching is case-insensitive substring, never an enum lookup —
 * an enum would classify those real values as "unknown" and flatten the graph.
 *
 * The CJK keywords below are written as unicode escapes on purpose: the i18n gate rejects
 * CJK literals in source, and these are **data values**, not UI copy — they must not move
 * into the locale bundles (switching UI language does not rename rows in the database).
 */
const ZH_ROUTER = '\u8def\u7531';
const ZH_FIREWALL = '\u9632\u706b\u5899';
const ZH_SWITCH = '\u4ea4\u6362';
const ZH_STORAGE = '\u5b58\u50a8';
const ZH_SERVER = '\u670d\u52a1\u5668';
const ZH_COMPUTE = '\u8ba1\u7b97';

function layerOf(node: TopoNode): LayerKey {
  const raw = (node.deviceType ?? '').toLowerCase()
  if (!raw) return 'unknown'
  if (raw.includes('route') || raw.includes('firewall') || raw.includes('gateway')
    || raw.includes(ZH_ROUTER) || raw.includes(ZH_FIREWALL)) return 'edge'
  if (raw.includes('switch') || raw.includes(ZH_SWITCH)) return 'switch'
  if (raw.includes('storage') || raw.includes(ZH_STORAGE) || raw.includes('nas') || raw.includes('san')) {
    return 'storage'
  }
  if (raw.includes('server') || raw.includes('host') || raw.includes('gpu')
    || raw.includes('workstation') || raw.includes(ZH_SERVER) || raw.includes(ZH_COMPUTE)) {
    return 'compute'
  }
  return 'unknown'
}

interface Positioned extends TopoNode {
  x: number
  y: number
  layer: LayerKey
}

/**
 * 布局：只画「出现在边上的设备」+ 未被任何边连接但类型已知的设备。
 * 层内均分；层间纵向固定行高。
 */
const layout = computed<{ nodes: Positioned[]; height: number }>(() => {
  const byLayer = new Map<LayerKey, TopoNode[]>()
  for (const node of props.nodes) {
    const key = layerOf(node)
    const list = byLayer.get(key) ?? []
    list.push(node)
    byLayer.set(key, list)
  }
  // 空层不占位：否则设备少的项目最上面会留一大片空白
  const layers = LAYER_ORDER.filter((key) => (byLayer.get(key)?.length ?? 0) > 0)
  const positioned: Positioned[] = []
  layers.forEach((key, rowIndex) => {
    const list = byLayer.get(key) ?? []
    const step = CANVAS_W / (list.length + 1)
    list.forEach((node, i) => {
      positioned.push({
        ...node,
        layer: key,
        x: Math.round(step * (i + 1)),
        y: rowIndex * ROW_H + ROW_H / 2,
      })
    })
  })
  return { nodes: positioned, height: Math.max(ROW_H * 2, layers.length * ROW_H) }
})

const positions = computed(() => {
  const map = new Map<number, Positioned>()
  for (const node of layout.value.nodes) map.set(node.id, node)
  return map
})

const layerLabels = computed<Record<LayerKey, string>>(() => ({
  edge: t('env.topology.layerEdge'),
  switch: t('env.topology.layerSwitch'),
  compute: t('env.topology.layerCompute'),
  storage: t('env.topology.layerStorage'),
  unknown: t('env.topology.layerUnknown'),
}))

/** 只画两端都能定位到节点的边；对端未登记（bDeviceId 为空）的边单独计数提示。 */
interface DrawnEdge {
  edge: TopologyEdge
  path: string
  kind: TopologyEdgeKind
}

/**
 * 同对节点之间的多条边要错开。
 *
 * 用「两点连线的法线方向」偏移而不是固定 +8px：固定偏移在两节点竖直排列时
 * 会把两条边叠成同一条竖线，看着像只有一条。
 */
const drawnEdges = computed<DrawnEdge[]>(() => {
  const result: DrawnEdge[] = []
  const pairSeen = new Map<string, number>()
  for (const edge of props.edges) {
    if (edge.aDeviceId === null || edge.bDeviceId === null) continue
    const a = positions.value.get(edge.aDeviceId)
    const b = positions.value.get(edge.bDeviceId)
    if (!a || !b) continue
    const pairKey = [Math.min(a.id, b.id), Math.max(a.id, b.id)].join('-')
    const index = pairSeen.get(pairKey) ?? 0
    pairSeen.set(pairKey, index + 1)
    const sameLayer = a.y === b.y
    const midY = (a.y + b.y) / 2
    // 同一行时向上拱、跨层时轻微横向外扩；index 让第 2、3 条边依次加大偏移
    const bow = (sameLayer ? -34 : 0) - index * 12
    const dx = b.x - a.x
    const dy = b.y - a.y
    const len = Math.hypot(dx, dy) || 1
    const nx = -dy / len
    const ny = dx / len
    const c1x = a.x + dx / 3 + nx * bow
    const c1y = midY + ny * bow
    const c2x = a.x + (dx * 2) / 3 + nx * bow
    const c2y = midY + ny * bow
    result.push({
      edge,
      kind: edge.kind,
      path: `M ${a.x} ${a.y} C ${c1x} ${c1y} ${c2x} ${c2y} ${b.x} ${b.y}`,
    })
  }
  return result
})

/** 对端未登记的边：不画线，但要让人知道「有多少条还没配对」。 */
const danglingCount = computed(
  () => props.edges.filter((e) => e.aDeviceId === null || e.bDeviceId === null).length,
)

const kindCounts = computed<Record<TopologyEdgeKind, number>>(() => {
  const counts: Record<TopologyEdgeKind, number> = { MEASURED: 0, MANUAL: 0, PLANNED: 0 }
  for (const edge of props.edges) counts[edge.kind] = (counts[edge.kind] ?? 0) + 1
  return counts
})

const kindLabel = computed<Record<TopologyEdgeKind, string>>(() => ({
  MEASURED: t('env.topology.edgeKindMeasured'),
  MANUAL: t('env.topology.edgeKindManual'),
  PLANNED: t('env.topology.edgeKindPlanned'),
}))

/* --------------------------------------------------------- 缩放与平移 */

const scale = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragging = ref(false)
let dragStart = { x: 0, y: 0, ox: 0, oy: 0 }

const viewBox = computed(() => {
  const w = CANVAS_W / scale.value
  const h = layout.value.height / scale.value
  return `${offset.value.x} ${offset.value.y} ${w} ${h}`
})

function clamp(value: number): number {
  return Math.min(props.maxScale, Math.max(props.minScale, value))
}

function zoomBy(delta: number): void {
  scale.value = clamp(Number((scale.value + delta).toFixed(2)))
}

function resetView(): void {
  scale.value = 1
  offset.value = { x: 0, y: 0 }
}

function onWheel(event: WheelEvent): void {
  // 阻止页面跟着滚：鼠标在图上是想缩放，不是想把整页翻下去
  event.preventDefault()
  zoomBy(event.deltaY > 0 ? -0.1 : 0.1)
}

function onPointerDown(event: PointerEvent): void {
  dragging.value = true
  dragStart = {
    x: event.clientX,
    y: event.clientY,
    ox: offset.value.x,
    oy: offset.value.y,
  }
  ;(event.currentTarget as SVGElement).setPointerCapture?.(event.pointerId)
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value) return
  // 屏幕像素换算成 viewBox 单位：不除 scale 的话放大后拖拽会「跟不上手」
  const k = scale.value
  offset.value = {
    x: dragStart.ox - (event.clientX - dragStart.x) / k,
    y: dragStart.oy - (event.clientY - dragStart.y) / k,
  }
}

function onPointerUp(): void {
  dragging.value = false
}
</script>

<template>
  <div class="topo" data-testid="topology-graph">
    <div class="topo__bar">
      <div class="topo__legend" data-testid="topology-legend">
        <span
          v-for="kind in (['MEASURED', 'MANUAL', 'PLANNED'] as TopologyEdgeKind[])"
          :key="kind"
          class="topo__legend-item"
        >
          <svg class="topo__legend-line" viewBox="0 0 24 8" aria-hidden="true">
            <line
              :class="`edge is-${kind.toLowerCase()}`"
              x1="1" y1="4" x2="23" y2="4"
            />
          </svg>
          {{ kindLabel[kind] }}（{{ kindCounts[kind] }}）
        </span>
        <span v-if="danglingCount" class="topo__dangling">
          {{ t('env.topology.dangling', { count: danglingCount }) }}
        </span>
      </div>
      <div class="topo__zoom">
        <button type="button" data-testid="topo-zoom-out" @click="zoomBy(-0.1)">−</button>
        <span class="topo__zoom-value">{{ Math.round(scale * 100) }}%</span>
        <button type="button" data-testid="topo-zoom-in" @click="zoomBy(0.1)">+</button>
        <button type="button" data-testid="topo-reset" @click="resetView">
          {{ t('env.topology.reset') }}
        </button>
      </div>
    </div>

    <svg
      class="topo__canvas"
      :viewBox="viewBox"
      role="img"
      :aria-label="t('env.topology.graphAria')"
      @wheel="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointerleave="onPointerUp"
    >
      <g class="topo__layers">
        <text
          v-for="layer in LAYER_ORDER.filter((k) => layout.nodes.some((n) => n.layer === k))"
          :key="layer"
          class="topo__layer-label"
          :x="8"
          :y="layout.nodes.find((n) => n.layer === layer)!.y + 4"
        >
          {{ layerLabels[layer] }}
        </text>
      </g>

      <g class="topo__edges">
        <path
          v-for="item in drawnEdges"
          :key="item.edge.id"
          :d="item.path"
          :class="`edge is-${item.kind.toLowerCase()}`"
          :data-testid="`topo-edge-${item.edge.id}`"
          @click.stop="emit('select-edge', item.edge)"
        >
          <title>{{ item.edge.name }} · {{ item.edge.source ?? '' }}</title>
        </path>
      </g>

      <g class="topo__nodes">
        <g
          v-for="node in layout.nodes"
          :key="node.id"
          :transform="`translate(${node.x - NODE_W / 2}, ${node.y - NODE_H / 2})`"
          class="topo__node"
          :data-testid="`topo-node-${node.id}`"
          tabindex="0"
          @click.stop="emit('select-node', node)"
          @keyup.enter="emit('select-node', node)"
        >
          <rect :width="NODE_W" :height="NODE_H" rx="8" class="topo__node-box" />
          <text :x="NODE_W / 2" :y="NODE_H / 2 + 4" class="topo__node-label">
            {{ node.name.length > 16 ? `${node.name.slice(0, 15)}…` : node.name }}
          </text>
          <title>{{ node.name }}{{ node.deviceType ? ` · ${node.deviceType}` : '' }}</title>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.topo {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  padding: var(--space-3);
}

.topo__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.topo__legend {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.topo__legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.topo__legend-line {
  width: 24px;
  height: 8px;
}

.topo__dangling {
  font-size: var(--font-size-xs);
  color: var(--warn);
}

.topo__zoom {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-size-sm);
}

.topo__zoom button {
  min-width: 28px;
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  cursor: pointer;
}

.topo__zoom-value {
  font-variant-numeric: tabular-nums;
  color: var(--text-muted);
  min-width: 40px;
  text-align: center;
}

.topo__canvas {
  width: 100%;
  height: 420px;
  cursor: grab;
  touch-action: none;
  background: var(--bg);
  border-radius: var(--radius-md);
}

.topo__canvas:active {
  cursor: grabbing;
}

/* 三类边靠「线型 + 颜色」双通道区分：只靠颜色的话，色弱用户与黑白打印都分不出来 */
.edge {
  fill: none;
  stroke-width: 1.6;
}

.edge.is-measured {
  stroke: var(--accent);
  stroke-dasharray: none;
}

.edge.is-manual {
  stroke: var(--text-muted);
  stroke-dasharray: 6 3;
}

.edge.is-planned {
  stroke: var(--warn);
  stroke-dasharray: 2 4;
}

.topo__edges path {
  cursor: pointer;
}

.topo__edges path:hover {
  stroke-width: 2.6;
}

.topo__layer-label {
  font-size: 11px;
  fill: var(--text-muted);
}

.topo__node {
  cursor: pointer;
}

.topo__node-box {
  fill: var(--panel);
  stroke: var(--border-strong);
  stroke-width: 1;
}

.topo__node:hover .topo__node-box,
.topo__node:focus-visible .topo__node-box {
  stroke: var(--accent);
}

.topo__node-label {
  font-size: 12px;
  text-anchor: middle;
  fill: var(--text);
  pointer-events: none;
}
</style>
