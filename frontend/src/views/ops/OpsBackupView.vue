<script setup lang="ts">
/**
 * 运维 · 备份与恢复（v5.16.0 / FR-v5.16-06~07）。
 *
 * 三件事：清单（真实目录扫描 + SHA-256 三态）、新建（先预演再执行）、
 * 恢复演练（只到 `_it` 库）+ 源库/演练库行数比对。
 * 「找历史数据」区块复用既有只读找回页，不另写一份检索。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, NTag, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import ReadOnlyRecoverView from '@/views/maintenance/ReadOnlyRecoverView.vue'
import { useAuthStore } from '@/stores/auth'
import { opsApi, opsConsole } from '@/api/opsV516'
import type { BackupItem, CompareView, OpsTask } from '@/api/opsV516'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const rows = ref<BackupItem[]>([])
const loading = ref(false)
const errorText = ref('')
const notice = ref('')
const sourceDb = ref('')
const targetDb = ref('')
const compare = ref<CompareView | null>(null)
const plan = ref<string[]>([])
const tasks = ref<OpsTask[]>([])
const openLog = ref<number | null>(null)

const columns = computed<DataTableColumns<BackupItem>>(() => [
  { title: t('opsV516.backup.colName'), key: 'name', ellipsis: { tooltip: true } },
  {
    title: t('opsV516.backup.colSize'),
    key: 'sizeBytes',
    width: 120,
    render: (row) => `${Math.round(row.sizeBytes / 1024)} KiB`,
  },
  { title: t('opsV516.backup.colModified'), key: 'modifiedAt', width: 200 },
  {
    title: t('opsV516.backup.colSha'),
    key: 'sha256State',
    width: 130,
    render: (row) =>
      h(NTag, { size: 'small', type: row.sha256State === 'VERIFIED' ? 'success' : 'warning' },
        { default: () => row.sha256State }),
  },
  { title: t('opsV516.backup.colVerify'), key: 'verifyState', width: 130 },
])

/**
 * 任务历史列（W66-05）：状态 / 阶段 / 进度 / 类型。日志不进表格列，
 * 单行展开看全文并支持下载成 .log —— 长日志塞进单元格会把表格撑变形。
 */
const taskColumns = computed<DataTableColumns<OpsTask>>(() => [
  { title: t('opsV516.task.colId'), key: 'id', width: 80 },
  { title: t('opsV516.task.colType'), key: 'taskType', width: 160 },
  { title: t('opsV516.task.colStatus'), key: 'status', width: 110 },
  { title: t('opsV516.task.colPhase'), key: 'phase', width: 120 },
  {
    title: t('opsV516.task.colProgress'),
    key: 'progress',
    width: 100,
    render: (row) => `${row.progress}%`,
  },
])

async function load() {
  if (!projectId.value) return
  loading.value = true
  errorText.value = ''
  try {
    rows.value = (await opsApi.backups(projectId.value)) as unknown as BackupItem[]
    tasks.value = await opsApi.tasks(projectId.value, 20)
  } catch {
    errorText.value = t('opsV516.common.loadFailed')
  } finally {
    loading.value = false
  }
}

/** 日志下载：内容来自真实 `log_text`，为空就不给下载按钮，不导出空文件冒充有日志。 */
function downloadLog(row: OpsTask) {
  const blob = new Blob([row.logText ?? ''], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `ops-task-${row.id}.log`
  link.click()
  URL.revokeObjectURL(url)
}

async function previewBackup() {
  if (!projectId.value) return
  plan.value = []
  notice.value = ''
  try {
    const created = await opsApi.createTask(projectId.value, { taskType: 'BACKUP', dryRun: true })
    plan.value = created.plan ?? []
    notice.value = t('opsV516.backup.previewDone')
  } catch {
    errorText.value = t('opsV516.common.actionFailed')
  }
}

async function runDrill() {
  if (!projectId.value) return
  notice.value = ''
  try {
    const result = await opsApi.restoreDrill(projectId.value, targetDb.value || 'robot_it', true)
    notice.value = `${t('opsV516.backup.drillAllowed')} ${String(result.command ?? '')}`
  } catch {
    notice.value = t('opsV516.backup.drillRefused')
  }
}

async function runCompare() {
  if (!projectId.value) return
  compare.value = null
  notice.value = ''
  try {
    compare.value = await opsConsole.compare(projectId.value, sourceDb.value, targetDb.value)
    notice.value = compare.value.consistent ? t('opsV516.backup.consistent') : t('opsV516.backup.mismatched')
  } catch {
    notice.value = t('opsV516.backup.compareRefused')
  }
}

onMounted(load)
</script>

<template>
  <section class="ops-page">
    <header class="ops-head">
      <h2>{{ t('opsV516.backup.title') }}</h2>
      <NButton size="small" :loading="loading" @click="load">{{ t('opsV516.common.refresh') }}</NButton>
    </header>
    <p class="ops-desc">{{ t('opsV516.backup.desc') }}</p>
    <p v-if="errorText" class="ops-error">{{ errorText }}</p>
    <p v-if="notice" class="ops-notice">{{ notice }}</p>

    <div class="ops-row">
      <NButton size="small" @click="previewBackup">{{ t('opsV516.backup.preview') }}</NButton>
      <span class="ops-muted">{{ t('opsV516.backup.previewHint') }}</span>
    </div>
    <ul v-if="plan.length" class="ops-plan">
      <li v-for="step in plan" :key="step">{{ step }}</li>
    </ul>

    <DataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: BackupItem) => row.name"
      :empty-title="t('opsV516.backup.empty')"
      :empty-description="t('opsV516.backup.emptyDesc')"
    />

    <h3 class="ops-sub">{{ t('opsV516.backup.drill') }}</h3>
    <p class="ops-muted">{{ t('opsV516.backup.drillHint') }}</p>
    <div class="ops-row">
      <NInput v-model:value="targetDb" :placeholder="t('opsV516.backup.targetPlaceholder')" style="max-width: 240px" />
      <NButton size="small" @click="runDrill">{{ t('opsV516.backup.drillRun') }}</NButton>
    </div>

    <h3 class="ops-sub">{{ t('opsV516.backup.compare') }}</h3>
    <div class="ops-row">
      <NInput v-model:value="sourceDb" :placeholder="t('opsV516.backup.sourcePlaceholder')" style="max-width: 240px" />
      <NInput v-model:value="targetDb" :placeholder="t('opsV516.backup.targetPlaceholder')" style="max-width: 240px" />
      <NButton size="small" @click="runCompare">{{ t('opsV516.backup.compareRun') }}</NButton>
    </div>
    <div v-if="compare" class="ops-muted">
      {{ t('opsV516.backup.compared') }} {{ compare.comparedTables }} ·
      <NTag size="small" :type="compare.consistent ? 'success' : 'warning'">
        {{ compare.consistent ? t('opsV516.backup.consistent') : t('opsV516.backup.mismatched') }}
      </NTag>
      <ul v-if="compare.mismatched.length">
        <li v-for="row in compare.mismatched" :key="row.table">
          {{ row.table }} {{ row.sourceRows }} → {{ row.targetRows }} ({{ row.delta }})
        </li>
      </ul>
    </div>

    <h3 class="ops-sub">{{ t('opsV516.task.title') }}</h3>
    <p class="ops-muted">{{ t('opsV516.task.desc') }}</p>
    <DataTable
      :columns="taskColumns"
      :data="tasks"
      :loading="loading"
      :row-key="(row: OpsTask) => row.id"
      :empty-title="t('opsV516.task.empty')"
      :empty-description="t('opsV516.task.emptyDesc')"
    />
    <div v-for="row in tasks" :key="'log-' + row.id" class="ops-logrow">
      <NButton size="tiny" quaternary @click="openLog = openLog === row.id ? null : row.id">
        {{ openLog === row.id ? t('opsV516.common.hide') : t('opsV516.task.viewLog') }}
      </NButton>
      <NButton v-if="row.logText" size="tiny" quaternary @click="downloadLog(row)">
        {{ t('opsV516.task.downloadLog') }}
      </NButton>
      <pre v-if="openLog === row.id" class="ops-pre">{{ row.logText || t('opsV516.task.noLog') }}</pre>
    </div>

    <h3 class="ops-sub">{{ t('opsV516.backup.history') }}</h3>
    <ReadOnlyRecoverView />
  </section>
</template>

<style scoped>
.ops-page { display: flex; flex-direction: column; gap: 12px; }
.ops-head { display: flex; align-items: center; justify-content: space-between; }
.ops-desc { color: var(--text-muted); margin: 0; }
.ops-sub { margin: 12px 0 0; }
.ops-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ops-muted { color: var(--text-muted); font-size: 12px; }
.ops-error { color: var(--fail); margin: 0; }
.ops-notice { color: var(--accent); margin: 0; }
.ops-plan { margin: 0; padding-left: 18px; font-size: 12px; color: var(--text-muted); }
.ops-logrow { display: flex; align-items: flex-start; gap: 8px; flex-wrap: wrap; }
.ops-pre { background: var(--surface-2); padding: 8px; border-radius: var(--radius-sm); overflow-x: auto; width: 100%; margin: 0; }
</style>
