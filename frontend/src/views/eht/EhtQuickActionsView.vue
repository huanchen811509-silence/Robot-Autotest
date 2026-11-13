<script setup lang="ts">
/**
 * EHT-12 / FR-v5.5-14,15：便捷操作聚合 + 执行进度 / 错误原因。
 *
 * 四个动作都调真实端点，失败时展示原因码与人话摘要；没有本地假数据。
 * 「一键执行」先过准入预检，不通过时按钮不可用（不静默提交）。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ehtViewsApi } from '@/api/ehtViews'
import { pytestProjectApi, type SyncReport } from '@/api/pytestProject'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)
const syncReport = ref<SyncReport | null>(null)
const admission = ref<Record<string, unknown> | null>(null)
const chapter = ref<Record<string, unknown> | null>(null)
const settings = ref<Record<string, string>>({})
const message = ref('')
const busy = ref(false)

async function load(): Promise<void> {
  if (!projectId.value) {
    return
  }
  try {
    const [a, s, c] = await Promise.all([
      ehtViewsApi.admission(projectId.value),
      pytestProjectApi.settings(projectId.value),
      ehtViewsApi.failureChapter(projectId.value),
    ])
    admission.value = a
    settings.value = s
    chapter.value = c
  } catch {
    message.value = t('eht.common.loadFailed')
  }
}
onMounted(load)

const allowed = computed(() => admission.value?.allowed === true)

async function runSync(): Promise<void> {
  busy.value = true
  message.value = ''
  try {
    syncReport.value = await pytestProjectApi.sync(projectId.value)
    message.value = t('eht.quickActions.syncDone', { count: syncReport.value?.bound ?? 0 })
  } catch {
    message.value = t('eht.quickActions.syncFailed')
  } finally {
    busy.value = false
  }
}

async function checkAdmission(): Promise<void> {
  busy.value = true
  try {
    admission.value = await ehtViewsApi.admission(projectId.value)
    message.value = allowed.value
      ? t('eht.quickActions.admissionOk')
      : t('eht.quickActions.admissionDenied', { reason: String(admission.value?.reason ?? '—') })
  } catch {
    message.value = t('eht.common.loadFailed')
  } finally {
    busy.value = false
  }
}

function commandText(): string {
  const s = settings.value
  return [s.python_bin ?? '', '-m pytest',
    '--env_config ' + (s.env_config ?? ''), '--mode ' + (s.mode_default ?? 'fake'),
    '--capture=fd', '-o junit_logging=out-err', '-o junit_family=xunit2'].join(' ')
}

async function copyCommand(): Promise<void> {
  try {
    await navigator.clipboard.writeText(commandText())
    message.value = t('eht.quickActions.copied')
  } catch {
    message.value = t('eht.quickActions.copyFailed')
  }
}
</script>

<template>
  <section class="eht-page">
    <header class="eht-page__head">
      <h1>{{ t('eht.quickActions.title') }}</h1>
    </header>
    <p class="eht-page__note">
      {{ t('eht.common.source') }}：/pytest/bindings/sync · /execution-coordination/admission ·
      /runs/{id}/failure-breakdown/chapter
    </p>
    <div class="eht-actions">
      <button type="button" :disabled="busy" @click="runSync">{{ t('eht.quickActions.sync') }}</button>
      <button type="button" :disabled="busy" @click="checkAdmission">{{ t('eht.quickActions.precheck') }}</button>
      <button type="button" :disabled="busy" @click="copyCommand">{{ t('eht.quickActions.copy') }}</button>
      <RouterLink class="eht-link" to="/execution-plans">{{ t('eht.quickActions.launch') }}</RouterLink>
    </div>
    <p class="eht-message">{{ message }}</p>
    <div class="eht-block">
      <h2>{{ t('eht.quickActions.progress') }}</h2>
      <p class="eht-muted">{{ t('eht.quickActions.admission') }}：{{ admission?.allowed ?? '—' }}</p>
      <p class="eht-muted">{{ t('eht.quickActions.window') }}：{{ admission?.windowDescription ?? '—' }}</p>
      <p class="eht-muted">{{ t('eht.quickActions.bound') }}：{{ syncReport?.bound ?? '—' }}</p>
    </div>
    <div class="eht-block">
      <h2>{{ t('eht.quickActions.failureReason') }}</h2>
      <p class="eht-muted">{{ chapter?.title ?? '—' }} · {{ chapter?.status ?? '—' }}</p>
      <p class="eht-muted">{{ chapter?.summary ?? t('eht.common.empty') }}</p>
      <pre class="eht-code">{{ commandText() }}</pre>
    </div>
  </section>
</template>

<style scoped>
.eht-page { padding: 24px 28px; display: grid; gap: 16px; }
.eht-page__head h1 { margin: 0; font-size: 22px; }
.eht-page__note { margin: 0; font-size: 13px; color: var(--text-muted); }
.eht-actions { display: flex; gap: 12px; flex-wrap: wrap; }
.eht-actions button, .eht-link { padding: 6px 14px; border: 1px solid var(--border); border-radius: 6px; background: transparent; cursor: pointer; font-size: 13px; }
.eht-actions button:disabled { opacity: 0.5; cursor: default; }
.eht-message { margin: 0; font-size: 13px; color: var(--ok); }
.eht-block { border: 1px solid var(--border); border-radius: 8px; padding: 16px; display: grid; gap: 8px; }
.eht-block h2 { margin: 0; font-size: 16px; }
.eht-muted { margin: 0; font-size: 13px; color: var(--text-muted); }
.eht-code { margin: 0; padding: 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 12px; overflow-x: auto; }
</style>
