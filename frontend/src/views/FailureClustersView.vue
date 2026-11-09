<script setup lang="ts">
/**
 * 失败聚类（ORCH-03，v2.1）。
 *
 * 页面只做两件事：**按类别分组展示簇** + **按簇一键重跑**。
 * 刻意不做「自动聚类图表」：簇名本身是可读文本，画成气泡图反而看不出「这一簇是什么」。
 *
 * 三个易错点在本页显式处理：
 * ① `ratio` 是 0~1 的小数，展示要转百分比，不能直接拼 `%`（会显示 0.6667%）；
 * ② 簇的 `caseIds` 可能为空（导入的失败行没有关联台账），重跑按钮要禁用而不是发空请求；
 * ③ 同一 runId 重复查询结果必须一致——页面不做前端二次聚合，直接渲染后端顺序。
 *
 * 表格走 `DataTable` 壳而不是原生 `<table>`：门禁 `tools/check_tables.py` 要求数据网格统一走壳
 * （壳负责总列宽、粘性表头、空态），原生表格只留在三处白名单里。
 */
import { computed, h, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import AiAnswerCard from '@/components/AiAnswerCard.vue'
import { failureClusterApi, type FailureCluster, type FailureClusterView } from '@/api/failureClusters'
import { aiWorkbenchApi, type AiResult } from '@/api/aiWorkbench'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const runId = ref('')
/**
 * 两种口径（W5）：单轮看「这一轮哪里坏了」，最近 N 轮看「连续几轮都栽在同一处」。
 *
 * 跨轮视图不能重跑：后端按簇重跑要求单轮 runId，跨轮簇合并了多轮用例，
 * 拿任意一轮去重跑都会破坏统计口径，所以按钮直接禁用并给出说明。
 */
const mode = ref<'run' | 'recent'>('run')
const recentRuns = ref(5)
const isRecent = computed(() => mode.value === 'recent')
const view = ref<FailureClusterView | null>(null)
const loading = ref(false)
const errorText = ref('')
const notice = ref('')
/** v3.3.0：AI 分诊结果与真实运行编号绑定；跨轮视图没有单轮编号，按钮不出现。 */
const triage = ref<AiResult | null>(null)
const triageBusy = ref(false)
const triageError = ref('')

const total = computed(() => view.value?.total ?? 0)

async function load(): Promise<void> {
  if (!isRecent.value && !runId.value.trim()) return
  loading.value = true
  errorText.value = ''
  notice.value = ''
  triage.value = null
  triageError.value = ''
  try {
    view.value = isRecent.value
      ? await failureClusterApi.recent(projectId.value, recentRuns.value)
      : await failureClusterApi.load(projectId.value, runId.value.trim())
  } catch (error) {
    view.value = null
    handle(error)
  } finally {
    loading.value = false
  }
}

/** 切口径先清空旧结果：上一个口径的簇留在屏幕上会被误读成新口径的结论。 */
function switchMode(next: 'run' | 'recent'): void {
  if (mode.value === next) return
  mode.value = next
  view.value = null
  notice.value = ''
  errorText.value = ''
  triage.value = null
  triageError.value = ''
  void load()
}

async function rerun(clusterKey: string, caseIds: number[]): Promise<void> {
  if (isRecent.value || caseIds.length === 0) return
  try {
    const run = await failureClusterApi.rerun(projectId.value, runId.value.trim(), clusterKey)
    notice.value = t('failureClusters.rerunStarted', { runId: run.runId })
    errorText.value = ''
  } catch (error) {
    handle(error)
  }
}

/** 0.6667 → "66.7%"；后端已经是 4 位小数，这里只做展示转换。 */
function percent(ratio: number): string {
  return `${(ratio * 100).toFixed(1)}%`
}

function handle(error: unknown): void {
  errorText.value = error instanceof ApiError ? error.message : t('failureClusters.unknownError')
}

/** AI 分诊：把当前运行的失败簇交给证据装配器，输出事实 / 推测 / 建议三段。 */
async function runTriage(): Promise<void> {
  if (isRecent.value || !runId.value.trim()) return
  triageBusy.value = true
  triageError.value = ''
  try {
    triage.value = await aiWorkbenchApi.triage(projectId.value, runId.value.trim())
  } catch (error) {
    triage.value = null
    triageError.value = error instanceof ApiError ? error.message : t('failureAi.failed')
  } finally {
    triageBusy.value = false
  }
}

/**
 * 列定义。单元格用 render 而不是模板 slot：每个类别是一张独立的表（分组展示），
 * 列定义必须能复用，写进模板就得复制 5 份。
 */
const columns = computed<DataTableColumns<FailureCluster>>(() => [
  {
    title: t('failureClusters.clusterKey'),
    key: 'key',
    minWidth: 220,
    render: (row) => h('span', { class: 'key' }, row.key),
  },
  {
    title: t('failureClusters.count'),
    key: 'count',
    width: 90,
    align: 'right',
  },
  {
    title: t('failureClusters.share'),
    key: 'ratio',
    width: 100,
    align: 'right',
    render: (row) => percent(row.ratio),
  },
  {
    title: t('failureClusters.samples'),
    key: 'samples',
    minWidth: 220,
    render: (row) =>
      h(
        'div',
        { class: 'samples' },
        row.samples.map((sample, index) =>
          // caseId 可能为 null（用例已删但结果还在），兜到下标，避免 key 出现 null
          h(
            'span',
            { key: String(sample.caseId ?? sample.caseCode ?? index) },
            sample.caseCode || sample.caseTitle || '—',
          ),
        ),
      ),
  },
  {
    title: '',
    key: 'actions',
    width: 170,
    render: (row) => {
      const disabled = isRecent.value || row.caseIds.length === 0
      const hint = isRecent.value
        ? t('failureClusters.recentRerunHint')
        : row.caseIds.length === 0 ? t('failureClusters.noCase') : ''
      return h(
        'button',
        {
          class: 'btn btn-ghost',
          type: 'button',
          'data-testid': 'rerun',
          disabled,
          title: hint,
          onClick: () => rerun(row.key, row.caseIds),
        },
        t('failureClusters.rerun', { count: row.caseIds.length }),
      )
    },
  },
])

/** 簇键在整轮里唯一，直接当行键。 */
function rowKeyOf(row: FailureCluster): string {
  return row.key
}

/** 行标记交给 `DataTable` 转发到 `<tr>`，验收脚本与单测都按它定位。 */
function rowProps(): Record<string, unknown> {
  return { 'data-testid': 'cluster-row' }
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('failureClusters.title') }}</h1>
      <p class="muted">{{ t('failureClusters.subtitle') }}</p>
    </header>

    <div class="bar mode-bar" data-testid="mode-switch">
      <button
        class="btn"
        :class="{ 'btn-ghost': mode !== 'run' }"
        type="button"
        data-testid="mode-run"
        :aria-pressed="mode === 'run'"
        @click="switchMode('run')"
      >
        {{ t('failureClusters.modeRun') }}
      </button>
      <button
        class="btn"
        :class="{ 'btn-ghost': mode !== 'recent' }"
        type="button"
        data-testid="mode-recent"
        :aria-pressed="mode === 'recent'"
        @click="switchMode('recent')"
      >
        {{ t('failureClusters.modeRecent') }}
      </button>
    </div>

    <form class="bar" data-testid="query" @submit.prevent="load">
      <select
        v-if="isRecent"
        v-model.number="recentRuns"
        class="input"
        data-testid="recent-runs"
        :aria-label="t('failureClusters.recentRuns')"
        @change="load"
      >
        <option v-for="n in [1, 3, 5, 10, 20]" :key="n" :value="n">{{ n }}</option>
      </select>
      <input
        v-else
        v-model="runId"
        class="input"
        type="text"
        :placeholder="t('failureClusters.runIdPlaceholder')"
      />
      <button class="btn" type="submit" :disabled="loading">
        {{ t('failureClusters.query') }}
      </button>
      <button
        v-if="!isRecent"
        class="btn btn-ghost"
        type="button"
        data-testid="ai-triage"
        :disabled="triageBusy || !runId.trim()"
        @click="runTriage"
      >
        {{ t('failureAi.triage') }}
      </button>
    </form>

    <AiAnswerCard
      v-if="!isRecent && (triage || triageBusy || triageError)"
      :result="triage"
      :loading="triageBusy"
      :error="triageError"
    />

    <p v-if="notice" class="notice" data-testid="notice">{{ notice }}</p>
    <p v-if="errorText" class="error" data-testid="error">{{ errorText }}</p>

    <template v-if="view">
      <p class="muted" data-testid="summary">
        {{
          isRecent
            ? t('failureClusters.recentSummary', {
                total,
                classes: view.classes.length,
                runs: recentRuns,
              })
            : t('failureClusters.summary', { total, classes: view.classes.length })
        }}
      </p>

      <section
        v-for="group in view.classes"
        :key="group.failureClass"
        class="class-group"
        data-testid="class-group"
      >
        <h2>
          <span class="badge" :data-class="group.failureClass">{{ group.failureClass }}</span>
          <span class="muted">
            {{ t('failureClusters.classCount', { count: group.count, percent: percent(group.ratio) }) }}
          </span>
        </h2>

        <!-- max-height=null：每个类别只有几行，套内滚会出现「小框里再滚一层」 -->
        <DataTable
          class="clusters"
          :columns="columns"
          :data="group.clusters"
          :row-key="rowKeyOf"
          :row-props="rowProps"
          :max-height="null"
          :skeleton-rows="1"
        />
      </section>

      <p v-if="view.total === 0" class="muted" data-testid="no-failure">
        {{ t('failureClusters.noFailure') }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.class-group {
  margin-top: 18px;
}

.mode-bar {
  margin-bottom: 8px;
}
.badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  border: 1px solid var(--border);
  font-size: 12px;
  margin-right: 8px;
}
.badge[data-class='ENV'] {
  color: var(--warn);
}
.badge[data-class='INFRA'] {
  color: var(--fail);
}
.badge[data-class='CANCELED'] {
  color: var(--text-muted);
}
/*
 * 单元格由 render 函数产出，拿不到本组件的 scoped 属性，只能用 :deep 穿透。
 * 锚点选 `.clusters`（挂在 DataTable 根节点上，根节点会继承本组件的 scope id）。
 */
.clusters :deep(.key) {
  font-family: var(--font-mono);
  word-break: break-all;
}
.clusters :deep(.samples) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  color: var(--text-muted);
}
.error {
  color: var(--fail);
}
</style>
