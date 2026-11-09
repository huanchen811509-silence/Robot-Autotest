<script setup lang="ts">
/**
 * FR-v3.11-02 · 采集记录分析（只读）。
 *
 * 只发一次 GET /asset-host-inventory，按「主机关键词 / 起止时间 / 状态」在**前端**
 * 过滤（数据本来就在手上，没必要为每次改筛选条件再打一次接口）；趋势是这些真实
 * 记录的计数与摘要列，不是拟合出来的曲线。没有任何真实记录时显示「0 条」。
 * 空态显示「0 条」：沿用 env.common.empty 文案键，不硬编码中文，也不补造示例行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import { hostInventoryApi, type HostInventoryRecord } from '@/api/environment'
import { formatDateTime } from '@/utils/format'

interface Row {
  id: number
  runId: string
  collectedAt: string
  itemCount: string
  verdict: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const records = ref<HostInventoryRecord[]>([])
const keyword = ref('')
const from = ref('')
const to = ref('')
const status = ref('')
const loading = ref(false)
const failed = ref(false)

const columns = computed<DataTableColumns<Row>>(() => [
  { title: t('env.analysis.colSource'), key: 'runId' },
  { title: t('env.analysis.colCollectedAt'), key: 'collectedAt' },
  { title: t('env.analysis.colItemCount'), key: 'itemCount' },
  { title: t('env.analysis.colVerdict'), key: 'verdict' },
])

const filtered = computed(() => records.value.filter((r) => {
  if (keyword.value && !r.host.toLowerCase().includes(keyword.value.trim().toLowerCase())) return false
  if (status.value && r.status !== status.value) return false
  const time = Date.parse(r.collectedAt)
  if (from.value && Number.isFinite(time) && time < Date.parse(from.value)) return false
  if (to.value && Number.isFinite(time) && time > Date.parse(`${to.value}T23:59:59`)) return false
  return true
}))

const rows = computed<Row[]>(() => filtered.value.map((r) => ({
  id: r.id,
  runId: `${r.host} · #${r.id}`,
  collectedAt: formatDateTime(r.collectedAt),
  itemCount: `${t('env.analysis.nic')} ${r.nicCount} / ${t('env.analysis.roce')} ${r.rocePorts}`,
  verdict: r.status,
})))

const trend = computed(() => {
  const total = filtered.value.length
  const ok = filtered.value.filter((r) => r.status === 'OK').length
  const partial = filtered.value.filter((r) => r.status === 'PARTIAL').length
  const failedCount = filtered.value.filter((r) => r.status === 'FAILED').length
  return `${t('env.analysis.trendTotal', { total })} · OK ${ok} · PARTIAL ${partial} · FAILED ${failedCount}`
})

async function load() {
  if (!projectId.value) { records.value = []; return }
  loading.value = true
  failed.value = false
  try {
    records.value = await hostInventoryApi.list(projectId.value, { limit: 100 })
  } catch {
    records.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(projectId, load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.analysis.title') }}</h1>
      <p class="muted">{{ t('env.common.subtitle') }}</p>
    </header>

    <div v-if="failed" class="banner error" data-testid="analysis-failed">
      {{ t('env.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="analysis-retry" @click="load">
        {{ t('env.common.retry') }}
      </NButton>
    </div>

    <section class="panel">
      <h2>{{ t('env.analysis.filterTitle') }}</h2>
      <div class="action">
        <NInput v-model:value="keyword" :placeholder="t('env.analysis.hostPlaceholder')"
          data-testid="analysis-keyword" style="max-width: 180px" />
        <label class="muted">{{ t('env.analysis.from') }}
          <input v-model="from" type="date" data-testid="analysis-from" class="pick" />
        </label>
        <label class="muted">{{ t('env.analysis.to') }}
          <input v-model="to" type="date" data-testid="analysis-to" class="pick" />
        </label>
        <select v-model="status" class="pick" data-testid="analysis-status">
          <option value="">{{ t('env.analysis.allStatus') }}</option>
          <option value="OK">OK</option>
          <option value="PARTIAL">PARTIAL</option>
          <option value="FAILED">FAILED</option>
        </select>
      </div>
      <p class="muted" data-testid="analysis-trend">{{ trend }}</p>
    </section>

    <section class="panel">
      <h2>{{ t('env.analysis.panel') }}</h2>
      <p v-if="loading" class="muted" data-testid="analysis-loading">{{ t('env.common.loading') }}</p>
      <DataTable v-else :columns="columns" :data="rows" :row-key="(row: Row) => row.id">
        <template #empty>
          <p class="empty" data-testid="analysis-empty">{{ t('env.common.empty') }}</p>
        </template>
      </DataTable>
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
.action label { display: flex; gap: 6px; align-items: center; }
.pick { font-size: 13px; padding: 6px 8px; max-width: 240px; border-radius: 6px;
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  background: color-mix(in srgb, currentColor 4%, transparent); color: inherit; }
.pick:focus-visible { outline: 2px solid color-mix(in srgb, currentColor 60%, transparent); }
.pick:hover { border-color: color-mix(in srgb, currentColor 50%, transparent); }
</style>
