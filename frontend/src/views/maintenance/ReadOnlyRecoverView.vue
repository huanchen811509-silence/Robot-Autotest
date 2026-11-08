<script setup lang="ts">
/**
 * MENU-04：只读找回（只读视图）。
 *
 * 仅使用 GET 查询既有 run / 证据，不调用任何执行、触发、重跑接口，不产生新 run；
 * 不发起任何写请求；行集恒为空、显示「0 条」，不编造 / 硬编码业务数据。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'

interface RecoverRow {
  id: number
  runId: string
  source: string
  state: string
  sha: string
}

const { t } = useI18n()

const rows = ref<RecoverRow[]>([])

const columns = computed<DataTableColumns<RecoverRow>>(() => [
  { title: `${t('maintenance.recover.colSource')} run_id`, key: 'runId' },
  { title: t('maintenance.recover.colSource'), key: 'source' },
  { title: t('maintenance.recover.colStatus'), key: 'state' },
  { title: `${t('maintenance.recover.colSha')} SHA`, key: 'sha' },
])
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('maintenance.recover.title') }}</h1>
      <p class="muted">{{ t('maintenance.recover.subtitle') }}</p>
    </header>
    <p class="notice">{{ t('maintenance.recover.notice') }}</p>
    <section class="panel">
      <h2>{{ t('maintenance.recover.panel') }}</h2>
      <DataTable :columns="columns" :data="rows" :row-key="(row: RecoverRow) => row.id">
        <template #empty>
          <p class="empty" data-testid="empty">{{ t('maintenance.common.empty') }}</p>
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
.grid { width: 100%; border-collapse: collapse; font-size: 13px; }
.grid th, .grid td { border: 1px solid color-mix(in srgb, currentColor 14%, transparent); padding: 6px 10px; text-align: left; }
</style>
