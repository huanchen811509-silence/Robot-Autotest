<script setup lang="ts">
/**
 * MENU-02：环境总览（只读视图）。
 *
 * 本页不发起任何写请求（无 POST / PUT / DELETE / PATCH），只读数据统一走 GET；
 * 后端只读接口尚未在本版接入，故行集恒为空、显示「0 条」，不编造 / 硬编码业务数据。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'

interface EnvironmentRow {
  id: number
  name: string
  status: string
  nodeCount: number
}

const { t } = useI18n()

const rows = ref<EnvironmentRow[]>([])

const columns = computed<DataTableColumns<EnvironmentRow>>(() => [
  { title: t('env.overview.colName'), key: 'name' },
  { title: t('env.overview.colStatus'), key: 'status' },
  { title: t('env.overview.colNodeCount'), key: 'nodeCount' },
])
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.overview.title') }}</h1>
      <p class="muted">{{ t('env.common.subtitle') }}</p>
    </header>
    <section class="panel">
      <h2>{{ t('env.overview.panel') }}</h2>
      <DataTable :columns="columns" :data="rows" :row-key="(row: EnvironmentRow) => row.id">
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
