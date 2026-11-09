<script setup lang="ts">
/**
 * 归档中心（v3.4.0 / FR-3.4-01）。
 *
 * 冷热分层的关键约束都在界面上体现：
 * ① 默认只试运行，真正搬数据要二次确认；
 * ② 策略未启用时执行按钮直接禁用（后端也会拒绝）；
 * ③ 恢复按原主键插回，冲突要显式报错而不是静默丢数据。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  archiveApi,
  type ArchiveExecuteResult,
  type ArchivePolicy,
  type ArchivePreview,
  type ArchivedRunInfo,
  type ColdResult,
} from '@/api/archive'
import { ApiError } from '@/api/http'
import { confirmAction } from '@/composables/useConfirm'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const policy = ref<ArchivePolicy | null>(null)
const preview = ref<ArchivePreview | null>(null)
const result = ref<ArchiveExecuteResult | null>(null)
const runs = ref<ArchivedRunInfo[]>([])
const cold = ref<ColdResult[]>([])
const selectedRun = ref('')
const loading = ref(false)
const busy = ref(false)
const errorText = ref('')
const notice = ref('')

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  errorText.value = ''
  try {
    const [policyData, runData] = await Promise.all([
      archiveApi.policy(projectId.value),
      archiveApi.runs(projectId.value),
    ])
    policy.value = policyData
    runs.value = runData
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('archive.loadFailed')
  } finally {
    loading.value = false
  }
}

async function savePolicy(): Promise<void> {
  if (!policy.value) return
  busy.value = true
  notice.value = ''
  try {
    policy.value = await archiveApi.savePolicy({ ...policy.value, projectId: projectId.value })
    notice.value = t('archive.saved')
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('archive.loadFailed')
  } finally {
    busy.value = false
  }
}

async function runPreview(): Promise<void> {
  busy.value = true
  notice.value = ''
  try {
    preview.value = await archiveApi.preview(projectId.value)
    result.value = null
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('archive.loadFailed')
  } finally {
    busy.value = false
  }
}

async function execute(dryRun: boolean): Promise<void> {
  if (dryRun) {
    result.value = await archiveApi.run(projectId.value, true)
    return
  }
  if (!(await confirmAction(t('archive.executeConfirm'), { tone: 'danger' }))) return
  busy.value = true
  notice.value = ''
  try {
    result.value = await archiveApi.run(projectId.value, false)
    notice.value = t('archive.executed', {
      runs: result.value.archivedRuns,
      tests: result.value.movedTestResults,
      skipped: result.value.skipped.length,
    })
    preview.value = null
    await load()
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('archive.loadFailed')
  } finally {
    busy.value = false
  }
}

async function openRun(run: ArchivedRunInfo): Promise<void> {
  selectedRun.value = run.runId
  cold.value = []
  try {
    cold.value = await archiveApi.coldResults(projectId.value, run.runId)
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('archive.loadFailed')
  }
}

async function restore(run: ArchivedRunInfo): Promise<void> {
  if (!(await confirmAction(t('archive.restoreConfirm'), { tone: 'danger' }))) return
  busy.value = true
  notice.value = ''
  try {
    const restored = await archiveApi.restore(projectId.value, run.runId)
    notice.value = t('archive.restored', {
      tests: restored.restoredTestResults,
      keywords: restored.restoredKeywordResults,
    })
    cold.value = []
    selectedRun.value = ''
    await load()
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('archive.restoreConflict')
  } finally {
    busy.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="page" data-testid="archive-center">
    <header class="page-head">
      <div>
        <h1>{{ t('archive.title') }}</h1>
        <p class="muted">{{ t('archive.subtitle') }}</p>
      </div>
      <button class="btn" type="button" :disabled="loading" @click="load">
        {{ t('archive.refresh') }}
      </button>
    </header>

    <p v-if="loading" class="muted">{{ t('common.loading') }}</p>
    <p v-if="errorText" class="error" data-testid="archive-error">{{ errorText }}</p>
    <p v-if="notice" class="notice" data-testid="archive-notice">{{ notice }}</p>

    <section v-if="policy" class="card" data-testid="archive-policy">
      <h2 class="card-title">{{ t('archive.policy') }}</h2>
      <p class="muted">{{ t('archive.retentionHint') }}</p>
      <div class="form">
        <label class="field">
          <span class="muted">{{ t('archive.enabled') }}</span>
          <input v-model="policy.enabled" type="checkbox" data-testid="policy-enabled" />
        </label>
        <label class="field">
          <span class="muted">{{ t('archive.retentionDays') }}</span>
          <input v-model.number="policy.retentionDays" class="input" type="number" min="1" />
        </label>
        <label class="field">
          <span class="muted">{{ t('archive.batchSize') }}</span>
          <input v-model.number="policy.batchSize" class="input" type="number" min="1" />
        </label>
        <button class="btn" type="button" :disabled="busy" @click="savePolicy">
          {{ t('archive.savePolicy') }}
        </button>
      </div>
      <p v-if="!policy.enabled" class="error">{{ t('archive.policyDisabled') }}</p>
      <div class="form">
        <button class="btn btn-ghost" type="button" :disabled="busy" @click="runPreview">
          {{ t('archive.preview') }}
        </button>
        <button class="btn btn-ghost" type="button" :disabled="busy" @click="execute(true)">
          {{ t('archive.dryRun') }}
        </button>
        <button
          class="btn"
          type="button"
          data-testid="archive-execute"
          :disabled="busy || !policy.enabled"
          @click="execute(false)"
        >
          {{ t('archive.execute') }}
        </button>
      </div>
      <p class="muted">{{ t('archive.dryRunHint') }}</p>
    </section>

    <section v-if="preview" class="card" data-testid="archive-preview">
      <h2 class="card-title">{{ t('archive.previewTitle') }}</h2>
      <ul class="list">
        <li v-for="item in preview.items" :key="item.runId">
          {{ item.runId }} · {{ t('archive.detailCount', { tests: item.testResults, keywords: item.keywordResults }) }}
        </li>
        <li v-if="!preview.items.length" class="muted">{{ t('archive.noCandidates') }}</li>
      </ul>
    </section>

    <p v-if="result" class="notice" data-testid="archive-result">
      {{ t('archive.executed', {
        runs: result.archivedRuns,
        tests: result.movedTestResults,
        skipped: result.skipped.length,
      }) }}
    </p>

    <section class="card" data-testid="archive-runs">
      <h2 class="card-title">{{ t('archive.runs') }}</h2>
      <ul class="list">
        <li v-for="run in runs" :key="run.id" data-testid="archive-run">
          <button class="btn btn-ghost" type="button" @click="openRun(run)">
            {{ run.runId }}
          </button>
          <span class="muted">
            {{ t('archive.detailCount', {
              tests: run.testResultCount,
              keywords: run.keywordResultCount,
            }) }}
            · {{ t('archive.archivedAt') }} {{ run.archivedAt }}
            <template v-if="run.restoredAt">
              · {{ t('archive.restoredAt') }} {{ run.restoredAt }}
            </template>
          </span>
          <button
            class="btn btn-ghost"
            type="button"
            :disabled="busy"
            data-testid="archive-restore"
            @click="restore(run)"
          >
            {{ t('archive.restore') }}
          </button>
        </li>
        <li v-if="!runs.length" class="muted">{{ t('archive.noRuns') }}</li>
      </ul>

      <template v-if="selectedRun">
        <h3>{{ t('archive.coldResults') }} · {{ selectedRun }}</h3>
        <ul class="list">
          <li v-for="item in cold" :key="item.id" data-testid="archive-cold">
            {{ item.caseId ?? '-' }} · {{ item.verdict }} · {{ item.durationMs ?? 0 }}ms ·
            {{ item.failureKind || '-' }} · {{ item.failureMsg }}
          </li>
          <li v-if="!cold.length" class="muted">{{ t('archive.emptyCold') }}</li>
        </ul>
      </template>
    </section>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.card {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
}
.card-title { margin: 0 0 8px; font-size: 15px; }
.form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px; margin: 10px 0; }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 12px; }
.input {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel);
  color: var(--text);
  font-size: 13px;
  width: 100px;
}
.list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.list .muted { margin-left: 6px; }
.muted { color: var(--text-muted); }
.error { color: var(--fail); }
.notice { color: var(--ok); }
</style>
