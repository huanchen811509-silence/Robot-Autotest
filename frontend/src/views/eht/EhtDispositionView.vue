<script setup lang="ts">
/**
 * EHT-11 / L-54-01 · 处置闭环。
 *
 * 数据源：GET /api/projects/{id}/dispositions/ledger（真实端点，非 mock）。响应原样渲染：
 * 对象按「字段 / 值」列出，数组按行列出，页面自己不重算任何口径。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { ehtViewsApi } from '@/api/ehtViews'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)
const payload = ref<unknown>(null)
const loading = ref(false)
const failed = ref(false)

async function load(): Promise<void> {
  if (!projectId.value) {
    return
  }
  loading.value = true
  failed.value = false
  try {
    payload.value = await ehtViewsApi.dispositionLedger(projectId.value)
  } catch {
    failed.value = true
    payload.value = null
  } finally {
    loading.value = false
  }
}
onMounted(load)

interface KvRow {
  key: string
  value: string
}

const kvRows = computed<KvRow[]>(() => {
  const d = payload.value
  if (!d || typeof d !== 'object' || Array.isArray(d)) {
    return []
  }
  return Object.entries(d as Record<string, unknown>).map(([key, value]) => ({
    key,
    value: value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value ?? '—'),
  }))
})

const listRows = computed<KvRow[]>(() => {
  const d = payload.value
  if (!Array.isArray(d)) {
    return []
  }
  return d.map((row, index) => ({ key: String(index + 1), value: JSON.stringify(row) }))
})

const columns = computed<DataTableColumns<KvRow>>(() => [
  { title: t('eht.common.key'), key: 'key' },
  { title: t('eht.common.value'), key: 'value' },
])
</script>

<template>
  <section class="eht-page">
    <header class="eht-page__head">
      <h1>{{ t('eht.disposition.title') }}</h1>
      <button type="button" class="eht-page__refresh" :disabled="loading" @click="load">
        {{ t('eht.common.refresh') }}
      </button>
    </header>
    <p class="eht-page__note">{{ t('eht.common.source') }}：GET /api/projects/{id}/dispositions/ledger</p>
    <p v-if="failed" class="eht-page__error">{{ t('eht.common.loadFailed') }}</p>
    <DataTable
      v-else
      :columns="columns"
      :data="kvRows.length ? kvRows : listRows"
      :loading="loading"
      :error="failed"
    />
  </section>
</template>

<style scoped>
.eht-page { padding: 24px 28px; display: grid; gap: 16px; }
.eht-page__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.eht-page__head h1 { margin: 0; font-size: 22px; }
.eht-page__refresh { padding: 4px 12px; border: 1px solid var(--border); border-radius: 6px; background: transparent; cursor: pointer; }
.eht-page__refresh:disabled { opacity: 0.5; cursor: default; }
.eht-page__note { margin: 0; font-size: 13px; color: var(--text-muted); }
.eht-page__error { margin: 0; font-size: 13px; color: var(--fail); }
</style>
