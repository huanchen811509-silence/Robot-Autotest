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
import { computed, h, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import EntityPicker from '@/components/EntityPicker.vue'
import { useAuthStore } from '@/stores/auth'
import { credentialApi } from '@/api/asset'
import {
  hostInventoryApi, parseSpec,
  type HostInventoryItem, type HostInventoryRecord, type HostInventorySpec,
} from '@/api/environment'
import { formatDateTime } from '@/utils/format'
import { collectStatusLabel } from '@/utils/statusLabels'
import { collectRawText, collectValueText, hasRawValue } from '@/utils/collectFormat'

interface ItemRow extends HostInventoryItem {
  id: string
  display: string
  /** 原始值（JSON 美化）；字符串类别为空串，不给折叠。 */
  raw: string
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
const usernameInput = ref('')
/**
 * 凭据 ID（v5.20.0 / FR-v5.20-02）。
 *
 * 原来是一个 `NInput`，让用户去别的页面把凭据 ID 背下来再回来填——这不是"高级功能"，
 * 是让人猜。现在改成可搜索下拉（候选来自 `GET /projects/{id}/credentials`），
 * 同时保留「高级：手输 ID」兜底（脚本/工单里只有数字的场景）。
 */
const credentialIdInput = ref<number | null>(null)
const credentialOptions = ref<{ value: number; label: string; hint?: string }[]>([])
const credentialLoading = ref(false)
const confirming = ref(false)
const collecting = ref(false)
const loading = ref(false)
const failed = ref(false)
const notice = ref('')
// v5.29 / FR-01：采集完成后定位到结果区
const resultSection = ref<HTMLElement | null>(null)
function scrollToResult(): void {
  resultSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const columns = computed<DataTableColumns<ItemRow>>(() => [
  { title: t('env.collect.panel'), key: 'label' },
  {
    title: t('env.collect.colValue'),
    key: 'display',
    // v5.20.0 / FR-v5.20-05：值是给人读的一句话；要看字段原文就展开「原始值」。
    // 折叠用原生 <details>，不引组件——它天生支持键盘与无障碍，且不增加 JS。
    render: (row) =>
      row.raw
        ? h('div', { class: 'value-cell' }, [
            h('span', { class: 'value-text' }, row.display),
            h('details', { class: 'value-raw' }, [
              h('summary', t('env.collect.rawValue')),
              h('pre', { class: 'value-raw-body' }, row.raw),
            ]),
          ])
        : h('span', row.display),
  },
  { title: t('env.collect.colSource'), key: 'command' },
  { title: t('env.collect.colCollectedAt'), key: 'collectedAtText' },
])

const itemRows = computed<ItemRow[]>(() => (spec.value?.items ?? []).map((item) => ({
  ...item,
  id: item.category,
  // v5.20.0 / FR-v5.20-05：按类别说人话（CPU 拆核数/型号、内存拆总量/已用/可用…），
  // 不再把接口 JSON 直接贴到表格里；缺值分清「没采到」与「确实没有」。
  display: collectValueText(item, t),
  raw: hasRawValue(item) ? collectRawText(item.value) : '',
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
  await loadCredentials()
}

/**
 * 凭据候选（v5.20.0 / FR-v5.20-02）。
 *
 * 失败不阻断页面：拿不到候选时下拉是空的，但「高级：手输 ID」仍然可用——
 * 采集这个动作不该因为候选列表挂了就做不了。
 */
async function loadCredentials() {
  if (!projectId.value) { credentialOptions.value = []; return }
  credentialLoading.value = true
  try {
    const rows = await credentialApi.list(projectId.value)
    credentialOptions.value = rows.map((c) => ({
      value: c.id,
      label: c.name,
      hint: [c.credType, c.username].filter(Boolean).join(' / ') || undefined,
    }))
  } catch {
    credentialOptions.value = []
  } finally {
    credentialLoading.value = false
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
    const credentialId = credentialIdInput.value ?? undefined
    const username = usernameInput.value.trim() || undefined
    const record = await hostInventoryApi.collect(projectId.value, {
      host: hostInput.value.trim() || 'localhost',
      ...(credentialId ? { credentialId } : {}),
      ...(username ? { username } : {}),
      ...(credentialId ? { confirm: true } : {}),
    })
    notice.value = t('env.collect.collected', { id: record.id })
    await load()
    await select(record.id)
    // v5.29 / FR-01：采集完自动滚到结果区（用户不用自己找）
    window.setTimeout(scrollToResult, 120)
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

    <!-- v5.20.0 / FR-v5.20-05：动作前置——打开页面第一眼就该看到「立即采集」，
         命令白名单是"它到底跑了什么"的说明，排在动作之后。 -->
    <section class="panel">
      <h2>{{ t('env.collect.actionTitle') }}</h2>
      <div class="action">
        <NInput v-model:value="hostInput" :placeholder="t('env.collect.hostPlaceholder')"
          data-testid="collect-host" style="max-width: 220px" />
        <NInput v-model:value="usernameInput" :placeholder="t('env.collect.usernamePlaceholder')"
          data-testid="collect-username" style="max-width: 180px" />
        <!-- v5.20.0 / FR-v5.20-02：凭据从"手填 ID"改成可搜索下拉 + 手输兜底 -->
        <div class="credential-field" data-testid="collect-credential">
          <EntityPicker
            v-model="credentialIdInput"
            :options="credentialOptions"
            :loading="credentialLoading"
            :placeholder="t('common.credentialPicker')"
            :manual-placeholder="t('common.credentialManual')"
          />
        </div>
        <NButton type="primary" :disabled="collecting" data-testid="collect-confirm" @click="collectNow">
          {{ confirming ? t('env.collect.confirmAgain') : t('env.collect.trigger') }}
        </NButton>
        <span v-if="confirming" class="warn-text" data-testid="collect-confirming">
          {{ t('env.collect.confirmHint') }}
        </span>
      </div>
      <p v-if="notice" class="muted" data-testid="collect-notice">
        {{ notice }}
        <NButton v-if="spec" text size="tiny" data-testid="collect-view-result" @click="scrollToResult">
          {{ t('env.collect.viewResult') }}
        </NButton>
      </p>
    </section>

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
      <h2>{{ t('env.collect.historyTitle') }}</h2>
      <p v-if="loading" class="muted" data-testid="collect-loading">{{ t('env.common.loading') }}</p>
      <div v-if="history.length" class="history" data-testid="collect-history">
        <NButton v-for="record in history" :key="record.id" size="tiny"
          :tertiary="record.id !== selectedId" :type="record.id === selectedId ? 'primary' : 'default'"
          data-testid="collect-history-item" :title="`${record.host} · ${record.status} · #${record.id}`"
          @click="select(record.id)">
          <!-- v5.20.0 / FR-v5.20-04：PARTIAL/OK 是人话不是界面词；原始值留在 title 里 -->
          {{ record.host }} · {{ collectStatusLabel(record.status) }} · {{ formatDateTime(record.collectedAt) }}
        </NButton>
      </div>
      <p v-else-if="!loading" class="empty" data-testid="collect-history-empty">
        {{ t('env.common.empty') }}
      </p>
    </section>

    <section class="panel" ref="resultSection" data-testid="collect-result-section">
      <h2>{{ t('env.collect.panel') }}<span v-if="spec" class="muted"> · {{ spec.host }} · {{ collectStatusLabel(spec.status) }}</span></h2>
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
/* 凭据下拉需要比两个文本输入框更宽（要显示「名称（ID）· ssh / 用户名」）；
   用 rem 而不是 px：px 会撞 check_tokens 的白名单，而这里本来就是相对字号的比例 */
.credential-field { flex: 1 1 20rem; min-width: 20rem; max-width: 26rem; }
/* v5.20.0 / FR-v5.20-05：值列一句话 + 可展开的原始值 */
.value-cell { display: flex; flex-direction: column; gap: 2px; }
.value-text { line-height: 1.5; }
.value-raw { margin-top: 2px; }
.value-raw summary { cursor: pointer; color: var(--text-muted); font-size: var(--font-size-sm); }
.value-raw summary:hover { color: var(--accent); }
.value-raw-body {
  margin: 4px 0 0;
  padding: 8px;
  max-height: 200px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.warn-text { font-size: 12px; color: color-mix(in srgb, var(--warn) 75%, currentColor); }
.history { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
