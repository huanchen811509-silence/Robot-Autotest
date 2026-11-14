<script setup lang="ts">
/**
 * 执行计划（即时发起台）—— v4.11.0 / FR-v4.11-12..16；v5.7.0 / W57-29/30 重构选区。
 *
 * **与「测试计划」的分界**：本页 = 「现在就把这一批用例跑一遍」；
 * `/plans` = cron 定时编排。两套模型不同，所以是两页，不合并。
 *
 * W57-29/30 三处变化：
 * 1. 选区唯一真源换成 `useRunSelection` 的 `Map<caseId, Set<source>>`：取消用例集
 *    **只摘该集合来源**，不再用「剩余集合并集」重建已选（旧实现会误删手工单独勾选的用例）。
 * 2. 四模式（ALL / SET / CASE / MIXED）各自保留草稿；用例集搜索/翻页走后端分页，去掉 size:100。
 * 3. 发起先走 `preview → confirm-and-start` 两步；后端未部署（404）或幂等冲突（409）
 *    时**回退到旧 `runCases` 的 caseIds 路径**并给出可读提示，灰度期新旧后端都能用。
 *
 * 来源标记：发起时 `runRequestId` 用 `EP-` 前缀，本页「发起记录」按此前缀过滤。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { caseApi, caseSetApi, type TestCase, type TestSet } from '@/api/cases'
import { executionApi, type ExecutionRun, type SafetyPrecheck } from '@/api/execution'
import { runLaunchApi, type RunPreviewPayload } from '@/api/runLaunch'
import { ApiError } from '@/api/http'
import DataTable from '@/components/DataTable.vue'
import RunSelectionPanel from '@/components/RunSelectionPanel.vue'
import { useRunSelection, type RunMode, type SourceEntry } from '@/composables/useRunSelection'
import type { DataTableColumns } from 'naive-ui'

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const projectId = computed(() => auth.projectId ?? 0)
const REQUEST_PREFIX = 'EP-'

/* ------------------------------------------------------------ 选择区（唯一真源） */

/** W57-29：所有勾选都记在 useRunSelection 里；视图只负责把 props 递给面板、把事件递回来。 */
const selection = useRunSelection('MIXED')
/** 兼容既有断言的命名（`selectedCaseIds` 老断言不删只补）。 */
const selectedCaseIds = selection.selectedCaseIds
const selectedCount = selection.selectedCount
const selectedSetIds = selection.selectedSetIds
const mode = selection.mode
const allSelected = selection.allSelected
const sources = computed<SourceEntry[]>(() => selection.toPayload().sources)
const setNames = computed<Record<number, string>>(() =>
  Object.fromEntries(caseSets.value.map((set) => [set.id, set.name])),
)

/* ------------------------------------------------------------ 用例集（后端分页 + keyword） */

const caseSets = ref<TestSet[]>([])
const setsLoading = ref(false)
const setKeyword = ref('')
const setPage = ref(1)
const setTotal = ref(0)
/** W57-30：去掉旧实现的 `size:100` 硬上限，走 keyword + page 的后端分页。 */
const SET_PAGE_SIZE = 20

const caseRows = ref<TestCase[]>([])
const caseKeyword = ref('')
const caseLoading = ref(false)
const casePage = ref(1)
const caseTotal = ref(0)
const CASE_PAGE_SIZE = 20

async function loadCaseSets(): Promise<void> {
  if (!projectId.value) return
  setsLoading.value = true
  try {
    const page = await caseSetApi.page(projectId.value, {
      keyword: setKeyword.value.trim() || undefined,
      page: setPage.value,
      size: SET_PAGE_SIZE,
    })
    caseSets.value = page.items
    setTotal.value = page.total
  } catch {
    caseSets.value = []
    setTotal.value = 0
  } finally {
    setsLoading.value = false
  }
}

async function loadCases(): Promise<void> {
  if (!projectId.value) return
  caseLoading.value = true
  try {
    const page = await caseApi.page(projectId.value, {
      keyword: caseKeyword.value.trim() || undefined,
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

/**
 * 勾选用例集：先取该集合的成员 id，再交给唯一真源「按来源」增删。
 * 取消时只摘 `set:<id>`，手工勾选 / 其它集合的来源原样保留（缺陷修复点）。
 */
async function onToggleSet(set: TestSet): Promise<void> {
  let ids: number[] = []
  try {
    const items = await caseSetApi.items(projectId.value, set.id)
    ids = items.map((item) => item.id)
  } catch {
    ids = []
  }
  selection.toggleSet(set.id, ids)
}

function onToggleCase(caseId: number): void {
  selection.toggleCase(caseId)
}

function onSetMode(next: RunMode): void {
  selection.setMode(next)
}

function onSetAll(on: boolean): void {
  selection.setAll(on)
}

function onClear(): void {
  selection.clear()
}

function onSearchSets(keyword: string): void {
  setKeyword.value = keyword
  setPage.value = 1
  void loadCaseSets()
}

function onSetPage(page: number): void {
  setPage.value = page
  void loadCaseSets()
}

function onSearchCases(keyword: string): void {
  caseKeyword.value = keyword
  casePage.value = 1
  void loadCases()
}

function onCasePage(page: number): void {
  casePage.value = page
  void loadCases()
}

/* ------------------------------------------------------------ 参数区 */

/**
 * 五个参数保持 `null` / `''` 表示「不填」——提交时**不出现该键**，
 * 让后端用它自己的默认值。给 0 或空串都不行（会覆盖后端默认，FR-v4.11-14）。
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
/** ALL 模式靠 allSelected 也能发起，其余模式靠逐条 selectedCount。 */
const canStart = computed(
  () =>
    (selectedCount.value > 0 || (mode.value === 'ALL' && allSelected.value)) &&
    precheck.value?.allowed !== false &&
    !starting.value,
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
    const result = await executionApi.safetyPrecheck(projectId.value, [...selectedCaseIds.value])
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

/** 选择变化即预检（含用例集勾选带来的批量变化），用 watch 避免漏网。 */
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

  const requestId = newRequestId()
  const caseIds = [...new Set(selectedCaseIds.value)]
  const previewPayload: RunPreviewPayload = {
    caseIds,
    setIds: selectedSetIds.value,
    mode: mode.value,
    all: mode.value === 'ALL' && allSelected.value,
    runRequestId: requestId,
  }
  /** 旧路径的兼容载荷：与既有 caseIds 提交完全一致，参数同样「不填就不带键」。 */
  const legacyPayload: Parameters<typeof executionApi.runCases>[1] = {
    caseIds,
    runRequestId: requestId,
  }
  // 只有用户显式填了才带该字段：不填 = 交给后端默认。两份载荷同步填充。
  if (environmentId.value !== null) {
    previewPayload.environmentId = environmentId.value
    legacyPayload.environmentId = environmentId.value
  }
  if (concurrency.value !== null) {
    previewPayload.concurrency = concurrency.value
    legacyPayload.concurrency = concurrency.value
  }
  if (timeoutSeconds.value !== null) {
    previewPayload.timeoutSeconds = timeoutSeconds.value
    legacyPayload.timeoutSeconds = timeoutSeconds.value
  }
  if (testTimeoutSeconds.value !== null) {
    previewPayload.testTimeoutSeconds = testTimeoutSeconds.value
    legacyPayload.testTimeoutSeconds = testTimeoutSeconds.value
  }
  if (includeTag.value.trim()) {
    previewPayload.includeTag = includeTag.value.trim()
    legacyPayload.includeTag = includeTag.value.trim()
  }

  try {
    // 新流程：先预览拿 token，再确认并发起。
    const preview = await runLaunchApi.preview(projectId.value, previewPayload)
    const run = await runLaunchApi.confirmAndStart(projectId.value, preview.token, previewPayload)
    await router.push(`/runs/${encodeURIComponent(run.runId)}`)
  } catch (e) {
    // 后端未部署（404）/ 幂等冲突（409）/ 预览不可用：回退到旧 caseIds 路径，并给出可读提示。
    const code = e instanceof ApiError ? e.code : 0
    startError.value =
      code === 404 || code === 409
        ? t('w57.executionPlans.newLaunchUnavailable', { code })
        : t('w57.executionPlans.previewUnavailable')
    try {
      const run = await executionApi.runCases(projectId.value, legacyPayload)
      await router.push(`/runs/${encodeURIComponent(run.runId)}`)
    } catch (legacyError) {
      startError.value =
        legacyError instanceof Error ? legacyError.message : t('executionPlans.startFailed')
    }
  } finally {
    starting.value = false
  }
}

/* ------------------------------------------------------------ 发起记录 */

const records = ref<ExecutionRun[]>([])
const recordsLoading = ref(false)

/** 只显示「本页发起」的记录：`runRequestId` 以 `EP-` 开头。 */
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

    <p v-if="startError" class="error" data-testid="start-error">{{ startError }}</p>

    <!-- 阻断提示：把「哪条用例、什么安全级」直接说出来 -->
    <p v-if="blockedCodes.length" class="error" data-testid="blocked-hint">
      {{ t('executionPlans.blocked', { codes: blockedCodes.join('、') }) }}
    </p>

    <!-- W57-29/30：四模式选区（用例集 + 用例 + 来源下钻），props 进 / 事件出 -->
    <RunSelectionPanel
      :case-sets="caseSets"
      :case-rows="caseRows"
      :case-total="caseTotal"
      :case-page="casePage"
      :case-page-size="CASE_PAGE_SIZE"
      :selected-case-ids="selectedCaseIds"
      :selected-count="selectedCount"
      :selected-set-ids="selectedSetIds"
      :mode="mode"
      :sources="sources"
      :set-names="setNames"
      :set-total="setTotal"
      :set-page="setPage"
      :set-page-size="SET_PAGE_SIZE"
      :all-selected="allSelected"
      @toggle-set="onToggleSet"
      @toggle-case="onToggleCase"
      @set-mode="onSetMode"
      @set-all="onSetAll"
      @clear="onClear"
      @search-sets="onSearchSets"
      @set-page="onSetPage"
      @search-cases="onSearchCases"
      @case-page="onCasePage"
    />

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
.page {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.card {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
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
  box-sizing: border-box;
  max-width: 100%;
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
</style>
