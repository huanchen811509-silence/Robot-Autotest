<script setup lang="ts">
/**
 * MENU-02：拓扑与快照（只读视图）。
 *
 * 本页不发起任何写请求，只读数据统一走 GET；行集恒为空、显示「0 条」，
 * 不编造 / 硬编码业务数据。快照 SHA 仅在有真实数据时展示。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'

interface SnapshotRow {
  id: number
  name: string
  capturedAt: string
  sha: string
  status: string
}

const { t } = useI18n()

const rows = ref<SnapshotRow[]>([])

const columns = computed<DataTableColumns<SnapshotRow>>(() => [
  { title: t('env.topology.colName'), key: 'name' },
  { title: t('env.topology.colCollectedAt'), key: 'capturedAt' },
  { title: 'SHA', key: 'sha' },
  { title: t('env.topology.colStatus'), key: 'status' },
])
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.topology.title') }}</h1>
      <p class="muted">{{ t('env.common.subtitle') }}</p>
    </header>
    <section class="panel">
      <h2>{{ t('env.topology.panel') }}</h2>
      <DataTable :columns="columns" :data="rows" :row-key="(row: SnapshotRow) => row.id">
        <template #empty>
          <p class="empty" data-testid="empty">{{ t('env.common.empty') }}</p>
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
.grid { width: 100%; border-collapse: collapse; font-size: 13px; }
.grid th, .grid td { border: 1px solid color-mix(in srgb, currentColor 14%, transparent); padding: 6px 10px; text-align: left; }
</style>
