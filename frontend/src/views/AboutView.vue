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
import AppLogo from '@/components/AppLogo.vue'
import { settingsApi, type BuildInfo } from '@/api/settings'
import {
  CHANGELOG_SECTION_LABELS,
  parseChangelog,
  renderInlineMarkdown,
  type ChangelogRelease,
} from '@/config/changelog'

const { t } = useI18n()
const info = ref<BuildInfo | null>(null)
const error = ref('')
const updatedAt = ref('')
const refreshing = ref(false)

/**
 * 3.9.0 / FR-v3.9-17：关于页要能直接回答「这一版改了什么」。
 *
 * 数据源与版本迭代页**同一份**（`/CHANGELOG.md` → parseChangelog），不另建一份摘要，
 * 否则两处口径迟早对不上。只取首条（当前版本）的前几条，完整历史仍由版本迭代页承担。
 */
const latestRelease = ref<ChangelogRelease | null>(null)
const changelogError = ref('')

type LatestChange = { key: string; label: string; text: string }

/** 小节锚点 → 语言包文案；标签由 CHANGELOG 的 `<!-- added -->` 锚点决定，代码里不写死文案。 */
function sectionLabel(key: string): string {
  const i18nKey = CHANGELOG_SECTION_LABELS[key]
  return i18nKey ? t(i18nKey) : key
}

const latestItems = computed<LatestChange[]>(() => {
  const release = latestRelease.value
  if (!release) return []
  const rows = release.sections.flatMap((section) =>
    section.items.map((text, index) => ({
      key: `${section.key}-${index}`,
      label: sectionLabel(section.key),
      text,
    })),
  )
  return rows.slice(0, 4)
})

async function loadChangelog(): Promise<void> {
  try {
    const response = await fetch('/CHANGELOG.md')
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    latestRelease.value = parseChangelog(await response.text())[0] ?? null
    changelogError.value = ''
  } catch (e) {
    // 失败只降级这一块，不影响版本号与自检信息（它们来自后端接口）
    changelogError.value = e instanceof Error ? e.message : String(e)
  }
}

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
  void loadChangelog()
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
      <span class="about-brand"><AppLogo :size="52" with-wordmark /></span>
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

      <!-- 3.9.0 / FR-v3.9-17：版本开发完成后，「关于」与「版本迭代」必须同步生效。
           这里直接复用版本迭代页的数据源，让关于页能自证这一版改了什么。 -->
      <section class="panel">
        <h2 class="panel-head">
          <span>{{ t('aboutPage.recentChanges') }}</span>
          <RouterLink class="link" to="/changelog">{{ t('aboutPage.viewAllChangelog') }}</RouterLink>
        </h2>
        <p v-if="changelogError" class="notice">
          {{ t('aboutPage.changelogFailed') }}：{{ changelogError }}
        </p>
        <p v-else-if="!latestRelease" class="muted">{{ t('aboutPage.noChanges') }}</p>
        <template v-else>
          <p class="muted small release-title" v-html="renderInlineMarkdown(latestRelease.title)"></p>
          <ul class="changes">
            <li v-for="row in latestItems" :key="row.key">
              <span class="change-tag">{{ row.label }}</span>
              <span class="change-text" v-html="renderInlineMarkdown(row.text)"></span>
            </li>
          </ul>
        </template>
      </section>

      <!-- v3.11.0-delivery：交付包能力要在「关于」里看得见，命令与随包 bin/README.md 同源。
           命令文本是工程标识而非自然语言，故不进 i18n；说明文案一律走语言包。 -->
      <section class="panel">
        <h2>{{ t('aboutPage.delivery') }}</h2>
        <p class="muted small">{{ t('aboutPage.deliveryHint') }}</p>
        <dl class="kv">
          <dt>{{ t('aboutPage.deliveryInstall') }}</dt>
          <dd><code>./install.sh --prefix &lt;dir&gt; --apply</code></dd>
          <dt>{{ t('aboutPage.deliveryDoctor') }}</dt>
          <dd><code>bin/doctor.sh --ci</code></dd>
          <dt>{{ t('aboutPage.deliveryOps') }}</dt>
          <dd><code>bin/status.sh</code> · <code>bin/backup.sh --apply</code></dd>
          <dt>{{ t('aboutPage.deliveryUninstall') }}</dt>
          <dd><code>./uninstall.sh --prefix &lt;dir&gt; --apply</code></dd>
        </dl>
      </section>

      <section class="panel">
        <h2>{{ t('aboutPage.ports') }}</h2>        <dl class="kv">
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
.about-brand { display: inline-flex; margin-bottom: var(--space-2); }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.intro { margin: 6px 0 0; max-width: 760px; line-height: 1.7; }
.small { font-size: 12px; }
.head-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 12px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 14px; font-size: 16px; }
.panel-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.link { font-size: 13px; font-weight: 400; color: var(--accent); text-decoration: none; }
.link:hover { text-decoration: underline; }
.release-title { margin: 0 0 10px; font-family: var(--font-mono, monospace); }
.changes { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; font-size: 13px; line-height: 1.7; }
.changes li { display: flex; align-items: baseline; gap: 8px; }
.change-tag {
  flex: none; min-width: 56px; text-align: center; font-size: 11px;
  padding: 1px 6px; border-radius: 4px;
  border: 1px solid color-mix(in srgb, currentColor 18%, transparent);
  color: color-mix(in srgb, currentColor 70%, transparent);
  background: color-mix(in srgb, currentColor 5%, transparent);
}
.change-text { min-width: 0; }
.change-text :deep(code) {
  padding: 1px 6px; border-radius: 4px;
  background: color-mix(in srgb, currentColor 9%, transparent);
  font-family: var(--font-mono, monospace); font-size: 12px;
}
.change-text :deep(strong) { font-weight: 600; }
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
