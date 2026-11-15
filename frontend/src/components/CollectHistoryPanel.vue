<script setup lang="ts">
/**
 * 采集历史 + 两次对比（v5.13.0 / W63-10）。
 * 只消费摘要字段，不渲染 specJson 原文；无历史时给自解释空态而不是空表格。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { CollectHistoryRow } from '@/api/collectProfiles'
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
</script>

<template>
  <div class="collect-history" data-testid="collect-history">
    <h3>{{ t('w63.historyTitle') }}</h3>
    <div v-if="!rows.length" class="muted" data-testid="collect-history-empty">
      {{ t('w63.historyEmpty') }}
    </div>
    <template v-else>
      <table class="data-table" data-testid="collect-history-table">
        <thead>
          <tr>
            <th>{{ t('w63.historyTime') }}</th>
            <th>{{ t('w63.historyCredential') }}</th>
            <th>{{ t('w63.historyResult') }}</th>
            <th>{{ t('w63.historyItems') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" :data-testid="`collect-history-${row.id}`">
            <td>{{ row.collectedAt ?? '-' }}</td>
            <td>{{ credentialText }}</td>
            <td>{{ row.status ?? '-' }}</td>
            <td>{{ row.nicCount }} / {{ row.rocePorts }}</td>
          </tr>
        </tbody>
      </table>
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
