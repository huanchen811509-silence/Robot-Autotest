<script setup lang="ts">
/**
 * 运维 · 归档与清理（v5.16.0 / FR-v5.16-08）。
 *
 * 归档动作全部复用既有 `archiveApi`（策略 / 预演 / 执行 / 冷查 / 还原），
 * 本页只做「一处可操作入口」：旧 `/admin/archive` 路由重定向到这里。
 * 预演零写入，执行必须二次确认 —— 与既有归档中心同一套护栏，不另造一份。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NTag } from 'naive-ui'
import RecoveryRecordsView from '@/views/maintenance/RecoveryRecordsView.vue'
import { useAuthStore } from '@/stores/auth'
import { archiveApi } from '@/api/archive'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const policy = ref<Record<string, unknown> | null>(null)
const preview = ref<Record<string, unknown> | null>(null)
const result = ref<Record<string, unknown> | null>(null)
const notice = ref('')
const loading = ref(false)

function count(key: string): number | null {
  const value = (preview.value ?? result.value ?? {})[key]
  return typeof value === 'number' ? value : null
}

async function load() {
  if (!projectId.value) return
  loading.value = true
  try {
    const data = await archiveApi.policy(projectId.value)
    policy.value = data as unknown as Record<string, unknown>
  } catch {
    notice.value = t('opsV516.common.loadFailed')
  } finally {
    loading.value = false
  }
}

async function runPreview() {
  if (!projectId.value) return
  notice.value = ''
  try {
    preview.value = (await archiveApi.preview(projectId.value)) as unknown as Record<string, unknown>
    notice.value = t('opsV516.archive.previewDone')
  } catch {
    notice.value = t('opsV516.common.actionFailed')
  }
}

onMounted(load)
</script>

<template>
  <section class="ops-page">
    <header class="ops-head">
      <h2>{{ t('opsV516.archive.title') }}</h2>
      <NButton size="small" :loading="loading" @click="load">{{ t('opsV516.common.refresh') }}</NButton>
    </header>
    <p class="ops-desc">{{ t('opsV516.archive.desc') }}</p>
    <p v-if="notice" class="ops-notice">{{ notice }}</p>

    <div class="ops-card">
      <h3>{{ t('opsV516.archive.policy') }}</h3>
      <p v-if="policy" class="ops-muted">{{ t('opsV516.archive.policyLoaded') }}</p>
      <p v-else class="ops-muted">{{ t('opsV516.archive.noPolicy') }}</p>
    </div>

    <div class="ops-row">
      <NButton size="small" @click="runPreview">{{ t('opsV516.archive.preview') }}</NButton>
      <span class="ops-muted">{{ t('opsV516.archive.previewHint') }}</span>
    </div>
    <ul v-if="preview" class="ops-plan">
      <li>{{ t('opsV516.archive.archived') }}：{{ count('archived') ?? t('opsV516.common.notCollected') }}</li>
      <li>{{ t('opsV516.archive.purged') }}：{{ count('purged') ?? t('opsV516.common.notCollected') }}</li>
      <li>{{ t('opsV516.archive.kept') }}：{{ count('kept') ?? t('opsV516.common.notCollected') }}</li>
    </ul>
    <p class="ops-muted">
      <NTag size="small" type="success">{{ t('opsV516.archive.entryOnly') }}</NTag>
      {{ t('opsV516.archive.entryHint') }}
    </p>

    <h3 class="ops-sub">{{ t('opsV516.archive.records') }}</h3>
    <RecoveryRecordsView />
  </section>
</template>

<style scoped>
.ops-page { display: flex; flex-direction: column; gap: 12px; }
.ops-head { display: flex; align-items: center; justify-content: space-between; }
.ops-desc { color: var(--text-muted); margin: 0; }
.ops-sub { margin: 12px 0 0; }
.ops-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ops-card { border: 1px solid var(--border-strong); border-radius: var(--radius-sm); padding: 12px; background: var(--panel); }
.ops-card h3 { margin: 0 0 8px; font-size: 13px; }
.ops-muted { color: var(--text-muted); font-size: 12px; }
.ops-notice { color: var(--accent); margin: 0; }
.ops-plan { margin: 0; padding-left: 18px; font-size: 12px; color: var(--text-muted); }
</style>
