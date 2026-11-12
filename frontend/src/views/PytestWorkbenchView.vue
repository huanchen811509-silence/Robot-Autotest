<script setup lang="ts">
/**
 * v5.1.0 · pytest 工程工作台（FR-v5.1-05 / FR-v5.1-12）。
 *
 * 一个页面回答四个问题：能不能跑（健康）、接上了多少（覆盖率）、哪些接上了（绑定）、
 * 配置对不对（工程配置）。刻意不做「多层 tab」：这四件事互相印证，
 * 分开看会让人反复切页面对数字。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  pytestProjectApi,
  type BindingStats,
  type EvidenceExtractResult,
  type PytestCaseStep,
  type PytestHealth,
  type SyncReport,
} from '@/api/pytestProject'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const health = ref<PytestHealth | null>(null)
const bindings = ref<BindingStats | null>(null)
const settings = reactive<Record<string, string>>({})
const loading = ref(false)
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

const settingLabels: Record<string, string> = {
  repo_path: '仓库路径（执行机本地）',
  python_bin: 'pytest 解释器',
  env_config: 'run_env 模板',
  inventory_path: 'inventory 模板',
  repo_url: '仓库地址',
  branch: '分支 / tag',
  mode_default: '默认模式（fake / real）',
  inventory_key_rule: 'host_key 规则（hostname / hostname@ip）',
  fake_plan_file: 'Fake 计划文件',
  extra_args: '追加参数',
  timeout_s: 'run 级超时（秒）',
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

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    health.value = await pytestProjectApi.health(projectId.value)
    bindings.value = await pytestProjectApi.bindings(projectId.value)
    const raw = await pytestProjectApi.settings(projectId.value)
    for (const key of Object.keys(settings)) delete settings[key]
    Object.assign(settings, raw)
  } catch {
    failed.value = true
  } finally {
    loading.value = false
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

async function save(): Promise<void> {
  saving.value = true
  try {
    const saved = await pytestProjectApi.saveSettings(projectId.value, { ...settings })
    for (const key of Object.keys(settings)) delete settings[key]
    Object.assign(settings, saved)
    message.value = t('pytestWorkbench.saved')
    await load()
  } catch {
    failed.value = true
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
      <table v-else class="table">
        <thead>
          <tr>
            <th>{{ t('pytestWorkbench.scriptPath') }}</th>
            <th>{{ t('pytestWorkbench.nodeId') }}</th>
            <th>{{ t('pytestWorkbench.source') }}</th>
            <th>{{ t('pytestWorkbench.verified') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in bindings.items" :key="item.id">
            <td class="mono">{{ item.scriptPath }}</td>
            <td class="mono muted">{{ item.nodeId ?? '—' }}</td>
            <td>{{ item.resolveSource }}</td>
            <td>{{ item.verified ? '✓' : '—' }}</td>
          </tr>
        </tbody>
      </table>
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
          <span class="field-label">{{ settingLabels[field] ?? field }}</span>
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
.btn { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 6px; padding: 6px 12px; font-size: 13px; cursor: pointer; }
.btn.primary { background: var(--accent); color: #fff; border-color: var(--accent); }
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
.form { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.field { display: grid; gap: 4px; }
.field-label { font-size: 12px; color: var(--text-muted); }
.input { border: 1px solid var(--border); border-radius: 6px; padding: 6px 10px; font-size: 13px; background: var(--surface); color: var(--text); }
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
