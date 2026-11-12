<script setup lang="ts">
/**
 * v5.1.0 · pytest 工程工作台（FR-v5.1-05 / FR-v5.1-12）。
 *
 * 一个页面回答四个问题：能不能跑（健康）、接上了多少（覆盖率）、哪些接上了（绑定）、
 * 配置对不对（工程配置）。刻意不做「多层 tab」：这四件事互相印证，
 * 分开看会让人反复切页面对数字。
 */
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import {
  pytestProjectApi,
  type BindingStats,
  type CaseScriptBinding,
  type EvidenceExtractResult,
  type PytestCaseStep,
  type PytestHealth,
  type SyncReport,
} from '@/api/pytestProject'
import {
  executionCoordinationApi,
  type Admission,
  type ExecutionLedger,
} from '@/api/executionCoordination'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const health = ref<PytestHealth | null>(null)
const bindings = ref<BindingStats | null>(null)

/**
 * 绑定列表列定义（迁 DataTable 壳，W54-06 遗留清理）。
 *
 * <p>四列与原来的原生表格一一对应：脚本路径 / nodeid / 解析来源 / 是否已校验。
 * 用 render 而不是直接给 key，是为了保留原来的等宽字体与弱化色——
 * 迁壳只换容器，不换呈现。
 */
const bindingColumns = computed<DataTableColumns<CaseScriptBinding>>(() => [
  {
    title: t('pytestWorkbench.scriptPath'),
    key: 'scriptPath',
    render: (row) => h('span', { class: 'mono' }, row.scriptPath),
  },
  {
    title: t('pytestWorkbench.nodeId'),
    key: 'nodeId',
    render: (row) => h('span', { class: 'mono muted' }, row.nodeId ?? '—'),
  },
  {
    title: t('pytestWorkbench.source'),
    key: 'resolveSource',
  },
  {
    title: t('pytestWorkbench.verified'),
    key: 'verified',
    render: (row) => (row.verified ? '✓' : '—'),
  },
])
const settings = reactive<Record<string, string>>({})
const loading = ref(false)
/** 保存被拒时的逐字段原因（后端整体拒绝，不存一半）。 */
const saveErrors = ref<{ field: string; message: string }[]>([])
/** 保存/加载后仍未填的必填项（空数组 = 齐了）。 */
const missingFields = ref<string[]>([])
const ledger = ref<ExecutionLedger | null>(null)
const admission = ref<Admission | null>(null)
const ledgerFailed = ref(false)
const syncing = ref(false)
const saving = ref(false)
const failed = ref(false)
const message = ref('')

/**
 * 步骤证据（FR-v5.1-08）。
 *
 * 证据是「按 run」的，不是「按工程」的，所以这一块要用户给 runId。
 * 抽两段：抽到的计数（说明抽到了什么）与按用例分组后的步骤（说明每一步做了什么）。
 */
const evidenceRunId = ref('')
const extracting = ref(false)
const evidenceResult = ref<EvidenceExtractResult | null>(null)
const evidenceSteps = ref<PytestCaseStep[]>([])

/** 按 nodeId 分组；步骤已在后端按 nodeId + seq 排好，这里只做分桶，不重排。 */
const evidenceGroups = computed(() => {
  const groups = new Map<string, PytestCaseStep[]>()
  for (const step of evidenceSteps.value) {
    const list = groups.get(step.nodeId) ?? []
    list.push(step)
    groups.set(step.nodeId, list)
  }
  return [...groups.entries()].map(([nodeId, steps]) => ({
    nodeId,
    caseKey: steps[0]?.caseKey ?? '',
    steps,
    fails: steps.filter((step) => step.status === 'FAIL').length,
    skips: steps.filter((step) => step.status === 'SKIP').length,
  }))
})

/** 配置项展示顺序：必填在前，其余按使用频率。 */
const SETTING_FIELDS = [
  'repo_path',
  'python_bin',
  'env_config',
  'inventory_path',
  'repo_url',
  'branch',
  'mode_default',
  'inventory_key_rule',
  'fake_plan_file',
  'extra_args',
  'timeout_s',
]

const settingLabelKeys: Record<string, string> = {
  repo_path: 'pytestWorkbench.settingLabels.repo_path',
  python_bin: 'pytestWorkbench.settingLabels.python_bin',
  env_config: 'pytestWorkbench.settingLabels.env_config',
  inventory_path: 'pytestWorkbench.settingLabels.inventory_path',
  repo_url: 'pytestWorkbench.settingLabels.repo_url',
  branch: 'pytestWorkbench.settingLabels.branch',
  mode_default: 'pytestWorkbench.settingLabels.mode_default',
  inventory_key_rule: 'pytestWorkbench.settingLabels.inventory_key_rule',
  fake_plan_file: 'pytestWorkbench.settingLabels.fake_plan_file',
  extra_args: 'pytestWorkbench.settingLabels.extra_args',
  timeout_s: 'pytestWorkbench.settingLabels.timeout_s',
}

/**
 * 同步报告里的四类计数。与绑定列表分开渲染：列表是「哪一条」，
 * 计数是「差多少」——治理动作依赖后者，翻列表看不出规模。
 */
const lastSync = ref<SyncReport | null>(null)

/** 双口径全部取后端值：前端不再自己减 178、也不再拿 bound 当分子。 */
const calibers = computed(() => {
  if (!health.value) {
    return []
  }
  return [
    {
      key: 'A',
      label: t('pytestWorkbench.caliberA'),
      bound: health.value.bound,
      total: health.value.totalCases,
      percent: health.value.coveragePercent,
    },
    {
      key: 'B',
      label: t('pytestWorkbench.caliberB'),
      bound: health.value.boundExcludingPending,
      total: health.value.totalExcludingPending,
      percent: health.value.coverageExcludingPendingPercent,
    },
  ]
})

/**
 * 秒 → 「1 小时 12 分」/「3 分 5 秒」。
 *
 * 不用 `Intl.RelativeTimeFormat`：那是「3 分钟前」这类相对表述，
 * 而这里要的是**时长**，两者混用会让「平均 0 秒」看起来像「刚刚」。
 */
function formatDuration(seconds: number): string {
  if (!seconds || seconds <= 0) return '—'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m ${rest}s`
  return `${rest}s`
}

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    health.value = await pytestProjectApi.health(projectId.value)
    bindings.value = await pytestProjectApi.bindings(projectId.value)
    const raw = await pytestProjectApi.settings(projectId.value)
    for (const key of Object.keys(settings)) delete settings[key]
    Object.assign(settings, raw)
    await loadLedger()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 台账与准入单独加载：它们属于「执行协调」，与 pytest 工程配置无关。
 * 任一失败只把这一块标灰，不能让整页显示「加载失败」——
 * 后端没升级时页面其余部分仍然可用。
 */
async function loadLedger(): Promise<void> {
  ledgerFailed.value = false
  try {
    const [record, gate] = await Promise.all([
      executionCoordinationApi.ledger(projectId.value),
      executionCoordinationApi.admission(projectId.value),
    ])
    ledger.value = record
    admission.value = gate
  } catch {
    ledgerFailed.value = true
  }
}

async function reprobe(): Promise<void> {
  loading.value = true
  try {
    health.value = await pytestProjectApi.health(projectId.value, true)
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function sync(): Promise<void> {
  syncing.value = true
  message.value = ''
  try {
    const report = await pytestProjectApi.sync(projectId.value)
    lastSync.value = report
    message.value = t('pytestWorkbench.syncDone', {
      bound: report.bound,
      orphan: report.orphanScripts,
      dup: report.duplicateBindings,
    })
    await load()
  } catch (error) {
    failed.value = true
    message.value = String(error)
  } finally {
    syncing.value = false
  }
}

/** 保存配置：后端整体校验，任一字段非法则什么都不落库。 */
async function save(): Promise<void> {
  saving.value = true
  saveErrors.value = []
  message.value = ''
  try {
    const result = await pytestProjectApi.saveSettings(projectId.value, { ...settings })
    for (const key of Object.keys(settings)) delete settings[key]
    Object.assign(settings, result.settings)
    // 保存后立刻拿到新的健康快照与必填缺口，不需要再请求两次
    health.value = result.health
    missingFields.value = result.missingForExecute
    message.value = result.missingForExecute.length === 0
      ? t('pytestWorkbench.saved')
      : t('pytestWorkbench.savedWithMissing', {
        fields: result.missingForExecute.join(', '),
      })
  } catch (error) {
    // 校验失败是「可预期失败」，原因要原样展示；不要静默成「加载失败」
    message.value = String(error)
    try {
      saveErrors.value = await pytestProjectApi.validateSettings(projectId.value, { ...settings })
    } catch {
      saveErrors.value = []
    }
  } finally {
    saving.value = false
  }
}

/**
 * 抽取证据。先抽再查：抽取会覆盖写该 run 的证据行，查接口永远读最新一份，
 * 两段都成功才认为「看到的是刚抽的那一份」，不会出现「计数是新的、步骤是旧的」。
 */
async function extractEvidence(): Promise<void> {
  if (!evidenceRunId.value.trim()) {
    return
  }
  extracting.value = true
  message.value = ''
  try {
    evidenceResult.value = await pytestProjectApi.extractEvidence(
      projectId.value,
      evidenceRunId.value.trim(),
    )
    evidenceSteps.value = await pytestProjectApi.evidence(
      projectId.value,
      evidenceRunId.value.trim(),
    )
  } catch (error) {
    failed.value = true
    message.value = String(error)
  } finally {
    extracting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('pytestWorkbench.title') }}</h1>
      <p class="muted">{{ t('pytestWorkbench.subtitle') }}</p>
    </header>

    <p v-if="failed" class="notice notice-fail">{{ t('pytestWorkbench.failed') }}</p>
    <p v-if="message" class="notice notice-ok">{{ message }}</p>

    <section v-if="health" class="card">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.health') }}</h2>
        <div class="actions">
          <button class="btn" :disabled="loading" @click="reprobe">
            {{ t('pytestWorkbench.refresh') }}
          </button>
        </div>
      </div>
      <div class="badges">
        <span :class="['badge', health.healthy ? 'ok' : 'fail']">
          {{ health.healthy ? t('pytestWorkbench.healthy') : t('pytestWorkbench.unhealthy') }}
        </span>
        <span :class="['badge', health.repoReachable ? 'ok' : 'warn']">
          {{ t('pytestWorkbench.repo') }}: {{ health.repoPath ?? t('pytestWorkbench.repoMissing') }}
        </span>
        <span class="badge">
          {{ t('pytestWorkbench.interpreter') }}: {{ health.pythonBin ?? '—' }}
        </span>
        <span class="badge">
          pytest: {{ health.pytestVersion ?? '—' }}
        </span>
        <span :class="['badge', health.contractMissing.length === 0 ? 'ok' : 'fail']">
          {{ t('pytestWorkbench.contract') }}:
          {{ health.contractMissing.length === 0
            ? t('pytestWorkbench.contractOk')
            : t('pytestWorkbench.contractMissing', { items: health.contractMissing.join(', ') }) }}
        </span>
        <span class="badge">{{ t('pytestWorkbench.mode') }}: {{ health.modeDefault }}</span>
      </div>
      <ul v-if="health.hints.length" class="hints">
        <li v-for="hint in health.hints" :key="hint">{{ hint }}</li>
      </ul>
    </section>

    <section v-if="health" class="card" data-test="coverage">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.coverage') }}</h2>
      </div>
      <div class="calibers">
        <div v-for="item in calibers" :key="item.key" class="caliber" :data-test="`caliber-${item.key}`">
          <p class="caliber-label">{{ item.label }}</p>
          <p class="coverage">{{ item.percent }}%</p>
          <p class="muted small">
            {{ t('pytestWorkbench.coverageDetail', { bound: item.bound, total: item.total }) }}
          </p>
        </div>
      </div>
      <p class="muted small">
        {{ t('pytestWorkbench.coverageHint', {
          withPending: health.coveragePercent,
          withoutPending: health.coverageExcludingPendingPercent,
        }) }}
      </p>
      <p class="muted small" data-test="pending-note">
        {{ t('pytestWorkbench.pendingNote', {
          pending: health.pendingCases,
          total: health.totalExcludingPending,
          bound: health.boundExcludingPending,
        }) }}
      </p>
    </section>

    <section class="card" data-test="coordination">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.coordination') }}</h2>
        <button class="ghost" :disabled="loading" @click="loadLedger">
          {{ t('pytestWorkbench.refreshLedger') }}
        </button>
      </div>
      <p v-if="ledgerFailed" class="notice notice-fail" data-test="ledger-failed">
        {{ t('pytestWorkbench.ledgerFailed') }}
      </p>
      <template v-else-if="ledger">
        <p
          class="notice"
          :class="ledger.windowOpen ? 'notice-ok' : 'notice-fail'"
          data-test="window-state"
        >
          {{ ledger.windowOpen
            ? t('pytestWorkbench.windowOpen', { window: ledger.windowDescription })
            : t('pytestWorkbench.windowClosed', { window: ledger.windowDescription }) }}
          <span v-if="ledger.exclusive"> · {{ t('pytestWorkbench.exclusive') }}</span>
        </p>
        <dl class="facts">
          <div>
            <dt>{{ t('pytestWorkbench.ledgerRuns') }}</dt>
            <dd data-test="ledger-runs">{{ ledger.runsToday }}</dd>
          </div>
          <div>
            <dt>{{ t('pytestWorkbench.ledgerSuccess') }}</dt>
            <dd data-test="ledger-success">{{ ledger.successRatePercent }}%</dd>
          </div>
          <div>
            <dt>{{ t('pytestWorkbench.ledgerCases') }}</dt>
            <dd>{{ ledger.passedTests }} / {{ ledger.totalTests }}</dd>
          </div>
          <div>
            <dt>{{ t('pytestWorkbench.ledgerAvg') }}</dt>
            <dd>{{ formatDuration(ledger.avgDurationSeconds) }}</dd>
          </div>
          <div>
            <dt>{{ t('pytestWorkbench.ledgerActive') }}</dt>
            <dd>
              {{ t('pytestWorkbench.ledgerActiveValue', {
                running: ledger.running, queued: ledger.queued,
              }) }}
            </dd>
          </div>
        </dl>
        <p v-if="admission && !admission.allowed" class="notice notice-fail" data-test="admission">
          {{ admission.reason }} · {{ admission.hint }}
        </p>
        <p v-else-if="admission && admission.allowed" class="muted small">
          {{ t('pytestWorkbench.admissionOk') }}
        </p>
      </template>
    </section>

    <section v-if="lastSync" class="card" data-test="four-classes">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.fourClasses') }}</h2>
        <span :class="['badge', lastSync.balanced ? 'ok' : 'fail']" data-test="balanced">
          {{ lastSync.balanced
            ? t('pytestWorkbench.balanced')
            : t('pytestWorkbench.unbalanced') }}
        </span>
      </div>
      <div class="badges">
        <span class="badge">{{ t('pytestWorkbench.boundLabel') }}: {{ lastSync.bound }}</span>
        <span class="badge">{{ t('pytestWorkbench.unboundLabel') }}: {{ lastSync.unbound }}</span>
        <span class="badge">{{ t('pytestWorkbench.orphanLabel') }}: {{ lastSync.orphanScripts }}</span>
        <span class="badge">{{ t('pytestWorkbench.duplicateLabel') }}: {{ lastSync.duplicateBindings }}</span>
        <span class="badge" data-test="renamed">
          {{ t('pytestWorkbench.renamedLabel') }}: {{ lastSync.renamedScripts }}
        </span>
      </div>
      <ul v-if="lastSync.renamedPairs.length" class="hints" data-test="renamed-pairs">
        <li v-for="pair in lastSync.renamedPairs" :key="pair">{{ pair }}</li>
      </ul>
    </section>

    <section class="card">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.bindings') }}</h2>
        <div class="actions">
          <button class="btn" :disabled="syncing" @click="sync">
            {{ syncing ? t('pytestWorkbench.syncing') : t('pytestWorkbench.sync') }}
          </button>
        </div>
      </div>
      <p v-if="!bindings || bindings.items.length === 0" class="muted">
        {{ t('pytestWorkbench.empty') }}
      </p>
      <DataTable
        v-else
        :columns="bindingColumns"
        :data="bindings.items"
        :max-height="360"
      />
    </section>

    <section class="card" data-test="evidence">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.evidence') }}</h2>
        <div class="actions">
          <input
            v-model="evidenceRunId"
            class="input"
            data-test="evidence-run-id"
            :placeholder="t('pytestWorkbench.evidenceRunHint')"
          />
          <button class="btn" :disabled="extracting" data-test="evidence-extract" @click="extractEvidence">
            {{ extracting ? t('pytestWorkbench.extracting') : t('pytestWorkbench.extract') }}
          </button>
        </div>
      </div>
      <p class="muted small">{{ t('pytestWorkbench.evidenceHint') }}</p>

      <div v-if="evidenceResult" class="badges" data-test="evidence-summary">
        <span :class="['badge', evidenceResult.captured ? 'ok' : 'warn']">
          {{ evidenceResult.captured
            ? t('pytestWorkbench.captured')
            : t('pytestWorkbench.notCaptured') }}
        </span>
        <span class="badge">{{ t('pytestWorkbench.evidenceCases') }}: {{ evidenceResult.cases }}</span>
        <span class="badge">{{ t('pytestWorkbench.evidenceSteps') }}: {{ evidenceResult.stepCount }}</span>
        <span class="badge">{{ t('pytestWorkbench.evidenceCommands') }}: {{ evidenceResult.commandCount }}</span>
        <span class="badge">{{ t('pytestWorkbench.evidenceCaptured') }}: {{ evidenceResult.capturedCases }}</span>
      </div>
      <ul v-if="evidenceResult && evidenceResult.hints.length" class="hints" data-test="evidence-hints">
        <li v-for="hint in evidenceResult.hints" :key="hint">{{ hint }}</li>
      </ul>

      <section v-for="group in evidenceGroups" :key="group.nodeId" class="group" data-test="evidence-group">
        <header class="group-head">
          <span class="mono">{{ group.nodeId }}</span>
          <span v-if="group.fails" class="badge fail">FAIL {{ group.fails }}</span>
          <span v-if="group.skips" class="badge warn">SKIP {{ group.skips }}</span>
        </header>
        <ol class="steps">
          <li v-for="step in group.steps" :key="step.id" :class="['step', step.status?.toLowerCase() ?? '']">
            <span class="kind">{{ step.kind }}</span>
            <span v-if="step.label" class="label">{{ step.label }}</span>
            <span class="content mono">{{ step.content }}</span>
            <span v-if="step.stdoutPath" class="muted small">→ {{ step.stdoutPath }}</span>
          </li>
        </ol>
      </section>

      <p v-if="evidenceResult && evidenceGroups.length === 0" class="muted" data-test="evidence-empty">
        {{ t('pytestWorkbench.evidenceEmpty') }}
      </p>
    </section>

    <section class="card">
      <div class="card-head">
        <h2>{{ t('pytestWorkbench.settings') }}</h2>
        <div class="actions">
          <button class="btn primary" :disabled="saving" @click="save">
            {{ t('pytestWorkbench.save') }}
          </button>
        </div>
      </div>
      <p class="muted small">{{ t('pytestWorkbench.requiredHint') }}</p>
      <div class="form">
        <label v-for="field in SETTING_FIELDS" :key="field" class="field">
          <span class="field-label">{{ t(settingLabelKeys[field] ?? field) }}</span>
          <input v-model="settings[field]" class="input" :placeholder="field" />
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: var(--text-muted); font-size: 13px; margin: 0; }
.small { font-size: 12px; }
.card { border: 1px solid var(--border); border-radius: 8px; padding: 16px 18px; display: grid; gap: 12px; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-head h2 { margin: 0; font-size: 16px; }
.actions { display: flex; gap: 8px; }
.btn { border: 1px solid var(--border); background: var(--surface-2); color: var(--text); border-radius: 6px; padding: 6px 12px; font-size: 13px; cursor: pointer; }
.btn.primary { background: var(--accent); color: var(--gray-0); border-color: var(--accent); }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.badges { display: flex; flex-wrap: wrap; gap: 8px; }
.badge { border: 1px solid var(--border); border-radius: 999px; padding: 4px 10px; font-size: 12px; }
.badge.ok { border-color: var(--ok); color: var(--ok); }
.badge.warn { border-color: var(--warn); color: var(--warn); }
.badge.fail { border-color: var(--fail); color: var(--fail); }
.hints { margin: 0; padding-left: 18px; font-size: 13px; color: var(--text-muted); }
.coverage { font-size: 34px; font-weight: 600; margin: 0; }
.calibers { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
.caliber { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; display: grid; gap: 4px; }
.caliber-label { margin: 0; font-size: 13px; color: var(--text-muted); }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th, .table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); }
.mono { font-family: var(--font-mono, monospace); }
.form { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.field { display: grid; gap: 4px; }
.field-label { font-size: 12px; color: var(--text-muted); }
.input { border: 1px solid var(--border); border-radius: 6px; padding: 6px 10px; font-size: 13px; background: var(--surface-2); color: var(--text); }
.notice { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; margin: 0; }
.notice-fail { border-color: var(--fail); color: var(--fail); }
.notice-ok { border-color: var(--ok); color: var(--ok); }
.group { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; display: grid; gap: 8px; }
.group-head { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.steps { margin: 0; padding-left: 20px; display: grid; gap: 4px; font-size: 12px; }
.step { display: flex; flex-wrap: wrap; gap: 6px; align-items: baseline; }
.step.fail { color: var(--fail); }
.step.skip { color: var(--warn); }
.step.pass { color: var(--ok); }
.kind { border: 1px solid var(--border); border-radius: 4px; padding: 0 4px; font-size: 11px; color: var(--text-muted); }
.label { font-weight: 600; }
.content { color: var(--text-muted); word-break: break-all; }
</style>
