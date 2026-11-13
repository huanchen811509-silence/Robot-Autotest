<script setup lang="ts">
/**
 * FR-v3.11-03 · 恢复记录（只读，不含恢复命令正文）。
 *
 * 数据源是**真实审计日志**：GET /audit 里 action 以 case.batch.undo. 开头的撤销/恢复动作。
 * 只映射时间 / 对象 / 动作名 / 操作人四个字段；审计条目的 detail（可能含令牌与命令痕迹）
 * 一律不取、不渲染——沿用 v2.2 的安全口径。0 条就是 0 条，不补造记录。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import { useAuthStore } from '@/stores/auth'
import { fetchRecoveryRecords } from '@/api/maintenance'
import { formatDateTime } from '@/utils/format'

interface RecoveryRow {
  id: number
  occurredAt: string
  target: string
  result: string
  remark: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const rows = ref<RecoveryRow[]>([])
const scanned = ref(0)
const loading = ref(false)
const failed = ref(false)

const columns = computed<DataTableColumns<RecoveryRow>>(() => [
  { title: t('maintenance.records.colTime'), key: 'occurredAt' },
  { title: t('maintenance.records.colTarget'), key: 'target' },
  { title: t('maintenance.records.colResult'), key: 'result' },
  { title: t('maintenance.records.colRemark'), key: 'remark' },
])

async function load() {
  if (!projectId.value) { rows.value = []; scanned.value = 0; return }
  loading.value = true
  failed.value = false
  try {
    const data = await fetchRecoveryRecords(projectId.value)
    scanned.value = data.total
    // 只搬这四个字段；detail / ip / summary 里可能有命令正文或凭据，不进视图模型。
    rows.value = data.rows.map((r) => ({
      id: r.id,
      occurredAt: formatDateTime(r.occurredAt),
      target: r.target,
      result: r.action,
      remark: r.operator ?? t('maintenance.common.notCollected'),
    }))
  } catch {
    rows.value = []
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
      <h1>{{ t('maintenance.records.title') }}</h1>
      <p class="muted">{{ t('maintenance.records.subtitle') }}</p>
    </header>
    <p class="notice">{{ t('maintenance.records.notice') }}</p>
    <p class="muted" data-testid="records-scanned">{{ t('maintenance.records.noCommand') }}</p>

    <div v-if="failed" class="banner error" data-testid="records-failed">
      {{ t('maintenance.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="records-retry" @click="load">
        {{ t('maintenance.common.retry') }}
      </NButton>
    </div>

    <section class="panel">
      <h2>{{ t('maintenance.records.panel') }}</h2>
      <p v-if="loading" class="muted" data-testid="records-loading">
        {{ t('maintenance.common.loading') }}
      </p>
      <DataTable v-else :columns="columns" :data="rows" :row-key="(row: RecoveryRow) => row.id">
        <template #empty>
          <EmptyState
            data-testid="records-empty"
            :title="t('emptyGuide.records.title')"
            :description="t('emptyGuide.records.reason')"
            icon="refresh"
            :illustration-width="64"
          >
            <template #action>
              <EmptyGuide to="/maintenance/principles" :label="t('emptyGuide.action.principles')" />
            </template>
          </EmptyState>
        </template>
      </DataTable>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.notice { font-size: 13px; padding: 8px 12px; border-radius: 6px; border: 1px solid color-mix(in srgb, currentColor 18%, transparent); }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.banner { border-radius: 8px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.banner.error { border: 1px solid color-mix(in srgb, var(--fail) 50%, transparent); }
</style>
