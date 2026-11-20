<script setup lang="ts">
/**
 * EHT-10 / FR-v5.5-11 ｜ v5.32.0 / FR-v5.32-01 改造：pytest 工程用例管理页。
 *
 * 分母、覆盖率、绑定都来自真实端点，页面不做本地口径计算；
 * 筛选条件直接作为查询参数发给 /cases，选中集合只用于批量选例展示。
 * 优先级下拉由 /cases 分页数据的 distinct priority 汇总，页面不写死任何枚举字面。
 *
 * v5.32.0 补三件原本缺的能力（R5「同一类对象两套体验」）：
 * 1. **服务端分页**：原先写死 `page=1&size=50`，469 条用例只看得到前 50 条且无法翻页；
 * 2. **行详情**：原先没有详情入口，复用项目库同一个 `CaseDetailPanel`（单一实现）；
 * 3. **列集对齐**：补分类/安全级/评审态，与项目用例库同口径（未定级仍降噪显示 `—`）。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import DetailDock from '@/components/DetailDock.vue'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { RouterLink } from 'vue-router'
import { ehtViewsApi, type EhtRecord } from '@/api/ehtViews'
import { pytestProjectApi, type PytestHealth } from '@/api/pytestProject'
import { useAuthStore } from '@/stores/auth'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import { reviewStatusLabel, reviewStatusTone, safetyLevelLabel, safetyLevelTone } from '@/utils/statusLabels'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)
const cases = ref<EhtRecord[]>([])
const total = ref(0)
const health = ref<PytestHealth | null>(null)
const priority = ref('')
const priorities = ref<string[]>([])
const keyword = ref('')
const selected = ref<number[]>([])
const loading = ref(false)
const failed = ref(false)

/** 服务端分页：页码与页大小都要发到后端，本地不切片。 */
const page = ref(1)
const { size, pageSizes } = useListPageSize()
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

/** 详情抽屉：行点击打开，复用项目库同一个面板。 */
const detailId = ref<number | null>(null)

const EMPTY = '—'

async function load(): Promise<void> {
  if (!projectId.value) {
    return
  }
  loading.value = true
  failed.value = false
  try {
    const params: Record<string, string | number> = { page: page.value, size: size.value }
    if (priority.value) {
      params.priority = priority.value
    }
    if (keyword.value) {
      params.keyword = keyword.value
    }
    const [result, h, priorityValues] = await Promise.all([
      ehtViewsApi.cases(projectId.value, params),
      pytestProjectApi.health(projectId.value),
      ehtViewsApi.casePriorities(projectId.value),
    ])
    cases.value = result.items ?? []
    total.value = result.total ?? 0
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

/** 改筛选条件必须把页码归 1，否则会停在越界页看到空表。 */
function applyFilter(): void {
  page.value = 1
  void load()
}

function turnPage(next: number): void {
  if (next < 1 || next > pageCount.value) return
  page.value = next
  void load()
}

function changePageSize(next: number): void {
  size.value = next as PageSize
  page.value = 1
  void load()
}

function rowNumber(row: EhtRecord): number {
  const raw = row.id
  return typeof raw === 'number' ? raw : Number(raw)
}

const columns = computed<DataTableColumns<EhtRecord>>(() => [
  { title: t('eht.caseLibrary.code'), key: 'caseCode', width: 150 },
  { title: t('eht.caseLibrary.titleCol'), key: 'title', minWidth: 260, ellipsis: { tooltip: true } },
  {
    title: t('eht.caseLibrary.category'),
    key: 'category',
    width: 130,
    render: (row) => (row.category ? String(row.category) : EMPTY),
  },
  {
    title: t('eht.caseLibrary.priority'),
    key: 'priority',
    width: 100,
    render: (row) => (row.priority ? String(row.priority) : EMPTY),
  },
  {
    // 与项目库同口径：未定级不刷满屏红标，只在详情与筛选里出现（FR-v5.31-05）
    title: t('eht.caseLibrary.safety'),
    key: 'safetyLevel',
    width: 110,
    render: (row) => {
      const level = String(row.safetyLevel ?? '')
      if (!level || level === 'LX') return EMPTY
      return h(StatusTag, { label: safetyLevelLabel(level), tone: safetyLevelTone(level) })
    },
  },
  {
    title: t('eht.caseLibrary.review'),
    key: 'reviewStatus',
    width: 110,
    render: (row) => {
      const state = String(row.reviewStatus ?? '')
      if (!state) return EMPTY
      return h(StatusTag, { label: reviewStatusLabel(state), tone: reviewStatusTone(state) })
    },
  },
])

function toggle(id: number): void {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((x) => x !== id)
    : [...selected.value, id]
}

const pageAllSelected = computed(
  () => cases.value.length > 0 && cases.value.every((row) => selected.value.includes(rowNumber(row))),
)

function togglePage(): void {
  const ids = cases.value.map(rowNumber)
  selected.value = pageAllSelected.value
    ? selected.value.filter((id) => !ids.includes(id))
    : [...new Set([...selected.value, ...ids])]
}

function openDetail(row: EhtRecord): void {
  const id = rowNumber(row)
  if (Number.isFinite(id) && id > 0) detailId.value = id
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
      <input v-model="keyword" type="search" :placeholder="t('eht.caseLibrary.keyword')" @keyup.enter="applyFilter" />
      <select v-model="priority" @change="applyFilter">
        <option value="">{{ t('eht.caseLibrary.allPriorities') }}</option>
        <option v-for="item in priorities" :key="item" :value="item">{{ item }}</option>
      </select>
      <button type="button" @click="applyFilter">{{ t('eht.caseLibrary.filter') }}</button>
      <span class="eht-muted">{{ t('eht.caseLibrary.selected', { count: selected.length }) }}</span>
      <RouterLink to="/eht/manual-execution">{{ t('eht.caseLibrary.manualEntry') }}</RouterLink>
    </div>
    <p v-if="failed" class="eht-page__error">{{ t('eht.common.loadFailed') }}</p>
    <template v-else>
      <DataTable
        :columns="columns"
        :data="cases"
        :loading="loading"
        :error="failed"
        :row-props="(row) => ({ class: 'eht-row', onClick: () => openDetail(row) })"
      />
      <div class="eht-pager">
        <label class="eht-select-all">
          <input type="checkbox" :checked="pageAllSelected" @change="togglePage" />
          {{ t('eht.caseLibrary.selectPage') }}
        </label>
        <span class="eht-muted">{{ t('eht.caseLibrary.pageInfo', { page, pages: pageCount, total }) }}</span>
        <div class="eht-pager__buttons">
          <button type="button" :disabled="page <= 1" @click="turnPage(page - 1)">
            {{ t('eht.caseLibrary.prev') }}
          </button>
          <select :value="size" @change="changePageSize(Number(($event.target as HTMLSelectElement).value))">
            <option v-for="opt in pageSizes" :key="opt" :value="opt">{{ opt }}</option>
          </select>
          <button type="button" :disabled="page >= pageCount" @click="turnPage(page + 1)">
            {{ t('eht.caseLibrary.next') }}
          </button>
        </div>
      </div>
    </template>
    <div v-if="selected.length" class="eht-select">
      <label v-for="id in selected.slice(0, 30)" :key="id">
        <input type="checkbox" :checked="selected.includes(id)" @change="toggle(id)" />
        #{{ id }}
      </label>
      <span v-if="selected.length > 30" class="eht-muted">…</span>
    </div>

    <DetailDock :open="detailId !== null" @close="detailId = null">
      <CaseDetailPanel
        v-if="detailId"
        :project-id="projectId"
        :case-id="detailId"
        source="project"
      />
    </DetailDock>
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

/* 分页条：与项目用例库同位置（表格下方右侧），换页不改变筛选条件 */
.eht-pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.eht-pager__buttons { display: flex; align-items: center; gap: var(--space-2); }
.eht-pager button,
.eht-pager select {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  padding: 2px 10px;
}
.eht-pager button:disabled { opacity: 0.45; cursor: not-allowed; }
.eht-select-all { display: flex; align-items: center; gap: var(--space-1); }

/* 整行可点开详情：给出手型与 hover 底色，否则用户不知道能点 */
:deep(.eht-row) { cursor: pointer; }
</style>
