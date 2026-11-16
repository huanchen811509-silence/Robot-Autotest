<script setup lang="ts">
/**
 * FR-v3.11-02 · 拓扑与快照（默认只读，建快照须显式确认）。
 *
 * 快照列表、环境清单、设备与链路都走真实 GET；选中两份快照后调 diff（纯计算，不写库）。
 * 「建快照」把当前真实资产（设备 + 链路）序列化成 snapshot_json 后提交，且要二次确认——
 * 不是给用户一个能随手造一份假拓扑的输入框。两份快照都没有时对比区显示空态，不编数据。
 * 空态显示「0 条」：沿用 env.common.empty 文案键，不硬编码中文，也不补造示例行。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import { useAuthStore } from '@/stores/auth'
import { deviceApi, linkApi } from '@/api/asset'
import {
  deviceSnapshotApi, envApi, topologyTrustApi,
  type PlannedNode, type TopologyEdge, type TopologyEdgeKind,
  type DeviceSnapshot, type DeviceSnapshotItem, type EnvProfileSummary,
  type EnvSnapshot, type ItemDiff, type SnapshotDiff,
} from '@/api/environment'
import { formatDateTime } from '@/utils/format'

interface Row {
  id: number
  name: string
  capturedAt: string
  status: string
}

interface DiffRow extends SnapshotDiff {
  id: string
}

interface ItemFactRow {
  id: string
  itemKey: string
  category: string
  display: string
}

interface ItemDiffRow extends ItemDiff {
  id: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const profiles = ref<EnvProfileSummary[]>([])
const envName = ref('')
const snapshots = ref<EnvSnapshot[]>([])
const beforeId = ref('')
const afterId = ref('')
const diffs = ref<SnapshotDiff[] | null>(null)
const deviceSnapshots = ref<DeviceSnapshot[]>([])
const devBeforeId = ref('')
const devAfterId = ref('')
const snapshotItems = ref<DeviceSnapshotItem[]>([])
const itemDiffs = ref<ItemDiff[] | null>(null)
const confirming = ref(false)
const busy = ref(false)
const loading = ref(false)
const failed = ref(false)
const notice = ref('')

const columns = computed<DataTableColumns<Row>>(() => [
  { title: t('env.topology.colName'), key: 'name' },
  { title: t('env.topology.colCollectedAt'), key: 'capturedAt' },
  { title: t('env.topology.colStatus'), key: 'status' },
])
const diffColumns = computed<DataTableColumns<DiffRow>>(() => [
  { title: t('env.topology.diffPath'), key: 'path' },
  { title: t('env.topology.diffChange'), key: 'change' },
  { title: t('env.topology.diffBefore'), key: 'before' },
  { title: t('env.topology.diffAfter'), key: 'after' },
])
const itemColumns = computed<DataTableColumns<ItemFactRow>>(() => [
  { title: t('env.topology.itemColKey'), key: 'itemKey' },
  { title: t('env.topology.itemColCategory'), key: 'category' },
  { title: t('env.topology.itemColValue'), key: 'display' },
])
const itemDiffColumns = computed<DataTableColumns<ItemDiffRow>>(() => [
  { title: t('env.topology.diffPath'), key: 'key' },
  { title: t('env.topology.diffChange'), key: 'change' },
  { title: t('env.topology.diffBefore'), key: 'before' },
  { title: t('env.topology.diffAfter'), key: 'after' },
])

const rows = computed<Row[]>(() => snapshots.value.map((s) => ({
  id: s.id,
  name: `${s.name} · ${s.profileType || t('env.common.notCollected')}`,
  capturedAt: formatDateTime(s.snapshotAt),
  status: `${(s.snapshotJson || '').length} B`,
})))
const diffRows = computed<DiffRow[]>(() => (diffs.value ?? []).map((d, i) => ({ ...d, id: `${i}-${d.path}` })))
const itemRows = computed<ItemFactRow[]>(() => snapshotItems.value.map((item) => ({
  id: String(item.id),
  itemKey: item.itemKey,
  category: item.category,
  display: item.collected
    ? (item.valueJson ?? '')
    : `${t('env.common.notCollected')} · ${item.reason ?? ''}`,
})))
const itemDiffRows = computed<ItemDiffRow[]>(() => (itemDiffs.value ?? [])
  .map((d, i) => ({ ...d, id: `${i}-${d.key}` })))
const selectedBefore = computed(() => snapshots.value.find((s) => String(s.id) === beforeId.value) ?? null)
const selectedAfter = computed(() => snapshots.value.find((s) => String(s.id) === afterId.value) ?? null)

async function loadProfiles() {
  if (!projectId.value) { profiles.value = []; return }
  profiles.value = await envApi.profiles(projectId.value)
  if (profiles.value.length > 0 && !envName.value) envName.value = profiles.value[0].name
}

async function loadSnapshots() {
  if (!projectId.value || !envName.value) { snapshots.value = []; return }
  loading.value = true
  failed.value = false
  try {
    snapshots.value = await envApi.snapshots(projectId.value, envName.value)
    diffs.value = null
    if (snapshots.value.length >= 2) {
      afterId.value = String(snapshots.value[0].id)
      beforeId.value = String(snapshots.value[1].id)
    } else {
      afterId.value = snapshots.value.length ? String(snapshots.value[0].id) : ''
      beforeId.value = ''
    }
  } catch {
    snapshots.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function compare() {
  if (!selectedBefore.value || !selectedAfter.value) return
  busy.value = true
  try {
    diffs.value = await envApi.diff(projectId.value, selectedBefore.value, selectedAfter.value)
  } catch {
    failed.value = true
  } finally {
    busy.value = false
  }
}

/** v5.15.0 / FR-v5.15-01~03: item-based snapshot facts, not a second invented data set. */
async function loadDeviceSnapshots() {
  if (!projectId.value) {
    deviceSnapshots.value = []
    snapshotItems.value = []
    itemDiffs.value = null
    return
  }
  try {
    deviceSnapshots.value = await deviceSnapshotApi.list(projectId.value, 20)
    if (deviceSnapshots.value.length >= 2) {
      devAfterId.value = String(deviceSnapshots.value[0].id)
      devBeforeId.value = String(deviceSnapshots.value[1].id)
    } else if (deviceSnapshots.value.length === 1) {
      devAfterId.value = String(deviceSnapshots.value[0].id)
      devBeforeId.value = ''
    }
    await selectDeviceSnapshot()
  } catch {
    deviceSnapshots.value = []
    snapshotItems.value = []
  }
}

async function selectDeviceSnapshot() {
  if (!devAfterId.value) { snapshotItems.value = []; return }
  try {
    snapshotItems.value = await deviceSnapshotApi.items(projectId.value, Number(devAfterId.value))
  } catch {
    snapshotItems.value = []
  }
}

async function compareByItem() {
  if (!devBeforeId.value || !devAfterId.value) return
  busy.value = true
  try {
    itemDiffs.value = await deviceSnapshotApi.diffByItem(
      projectId.value, Number(devBeforeId.value), Number(devAfterId.value))
  } catch {
    itemDiffs.value = []
  } finally {
    busy.value = false
  }
}

/** 快照内容 = 当前项目的真实设备与链路，不是手编的拓扑样例。 */
async function createSnapshot() {
  if (!confirming.value) { confirming.value = true; return }
  confirming.value = false
  busy.value = true
  notice.value = ''
  try {
    const [devices, links] = await Promise.all([
      deviceApi.page(projectId.value, { page: 1, size: 100 }),
      linkApi.page(projectId.value, { page: 1, size: 100 }),
    ])
    const payload = {
      name: envName.value || t('env.topology.defaultName'),
      profileType: 'topology',
      description: t('env.topology.createDescription'),
      snapshotJson: JSON.stringify({
        capturedAt: new Date().toISOString(),
        devices: devices.items.map((d) => ({ id: d.id, name: d.name, enabled: d.enabled })),
        links: links.items.map((l) => ({ id: l.id, name: l.name, enabled: l.enabled })),
      }),
    }
    const created = await envApi.create(projectId.value, payload)
    notice.value = t('env.topology.created', { id: created.id })
    await loadProfiles()
    await loadSnapshots()
  } catch {
    notice.value = t('env.common.loadFailed')
  } finally {
    busy.value = false
  }
}

/**
 * 拓扑边与图例（v5.18.0 / FR-v5.18-03 / W68-07）。
 *
 * <p>「仅实测」的过滤态写进 URL（`?topo=measured`）：它是**观察口径**，不是临时开关。
 * 排障时把链接发给别人，对方看到必须是同一张图；只在内存里存一个 ref 就做不到。
 */
const route = useRoute()
const router = useRouter()
const edges = ref<TopologyEdge[]>([])
const counts = ref<Record<string, number>>({})
const overridden = ref(0)
const plannedNodes = ref<PlannedNode[]>([])
const edgesFailed = ref(false)
const edgesLoading = ref(false)

const measuredOnly = computed(() => route.query.topo === 'measured')
/** 仅实测 = 只留 MEASURED：条数必须与接口的 MEASURED 计数逐数相等（判据 2）。 */
const visibleEdges = computed(() =>
  measuredOnly.value ? edges.value.filter((edge) => edge.kind === 'MEASURED') : edges.value)

async function loadEdges(): Promise<void> {
  const projectId = auth.projectId
  if (!projectId) {
    edges.value = []
    plannedNodes.value = []
    return
  }
  edgesLoading.value = true
  edgesFailed.value = false
  try {
    const view = await topologyTrustApi.edges(projectId)
    edges.value = view.edges ?? []
    counts.value = view.counts ?? {}
    overridden.value = view.overridden ?? 0
    plannedNodes.value = view.plannedNodes ?? []
  } catch {
    edgesFailed.value = true
    edges.value = []
    plannedNodes.value = []
  } finally {
    edgesLoading.value = false
  }
}

function toggleMeasuredOnly(next: boolean): void {
  void router.replace({ query: { ...route.query, topo: next ? 'measured' : undefined } })
}

function kindLabel(kind: TopologyEdgeKind | string): string {
  if (kind === 'MEASURED') return t('env.topology.edgeKindMeasured')
  if (kind === 'PLANNED') return t('env.topology.edgeKindPlanned')
  return t('env.topology.edgeKindManual')
}

const edgeColumns = computed<DataTableColumns<TopologyEdge>>(() => [
  { title: t('env.topology.colEdgeName'), key: 'name', minWidth: 220 },
  {
    title: t('env.topology.colEdgeKind'),
    key: 'kind',
    width: 120,
    render: (row) => h('span', { class: ['edge-kind', `edge-${row.kind.toLowerCase()}`] },
      kindLabel(row.kind)),
  },
  { title: t('env.topology.colEdgeSource'), key: 'source', width: 140 },
])

onMounted(async () => {
  await loadProfiles()
  await loadSnapshots()
  await loadDeviceSnapshots()
  await loadEdges()
})
watch(projectId, async () => {
  envName.value = ''
  await loadProfiles()
  await loadSnapshots()
  await loadDeviceSnapshots()
  await loadEdges()
})
watch(envName, loadSnapshots)
watch(devAfterId, selectDeviceSnapshot)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.topology.title') }}</h1>
      <p class="muted">{{ t('env.topology.subtitle') }}</p>
    </header>

    <div v-if="failed" class="banner error" data-testid="topology-failed">
      {{ t('env.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="topology-retry" @click="loadSnapshots">
        {{ t('env.common.retry') }}
      </NButton>
    </div>

    <!-- 拓扑图例与边（v5.18.0 / FR-v5.18-03~04）：四类固定颜色 + 仅实测过滤（落 URL） -->
    <section class="panel" data-testid="topology-legend-panel">
      <h2>{{ t('env.topology.legendTitle') }}</h2>
      <p class="muted">{{ t('env.topology.legendHint') }}</p>
      <ul class="legend" data-testid="topology-legend">
        <li class="legend-item" data-kind="MEASURED" data-testid="legend-measured">
          <span class="legend-dot dot-measured" />
          {{ t('env.topology.legendMeasured') }}
          <b data-testid="legend-measured-count">{{ counts.MEASURED ?? 0 }}</b>
        </li>
        <li class="legend-item" data-kind="MANUAL" data-testid="legend-manual">
          <span class="legend-dot dot-manual" />
          {{ t('env.topology.legendManual') }}
          <b data-testid="legend-manual-count">{{ counts.MANUAL ?? 0 }}</b>
        </li>
        <li class="legend-item" data-kind="PLANNED" data-testid="legend-planned">
          <span class="legend-dot dot-planned" />
          {{ t('env.topology.legendPlanned') }}
          <b data-testid="legend-planned-count">{{ counts.PLANNED ?? 0 }}</b>
        </li>
        <li class="legend-item" data-kind="CONFLICT" data-testid="legend-conflict">
          <span class="legend-dot dot-conflict" />
          {{ t('env.topology.legendConflict') }}
          <b data-testid="legend-conflict-count">{{ counts.CONFLICT ?? 0 }}</b>
        </li>
      </ul>
      <label class="action">
        <input
          type="checkbox"
          data-testid="topology-only-measured"
          :checked="measuredOnly"
          @change="toggleMeasuredOnly(($event.target as HTMLInputElement).checked)"
        />
        <span>{{ t('env.topology.onlyMeasured') }}</span>
      </label>
      <p class="muted" data-testid="topology-edges-count">
        {{ t('env.topology.edgesCount', {
          total: visibleEdges.length,
          legend: (counts.MEASURED ?? 0) + (counts.MANUAL ?? 0) + (counts.PLANNED ?? 0) + (counts.CONFLICT ?? 0),
          conflict: overridden,
        }) }}
      </p>
      <p v-if="overridden > 0" class="muted" data-testid="topology-conflict-hint">
        {{ t('env.topology.conflictHint', { count: overridden }) }}
      </p>
      <p v-if="edgesLoading" class="muted">{{ t('env.common.loading') }}</p>
      <p v-else-if="edgesFailed" class="banner error" data-testid="topology-edges-failed">
        {{ t('env.common.loadFailed') }}
      </p>
      <DataTable
        v-else
        :columns="edgeColumns"
        :data="visibleEdges"
        :row-key="(row: TopologyEdge) => `${row.id}-${row.kind}`"
        data-testid="topology-edges"
      >
        <template #empty>
          <p class="muted" data-testid="topology-edges-empty">{{ t('env.topology.edgesEmpty') }}</p>
        </template>
      </DataTable>
      <template v-if="plannedNodes.length > 0">
        <h3>{{ t('env.topology.plannedNodesTitle') }}</h3>
        <ul class="planned-nodes" data-testid="topology-planned-nodes">
          <li v-for="node in plannedNodes" :key="node.id">
            {{ node.name }} · {{ node.nodeType }}
          </li>
        </ul>
      </template>
    </section>

    <section class="panel">
      <h2>{{ t('env.topology.envTitle') }}</h2>
      <div class="action">
        <select v-model="envName" data-testid="topology-env" class="pick">
          <option v-for="p in profiles" :key="p.name" :value="p.name">{{ p.name }}</option>
        </select>
        <span class="muted" data-testid="topology-env-count">{{ profiles.length }} · {{ t('env.common.empty') }}</span>
      </div>
      <EmptyState
        v-if="profiles.length === 0"
        data-testid="topology-env-empty"
        :title="t('emptyGuide.topology.title')"
        :description="t('emptyGuide.topology.reason')"
        icon="device"
        :illustration-width="64"
      >
        <template #action>
          <EmptyGuide to="/environment/collect" :label="t('emptyGuide.action.collect')" />
        </template>
      </EmptyState>
    </section>

    <section class="panel">
      <h2>{{ t('env.topology.panel') }}</h2>
      <p v-if="loading" class="muted" data-testid="topology-loading">{{ t('env.common.loading') }}</p>
      <DataTable v-else :columns="columns" :data="rows" :row-key="(row: Row) => row.id">
        <template #empty>
          <EmptyState
            data-testid="topology-empty"
            :title="t('emptyGuide.topology.title')"
            :description="t('emptyGuide.topology.reason')"
            icon="device"
            :illustration-width="64"
          >
            <template #action>
              <EmptyGuide to="/environment/collect" :label="t('emptyGuide.action.collect')" />
            </template>
          </EmptyState>
        </template>
      </DataTable>
    </section>

    <section class="panel">
      <h2>{{ t('env.topology.diffTitle') }}</h2>
      <div class="action">
        <select v-model="beforeId" data-testid="topology-before" class="pick">
          <option value="">{{ t('env.topology.pickBefore') }}</option>
          <option v-for="s in snapshots" :key="`b-${s.id}`" :value="String(s.id)">
            #{{ s.id }} · {{ formatDateTime(s.snapshotAt) }}
          </option>
        </select>
        <select v-model="afterId" data-testid="topology-after" class="pick">
          <option value="">{{ t('env.topology.pickAfter') }}</option>
          <option v-for="s in snapshots" :key="`a-${s.id}`" :value="String(s.id)">
            #{{ s.id }} · {{ formatDateTime(s.snapshotAt) }}
          </option>
        </select>
        <NButton size="small" :disabled="!beforeId || !afterId || busy"
          data-testid="topology-diff" @click="compare">
          {{ t('env.topology.compare') }}
        </NButton>
      </div>
      <DataTable v-if="diffs !== null" :columns="diffColumns" :data="diffRows"
        :row-key="(row: DiffRow) => row.id" data-testid="topology-diff-table">
        <template #empty>
          <p class="empty" data-testid="topology-diff-empty">{{ t('env.topology.noDiff') }}</p>
        </template>
      </DataTable>
      <p v-else class="empty" data-testid="topology-diff-idle">{{ t('env.topology.diffIdle') }}</p>
    </section>

    <section class="panel">
      <h2>{{ t('env.topology.deviceTitle') }}</h2>
      <p class="muted">{{ t('env.topology.deviceHint') }}</p>
      <div class="action" v-if="deviceSnapshots.length">
        <select v-model="devBeforeId" data-testid="device-before" class="pick">
          <option v-for="s in deviceSnapshots" :key="`db-${s.id}`" :value="String(s.id)">
            #{{ s.id }} · {{ formatDateTime(s.collectedAt) }}
          </option>
        </select>
        <select v-model="devAfterId" data-testid="device-after" class="pick">
          <option v-for="s in deviceSnapshots" :key="`da-${s.id}`" :value="String(s.id)">
            #{{ s.id }} · {{ formatDateTime(s.collectedAt) }}
          </option>
        </select>
        <NButton size="small" :disabled="!devBeforeId || !devAfterId || busy"
          data-testid="device-diff" @click="compareByItem">
          {{ t('env.topology.itemCompare') }}
        </NButton>
      </div>
      <DataTable v-if="deviceSnapshots.length" :columns="itemColumns" :data="itemRows"
        :row-key="(row: ItemFactRow) => row.id" data-testid="device-items">
        <template #empty>
          <p class="empty" data-testid="device-items-empty">{{ t('env.common.empty') }}</p>
        </template>
      </DataTable>
      <p v-else class="empty" data-testid="device-idle">{{ t('env.topology.deviceIdle') }}</p>
      <DataTable v-if="itemDiffs !== null" :columns="itemDiffColumns" :data="itemDiffRows"
        :row-key="(row: ItemDiffRow) => row.id" data-testid="device-diff-table">
        <template #empty>
          <p class="empty" data-testid="device-diff-empty">{{ t('env.topology.itemNoDiff') }}</p>
        </template>
      </DataTable>
    </section>

    <section class="panel">
      <h2>{{ t('env.topology.createTitle') }}</h2>
      <p class="muted">{{ t('env.topology.createHint') }}</p>
      <NButton size="small" type="primary" :disabled="busy || !envName"
        data-testid="topology-confirm" @click="createSnapshot">
        {{ confirming ? t('env.topology.confirmAgain') : t('env.topology.create') }}
      </NButton>
      <p v-if="notice" class="muted" data-testid="topology-notice">{{ notice }}</p>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.banner { border-radius: 8px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.banner.error { border: 1px solid color-mix(in srgb, var(--fail) 50%, transparent); }
.action { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.pick { font-size: 13px; padding: 6px 8px; max-width: 240px; border-radius: 6px;
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  background: color-mix(in srgb, currentColor 4%, transparent); color: inherit; }
.pick:focus-visible { outline: 2px solid color-mix(in srgb, currentColor 60%, transparent); }
.pick:hover { border-color: color-mix(in srgb, currentColor 50%, transparent); }
.legend {
  display: flex;
  gap: 16px;
  list-style: none;
  padding: 0;
  margin: 8px 0;
  flex-wrap: wrap;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-sm);
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
/* 四类颜色线型固定：实测实心绿、人工实心蓝、规划空心橙、冲突红（不随主题变） */
.dot-measured {
  background: var(--ok);
}
.dot-manual {
  background: var(--accent);
}
.dot-planned {
  background: transparent;
  border: 2px solid var(--warn);
}
.dot-conflict {
  background: var(--fail);
}
.planned-nodes {
  list-style: disc;
  margin: 6px 0 0 18px;
  font-size: var(--font-size-sm);
}
</style>
