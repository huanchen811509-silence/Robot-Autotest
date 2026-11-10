<script setup lang="ts">
/**
 * 存量同步（v4.9.0 / FR-v4.9-01..07）。
 *
 * 三步闭环：预览（零写入）→ 逐条处理冲突 → 执行；执行后可整单回滚。
 * 预览只展示差异，不自动覆盖；`local_override` 与公共库已删的用例一律 SKIP。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import {
  projectSyncApi,
  type SyncItemRow,
  type SyncJobRow,
  type SyncPreview,
} from '@/api/projectSync'

const { t } = useI18n()
const auth = useAuthStore()

const projectId = computed(() => auth.projectId)
const preview = ref<SyncPreview | null>(null)
const jobs = ref<SyncJobRow[]>([])
const resolutions = ref<Record<string, string>>({})
const loading = ref(false)
const busy = ref(false)
const failed = ref('')
const notice = ref('')

const conflictItems = computed(
  () => preview.value?.items.filter((item) => item.action === 'CONFLICT') ?? [],
)
const hasPreview = computed(() => !!preview.value)

function actionLabel(action: string): string {
  return t(`sync.action.${action}`)
}

function resetResolutions(items: SyncItemRow[]): void {
  const next: Record<string, string> = {}
  for (const item of items) {
    if (item.action === 'CONFLICT') next[String(item.id)] = 'KEEP_LOCAL'
  }
  resolutions.value = next
}

async function loadJobs(): Promise<void> {
  if (!projectId.value) return
  try {
    jobs.value = await projectSyncApi.jobs(projectId.value, 20)
  } catch {
    jobs.value = []
  }
}

async function load(): Promise<void> {
  failed.value = ''
  if (!projectId.value) {
    failed.value = t('sync.noProject')
    return
  }
  loading.value = true
  try {
    await loadJobs()
  } finally {
    loading.value = false
  }
}

async function runPreview(): Promise<void> {
  if (!projectId.value) return
  busy.value = true
  failed.value = ''
  notice.value = ''
  try {
    const result = await projectSyncApi.preview(projectId.value)
    preview.value = result
    resetResolutions(result.items)
    await loadJobs()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : t('sync.previewFailed')
  } finally {
    busy.value = false
  }
}

async function execute(): Promise<void> {
  if (!projectId.value || !preview.value) return
  busy.value = true
  failed.value = ''
  notice.value = ''
  try {
    const result = await projectSyncApi.execute(
      projectId.value,
      preview.value.jobId,
      resolutions.value,
    )
    notice.value = t('sync.executed', {
      created: result.created,
      updated: result.updated,
      kept: result.conflictsKept,
      duplicated: result.duplicated,
    })
    preview.value = null
    await loadJobs()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : t('sync.executeFailed')
  } finally {
    busy.value = false
  }
}

async function rollback(job: SyncJobRow): Promise<void> {
  if (!projectId.value) return
  if (!window.confirm(t('sync.rollbackConfirm', { id: job.id }))) return
  busy.value = true
  failed.value = ''
  notice.value = ''
  try {
    const result = await projectSyncApi.rollback(projectId.value, job.id)
    notice.value = t('sync.rolledBack', { removed: result.removed, restored: result.restored })
    await loadJobs()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : t('sync.rollbackFailed')
  } finally {
    busy.value = false
  }
}

function statusLabel(status: string): string {
  return t(`sync.status.${status}`)
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('sync.title') }}</h1>
        <p class="muted">{{ t('sync.hint') }}</p>
      </div>
      <div class="head-actions">
        <button class="btn btn-primary" :disabled="busy || !projectId" @click="runPreview">
          {{ t('sync.preview') }}
        </button>
      </div>
    </header>

    <p v-if="failed" class="error">{{ failed }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>
    <p v-if="loading" class="muted">{{ t('global.loading') }}</p>

    <section v-if="hasPreview" class="panel">
      <header class="panel-head">
        <h2>{{ t('sync.previewTitle', { job: preview?.jobId }) }}</h2>
        <button class="btn" :disabled="busy" @click="execute">{{ t('sync.execute') }}</button>
      </header>
      <div class="summary">
        <span>{{ t('sync.total', { count: preview?.total }) }}</span>
        <span class="tag new">{{ t('sync.action.NEW') }} {{ preview?.newCount }}</span>
        <span class="tag update">{{ t('sync.action.UPDATE') }} {{ preview?.updateCount }}</span>
        <span class="tag conflict">
          {{ t('sync.action.CONFLICT') }} {{ preview?.conflictCount }}
        </span>
        <span class="tag">{{ t('sync.action.UNCHANGED') }} {{ preview?.unchangedCount }}</span>
        <span class="tag">{{ t('sync.action.SKIP') }} {{ preview?.skippedCount }}</span>
      </div>
      <p v-if="conflictItems.length" class="muted small">{{ t('sync.conflictHint') }}</p>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>{{ t('sync.column.externalId') }}</th>
              <th>{{ t('sync.column.title') }}</th>
              <th>{{ t('sync.column.category') }}</th>
              <th>{{ t('sync.column.action') }}</th>
              <th>{{ t('sync.column.resolution') }}</th>
              <th>{{ t('sync.column.message') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in preview?.items" :key="item.id">
              <td class="mono">{{ item.externalId }}</td>
              <td>{{ item.title }}</td>
              <td>{{ item.categoryL1 }}</td>
              <td>
                <span class="tag" :class="item.action.toLowerCase()">
                  {{ actionLabel(item.action) }}
                </span>
              </td>
              <td>
                <select
                  v-if="item.action === 'CONFLICT'"
                  v-model="resolutions[String(item.id)]"
                  class="select"
                >
                  <option value="KEEP_LOCAL">{{ t('sync.resolve.keepLocal') }}</option>
                  <option value="TAKE_REMOTE">{{ t('sync.resolve.takeRemote') }}</option>
                  <option value="DUPLICATE">{{ t('sync.resolve.duplicate') }}</option>
                </select>
                <span v-else class="muted">—</span>
              </td>
              <td class="muted small">{{ item.message }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <header class="panel-head">
        <h2>{{ t('sync.history') }}</h2>
        <button class="link" @click="loadJobs">{{ t('sync.refresh') }}</button>
      </header>
      <p v-if="jobs.length === 0" class="muted empty">{{ t('sync.noHistory') }}</p>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>#</th>
              <th>{{ t('sync.column.status') }}</th>
              <th>{{ t('sync.column.total') }}</th>
              <th>{{ t('sync.action.NEW') }}</th>
              <th>{{ t('sync.action.UPDATE') }}</th>
              <th>{{ t('sync.action.CONFLICT') }}</th>
              <th>{{ t('sync.column.createdAt') }}</th>
              <th>{{ t('sync.column.executedAt') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in jobs" :key="job.id">
              <td class="mono">{{ job.id }}</td>
              <td>
                <span class="tag">{{ statusLabel(job.status) }}</span>
              </td>
              <td>{{ job.total }}</td>
              <td>{{ job.newCount }}</td>
              <td>{{ job.updatedCount }}</td>
              <td>{{ job.conflictCount }}</td>
              <td class="muted small">{{ job.createdAt }}</td>
              <td class="muted small">{{ job.executedAt || '—' }}</td>
              <td>
                <button
                  v-if="job.status === 'DONE'"
                  class="link danger"
                  :disabled="busy"
                  @click="rollback(job)"
                >
                  {{ t('sync.rollback') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.page-head,
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.head-actions {
  display: flex;
  gap: var(--space-2);
}

.panel {
  margin-top: var(--space-4);
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-2) 0;
}

.tag {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
}

.tag.new {
  border-color: var(--ok);
  color: var(--ok);
}

.tag.update {
  border-color: var(--accent);
  color: var(--accent);
}

.tag.conflict {
  border-color: var(--fail);
  color: var(--fail);
}

.table-wrap {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
}

.table th,
.table td {
  text-align: left;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  vertical-align: top;
}

.select {
  padding: 4px 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

.notice {
  color: var(--ok);
}

.small {
  font-size: 12px;
}

.empty {
  padding: var(--space-3) 0;
}
</style>
