<script setup lang="ts">
/**
 * FR-v4.3-07 · 连接与拓扑（只读派生页）。
 *
 * 数据来自 `GET /api/projects/{projectId}/asset-info`：设备 + 端口（含网卡归属）+ 链路。
 * 本页不提供连接的新增/编辑——连接由链路定义，编辑入口仍在「测试资产 → 链路」。
 *
 * 四块内容：元信息条（生成时间 / 来源 / 复用状态 / 复用窗口）→ 拓扑图 → 连接明细表。
 * 刷新是唯一的写动作，走后端 `POST /asset-info/refresh`（无权限角色会收到 403，页面只提示失败）。
 * 空项目显示引导空态，不显示错误，也不编造示例行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import {
  getAssetInfo,
  refreshAssetInfo,
  type AssetConnection,
  type AssetInfo,
} from '@/api/assetInfo'
import { formatDateTime } from '@/utils/format'

interface ConnectionRow {
  id: number
  link: string
  aSide: string
  bSide: string
  protocol: string
  speed: string
  status: string
  aDevice: string
  aNic: string
  aPort: string
  bDevice: string
  bNic: string
  bPort: string
}

interface TopoBox {
  id: string
  name: string
  type: string
  enabled: boolean
  x: number
  y: number
  w: number
  h: number
}

interface TopoPath {
  id: string
  d: string
  enabled: boolean
  title: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const info = ref<AssetInfo | null>(null)
const loading = ref(false)
const failed = ref(false)
const refreshing = ref(false)
const notice = ref('')

const keywordDevice = ref('')
const keywordNic = ref('')
const keywordPort = ref('')

const dash = '—'

const nicLabel = (value: string | null): string =>
  value && value.trim() ? value : t('env.connections.nicUnassigned')

function sideText(device: string | null, nic: string | null, port: string | null): string {
  return `${device || dash} / ${nicLabel(nic)} / ${port || dash}`
}

const rows = computed<ConnectionRow[]>(() =>
  (info.value?.connections.items ?? []).map((item: AssetConnection) => ({
    id: item.linkId,
    link: `${item.linkName || `#${item.linkId}`}${item.linkType ? ` · ${item.linkType}` : ''}`,
    aSide: sideText(item.aDevice, item.aNic, item.aPort),
    bSide: sideText(item.bDevice, item.bNic, item.bPort),
    protocol: item.protocol || dash,
    speed: item.speedGbps === null ? dash : t('env.connections.speedValue', { value: item.speedGbps }),
    status: item.enabled ? t('env.connections.enabled') : t('env.connections.disabled'),
    aDevice: item.aDevice || '',
    aNic: item.aNic || '',
    aPort: item.aPort || '',
    bDevice: item.bDevice || '',
    bNic: item.bNic || '',
    bPort: item.bPort || '',
  })),
)

/** 本地过滤：设备 / 网卡 / 接口三段各自独立，互不放大（PRD FR-v4.3-07 R6）。 */
const filteredRows = computed<ConnectionRow[]>(() => {
  const device = keywordDevice.value.trim().toLowerCase()
  const nic = keywordNic.value.trim().toLowerCase()
  const port = keywordPort.value.trim().toLowerCase()
  if (!device && !nic && !port) return rows.value
  return rows.value.filter((row) => {
    const deviceHit = !device
      || row.aDevice.toLowerCase().includes(device)
      || row.bDevice.toLowerCase().includes(device)
    const nicHit = !nic
      || row.aNic.toLowerCase().includes(nic)
      || row.bNic.toLowerCase().includes(nic)
    const portHit = !port
      || row.aPort.toLowerCase().includes(port)
      || row.bPort.toLowerCase().includes(port)
    return deviceHit && nicHit && portHit
  })
})

const hasFilter = computed(
  () => Boolean(keywordDevice.value.trim() || keywordNic.value.trim() || keywordPort.value.trim()),
)

const columns = computed<DataTableColumns<ConnectionRow>>(() => [
  { title: t('env.connections.colLink'), key: 'link' },
  { title: t('env.connections.colA'), key: 'aSide' },
  { title: t('env.connections.colB'), key: 'bSide' },
  { title: t('env.connections.colProtocol'), key: 'protocol' },
  { title: t('env.connections.colSpeed'), key: 'speed' },
  { title: t('env.connections.colStatus'), key: 'status' },
])

const isEmpty = computed(() => info.value === null || info.value.empty)

/**
 * 拓扑布局：设备一列一台，网卡与接口在列内纵向嵌套；边按两端接口的中心连线。
 * 只做稳定布局，不做力导向——同数据两次渲染坐标必须一致（PRD FR-v4.3-03 R4）。
 */
const layout = computed(() => {
  const nodes = info.value?.topology.nodes ?? []
  const edges = info.value?.topology.edges ?? []
  const devices = nodes.filter((node) => node.type === 'device')
  const boxes: TopoBox[] = []
  const coordinates = new Map<string, TopoBox>()

  const COL_WIDTH = 300
  const BOX_WIDTH = 240
  const TOP = 16
  const DEVICE_HEIGHT = 34
  const NIC_HEIGHT = 26
  const PORT_HEIGHT = 24
  const ROW_GAP = 6

  let maxBottom = TOP
  devices.forEach((device, index) => {
    const x = 24 + index * COL_WIDTH
    let y = TOP
    const deviceBox: TopoBox = {
      id: device.id, name: device.name, type: device.type, enabled: device.enabled,
      x, y, w: BOX_WIDTH, h: DEVICE_HEIGHT,
    }
    boxes.push(deviceBox)
    coordinates.set(device.id, deviceBox)
    y += DEVICE_HEIGHT + 10

    const nics = nodes.filter((node) => node.type === 'nic' && node.parent === device.id)
    for (const nic of nics) {
      const nicBox: TopoBox = {
        id: nic.id, name: nic.name, type: nic.type, enabled: nic.enabled,
        x: x + 12, y, w: BOX_WIDTH - 24, h: NIC_HEIGHT,
      }
      boxes.push(nicBox)
      coordinates.set(nic.id, nicBox)
      y += NIC_HEIGHT + ROW_GAP

      const ports = nodes.filter((node) => node.type === 'port' && node.parent === nic.id)
      for (const port of ports) {
        const portBox: TopoBox = {
          id: port.id, name: port.name, type: port.type, enabled: port.enabled,
          x: x + 28, y, w: BOX_WIDTH - 48, h: PORT_HEIGHT,
        }
        boxes.push(portBox)
        coordinates.set(port.id, portBox)
        y += PORT_HEIGHT + ROW_GAP
      }
      maxBottom = Math.max(maxBottom, y)
      y += 4
    }
    maxBottom = Math.max(maxBottom, y)
  })

  const paths: TopoPath[] = []
  for (const edge of edges) {
    const from = coordinates.get(edge.from)
    const to = coordinates.get(edge.to)
    if (!from || !to) continue
    const fromCenter = from.x + from.w / 2
    const toCenter = to.x + to.w / 2
    const rightward = toCenter >= fromCenter
    const x1 = rightward ? from.x + from.w : from.x
    const y1 = from.y + from.h / 2
    const x2 = rightward ? to.x : to.x + to.w
    const y2 = to.y + to.h / 2
    const bend = Math.max(40, Math.abs(x2 - x1) / 2)
    const d = `M ${x1} ${y1} C ${x1 + (rightward ? bend : -bend)} ${y1}, `
      + `${x2 - (rightward ? bend : -bend)} ${y2}, ${x2} ${y2}`
    const title = `#${edge.linkId}${edge.linkType ? ` · ${edge.linkType}` : ''}`
      + `${edge.speedGbps === null ? '' : ` · ${edge.speedGbps} Gbps`}`
    paths.push({
      id: `edge-${edge.linkId}-${edge.from}-${edge.to}`,
      d,
      enabled: from.enabled && to.enabled,
      title,
    })
  }

  return {
    boxes,
    paths,
    width: Math.max(640, 24 + devices.length * COL_WIDTH),
    height: Math.max(120, maxBottom + 20),
  }
})

const hasTopology = computed(() => (info.value?.topology.nodes.length ?? 0) > 0)

function clearFilters(): void {
  keywordDevice.value = ''
  keywordNic.value = ''
  keywordPort.value = ''
}

async function load(): Promise<void> {
  if (!projectId.value) {
    info.value = null
    return
  }
  loading.value = true
  failed.value = false
  try {
    info.value = await getAssetInfo(projectId.value)
  } catch {
    info.value = null
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function doRefresh(): Promise<void> {
  if (!projectId.value || refreshing.value) return
  refreshing.value = true
  notice.value = ''
  try {
    info.value = await refreshAssetInfo(projectId.value)
    notice.value = t('env.connections.refreshDone')
  } catch {
    notice.value = t('env.connections.refreshFailed')
  } finally {
    refreshing.value = false
  }
}

onMounted(load)
watch(projectId, load)
</script>

<template>
  <div class="page" data-testid="connections-page">
    <header class="page-head">
      <h1>{{ t('env.connections.title') }}</h1>
      <p class="muted">{{ t('env.connections.subtitle') }}</p>
    </header>

    <div v-if="failed" class="banner error" data-testid="connections-failed">
      {{ t('env.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="connections-retry" @click="load">
        {{ t('env.common.retry') }}
      </NButton>
    </div>

    <section v-if="info" class="meta" data-testid="connections-meta">
      <span class="meta-item">
        <span class="meta-label">{{ t('env.connections.metaGeneratedAt') }}</span>
        <span class="meta-value">{{ formatDateTime(info.generatedAt) }}</span>
      </span>
      <span class="meta-item">
        <span class="meta-label">{{ t('env.connections.metaSource') }}</span>
        <span class="meta-value">{{ t('env.connections.sourceInternal') }}</span>
      </span>
      <span class="meta-item">
        <span class="meta-label">{{ t('env.connections.metaCache') }}</span>
        <span class="meta-value" data-testid="connections-cache">
          {{ info.meta.cacheHit ? t('env.connections.cacheHit') : t('env.connections.cacheMiss') }}
        </span>
      </span>
      <span class="meta-item">
        <span class="meta-label">{{ t('env.connections.metaTtl') }}</span>
        <span class="meta-value">
          {{ t('env.connections.metaTtlValue', { seconds: info.meta.ttlSeconds }) }}
        </span>
      </span>
      <NButton
        size="small"
        :disabled="refreshing"
        data-testid="connections-refresh"
        @click="doRefresh"
      >
        {{ refreshing ? t('env.connections.refreshing') : t('env.connections.refresh') }}
      </NButton>
    </section>

    <p v-if="notice" class="muted" data-testid="connections-notice">{{ notice }}</p>

    <p v-if="loading" class="muted" data-testid="connections-loading">{{ t('env.common.loading') }}</p>

    <section v-else-if="isEmpty" class="panel empty-panel">
      <h2 data-testid="connections-empty-title">{{ t('env.connections.emptyTitle') }}</h2>
      <p class="muted" data-testid="connections-empty">{{ t('env.connections.emptyHint') }}</p>
    </section>

    <template v-else>
      <section class="panel">
        <h2>{{ t('env.connections.topoTitle') }}</h2>
        <p v-if="!hasTopology" class="empty" data-testid="connections-topo-empty">
          {{ t('env.connections.topoEmpty') }}
        </p>
        <div v-else class="topo-scroll" data-testid="connections-topology">
          <svg
            :viewBox="`0 0 ${layout.width} ${layout.height}`"
            :width="layout.width"
            :height="layout.height"
            role="img"
            :aria-label="t('env.connections.topoTitle')"
          >
            <path
              v-for="edge in layout.paths"
              :key="edge.id"
              :d="edge.d"
              class="topo-edge"
              :class="{ 'topo-edge--off': !edge.enabled }"
            >
              <title>{{ edge.title }}</title>
            </path>
            <g v-for="box in layout.boxes" :key="box.id" :class="[`topo-box--${box.type}`, { 'topo-box--off': !box.enabled }]">
              <rect :x="box.x" :y="box.y" :width="box.w" :height="box.h" rx="6" class="topo-box" />
              <text :x="box.x + 10" :y="box.y + box.h / 2 + 4" class="topo-label">
                <title>{{ box.name }}</title>
                {{ box.name }}
              </text>
            </g>
          </svg>
        </div>
        <p v-if="info" class="muted stats" data-testid="connections-stats">
          {{ t('env.connections.statsDevices') }} {{ info.topology.stats.deviceCount }} ·
          {{ t('env.connections.statsNics') }} {{ info.topology.stats.nicCount }} ·
          {{ t('env.connections.statsPorts') }} {{ info.topology.stats.portCount }} ·
          {{ t('env.connections.statsLinks') }} {{ info.topology.stats.linkCount }}
        </p>
      </section>

      <section class="panel">
        <h2>{{ t('env.connections.connTitle') }}</h2>
        <div class="filters">
          <input
            v-model="keywordDevice"
            class="pick"
            data-testid="connections-filter-device"
            :placeholder="t('env.connections.filterDevice')"
            type="search"
          >
          <input
            v-model="keywordNic"
            class="pick"
            data-testid="connections-filter-nic"
            :placeholder="t('env.connections.filterNic')"
            type="search"
          >
          <input
            v-model="keywordPort"
            class="pick"
            data-testid="connections-filter-port"
            :placeholder="t('env.connections.filterPort')"
            type="search"
          >
          <NButton v-if="hasFilter" size="small" tertiary data-testid="connections-filter-clear" @click="clearFilters">
            {{ t('env.connections.filterClear') }}
          </NButton>
        </div>
        <DataTable
          :columns="columns"
          :data="filteredRows"
          :row-key="(row: ConnectionRow) => row.id"
          :empty-title="t('env.connections.emptyTitle')"
          :empty-description="hasFilter ? t('env.connections.emptyHint') : undefined"
          data-testid="connections-table"
        />
      </section>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.empty-panel { border-style: dashed; }
.banner { border-radius: 8px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.banner.error { border: 1px solid color-mix(in srgb, var(--fail) 50%, transparent); }
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; font-size: 12px; }
.meta-item { display: inline-flex; gap: 6px; align-items: baseline; }
.meta-label { color: color-mix(in srgb, currentColor 55%, transparent); }
.meta-value { font-variant-numeric: tabular-nums; }
.topo-scroll { overflow-x: auto; }
.topo-box { fill: color-mix(in srgb, currentColor 6%, transparent); stroke: color-mix(in srgb, currentColor 26%, transparent); }
.topo-box--device .topo-box { fill: color-mix(in srgb, var(--accent, currentColor) 12%, transparent); }
.topo-box--nic .topo-box { fill: color-mix(in srgb, currentColor 4%, transparent); }
.topo-box--off .topo-box { stroke-dasharray: 4 3; opacity: .6; }
.topo-label { font-size: 11px; fill: currentColor; pointer-events: none; }
.topo-edge { fill: none; stroke: color-mix(in srgb, var(--accent, currentColor) 55%, transparent); stroke-width: 1.4; }
.topo-edge--off { stroke-dasharray: 5 4; opacity: .55; }
.stats { margin: 10px 0 0; }
.filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.pick { font-size: 13px; padding: 6px 8px; width: 200px; border-radius: 6px;
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  background: color-mix(in srgb, currentColor 4%, transparent); color: inherit; }
.pick:focus-visible { outline: 2px solid color-mix(in srgb, currentColor 60%, transparent); }
.pick:hover { border-color: color-mix(in srgb, currentColor 50%, transparent); }
</style>
