<script setup lang="ts">
/**
 * MENU-05：CCL 性能基线（只读视图）。
 *
 * 每条基线条目显示来源 run_id 与 SHA；无数据显式显示「0 条」，不显示历史占位数值；
 * 不发起任何写请求，只读数据统一走 GET，不编造 / 硬编码业务数据。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'

interface BaselineRow {
  id: number
  runId: string
  sha: string
  item: string
  collectedAt: string
}

const { t } = useI18n()

const rows = ref<BaselineRow[]>([])

const columns = computed<DataTableColumns<BaselineRow>>(() => [
  { title: `${t('baseline.common.colSource')} run_id`, key: 'runId' },
  { title: 'SHA', key: 'sha' },
  { title: t('baseline.common.colItem'), key: 'item' },
  { title: t('baseline.common.colCollectedAt'), key: 'collectedAt' },
])
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('baseline.ccl.title') }}</h1>
      <p class="muted">{{ t('baseline.common.subtitle') }}</p>
    </header>
    <section class="panel">
      <h2>{{ t('baseline.common.panel') }}</h2>
      <DataTable :columns="columns" :data="rows" :row-key="(row: BaselineRow) => row.id">
        <template #empty>
          <p class="empty" data-testid="empty">{{ t('baseline.common.empty') }}</p>
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
