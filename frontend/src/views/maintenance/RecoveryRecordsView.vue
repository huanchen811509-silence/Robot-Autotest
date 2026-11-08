<script setup lang="ts">
/**
 * MENU-04：恢复记录（只读视图）。
 *
 * 本页只展示恢复记录的时间 / 对象 / 结果，不含恢复命令正文（命令原文一律不落前端、不展示）；
 * 不发起任何写请求，只读数据统一走 GET；行集恒为空、显示「0 条」，不编造业务数据。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'

interface RecoveryRow {
  id: number
  occurredAt: string
  target: string
  result: string
  remark: string
}

const { t } = useI18n()

const rows = ref<RecoveryRow[]>([])

const columns = computed<DataTableColumns<RecoveryRow>>(() => [
  { title: t('maintenance.records.colTime'), key: 'occurredAt' },
  { title: t('maintenance.records.colTarget'), key: 'target' },
  { title: t('maintenance.records.colResult'), key: 'result' },
  { title: t('maintenance.records.colRemark'), key: 'remark' },
])
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('maintenance.records.title') }}</h1>
      <p class="muted">{{ t('maintenance.records.subtitle') }}</p>
    </header>
    <p class="notice">{{ t('maintenance.records.notice') }}</p>
    <section class="panel">
      <h2>{{ t('maintenance.records.panel') }}</h2>
      <DataTable :columns="columns" :data="rows" :row-key="(row: RecoveryRow) => row.id">
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
