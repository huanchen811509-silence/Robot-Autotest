<script setup lang="ts">
/**
 * 关于页（NAV-03 / FR-v2.0-11，v2.3.1 起版本动态化）。
 *
 * 版本 / commit / 构建时间 / 依赖清单来自构建产物 build-info.properties，
 * 没有构建产物时页面明确显示「运行时兜底」，不伪装。
 *
 * 版本「动态」体现在两层：
 *   1. 值本身来自发布口径的唯一真源（仓库 `VERSION` → 构建产物；也可用 `APP_VERSION`
 *      环境变量在运行期覆盖，重启即生效），不在前端写死；
 *   2. 页面每 30 秒自动重取（标签页不可见时跳过），换产物 / 改环境变量后不必刷新页面。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import AppIcon from '@/components/AppIcon.vue'
import { settingsApi, type BuildInfo } from '@/api/settings'

const { t } = useI18n()
const info = ref<BuildInfo | null>(null)
const error = ref('')
const updatedAt = ref('')
const refreshing = ref(false)

/** 自动刷新间隔：够快能看到版本变化，又不至于把接口打出噪音。 */
const REFRESH_MS = 30_000
let timer: ReturnType<typeof setInterval> | undefined

type DepRow = BuildInfo['dependencies'][number]

const depColumns = computed<DataTableColumns<DepRow>>(() => [
  { title: t('aboutPage.name'), key: 'name', width: 260 },
  { title: t('aboutPage.version'), key: 'version', width: 160 },
  { title: t('aboutPage.source'), key: 'source' },
])

function depRowKey(row: DepRow): string {
  return row.name
}

const checkKeys = [
  { key: 'database', label: 'aboutPage.db' },
  { key: 'executor', label: 'aboutPage.executor' },
  { key: 'kernel', label: 'aboutPage.kernel' },
  { key: 'aiCli', label: 'aboutPage.aiCli' },
] as const

function statusLabel(value: string | undefined): string {
  if (value === 'OK') return t('aboutPage.ok')
  if (value === 'DOWN') return t('aboutPage.down')
  return t('aboutPage.missing')
}

/** 版本来源与 `gen_build_info.py` 的取值链一一对应，让「这个号是哪来的」说得清。 */
const versionSourceLabel = computed(() => {
  const source = info.value?.versionSource
  if (source === 'env') return t('aboutPage.versionSourceEnv')
  if (source === 'file') return t('aboutPage.versionSourceFile')
  if (source === 'pom') return t('aboutPage.versionSourcePom')
  return info.value?.source === 'runtime'
    ? t('aboutPage.versionSourceRuntime')
    : t('aboutPage.versionSourceBuild')
})

async function load(manual = false): Promise<void> {
  if (manual) refreshing.value = true
  try {
    info.value = await settingsApi.buildInfo()
    error.value = ''
    updatedAt.value = new Date().toLocaleTimeString()
  } catch (e) {
    // 自动刷新失败不覆盖已有的正常内容（网络抖动不该把整页变成错误页）
    if (manual || !info.value) {
      error.value = e instanceof Error ? e.message : String(e)
    }
  } finally {
    refreshing.value = false
  }
}

onMounted(() => {
  void load()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') void load()
  }, REFRESH_MS)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  timer = undefined
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('aboutPage.title') }}</h1>
      <p class="muted">{{ t('app.subtitle') }}</p>
      <p class="muted intro">{{ t('aboutPage.intro') }}</p>
      <div class="head-actions">
        <button class="btn btn-ghost" type="button" :disabled="refreshing" @click="load(true)">
          <AppIcon name="refresh" :size="16" />
          <span>{{ t('aboutPage.refresh') }}</span>
        </button>
        <span class="muted small">{{ t('aboutPage.autoRefresh') }}</span>
        <span v-if="updatedAt" class="muted small">
          {{ t('aboutPage.updatedAt', { time: updatedAt }) }}
        </span>
      </div>
    </header>
    <p v-if="error" class="notice notice-error">{{ error }}</p>

    <template v-if="info">
      <section class="panel">
        <h2>{{ t('aboutPage.version') }}</h2>
        <div class="version-row">
          <span class="version-value">{{ info.version }}</span>
          <span class="chip">{{ versionSourceLabel }}</span>
        </div>
        <dl class="kv">
          <dt>{{ t('aboutPage.commit') }}</dt><dd>{{ info.gitCommit }}</dd>
          <dt>{{ t('aboutPage.buildTime') }}</dt><dd>{{ info.buildTime }}</dd>
          <dt>{{ t('aboutPage.source') }}</dt>
          <dd>{{ info.source === 'build' ? t('aboutPage.sourceBuild') : t('aboutPage.sourceRuntime') }}</dd>
          <dt>{{ t('aboutPage.license') }}</dt><dd>{{ info.license }}</dd>
        </dl>
      </section>

      <section class="panel">
        <h2>{{ t('aboutPage.ports') }}</h2>
        <dl class="kv">
          <dt>{{ t('aboutPage.entryPort') }}</dt><dd>{{ info.entryPort }}</dd>
          <dt>{{ t('aboutPage.backendPort') }}</dt><dd>{{ info.backendPort }}</dd>
          <dt>{{ t('aboutPage.devPort') }}</dt><dd>{{ info.devPort }}</dd>
        </dl>
      </section>

      <section class="panel">
        <h2>{{ t('aboutPage.selfCheck') }}</h2>
        <div class="chips">
          <span v-for="check in checkKeys" :key="check.key" class="chip"
                :class="{ ok: info.selfCheck[check.key] === 'OK' }">
            {{ t(check.label) }}：{{ statusLabel(info.selfCheck[check.key]) }}
          </span>
        </div>
      </section>

      <section class="panel">
        <h2>{{ t('aboutPage.dependencies') }}（{{ info.dependencies.length }}）</h2>
        <DataTable
          :columns="depColumns"
          :data="info.dependencies"
          :row-key="depRowKey"
          :max-height="null"
          :empty-title="t('aboutPage.dependencies')"
        />
      </section>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.intro { margin: 6px 0 0; max-width: 760px; line-height: 1.7; }
.small { font-size: 12px; }
.head-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 12px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 14px; font-size: 16px; }
.kv { display: grid; grid-template-columns: 180px 1fr; gap: 8px 16px; margin: 0; font-size: 13px; }
.kv dt { opacity: .65; }
.kv dd { margin: 0; font-family: var(--font-mono, monospace); }
.version-row { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.version-value { font-size: 24px; font-weight: 600; font-family: var(--font-mono, monospace); }
.chips { display: flex; flex-wrap: wrap; gap: 10px; }
.chip { padding: 6px 12px; border-radius: 999px; font-size: 12px; border: 1px solid color-mix(in srgb, currentColor 20%, transparent); }
.chip.ok { border-color: var(--ok-border); }
.notice { padding: 8px 12px; border-radius: 6px; font-size: 13px; }
.notice-error { border: 1px solid var(--fail-border); }
</style>
