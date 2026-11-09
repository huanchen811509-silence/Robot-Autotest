<script setup lang="ts">
/**
 * FR-v3.11-02 · 环境准备（只读，不触发真实设备写入）。
 *
 * 基线配置取真实 `env_profile` 的最近一份快照（GET），展平成「键 / 基线值 / 建议」三列。
 * 「建议」只是给人看的核对提示，**不会**调用任何设备侧写入 / 执行接口，也不提交准备动作；
 * 这是 v2.2 起就有的只读口径，页面里没有任何写请求。
 * 空态显示「0 条」：沿用 env.common.empty 文案键，不硬编码中文，也不补造示例行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import { envApi, type EnvProfileSummary } from '@/api/environment'
import { formatDateTime } from '@/utils/format'

interface Row {
  id: string
  step: string
  status: string
  note: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const profiles = ref<EnvProfileSummary[]>([])
const envName = ref('')
const latestAt = ref('')
const rows = ref<Row[]>([])
const loading = ref(false)
const failed = ref(false)

const columns = computed<DataTableColumns<Row>>(() => [
  { title: t('env.prepare.colStep'), key: 'step' },
  { title: t('env.prepare.colStatus'), key: 'status' },
  { title: t('env.prepare.colNote'), key: 'note' },
])

/** 把快照 JSON 展平成可核对的键；解析不出来就明确写「未采集」，不猜一个默认值。 */
function flatten(node: unknown, prefix: string, out: Row[]) {
  if (node === null || typeof node !== 'object') {
    out.push({ id: prefix, step: prefix, status: String(node ?? t('env.common.notCollected')),
      note: t('env.prepare.advice') })
    return
  }
  if (Array.isArray(node)) {
    node.forEach((value, i) => flatten(value, `${prefix}[${i}]`, out))
    return
  }
  for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object') flatten(value, path, out)
    else out.push({ id: path, step: path, status: String(value ?? t('env.common.notCollected')),
      note: t('env.prepare.advice') })
  }
}

async function loadProfiles() {
  if (!projectId.value) { profiles.value = []; return }
  profiles.value = await envApi.profiles(projectId.value)
  if (profiles.value.length > 0 && !envName.value) envName.value = profiles.value[0].name
}

async function loadBaseline() {
  if (!projectId.value || !envName.value) { rows.value = []; return }
  loading.value = true
  failed.value = false
  try {
    const list = await envApi.snapshots(projectId.value, envName.value)
    const latest = list[0]
    if (!latest) { rows.value = []; latestAt.value = ''; return }
    latestAt.value = formatDateTime(latest.snapshotAt)
    const out: Row[] = []
    try { flatten(JSON.parse(latest.snapshotJson || '{}'), '', out) }
    catch { out.push({ id: 'parse', step: 'snapshot_json', status: t('env.common.notCollected'),
      note: t('env.prepare.advice') }) }
    rows.value = out
  } catch {
    rows.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(async () => { await loadProfiles(); await loadBaseline() })
watch(projectId, async () => { envName.value = ''; await loadProfiles(); await loadBaseline() })
watch(envName, loadBaseline)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.prepare.title') }}</h1>
      <p class="muted">{{ t('env.prepare.subtitle') }}</p>
    </header>
    <p class="notice">{{ t('env.prepare.notice') }}</p>

    <div v-if="failed" class="banner error" data-testid="prepare-failed">
      {{ t('env.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="prepare-retry" @click="loadBaseline">
        {{ t('env.common.retry') }}
      </NButton>
    </div>

    <section class="panel">
      <h2>{{ t('env.prepare.envTitle') }}</h2>
      <div class="action">
        <select v-model="envName" class="pick" data-testid="prepare-env">
          <option v-for="p in profiles" :key="p.name" :value="p.name">{{ p.name }}</option>
        </select>
        <span class="muted" data-testid="prepare-latest">{{ latestAt || t('env.common.notCollected') }}</span>
      </div>
      <p v-if="profiles.length === 0" class="empty" data-testid="prepare-env-empty">
        {{ t('env.common.empty') }}
      </p>
    </section>

    <section class="panel">
      <h2>{{ t('env.prepare.panel') }}</h2>
      <p v-if="loading" class="muted" data-testid="prepare-loading">{{ t('env.common.loading') }}</p>
      <DataTable v-else :columns="columns" :data="rows" :row-key="(row: Row) => row.id">
        <template #empty>
          <p class="empty" data-testid="prepare-empty">{{ t('env.common.empty') }}</p>
        </template>
      </DataTable>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.notice { font-size: 13px; padding: 8px 12px; border-radius: 6px; border: 1px solid color-mix(in srgb, currentColor 18%, transparent); }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.banner { border-radius: 8px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.banner.error { border: 1px solid color-mix(in srgb, var(--fail) 50%, transparent); }
.action { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.pick { font-size: 13px; padding: 6px 8px; max-width: 240px; border-radius: 6px;
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  background: color-mix(in srgb, currentColor 4%, transparent); color: inherit; }
.pick:focus-visible { outline: 2px solid color-mix(in srgb, currentColor 60%, transparent); }
.pick:hover { border-color: color-mix(in srgb, currentColor 50%, transparent); }
</style>
