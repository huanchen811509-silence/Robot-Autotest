<script setup lang="ts">
/**
 * W2 / RPTX-03 对外测试报告列表 + 建草稿（FR-v3.10-02 / FR-v3.10-03）。
 *
 * v3.10 起「建草稿」分两种来源，都不再手打 runId：
 *   ① 按执行记录（默认）：从 RunPicker 下拉选一条 run，标题自动带出（可改）；
 *   ② 按用例集（FR-v3.10-03）：选用例集，数据源取该集**最近一次执行**；
 *      也可以在「指定 RUN_ID」里钉死某一次（留空 = 最近一次）。
 * 两条路径最终都落成一条 run，后端用同一段代码建快照——所以「旧路径零回归」不需要
 * 另写一套回归，也不存在两条实现慢慢分叉的问题。
 *
 * 手打入口仍然是**降级**而非删除（判据④）：归档 run 被清掉记录后，仍可能有人拿着
 * RUN_ID 要出一份报告，一刀切删掉会断掉这条路。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NInput, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import RunPicker from '@/components/RunPicker.vue'
import CaseSetPicker from '@/components/CaseSetPicker.vue'
import { useAuthStore } from '@/stores/auth'
import { testReportsApi, type TestReportItem } from '@/api/testReports'
import type { TestSet } from '@/api/cases'
import type { ExecutionRun } from '@/api/execution'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref<TestReportItem[]>([])
const loading = ref(false)
const failed = ref(false)
const notice = ref('')
const title = ref('')
const advanced = ref(false)
const projectId = computed(() => auth.projectId ?? 0)

/** 来源：run = 按执行记录（默认） / case_set = 按用例集。 */
const source = ref<'run' | 'case_set'>('run')

const runId = ref<string | null>(null)
const manualRunId = ref('')
const caseSetId = ref<number | null>(null)
/** 按用例集时的「指定一次执行」；留空 = 后端取该集最近一次。 */
const caseSetRunId = ref('')

/** 有效 runId（仅 run 模式）：主路径取下拉选中值，高级路径取手输值。 */
const effectiveRunId = computed(() =>
  advanced.value ? manualRunId.value.trim() : (runId.value ?? '').trim(),
)

/** 能不能提交：两种来源各有各的前置条件，缺一不可，但不互相冒充。 */
const canSubmit = computed(() =>
  source.value === 'run' ? Boolean(effectiveRunId.value) : caseSetId.value !== null,
)

/** 来源展示：按 run 的旧报告（sourceType 缺失或 RUN）一律按「按执行记录」解释。 */
function sourceLabel(row: TestReportItem): string {
  const type = (row.sourceType ?? 'RUN').toUpperCase()
  if (type !== 'CASE_SET') return t('testReports.sourceRun')
  return row.caseSetId
    ? t('testReports.sourceCaseSetWith', { id: row.caseSetId })
    : t('testReports.sourceCaseSet')
}

const columns = computed<DataTableColumns<TestReportItem>>(() => [
  { title: t('testReports.colTitle'), key: 'title', minWidth: 160 },
  { title: t('testReports.colRun'), key: 'runId', minWidth: 160 },
  {
    title: t('testReports.colSource'),
    key: 'sourceType',
    minWidth: 130,
    render: (row) => sourceLabel(row),
  },
  { title: t('testReports.colState'), key: 'state', minWidth: 90 },
  { title: t('testReports.colVersion'), key: 'version', minWidth: 80 },
  {
    title: t('testReports.colCreatedAt'),
    key: 'createdAt',
    minWidth: 150,
    render: (row) => formatDateTime(row.createdAt),
  },
])

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    rows.value = await testReportsApi.list(projectId.value)
  } catch {
    failed.value = true
    notice.value = t('testReports.failed')
  } finally {
    loading.value = false
  }
}

/**
 * 选中执行记录后自动带出默认标题（判据③）。
 *
 * 默认标题带上 runId 而不是只有日期：同一份 run 可能建多份报告（不同口径），
 * 只写日期会得到两个一模一样、事后分不清的标题。
 */
function onPickRun(run: ExecutionRun | null): void {
  if (!run) {
    title.value = ''
    return
  }
  title.value = t('testReports.defaultTitle', { runId: run.runId })
}

/** 切来源时清掉另一种来源的选择，避免「界面上是按用例集、提交用的却是上一轮的 run」。 */
function switchSource(next: 'run' | 'case_set'): void {
  if (source.value === next) return
  source.value = next
  title.value = ''
  if (next === 'case_set') {
    runId.value = null
    manualRunId.value = ''
  } else {
    caseSetId.value = null
    caseSetRunId.value = ''
  }
}

/** 选用例集后带出「用例集报告 <名字>」：标题里不写 runId，因为数据源还没定（最近一次）。 */
function onPickCaseSet(picked: TestSet | null): void {
  title.value = picked ? t('testReports.defaultCaseSetTitle', { name: picked.name }) : ''
}

async function create(): Promise<void> {
  if (!canSubmit.value) return
  try {
    if (source.value === 'case_set') {
      await testReportsApi.createFromCaseSet(
        projectId.value,
        caseSetId.value!,
        caseSetRunId.value,
        title.value.trim(),
      )
    } else {
      await testReportsApi.create(projectId.value, effectiveRunId.value, title.value.trim())
    }
    runId.value = null
    manualRunId.value = ''
    caseSetId.value = null
    caseSetRunId.value = ''
    title.value = ''
    notice.value = t('testReports.created')
    await load()
  } catch {
    notice.value = t('testReports.failed')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('testReports.title') }}</h1>
      <p class="muted">{{ t('testReports.subtitle') }}</p>
    </header>

    <form class="bar create-bar" @submit.prevent="create">
      <div class="source-switch" role="group" :aria-label="t('testReports.colSource')">
        <button
          class="btn btn-ghost"
          :class="{ 'is-active': source === 'run' }"
          type="button"
          data-testid="source-run"
          @click="switchSource('run')"
        >
          {{ t('testReports.sourceRun') }}
        </button>
        <button
          class="btn btn-ghost"
          :class="{ 'is-active': source === 'case_set' }"
          type="button"
          data-testid="source-case-set"
          @click="switchSource('case_set')"
        >
          {{ t('testReports.sourceCaseSet') }}
        </button>
      </div>

      <template v-if="source === 'run'">
        <RunPicker v-if="!advanced" v-model="runId" :project-id="projectId" @select="onPickRun" />
        <NInput
          v-else
          v-model:value="manualRunId"
          class="input"
          :placeholder="t('testReports.runPlaceholder')"
          data-testid="manual-run-id"
        />
      </template>
      <template v-else>
        <CaseSetPicker v-model="caseSetId" :project-id="projectId" @select="onPickCaseSet" />
        <NInput
          v-model:value="caseSetRunId"
          class="input"
          :placeholder="t('testReports.caseSetRunOptional')"
          data-testid="case-set-run-id"
        />
      </template>

      <NInput
        v-model:value="title"
        class="title-input"
        :placeholder="t('testReports.titlePlaceholder')"
        data-testid="draft-title"
      />
      <button class="btn" type="submit" :disabled="!canSubmit">
        {{ t('testReports.create') }}
      </button>
      <button
        v-if="source === 'run'"
        class="btn btn-ghost"
        type="button"
        data-testid="toggle-advanced"
        @click="advanced = !advanced"
      >
        {{ advanced ? t('testReports.backToPicker') : t('testReports.advanced') }}
      </button>
      <button class="btn btn-ghost" type="button" @click="load">{{ t('testReports.refresh') }}</button>
    </form>

    <p v-if="notice" class="notice">{{ notice }}</p>

    <DataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :error="failed"
      :empty-title="t('testReports.empty')"
    />
  </div>
</template>

<style scoped>
.create-bar {
  align-items: center;
  flex-wrap: wrap;
}
.title-input {
  width: 240px;
}
.source-switch {
  display: inline-flex;
  gap: 4px;
}
.source-switch .is-active {
  background: var(--accent-soft);
  color: var(--accent);
}
.case-set-select {
  min-width: 240px;
}
</style>
