<script setup lang="ts">
/**
 * FR-v3.11-03 · 执行原则（只读）。
 *
 * 原则正文是产品口径（静态配置，走 i18n），策略值是**真值**：GET /system/settings
 * 与 GET /projects/{id}/settings 查出来的项目级/平台级设置，项目级覆盖平台级。
 * 本页不发起任何写请求；没有设置项时显示「0 条」，不拿默认值冒充配置。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import { fetchPrincipleSettings } from '@/api/maintenance'

interface PrincipleRow {
  id: string
  title: string
  description: string
}
interface SettingRow {
  id: string
  key: string
  value: string
  scope: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const principles = computed<PrincipleRow[]>(() => [
  { id: 'p1', title: t('maintenance.principles.p1Title'), description: t('maintenance.principles.p1Desc') },
  { id: 'p2', title: t('maintenance.principles.p2Title'), description: t('maintenance.principles.p2Desc') },
  { id: 'p3', title: t('maintenance.principles.p3Title'), description: t('maintenance.principles.p3Desc') },
])
const settings = ref<SettingRow[]>([])
const loading = ref(false)
const failed = ref(false)

const columns = computed<DataTableColumns<PrincipleRow>>(() => [
  { title: t('maintenance.principles.colTitle'), key: 'title' },
  { title: t('maintenance.principles.colDescription'), key: 'description' },
])
const settingColumns = computed<DataTableColumns<SettingRow>>(() => [
  { title: t('maintenance.principles.configKey'), key: 'key' },
  { title: t('maintenance.principles.configValue'), key: 'value' },
  { title: t('maintenance.principles.configScope'), key: 'scope' },
])

async function load() {
  if (!projectId.value) { settings.value = []; return }
  loading.value = true
  failed.value = false
  try {
    const items = await fetchPrincipleSettings(projectId.value)
    settings.value = items.map((s) => ({
      id: s.key, key: s.key, value: s.value,
      scope: s.scope === 'project'
        ? t('maintenance.principles.projectScope')
        : t('maintenance.principles.platformScope'),
    }))
  } catch {
    settings.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(projectId, load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('maintenance.principles.title') }}</h1>
      <p class="muted">{{ t('maintenance.common.subtitle') }}</p>
    </header>

    <div v-if="failed" class="banner error" data-testid="principles-failed">
      {{ t('maintenance.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="principles-retry" @click="load">
        {{ t('maintenance.common.retry') }}
      </NButton>
    </div>

    <section class="panel">
      <h2>{{ t('maintenance.principles.panel') }}</h2>
      <DataTable :columns="columns" :data="principles" :row-key="(row: PrincipleRow) => row.id">
        <template #empty>
          <p class="empty" data-testid="principles-empty">{{ t('maintenance.common.empty') }}</p>
        </template>
      </DataTable>
      <p class="muted">{{ t('maintenance.principles.principleReadOnly') }}</p>
    </section>

    <section class="panel">
      <h2>{{ t('maintenance.principles.settingsTitle') }}</h2>
      <p v-if="loading" class="muted" data-testid="principles-loading">
        {{ t('maintenance.common.loading') }}
      </p>
      <DataTable v-else :columns="settingColumns" :data="settings"
        :row-key="(row: SettingRow) => row.id" data-testid="principles-settings">
        <template #empty>
          <p class="empty" data-testid="principles-settings-empty">
            {{ t('maintenance.common.empty') }}
          </p>
        </template>
      </DataTable>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.banner { border-radius: 8px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.banner.error { border: 1px solid color-mix(in srgb, var(--fail) 50%, transparent); }
</style>
