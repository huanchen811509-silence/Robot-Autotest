<script setup lang="ts">
/**
 * 执行计划（即时发起台）—— v4.11.0 / FR-v4.11-12..16。
 *
 * **与「测试计划」的分界**（最容易混的一处）：
 * - 本页 = 「现在就把这一批用例跑一遍」：选用例 → 配参数 → 安全预检 → 发起 → 跳运行详情；
 * - `/plans` = cron 定时编排：什么时候跑、跑哪个用例集、失败怎么重试。
 * 两套模型完全不同（一次性 run vs 周期 plan），所以是两页，不合并。
 *
 * 来源标记：发起时 `runRequestId` 用 `EP-` 前缀，本页的「发起记录」按此前缀过滤，
 * 不新增表 / 列（PRD P-04=A）。前缀 + UUID 共 39 字符，落在 `VARCHAR(64)` 内。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { caseApi, caseSetApi, type TestCase, type TestSet } from '@/api/cases'
import {
  executionApi,
  type ExecutionRun,
  type SafetyPrecheck,
} from '@/api/execution'
import DataTable from '@/components/DataTable.vue'
import type { DataTableColumns } from 'naive-ui'

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const projectId = computed(() => auth.projectId ?? 0)

/** 本页发起的标记前缀；改这里必须同步改「发起记录」的过滤条件。 */
const REQUEST_PREFIX = 'EP-'

/* ------------------------------------------------------------ 选择区 */

const caseSets = ref<TestSet[]>([])
const setsLoading = ref(false)
const selectedSetIds = ref<number[]>([])
/** 已选用例 id —— **唯一真源**；用例集勾选只是「批量加/减」的便捷入口。 */
const selectedCaseIds = ref<number[]>([])
const setItems = ref<Record<number, number[]>>({})

const caseRows = ref<TestCase[]>([])
const caseKeyword = ref('')
const caseLoading = ref(false)
const casePage = ref(1)
const caseTotal = ref(0)
const CASE_PAGE_SIZE = 20

const selectedSet = computed(() => new Set(selectedCaseIds.value))
const selectedCount = computed(() => selectedCaseIds.value.length)

function isCaseSelected(caseId: number): boolean {
  return selectedSet.value.has(caseId)
}

function toggleCase(testCase: TestCase): void {
  if (isCaseSelected(testCase.id)) {
    selectedCaseIds.value = selectedCaseIds.value.filter((id) => id !== testCase.id)
  } else {
    selectedCaseIds.value = [...selectedCaseIds.value, testCase.id]
  }
  // 用例集勾选态由「它的用例是否都还在已选中」反推——手工取消一条后，
  // 该集合自动变成未全选，不需要额外记账。
  selectedSetIds.value = selectedSetIds.value.filter((setId) => {
    const items = setItems.value[setId] ?? []
    return items.length > 0 && items.every((id) => selectedCaseIds.value.includes(id))
  })
}

async function loadCaseSets(): Promise<void> {
  if (!projectId.value) return
  setsLoading.value = true
  try {
    const page = await caseSetApi.page(projectId.value, { page: 1, size: 100 })
    caseSets.value = page.items
  } catch {
    caseSets.value = []
  } finally {
    setsLoading.value = false
  }
}

/** 勾选用例集 = 把它的用例 id 并入已选；取消 = 移除该集合独有的那部分。 */
async function toggleSet(testSet: TestSet): Promise<void> {
  const active = selectedSetIds.value.includes(testSet.id)
  if (!active && setItems.value[testSet.id] === undefined) {
    try {
      const items = await caseSetApi.items(projectId.value, testSet.id)
      setItems.value = { ...setItems.value, [testSet.id]: items.map((item) => item.id) }
    } catch {
      setItems.value = { ...setItems.value, [testSet.id]: [] }
    }
  }
  const ids = setItems.value[testSet.id] ?? []
  if (active) {
    selectedSetIds.value = selectedSetIds.value.filter((id) => id !== testSet.id)
    const keep = new Set(
      selectedSetIds.value.flatMap((setId) => setItems.value[setId] ?? []),
    )
    selectedCaseIds.value = selectedCaseIds.value.filter((id) => keep.has(id))
  } else {
    selectedSetIds.value = [...selectedSetIds.value, testSet.id]
    selectedCaseIds.value = [...new Set([...selectedCaseIds.value, ...ids])]
  }
}

const caseColumns = computed<DataTableColumns<TestCase>>(() => [
  {
    title: '',
    key: 'pick',
    width: 56,
    render: (row) =>
      // 勾选框直接驱动 `selectedCaseIds`，不额外维护一份「视图选中态」
      h('input', {
        type: 'checkbox',
        checked: isCaseSelected(row.id),
        'data-testid': `pick-${row.id}`,
        onChange: () => toggleCase(row),
      }),
  },
  { title: t('executionPlans.colCode'), key: 'caseCode', width: 170 },
  { title: t('executionPlans.colTitle'), key: 'title', minWidth: 240 },
  { title: t('executionPlans.colPriority'), key: 'priority', width: 90 },
  {
    title: t('executionPlans.colSafety'),
    key: 'safetyLevel',
    width: 110,
    render: (row) => row.safetyLevel,
  },
])

/** 表格里的勾选框要 `h()` 渲染（见上面 `caseColumns`）。 */

async function loadCases(): Promise<void> {
  if (!projectId.value) return
  caseLoading.value = true
  try {
    const page = await caseApi.page(projectId.value, {
      keyword: caseKeyword.value,
      page: casePage.value,
      size: CASE_PAGE_SIZE,
    })
    caseRows.value = page.items
    caseTotal.value = page.total
  } catch {
    caseRows.value = []
    caseTotal.value = 0
  } finally {
    caseLoading.value = false
  }
}

function searchCases(): void {
  casePage.value = 1
  void loadCases()
}

/* ------------------------------------------------------------ 参数区 */

/**
 * 五个参数都保持 `null` / `''` 表示「不填」——提交时**不出现该键**，
 * 让后端用它自己的默认值。给 0 或空串都不行：那会覆盖后端默认（FR-v4.11-14）。
 */
const environmentId = ref<number | null>(null)
const concurrency = ref<number | null>(null)
const timeoutSeconds = ref<number | null>(null)
const testTimeoutSeconds = ref<number | null>(null)
const includeTag = ref('')

/* ------------------------------------------------------------ 安全预检 */

const precheck = ref<SafetyPrecheck | null>(null)
const precheckFailed = ref(false)
const blockedCodes = computed(() => (precheck.value?.blocked ?? []).map((item) => item.caseCode))
const canStart = computed(
  () => selectedCount.value > 0 && precheck.value?.allowed !== false && !starting.value,
)
const starting = ref(false)
const startError = ref('')

let precheckSeq = 0
async function runPrecheck(): Promise<void> {
  if (!projectId.value || selectedCaseIds.value.length === 0) {
    precheck.value = null
    precheckFailed.value = false
    return
  }
  const seq = ++precheckSeq
  try {
    const result = await executionApi.safetyPrecheck(projectId.value, selectedCaseIds.value)
    if (seq !== precheckSeq) return
    precheck.value = result
    precheckFailed.value = false
  } catch {
    // 预检失败不阻塞：退回「无预检」状态，真正的拦截由后端 403 兜底
    if (seq === precheckSeq) {
      precheck.value = null
      precheckFailed.value = true
    }
  }
}

/**
 * 选择变化即预检（含用例集勾选带来的批量变化）。
 * 用 `watch` 而不是在每个 toggle 里手调，避免「加了一条没预检」的漏网。
 */
watch(selectedCaseIds, () => void runPrecheck(), { deep: true })

/* ------------------------------------------------------------ 发起 */

function newRequestId(): string {
  const uuid =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
  return `${REQUEST_PREFIX}${uuid}`
}

async function start(): Promise<void> {
  if (!projectId.value || !canStart.value) return
  starting.value = true
  startError.value = ''
  const payload: Parameters<typeof executionApi.runCases>[1] = {
    caseIds: [...selectedCaseIds.value],
    runRequestId: newRequestId(),
  }
  // 只有用户显式填了才带该字段：不填 = 交给后端默认
  if (environmentId.value !== null) payload.environmentId = environmentId.value
  if (concurrency.value !== null) payload.concurrency = concurrency.value
  if (timeoutSeconds.value !== null) payload.timeoutSeconds = timeoutSeconds.value
  if (testTimeoutSeconds.value !== null) payload.testTimeoutSeconds = testTimeoutSeconds.value
  if (includeTag.value.trim()) payload.includeTag = includeTag.value.trim()

  try {
    const run = await executionApi.runCases(projectId.value, payload)
    await router.push(`/runs/${encodeURIComponent(run.runId)}`)
  } catch (error) {
    startError.value = error instanceof Error ? error.message : t('executionPlans.startFailed')
  } finally {
    starting.value = false
  }
}

/* ------------------------------------------------------------ 发起记录 */

const records = ref<ExecutionRun[]>([])
const recordsLoading = ref(false)

/**
 * 只显示「本页发起」的记录：`runRequestId` 以 `EP-` 开头。
 *
 * 用例页发起的运行把 `runRequestId` 留空或给别的值，因此天然不会出现在这里。
 * 取最近 200 条再过滤，够覆盖「最近 20 条本页发起」。
 */
async function loadRecords(): Promise<void> {
  if (!projectId.value) return
  recordsLoading.value = true
  try {
    const page = await executionApi.page(projectId.value, { page: 1, size: 200 })
    records.value = page.items
      .filter((run) => (run.runRequestId ?? '').startsWith(REQUEST_PREFIX))
      .slice(0, 20)
  } catch {
    records.value = []
  } finally {
    recordsLoading.value = false
  }
}

const recordColumns = computed<DataTableColumns<ExecutionRun>>(() => [
  { title: t('executionPlans.colRunId'), key: 'runId', width: 240 },
  { title: t('executionPlans.colState'), key: 'state', width: 120 },
  { title: t('executionPlans.colCases'), key: 'totalTests', width: 100 },
  {
    title: t('executionPlans.colStarted'),
    key: 'startedAt',
    width: 200,
    render: (row) => row.startedAt ?? row.enqueuedAt ?? '—',
  },
])

onMounted(async () => {
  await Promise.all([loadCaseSets(), loadCases(), loadRecords()])
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('executionPlans.title') }}</h1>
        <p class="muted">{{ t('executionPlans.hint') }}</p>
      </div>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="!canStart"
        data-testid="start-run"
        @click="start"
      >
        {{ starting ? t('executionPlans.starting') : t('executionPlans.start') }}
      </button>
    </header>

    <p v-if="startError" class="error">{{ startError }}</p>

    <!-- 阻断提示：把「哪条用例、什么安全级」直接说出来，不要只说「不可执行」 -->
    <p v-if="blockedCodes.length" class="error" data-testid="blocked-hint">
      {{ t('executionPlans.blocked', { codes: blockedCodes.join('、') }) }}
    </p>

    <div class="grid">
      <section class="card">
        <h2>{{ t('executionPlans.pickSets') }}</h2>
        <p v-if="setsLoading" class="muted">{{ t('global.loading') }}</p>
        <ul v-else class="set-list">
          <li v-for="set in caseSets" :key="set.id">
            <label class="set-row">
              <input
                type="checkbox"
                :checked="selectedSetIds.includes(set.id)"
                :data-testid="`pick-set-${set.id}`"
                @change="toggleSet(set)"
              />
              <span>{{ set.name }}</span>
              <span class="mono muted">{{ set.setCode }}</span>
            </label>
          </li>
        </ul>
        <p v-if="!setsLoading && caseSets.length === 0" class="muted">
          {{ t('executionPlans.noSets') }}
        </p>
        <p class="selected-count" data-testid="selected-count">
          {{ t('executionPlans.selected', { count: selectedCount }) }}
        </p>
      </section>

      <section class="card">
        <h2>{{ t('executionPlans.params') }}</h2>
        <div class="params">
          <label class="field">
            <span>{{ t('executionPlans.env') }}</span>
            <input v-model.number="environmentId" class="input" type="number" min="1" />
          </label>
          <label class="field">
            <span>{{ t('executionPlans.concurrency') }}</span>
            <input v-model.number="concurrency" class="input" type="number" min="1" />
          </label>
          <label class="field">
            <span>{{ t('executionPlans.timeout') }}</span>
            <input v-model.number="timeoutSeconds" class="input" type="number" min="1" />
          </label>
          <label class="field">
            <span>{{ t('executionPlans.testTimeout') }}</span>
            <input v-model.number="testTimeoutSeconds" class="input" type="number" min="1" />
          </label>
          <label class="field">
            <span>{{ t('executionPlans.includeTag') }}</span>
            <input v-model="includeTag" class="input" type="text" />
          </label>
        </div>
        <p class="muted small">{{ t('executionPlans.paramsHint') }}</p>
        <p v-if="precheckFailed" class="muted small">{{ t('executionPlans.precheckFailed') }}</p>
      </section>
    </div>

    <section class="card">
      <h2>{{ t('executionPlans.pickCases') }}</h2>
      <div class="filters">
        <input
          v-model="caseKeyword"
          class="input"
          type="search"
          :placeholder="t('executionPlans.searchPlaceholder')"
          @keyup.enter="searchCases"
        />
        <button class="btn" type="button" @click="searchCases">
          {{ t('executionPlans.search') }}
        </button>
      </div>
      <DataTable
        :columns="caseColumns"
        :data="caseRows"
        :loading="caseLoading"
        :row-key="(row: TestCase) => row.id"
        :max-height="420"
      />
      <div class="pager">
        <button class="btn" :disabled="casePage <= 1" @click="((casePage -= 1), loadCases())">
          {{ t('global.prev') }}
        </button>
        <span class="muted">
          {{ casePage }} / {{ Math.max(1, Math.ceil(caseTotal / CASE_PAGE_SIZE)) }}
        </span>
        <button
          class="btn"
          :disabled="casePage >= Math.ceil(caseTotal / CASE_PAGE_SIZE)"
          @click="((casePage += 1), loadCases())"
        >
          {{ t('global.next') }}
        </button>
      </div>
    </section>

    <section class="card">
      <div class="card-head">
        <h2>{{ t('executionPlans.records') }}</h2>
        <button class="btn" type="button" @click="loadRecords">
          {{ t('global.refresh') }}
        </button>
      </div>
      <DataTable
        :columns="recordColumns"
        :data="records"
        :loading="recordsLoading"
        :row-key="(row: ExecutionRun) => row.runId"
        :empty-title="t('executionPlans.noRecords')"
        :max-height="420"
      />
    </section>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.grid {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(320px, 1.2fr);
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.card {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.grid .card {
  margin-top: 0;
}

.card h2 {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.card-head h2 {
  margin: 0;
}

.set-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 240px;
  overflow: auto;
}

.set-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px 4px;
  cursor: pointer;
}

.selected-count {
  margin: var(--space-3) 0 0;
  font-weight: 600;
}

.params {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-3);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.input {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

.filters {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.pager {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.mono {
  font-family: var(--font-mono, monospace);
}

.small {
  font-size: var(--font-size-sm);
}

.error {
  color: var(--fail);
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
