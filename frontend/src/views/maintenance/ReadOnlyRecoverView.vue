<script setup lang="ts">
/**
 * FR-v3.11-03 · 只读找回（只发 GET、不产生新 run）。
 *
 * 检索走 GET /search?types=run（既有历史执行），命中项只是跳转到那条 run 的链接。
 * 页面里没有 create / quick-run / rerun / replay 等任何执行入口，也不写库；
 * 「找不到」显示「0 条」，不拿别的 run 凑数。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import { findHistoricalRuns } from '@/api/maintenance'
import type { SearchHit } from '@/api/search'
import { formatDateTime } from '@/utils/format'

interface RecoverRow {
  id: string
  runId: string
  source: string
  state: string
  sha: string
  path: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const keyword = ref('RUN-')
const rows = ref<RecoverRow[]>([])
const loading = ref(false)
const failed = ref(false)
const searched = ref(false)

const columns = computed<DataTableColumns<RecoverRow>>(() => [
  { title: t('maintenance.recover.colSource'), key: 'runId' },
  { title: t('maintenance.recover.panel'), key: 'source' },
  { title: t('maintenance.recover.colStatus'), key: 'state' },
  { title: t('maintenance.recover.colSha'), key: 'sha' },
])

async function search() {
  if (!projectId.value || !keyword.value.trim()) { rows.value = []; return }
  loading.value = true
  failed.value = false
  searched.value = true
  try {
    const hits: SearchHit[] = await findHistoricalRuns(projectId.value, keyword.value.trim())
    rows.value = hits.map((hit) => ({
      id: String(hit.id),
      runId: hit.title,
      source: hit.projectName ?? hit.subtitle ?? t('maintenance.common.notCollected'),
      state: hit.badge ?? t('maintenance.common.notCollected'),
      sha: hit.updatedAt ? formatDateTime(hit.updatedAt) : t('maintenance.common.notCollected'),
      path: hit.path,
    }))
  } catch {
    rows.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(search)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('maintenance.recover.title') }}</h1>
      <p class="muted">{{ t('maintenance.recover.subtitle') }}</p>
    </header>
    <p class="notice">{{ t('maintenance.recover.notice') }}</p>

    <section class="panel">
      <h2>{{ t('maintenance.recover.search') }}</h2>
      <div class="action">
        <NInput v-model:value="keyword" :placeholder="t('maintenance.recover.keywordPlaceholder')"
          data-testid="recover-keyword" style="max-width: 240px"
          @keyup.enter="search" />
        <NButton size="small" data-testid="recover-search" @click="search">
          {{ t('maintenance.recover.search') }}
        </NButton>
      </div>
      <p class="muted" data-testid="recover-hint">{{ t('maintenance.recover.hint') }}</p>
    </section>

    <div v-if="failed" class="banner error" data-testid="recover-failed">
      {{ t('maintenance.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="recover-retry" @click="search">
        {{ t('maintenance.common.retry') }}
      </NButton>
    </div>

    <section class="panel">
      <h2>{{ t('maintenance.recover.panel') }}</h2>
      <p v-if="loading" class="muted" data-testid="recover-loading">
        {{ t('maintenance.common.loading') }}
      </p>
      <DataTable v-else :columns="columns" :data="rows" :row-key="(row: RecoverRow) => row.id">
        <template #empty>
          <p class="empty" data-testid="recover-empty">
            {{ searched ? t('maintenance.recover.noResult') : t('maintenance.common.empty') }}
          </p>
        </template>
      </DataTable>
      <ul v-if="rows.length" class="links" data-testid="recover-links">
        <li v-for="row in rows" :key="row.id">
          <RouterLink :to="row.path" data-testid="recover-link">{{ row.runId }}</RouterLink>
        </li>
      </ul>
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
.action { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.links { margin: 10px 0 0; padding-left: 18px; font-size: 13px; }
</style>
