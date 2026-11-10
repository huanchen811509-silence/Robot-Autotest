<script setup lang="ts">
/**
 * 项目工作区（FR-v4.1-05）：导出 / 预检 / 导入 / 任务中心。
 *
 * 流程约束：
 * 1. 导入必须先预检（dry-run），预检不通过不允许点「执行导入」；
 * 2. 隔离区非空时要显式勾选确认，导入请求才带 confirmQuarantine；
 * 3. 任务行失败才出现「重试」，运行中的任务不提供重试入口（避免并发锁冲突）。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import {
  workspaceApi,
  type WorkspaceBundle,
  type WorkspaceGranularity,
  type WorkspaceJob,
  type WorkspaceReport,
  type WorkspaceReportItem,
  type WorkspaceStrategy,
} from '@/api/workspace'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const auth = useAuthStore()

const projectId = computed(() => auth.projectId)
const projectName = computed(() => auth.currentProject?.name ?? '')

const bundles = ref<WorkspaceBundle[]>([])
const jobs = ref<WorkspaceJob[]>([])
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')

const granularity = ref<WorkspaceGranularity>('PROJECT')
const rootPath = ref('')
const bundlePath = ref('')
const strategy = ref<WorkspaceStrategy>('SKIP')
const confirmQuarantine = ref(false)
const report = ref<WorkspaceReport | null>(null)
const reportMode = ref<'preflight' | 'import'>('preflight')
const busy = ref<'export' | 'preflight' | 'import' | 'retry' | ''>('')

async function loadAll(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    const [bundleRows, jobRows] = await Promise.all([
      workspaceApi.bundles(projectId.value),
      workspaceApi.jobs(projectId.value),
    ])
    bundles.value = bundleRows
    jobs.value = jobRows
  } catch (e) {
    error.value = localizeError(e)
    bundles.value = []
    jobs.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => void loadAll())

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = value
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

async function doExport(): Promise<void> {
  if (!projectId.value) return
  busy.value = 'export'
  error.value = null
  message.value = ''
  try {
    const result = await workspaceApi.exportBundle(projectId.value, {
      granularity: granularity.value,
      rootPath: granularity.value === 'PROJECT' ? undefined : rootPath.value.trim(),
    })
    report.value = result
    reportMode.value = 'import'
    message.value = result.ok ? t('workspaceUi.exported') : ''
    if (!result.ok) error.value = { summary: result.message || result.errorCode || '', detail: null }
    await loadAll()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    busy.value = ''
  }
}

async function doPreflight(): Promise<void> {
  if (!projectId.value) return
  if (!bundlePath.value.trim()) {
    message.value = t('workspaceUi.bundleRequired')
    return
  }
  busy.value = 'preflight'
  error.value = null
  message.value = ''
  try {
    const result = await workspaceApi.preflight(projectId.value, {
      bundlePath: bundlePath.value.trim(),
      strategy: strategy.value,
      confirmQuarantine: confirmQuarantine.value,
    })
    report.value = result
    reportMode.value = 'preflight'
    message.value = t('workspaceUi.preflightDone', {
      imported: result.filesImported,
      skipped: result.filesSkipped,
      quarantined: result.filesQuarantined,
    })
  } catch (e) {
    error.value = localizeError(e)
    report.value = null
  } finally {
    busy.value = ''
  }
}

async function doImport(): Promise<void> {
  if (!projectId.value) return
  if (!report.value || reportMode.value !== 'preflight' || !report.value.ok) {
    message.value = t('workspaceUi.preflightRequired')
    return
  }
  if (report.value.quarantine.length > 0 && !confirmQuarantine.value) {
    message.value = t('workspaceUi.quarantineConfirmRequired')
    return
  }
  busy.value = 'import'
  error.value = null
  message.value = ''
  try {
    const result = await workspaceApi.importBundle(projectId.value, {
      bundlePath: bundlePath.value.trim(),
      strategy: strategy.value,
      confirmQuarantine: confirmQuarantine.value,
    })
    report.value = result
    reportMode.value = 'import'
    message.value = result.ok ? t('workspaceUi.imported') : ''
    if (!result.ok) error.value = { summary: result.message || result.errorCode || '', detail: null }
    await loadAll()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    busy.value = ''
  }
}

async function retryJob(job: WorkspaceJob): Promise<void> {
  if (!projectId.value) return
  busy.value = 'retry'
  error.value = null
  message.value = ''
  try {
    const result = await workspaceApi.retry(projectId.value, job.id)
    report.value = result
    reportMode.value = 'import'
    message.value = t('workspaceUi.retried')
    await loadAll()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    busy.value = ''
  }
}

function pickBundle(bundle: WorkspaceBundle): void {
  bundlePath.value = bundle.path
  message.value = ''
  report.value = null
}

function statusLabel(status: string): string {
  const key = `workspaceUi.status${status.charAt(0)}${status.slice(1).toLowerCase()}`
  const label = t(key)
  return label === key ? status : label
}

function actionLabel(action: string): string {
  const key = `workspaceUi.action${action.charAt(0)}${action.slice(1).toLowerCase()}`
  const label = t(key)
  return label === key ? action : label
}

function bundleName(path: string): string {
  const parts = path.split(/[\\/]/)
  return parts[parts.length - 1] || path
}

const bundleColumns = computed<DataTableColumns<WorkspaceBundle>>(() => [
  {
    title: t('workspaceUi.bundlePath'),
    key: 'path',
    minWidth: 180,
    render: (row) => h('span', { title: row.path }, bundleName(row.path)),
  },
  { title: t('workspaceUi.granularity'), key: 'granularity', width: 110 },
  {
    title: t('workspaceUi.exportedAt'),
    key: 'exportedAt',
    width: 170,
    render: (row) => formatTime(row.exportedAt),
  },
  {
    title: t('workspaceUi.size'),
    key: 'bytes',
    width: 100,
    render: (row) => formatBytes(row.bytes),
  },
  { title: t('workspaceUi.files'), key: 'fileCount', width: 90 },
  {
    title: t('workspaceUi.signed'),
    key: 'signed',
    width: 100,
    render: (row) =>
      h(
        'span',
        { class: ['badge', row.signed ? 'badge-ok' : 'badge-warn'] },
        row.signed ? t('workspaceUi.signedYes') : t('workspaceUi.signedNo'),
      ),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 120,
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => pickBundle(row) },
        { default: () => t('workspaceUi.loadBundle') },
      ),
  },
])

const reportItemColumns = computed<DataTableColumns<WorkspaceReportItem>>(() => [
  { title: t('workspaceUi.category'), key: 'category', width: 120 },
  {
    title: t('workspaceUi.key'),
    key: 'key',
    minWidth: 200,
    ellipsis: { tooltip: true },
  },
  {
    title: t('workspaceUi.action'),
    key: 'action',
    width: 120,
    render: (row) => actionLabel(row.action),
  },
  {
    title: t('workspaceUi.reason'),
    key: 'reason',
    minWidth: 160,
    render: (row) => row.reason || '—',
  },
])

const jobColumns = computed<DataTableColumns<WorkspaceJob>>(() => [
  { title: t('workspaceUi.jobId'), key: 'id', width: 90 },
  { title: t('workspaceUi.jobType'), key: 'jobType', width: 140 },
  {
    title: t('workspaceUi.jobStatus'),
    key: 'status',
    width: 110,
    render: (row) =>
      h(
        'span',
        { class: ['badge', row.status === 'FAILED' ? 'badge-danger' : 'badge-ok'] },
        statusLabel(row.status),
      ),
  },
  { title: t('workspaceUi.jobProgress'), key: 'progress', width: 100, render: (row) => `${row.progress}%` },
  {
    title: t('workspaceUi.jobTarget'),
    key: 'targetPath',
    minWidth: 160,
    render: (row) => {
      const path = row.bundlePath || row.targetPath || ''
      return path ? h('span', { title: path }, bundleName(path)) : '—'
    },
  },
  {
    title: t('workspaceUi.jobStarted'),
    key: 'startedAt',
    width: 170,
    render: (row) => formatTime(row.startedAt || row.createdAt),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 110,
    render: (row) =>
      row.status === 'FAILED'
        ? h(
            NButton,
            { text: true, type: 'primary', size: 'small', loading: busy.value === 'retry', onClick: () => void retryJob(row) },
            { default: () => t('workspaceUi.retry') },
          )
        : h('span', null, '—'),
  },
])
</script>

<template>
  <div class="workspace-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ t('workspaceUi.title') }}</h1>
        <p class="page-subtitle">{{ t('workspaceUi.subtitle') }}</p>
        <p v-if="projectName" class="workspace-project">{{ projectName }}</p>
      </div>
      <div class="toolbar">
        <button type="button" class="btn" :disabled="loading || !projectId" @click="loadAll">
          {{ t('common.refresh') }}
        </button>
      </div>
    </div>

    <p v-if="!projectId" class="empty-box">{{ t('workspaceUi.noProject') }}</p>

    <template v-else>
      <ErrorLineView v-if="error" :error="error" />
      <p v-if="message" class="workspace-message" role="status">{{ message }}</p>

      <section class="card workspace-card">
        <h2 class="card-title">{{ t('workspaceUi.exportTitle') }}</h2>
        <p class="workspace-hint">{{ t('workspaceUi.exportHint') }}</p>
        <div class="workspace-form">
          <label class="workspace-field">
            <span>{{ t('workspaceUi.granularity') }}</span>
            <select v-model="granularity" class="input wide">
              <option value="PROJECT">{{ t('workspaceUi.granularityProject') }}</option>
              <option value="DIR">{{ t('workspaceUi.granularityDir') }}</option>
              <option value="FILE">{{ t('workspaceUi.granularityFile') }}</option>
            </select>
          </label>
          <label v-if="granularity !== 'PROJECT'" class="workspace-field">
            <span>{{ t('workspaceUi.rootPath') }}</span>
            <input
              v-model="rootPath"
              class="input wide"
              type="text"
              autocomplete="off"
              :placeholder="t('workspaceUi.rootPathPlaceholder')"
            />
          </label>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="busy === 'export'"
            @click="doExport"
          >
            {{ busy === 'export' ? t('workspaceUi.exporting') : t('workspaceUi.export') }}
          </button>
        </div>
        <h3 class="workspace-subtitle">{{ t('workspaceUi.bundlesTitle') }}</h3>
        <p v-if="loading" class="workspace-hint">{{ t('common.loading') }}</p>
        <p v-else-if="bundles.length === 0" class="empty-box">{{ t('workspaceUi.bundlesEmpty') }}</p>
        <div v-else class="table-scroll">
          <DataTable
            :columns="bundleColumns"
            :data="bundles"
            :loading="loading"
            :row-key="(row: WorkspaceBundle) => row.path"
            :max-height="null"
          />
        </div>
      </section>

      <section class="card workspace-card">
        <h2 class="card-title">{{ t('workspaceUi.importTitle') }}</h2>
        <p class="workspace-hint">{{ t('workspaceUi.importHint') }}</p>
        <div class="workspace-form">
          <label class="workspace-field workspace-field--wide">
            <span>{{ t('workspaceUi.bundlePathLabel') }}</span>
            <input
              v-model="bundlePath"
              class="input wide"
              type="text"
              autocomplete="off"
              :placeholder="t('workspaceUi.bundlePathPlaceholder')"
            />
          </label>
          <label class="workspace-field">
            <span>{{ t('workspaceUi.strategy') }}</span>
            <select v-model="strategy" class="input wide">
              <option value="SKIP">{{ t('workspaceUi.strategySkip') }}</option>
              <option value="OVERWRITE">{{ t('workspaceUi.strategyOverwrite') }}</option>
              <option value="RENAME">{{ t('workspaceUi.strategyRename') }}</option>
            </select>
          </label>
          <button
            type="button"
            class="btn"
            :disabled="busy === 'preflight'"
            @click="doPreflight"
          >
            {{ busy === 'preflight' ? t('workspaceUi.preflighting') : t('workspaceUi.preflight') }}
          </button>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="busy === 'import' || !report || !report.ok || reportMode !== 'preflight'"
            @click="doImport"
          >
            {{ busy === 'import' ? t('workspaceUi.importing') : t('workspaceUi.import') }}
          </button>
        </div>
        <label v-if="report && report.quarantine.length > 0" class="workspace-check">
          <input v-model="confirmQuarantine" type="checkbox" />
          <span>{{ t('workspaceUi.confirmQuarantine') }}</span>
        </label>
        <p class="workspace-hint">{{ t('workspaceUi.noPermissionHint') }}</p>

        <div v-if="report" class="workspace-report">
          <h3 class="workspace-subtitle">
            {{ t('workspaceUi.reportTitle') }}
            <span class="badge" :class="report.ok ? 'badge-ok' : 'badge-danger'">
              {{ report.ok ? t('workspaceUi.reportOk') : t('workspaceUi.reportFail') }}
            </span>
          </h3>
          <p v-if="report.errorCode" class="workspace-hint">
            {{ t('workspaceUi.errorCode') }}: {{ report.errorCode }}
          </p>
          <p v-if="report.message" class="workspace-hint">{{ report.message }}</p>
          <p class="workspace-hint">
            {{
              t('workspaceUi.reportCounts', {
                imported: report.filesImported,
                skipped: report.filesSkipped,
                quarantined: report.filesQuarantined,
              })
            }}
          </p>
          <div v-if="report.warnings.length" class="workspace-warnings">
            <p v-for="line in report.warnings" :key="line">{{ line }}</p>
          </div>
          <div v-if="report.items.length" class="table-scroll">
            <DataTable
              :columns="reportItemColumns"
              :data="report.items"
              :row-key="(row: WorkspaceReportItem) => `${row.category}-${row.key}`"
              :max-height="null"
            />
          </div>
          <div v-if="report.quarantine.length" class="workspace-quarantine">
            <h4>{{ t('workspaceUi.quarantine') }}</h4>
            <ul>
              <li v-for="entry in report.quarantine" :key="entry.path">
                <span class="workspace-path">{{ entry.path }}</span>
                <span class="workspace-hint">{{ entry.findings.join('；') }}</span>
              </li>
            </ul>
          </div>
        </div>
      </section>

      <section class="card workspace-card">
        <h2 class="card-title">{{ t('workspaceUi.jobsTitle') }}</h2>
        <p v-if="jobs.length === 0" class="empty-box">{{ t('workspaceUi.jobsEmpty') }}</p>
        <div v-else class="table-scroll">
          <DataTable
            :columns="jobColumns"
            :data="jobs"
            :loading="loading"
            :max-height="null"
          />
        </div>
        <p v-if="jobs.some((job) => job.errorMessage)" class="workspace-warnings">
          {{ t('workspaceUi.jobErrors') }}
        </p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.workspace-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.workspace-project {
  margin: var(--space-1) 0 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.workspace-card {
  padding: var(--space-4);
}

.workspace-hint {
  margin: var(--space-1) 0 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.workspace-message {
  margin: 0;
  color: var(--text);
}

.workspace-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.workspace-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--font-size-sm);
}

.workspace-field--wide {
  flex: 1 1 320px;
}

.workspace-subtitle {
  margin: var(--space-4) 0 var(--space-2);
  font-size: var(--font-size-md);
}

.workspace-path {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-check {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  font-size: var(--font-size-sm);
}

.workspace-report {
  margin-top: var(--space-3);
}

.workspace-warnings {
  margin: var(--space-2) 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.workspace-quarantine ul {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: var(--space-2) 0 0;
  padding-left: var(--space-4);
}

.workspace-quarantine li {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
</style>
