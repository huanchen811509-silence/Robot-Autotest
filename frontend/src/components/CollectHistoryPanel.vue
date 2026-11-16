<script setup lang="ts">
/**
 * 采集历史 + 两次对比（v5.13.0 / W63-10）。
 * 只消费摘要字段，不渲染 specJson 原文；无历史时给自解释空态而不是空表格。
 *
 * W7 修正：数据网格统一走 DataTable 壳（UX-05 / check_tables.py），不再写原生 <table>。
 * 行级 data-testid 改由 rowProps 提供，空态与对比区保持原有可观测性。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import type { CollectHistoryRow } from '@/api/collectProfiles'
import DataTable from './DataTable.vue'
import { diffCollectSnapshots, latestPair, snapshotItemsOf } from '@/utils/collectDiff'

const props = defineProps<{
  rows: CollectHistoryRow[]
  boundCredentialName?: string | null
}>()

const { t } = useI18n()
const pair = computed(() => latestPair(props.rows))
const diff = computed(() => diffCollectSnapshots(
  snapshotItemsOf(pair.value.previous),
  snapshotItemsOf(pair.value.current),
))
const credentialText = computed(() => props.boundCredentialName
  ? t('w63.boundCredentialLabel', { name: props.boundCredentialName })
  : t('w63.unbound'))

const columns = computed<DataTableColumns<CollectHistoryRow>>(() => [
  { title: t('w63.historyTime'), key: 'collectedAt', render: (row) => row.collectedAt ?? '-' },
  {
    // v5.17.0 / W67-08：凭证列改为**按行**，取本次采集时刻之前最近一次验证的凭证（掩码名）。
    // 改之前这一列渲染的是设备当前绑定凭证，整列一个值——历史等于没有。
    title: t('w63.historyCredential'),
    key: 'credential',
    render: (row) => row.credentialMaskedName
      || t('w63.credentialNotRecorded'),
  },
  { title: t('w63.historyResult'), key: 'status', render: (row) => row.status ?? '-' },
  {
    title: t('w63.historyItems'),
    key: 'items',
    render: (row) => `${row.nicCount} / ${row.rocePorts}`,
  },
])
</script>

<template>
  <div class="collect-history" data-testid="collect-history">
    <h3>{{ t('w63.historyTitle') }}</h3>
    <!-- 当前绑定凭证：与逐行的「本次采集所用凭证」区分开，两者不是一回事 -->
    <p class="muted" data-testid="bound-credential">{{ credentialText }}</p>
    <div v-if="!rows.length" class="muted" data-testid="collect-history-empty">
      {{ t('w63.historyEmpty') }}
    </div>
    <template v-else>
      <DataTable
        :columns="columns"
        :data="rows"
        :row-key="(row: CollectHistoryRow) => row.id"
        :row-props="(row: CollectHistoryRow) => ({ 'data-testid': `collect-history-${row.id}` })"
        :max-height="260"
        data-testid="collect-history-table"
      />
      <div v-if="pair.previous" data-testid="collect-diff">
        <h3>{{ t('w63.diffTitle') }}</h3>
        <p class="muted">
          {{ t('w63.diffAdded', { count: diff.added.length }) }} ·
          {{ t('w63.diffChanged', { count: diff.changed.length }) }} ·
          {{ t('w63.diffRemoved', { count: diff.removed.length }) }}
        </p>
        <ul>
          <li v-for="item in diff.changed" :key="item.key" data-testid="collect-diff-changed">
            {{ t(item.label) }}: {{ t('w63.diffOldToNew', { old: item.oldValue, new: item.newValue }) }}
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>
