<script setup lang="ts">
/**
 * 系统设置（NAV-02 / FR-v2.0-10 / D10 = A 分级；v4.12.0 承接全局语义）。
 * 平台级与项目级分栏：端口只读、数值走服务端白名单校验、每次变更写审计。
 *
 * <p>v4.12.0 / FR-v4.12-18~19：`/g/settings` 复用本组件，按 `route.meta.global` 切「全局语义」——
 * 只留平台段、标题换成平台设置、不再拉项目设置。为什么复用而不是另写一个全局设置页：
 * 原来的 `GlobalSettingsView` 只是一棵导航索引（6 个链接），而侧栏已经把这 6 项摊开了，
 * 点进来看到「和侧栏一样的菜单」等于二次导航；用户要的是**能改东西的页面**
 * （00-RESEARCH.md B7）。复用还能保证平台四项只有一份实现，不会两边校验口径漂移。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { NButton } from 'naive-ui'
import { ApiError } from '@/api/http'
import { settingsApi } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()

const platform = ref<Record<string, string>>({})
const project = ref<Record<string, string>>({})
const message = ref('')
const error = ref('')
const loading = ref(false)

/**
 * 全局语义只看路由 `meta.global`，与 `router/index.ts` 的 `shouldPreserveGlobalMode` 同一判据。
 *
 * <p>不用 `uiMode.store`：「进入 /g/settings 之后用户又把模式切回项目」时，
 * 页面内容不该跟着立刻翻脸（他还在同一页），路由才是这一页的真实语境。
 */
const isGlobal = computed(() => route.meta?.global === true)

const projectId = computed(() => auth.projectId)
const canEditPlatform = computed(() => auth.isAdmin)

const platformFields = [
  { key: 'execution.maxParallel', label: 'settings.maxParallel', kind: 'number' },
  { key: 'execution.defaultTimeoutSeconds', label: 'settings.defaultTimeout', kind: 'number' },
  { key: 'report.retentionDays', label: 'settings.retentionDays', kind: 'number' },
  { key: 'ai.enabled', label: 'settings.aiEnabled', kind: 'boolean' },
] as const

const projectFields = [
  { key: 'kernel.default', label: 'settings.defaultKernel', kind: 'enum' },
  { key: 'execution.timeoutSeconds', label: 'settings.projectTimeout', kind: 'number' },
  { key: 'case.tagSet', label: 'settings.tagSet', kind: 'text' },
] as const

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    platform.value = await settingsApi.platform()
    // 全局语义下不碰项目设置：全局用户可能没有当前项目，拉一次就是一次 403 噪音
    if (!isGlobal.value && projectId.value) {
      project.value = await settingsApi.project(projectId.value)
    }
  } catch {
    error.value = t('settings.loadFailed')
  } finally {
    loading.value = false
  }
}

function describe(e: unknown): string {
  if (e instanceof ApiError && e.code === 40300) return t('settings.forbidden')
  if (e instanceof ApiError && e.code === 40001) return t('settings.invalid')
  return e instanceof Error ? e.message : String(e)
}

async function savePlatform(): Promise<void> {
  message.value = ''
  error.value = ''
  const payload: Record<string, string> = {}
  for (const field of platformFields) payload[field.key] = platform.value[field.key] ?? ''
  try {
    platform.value = await settingsApi.putPlatform(payload)
    message.value = t('settings.saved')
  } catch (e) {
    error.value = describe(e)
    await load()
  }
}

async function saveProject(): Promise<void> {
  message.value = ''
  error.value = ''
  if (!projectId.value) {
    error.value = t('settings.noProject')
    return
  }
  const payload: Record<string, string> = {}
  for (const field of projectFields) payload[field.key] = project.value[field.key] ?? ''
  try {
    project.value = await settingsApi.putProject(projectId.value, payload)
    message.value = t('settings.saved')
  } catch (e) {
    error.value = describe(e)
    await load()
  }
}

onMounted(load)
watch(projectId, load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ isGlobal ? t('global.settingsTitle') : t('settings.title') }}</h1>
      <p class="muted">{{ isGlobal ? t('global.settingsHint') : t('settings.subtitle') }}</p>
      <RouterLink v-slot="{ navigate }" to="/nodes" custom>
        <NButton quaternary size="small" data-testid="entry-nodes" @click="navigate">
          {{ t('settings.nodesEntry') }}
        </NButton>
      </RouterLink>
    </header>

    <p v-if="message" class="notice notice-ok">{{ message }}</p>
    <p v-if="error" class="notice notice-error">{{ error }}</p>
    <p v-if="loading" class="muted">…</p>

    <section class="panel" data-testid="platform-settings-panel">
      <div class="panel-head">
        <h2>{{ t('settings.platform') }}</h2>
        <span class="tag">{{ t('settings.auditHint') }}</span>
      </div>
      <div class="grid">
        <label v-for="field in platformFields" :key="field.key" class="field">
          <span>{{ t(field.label) }}</span>
          <select
            v-if="field.kind === 'boolean'"
            v-model="platform[field.key]"
            :disabled="!canEditPlatform"
          >
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
          <input
            v-else
            v-model="platform[field.key]"
            type="number"
            min="1"
            :disabled="!canEditPlatform"
          />
        </label>
        <label class="field">
          <span>{{ t('settings.entryPort') }} · {{ t('settings.readonly') }}</span>
          <input :value="platform['entry.port'] ?? '8001'" type="text" readonly />
        </label>
      </div>
      <div class="panel-foot">
        <button class="btn" type="button" :disabled="!canEditPlatform || loading" @click="savePlatform">
          {{ t('settings.save') }}
        </button>
        <span v-if="!canEditPlatform" class="muted">{{ t('settings.forbidden') }}</span>
      </div>
    </section>

    <section v-if="!isGlobal" class="panel" data-testid="project-settings-panel">
      <div class="panel-head">
        <h2>{{ t('settings.project') }}</h2>
        <span class="tag">{{ projectId ? `#${projectId}` : t('settings.noProject') }}</span>
      </div>
      <div class="grid">
        <label v-for="field in projectFields" :key="field.key" class="field">
          <span>{{ t(field.label) }}</span>
          <select v-if="field.kind === 'enum'" v-model="project[field.key]" :disabled="!projectId">
            <option value="robot">robot</option>
            <option value="pytest">pytest</option>
          </select>
          <input
            v-else
            v-model="project[field.key]"
            :type="field.kind === 'number' ? 'number' : 'text'"
            :disabled="!projectId"
          />
        </label>
      </div>
      <div class="panel-foot">
        <button class="btn" type="button" :disabled="!projectId || loading" @click="saveProject">
          {{ t('settings.save') }}
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.panel-head h2 { margin: 0; font-size: 16px; }
.tag { font-size: 12px; opacity: .65; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }
.field { display: grid; gap: 6px; font-size: 13px; }
.field input, .field select {
  padding: 8px 10px; border-radius: 6px; font: inherit;
  border: 1px solid color-mix(in srgb, currentColor 22%, transparent);
  /* v3.9.0 / FR-v3.9-01：原来是 transparent，暗色下 <select> 的原生弹层会回落成白底浅字。
     改成不透明的面板色（与全局兜底同源），输入框与下拉外观保持一致。 */
  background-color: var(--panel); color: inherit;
}
.field input[readonly] { opacity: .6; }
.panel-foot { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.btn { padding: 8px 16px; border-radius: 6px; cursor: pointer; font: inherit; }
.btn:disabled { opacity: .45; cursor: not-allowed; }
.notice { padding: 8px 12px; border-radius: 6px; font-size: 13px; }
.notice-ok { border: 1px solid var(--ok-border); }
.notice-error { border: 1px solid var(--fail-border); }
</style>
