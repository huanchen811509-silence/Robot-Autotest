<script setup lang="ts">
/**
 * EHT-10 / FR-v5.5-11：pytest 工程用例管理页。
 *
 * 分母、覆盖率、绑定都来自真实端点，页面不做本地口径计算；
 * 筛选条件直接作为查询参数发给 /cases，选中集合只用于批量选例展示。
 * 优先级下拉由 /cases 分页数据的 distinct priority 汇总，页面不写死任何枚举字面。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { RouterLink } from 'vue-router'
import { ehtViewsApi, type EhtRecord } from '@/api/ehtViews'
import { pytestProjectApi, type PytestHealth } from '@/api/pytestProject'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)
const cases = ref<EhtRecord[]>([])
const total = ref(0)
const health = ref<PytestHealth | null>(null)
const priority = ref('')
const priorities = ref<string[]>([])
const keyword = ref('')
const selected = ref<string[]>([])
const loading = ref(false)
const failed = ref(false)

async function load(): Promise<void> {
  if (!projectId.value) {
    return
  }
  loading.value = true
  failed.value = false
  try {
    const params: Record<string, string | number> = { page: 1, size: 50 }
    if (priority.value) {
      params.priority = priority.value
    }
    if (keyword.value) {
      params.keyword = keyword.value
    }
    const [page, h, priorityValues] = await Promise.all([
      ehtViewsApi.cases(projectId.value, params),
      pytestProjectApi.health(projectId.value),
      ehtViewsApi.casePriorities(projectId.value),
    ])
    cases.value = page.items ?? []
    total.value = page.total ?? 0
    health.value = h
    priorities.value = priorityValues
  } catch {
    failed.value = true
    cases.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

const columns = computed<DataTableColumns<EhtRecord>>(() => [
  { title: t('eht.caseLibrary.code'), key: 'caseCode' },
  { title: t('eht.caseLibrary.titleCol'), key: 'title' },
  { title: t('eht.caseLibrary.priority'), key: 'priority' },
  { title: t('eht.caseLibrary.review'), key: 'reviewStatus' },
])

function toggle(code: string): void {
  selected.value = selected.value.includes(code)
    ? selected.value.filter((x) => x !== code)
    : [...selected.value, code]
}
</script>

<template>
  <section class="eht-page">
    <header class="eht-page__head">
      <h1>{{ t('eht.caseLibrary.title') }}</h1>
      <button type="button" class="eht-page__refresh" :disabled="loading" @click="load">
        {{ t('eht.common.refresh') }}
      </button>
    </header>
    <p class="eht-page__note">
      {{ t('eht.common.source') }}：GET /api/projects/{id}/cases + GET /api/projects/{id}/pytest/health
    </p>
    <dl class="eht-facts">
      <div><dt>{{ t('eht.caseLibrary.total') }}</dt><dd>{{ total }}</dd></div>
      <div><dt>{{ t('eht.caseLibrary.coverageAll') }}</dt><dd>{{ health?.coveragePercent ?? '—' }}%</dd></div>
      <div><dt>{{ t('eht.caseLibrary.coverageExPending') }}</dt><dd>{{ health?.coverageExcludingPendingPercent ?? '—' }}%</dd></div>
      <div><dt>{{ t('eht.caseLibrary.bound') }}</dt><dd>{{ health?.bound ?? '—' }}</dd></div>
    </dl>
    <div class="eht-filters">
      <input v-model="keyword" type="search" :placeholder="t('eht.caseLibrary.keyword')" />
      <select v-model="priority">
        <option value="">{{ t('eht.caseLibrary.allPriorities') }}</option>
        <option v-for="item in priorities" :key="item" :value="item">{{ item }}</option>
      </select>
      <button type="button" @click="load">{{ t('eht.caseLibrary.filter') }}</button>
      <span class="eht-muted">{{ t('eht.caseLibrary.selected', { count: selected.length }) }}</span>
      <RouterLink to="/eht/manual-execution">{{ t('eht.caseLibrary.manualEntry') }}</RouterLink>
    </div>
    <p v-if="failed" class="eht-page__error">{{ t('eht.common.loadFailed') }}</p>
    <DataTable v-else :columns="columns" :data="cases" :loading="loading" :error="failed" />
    <div class="eht-select">
      <label v-for="row in cases" :key="String(row.caseCode)">
        <input type="checkbox" :checked="selected.includes(String(row.caseCode))"
               @change="toggle(String(row.caseCode))" />
        {{ String(row.caseCode) }}
      </label>
    </div>
  </section>
</template>

<style scoped>
.eht-page { padding: 24px 28px; display: grid; gap: 16px; }
.eht-page__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.eht-page__head h1 { margin: 0; font-size: 22px; }
.eht-page__refresh { padding: 4px 12px; border: 1px solid var(--border); border-radius: 6px; background: transparent; cursor: pointer; }
.eht-page__note { margin: 0; font-size: 13px; color: var(--text-muted); }
.eht-page__error { margin: 0; font-size: 13px; color: var(--fail); }
.eht-facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 0; }
.eht-facts div { border: 1px solid var(--border); border-radius: 8px; padding: 12px; }
.eht-facts dt { font-size: 12px; color: var(--text-muted); }
.eht-facts dd { margin: 4px 0 0; font-size: 18px; }
.eht-filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.eht-filters input, .eht-filters select, .eht-filters button { border: 1px solid var(--border); border-radius: 6px; padding: 4px 10px; background: transparent; }
.eht-muted { font-size: 13px; color: var(--text-muted); }
.eht-select { display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; color: var(--text-muted); }
</style>
