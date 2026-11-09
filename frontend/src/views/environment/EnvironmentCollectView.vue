<script setup lang="ts">
/**
 * FR-v3.11-01 / 02 · 环境采集（默认只读，采集须显式确认）。
 *
 * 页面加载只发 GET：命令白名单 + 采集历史。POST /collect 只挂在「立即采集」按钮上，
 * 且要先点一次确认——采集会真的在目标机上跑 10 条命令，不能因为打开页面就发生。
 * 详情里的每一项都显示「分类 / 值或未采集原因 / 命令 / 采集时间」，与证据 JSON 同源，
 * 不在这里做任何二次加工（加工过就会出现「页面一套、报告一套」）。
 * 空态显示「0 条」：沿用 env.common.empty 文案键，不硬编码中文，也不补造示例行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import {
  hostInventoryApi, parseSpec,
  type HostInventoryItem, type HostInventoryRecord, type HostInventorySpec,
} from '@/api/environment'
import { formatDateTime } from '@/utils/format'

interface ItemRow extends HostInventoryItem {
  id: string
  display: string
  collectedAtText: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const commands = ref<[string, string][]>([])
const history = ref<HostInventoryRecord[]>([])
const selectedId = ref<number | null>(null)
const spec = ref<HostInventorySpec | null>(null)
const hostInput = ref('localhost')
const confirming = ref(false)
const collecting = ref(false)
const loading = ref(false)
const failed = ref(false)
const notice = ref('')

const columns = computed<DataTableColumns<ItemRow>>(() => [
  { title: t('env.collect.panel'), key: 'label' },
  { title: t('env.collect.colValue'), key: 'display' },
  { title: t('env.collect.colSource'), key: 'command' },
  { title: t('env.collect.colCollectedAt'), key: 'collectedAtText' },
])

const itemRows = computed<ItemRow[]>(() => (spec.value?.items ?? []).map((item) => ({
  ...item,
  id: item.category,
  display: item.value === null || item.value === undefined
    ? `${t('env.common.notCollected')} · ${item.reason ?? ''}`
    : (typeof item.value === 'string' ? item.value : JSON.stringify(item.value)),
  collectedAtText: formatDateTime(item.collectedAt),
})))

async function load() {
  if (!projectId.value) { commands.value = []; history.value = []; return }
  loading.value = true
  failed.value = false
  try {
    const [cmd, list] = await Promise.all([
      hostInventoryApi.commands(projectId.value),
      hostInventoryApi.list(projectId.value, { limit: 20 }),
    ])
    commands.value = Object.entries(cmd)
    history.value = list
    if (list.length > 0 && selectedId.value === null) await select(list[0].id)
  } catch {
    commands.value = []
    history.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function select(id: number) {
  selectedId.value = id
  spec.value = null
  try {
    const detail = await hostInventoryApi.detail(projectId.value, id)
    spec.value = parseSpec(detail.specJson)
  } catch {
    failed.value = true
  }
}

async function collectNow() {
  if (!confirming.value) { confirming.value = true; return }
  confirming.value = false
  collecting.value = true
  notice.value = ''
  try {
    const record = await hostInventoryApi.collect(projectId.value, { host: hostInput.value.trim() || 'localhost' })
    notice.value = t('env.collect.collected', { id: record.id })
    await load()
    await select(record.id)
  } catch {
    notice.value = t('env.common.loadFailed')
  } finally {
    collecting.value = false
  }
}

onMounted(load)
watch(projectId, () => { selectedId.value = null; spec.value = null; load() })
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.collect.title') }}</h1>
      <p class="muted">{{ t('env.collect.subtitle') }}</p>
    </header>

    <div v-if="failed" class="banner error" data-testid="collect-failed">
      {{ t('env.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="collect-retry" @click="load">
        {{ t('env.common.retry') }}
      </NButton>
    </div>

    <section class="panel">
      <h2>{{ t('env.collect.commandsTitle') }}</h2>
      <p class="muted">{{ t('env.collect.commandsHint') }}</p>
      <ul v-if="commands.length" class="cmd-list" data-testid="collect-commands">
        <li v-for="[category, command] in commands" :key="category">
          <code>{{ category }}</code><code class="cmd">{{ command }}</code>
        </li>
      </ul>
      <p v-else class="empty" data-testid="collect-commands-empty">{{ t('env.common.empty') }}</p>
    </section>

    <section class="panel">
      <h2>{{ t('env.collect.actionTitle') }}</h2>
      <div class="action">
        <NInput v-model:value="hostInput" :placeholder="t('env.collect.hostPlaceholder')"
          data-testid="collect-host" style="max-width: 220px" />
        <NButton type="primary" :disabled="collecting" data-testid="collect-confirm" @click="collectNow">
          {{ confirming ? t('env.collect.confirmAgain') : t('env.collect.trigger') }}
        </NButton>
        <span v-if="confirming" class="warn-text" data-testid="collect-confirming">
          {{ t('env.collect.confirmHint') }}
        </span>
      </div>
      <p v-if="notice" class="muted" data-testid="collect-notice">{{ notice }}</p>
    </section>

    <section class="panel">
      <h2>{{ t('env.collect.historyTitle') }}</h2>
      <p v-if="loading" class="muted" data-testid="collect-loading">{{ t('env.common.loading') }}</p>
      <div v-if="history.length" class="history" data-testid="collect-history">
        <NButton v-for="record in history" :key="record.id" size="tiny"
          :tertiary="record.id !== selectedId" :type="record.id === selectedId ? 'primary' : 'default'"
          data-testid="collect-history-item" @click="select(record.id)">
          {{ record.status }} · {{ formatDateTime(record.collectedAt) }}
        </NButton>
      </div>
      <p v-else-if="!loading" class="empty" data-testid="collect-history-empty">
        {{ t('env.common.empty') }}
      </p>
    </section>

    <section class="panel">
      <h2>{{ t('env.collect.panel') }}<span v-if="spec" class="muted"> · {{ spec.host }} · {{ spec.status }}</span></h2>
      <DataTable :columns="columns" :data="itemRows" :row-key="(row: ItemRow) => row.id">
        <template #empty>
          <p class="empty" data-testid="collect-empty">{{ t('env.common.empty') }}</p>
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
.cmd-list { margin: 10px 0 0; padding: 0; list-style: none; display: grid; gap: 6px; font-size: 12px; }
.cmd-list li { display: flex; gap: 10px; align-items: baseline; }
.cmd-list code:first-child { min-width: 56px; font-weight: 600; }
.cmd-list code.cmd { opacity: .75; overflow-wrap: anywhere; }
.action { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.warn-text { font-size: 12px; color: color-mix(in srgb, var(--warn) 75%, currentColor); }
.history { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
