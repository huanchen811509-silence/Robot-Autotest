<script setup lang="ts">
/**
 * FR-v3.11-02 · 拓扑与快照（默认只读，建快照须显式确认）。
 *
 * 快照列表、环境清单、设备与链路都走真实 GET；选中两份快照后调 diff（纯计算，不写库）。
 * 「建快照」把当前真实资产（设备 + 链路）序列化成 snapshot_json 后提交，且要二次确认——
 * 不是给用户一个能随手造一份假拓扑的输入框。两份快照都没有时对比区显示空态，不编数据。
 * 空态显示「0 条」：沿用 env.common.empty 文案键，不硬编码中文，也不补造示例行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import { useAuthStore } from '@/stores/auth'
import { deviceApi, linkApi } from '@/api/asset'
import { envApi, type EnvProfileSummary, type EnvSnapshot, type SnapshotDiff } from '@/api/environment'
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

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const profiles = ref<EnvProfileSummary[]>([])
const envName = ref('')
const snapshots = ref<EnvSnapshot[]>([])
const beforeId = ref('')
const afterId = ref('')
const diffs = ref<SnapshotDiff[] | null>(null)
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

const rows = computed<Row[]>(() => snapshots.value.map((s) => ({
  id: s.id,
  name: `${s.name} · ${s.profileType || t('env.common.notCollected')}`,
  capturedAt: formatDateTime(s.snapshotAt),
  status: `${(s.snapshotJson || '').length} B`,
})))
const diffRows = computed<DiffRow[]>(() => (diffs.value ?? []).map((d, i) => ({ ...d, id: `${i}-${d.path}` })))
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

onMounted(async () => { await loadProfiles(); await loadSnapshots() })
watch(projectId, async () => { envName.value = ''; await loadProfiles(); await loadSnapshots() })
watch(envName, loadSnapshots)
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
</style>
