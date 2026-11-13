<script setup lang="ts">
/**
 * 执行节点（NODX-01，v2.1）。
 *
 * 本版（NODX-01）先做**只读列表**：名字 / 状态 / 内核能力 / 并发上限 / 最近心跳。
 * 注册与停启用是低频管理动作，走按钮 + 确认框；「指定节点执行、离线拒绝」在 NODX-02，
 * 离线时长与最近任务的完整口径在 NODX-04 收口。
 *
 * 两个易错点：
 * ① 状态是后端口径的四态（NEW/ONLINE/OFFLINE/DISABLED），前端不做二次推断——
 *    「看起来像离线」的 NEW 节点必须显示 NEW，否则「注册了还没部署 agent」和
 *    「agent 挂了」在页面上无法区分；
 * ② 注册返回的明文令牌只展示一次（复制按钮 + 一次性对话框），组件里不落响应式缓存之外的地方。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NCheckbox, NInput, NInputNumber, NModal, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { nodeApi, type ExecutionNodeView, type NodeStatus } from '@/api/nodes'
import { metricsApi, type MetricsView } from '@/api/observability'
import { useAuthStore } from '@/stores/auth'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY } from '@/utils/format'

const { t, te } = useI18n()
const auth = useAuthStore()

const nodes = ref<ExecutionNodeView[]>([])
const metrics = ref<MetricsView | null>(null)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')

const formOpen = ref(false)
const saving = ref(false)
const form = ref({
  name: '',
  rfAvailable: true,
  pytestAvailable: false,
  maxConcurrency: 1,
  agentBaseUrl: '',
})
const issuedToken = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    nodes.value = await nodeApi.list()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
  // 节点健康读数（W4）：失败不挡节点列表——辅助指标不该让整页报错
  await loadMetrics()
}

async function loadMetrics(): Promise<void> {
  const projectId = auth.projectId ?? 0
  if (!projectId) {
    metrics.value = null
    return
  }
  try {
    metrics.value = await metricsApi.view(projectId)
  } catch {
    metrics.value = null
  }
}

/** 0–1 比例 → 百分比文本；后端已四舍五入到 4 位小数，这里只做展示。 */
function rateText(value: number): string {
  return `${Math.round(value * 1000) / 10}%`
}

function openCreate(): void {
  form.value = {
    name: '',
    rfAvailable: true,
    pytestAvailable: false,
    maxConcurrency: 1,
    agentBaseUrl: '',
  }
  formOpen.value = true
  message.value = ''
}

async function submit(): Promise<void> {
  if (!form.value.name.trim()) return
  saving.value = true
  try {
    const result = await nodeApi.register({
      name: form.value.name.trim(),
      rfAvailable: form.value.rfAvailable,
      pytestAvailable: form.value.pytestAvailable,
      maxConcurrency: form.value.maxConcurrency,
      agentBaseUrl: form.value.agentBaseUrl.trim() || undefined,
    })
    // 明文令牌只在此处出现一次；页面不把它存进 localStorage / store
    issuedToken.value = result.token
    formOpen.value = false
    message.value = t('nodesView.registered', { name: result.name })
    await load()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: ExecutionNodeView): Promise<void> {
  error.value = null
  message.value = ''
  try {
    if (row.status === 'DISABLED') {
      await nodeApi.enable(row.id)
      message.value = t('nodesView.enabled', { name: row.name })
    } else {
      await nodeApi.disable(row.id)
      message.value = t('nodesView.disabled', { name: row.name })
    }
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

function statusLabel(status: NodeStatus): string {
  return t(`nodesView.status.${status}`)
}

/** 四态着色（data-testid 供 spec 断言，class 供样式）；前端不推断状态，只着色。 */
function statusClass(status: NodeStatus): string {
  return `node-status is-${status.toLowerCase()}`
}

/**
 * 离线时长（NODX-04）：心跳在阈值内显示相对「x 前」，超阈值或 OFFLINE 明确显示
 * 「离线 x」。前端只做展示换算，状态口径仍以后端四态为准（不把久无心跳的 ONLINE
 * 擅自显示成 OFFLINE）。
 */
function heartbeatDisplay(row: ExecutionNodeView): string {
  if (!row.lastHeartbeatAt) return EMPTY
  const at = new Date(row.lastHeartbeatAt).getTime()
  if (Number.isNaN(at)) return row.lastHeartbeatAt
  const minutes = Math.max(0, Math.floor((Date.now() - at) / 60000))
  if (minutes < 1) return t('nodesView.justNow')
  if (minutes < 60) return t('nodesView.minutesAgo', { n: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('nodesView.hoursAgo', { n: hours })
  const days = Math.floor(hours / 24)
  return t('nodesView.daysAgo', { n: days })
}

/** 最近任务列：runId + 状态词条；状态词条复用运行态的 i18n。 */
function lastRunDisplay(row: ExecutionNodeView): string {
  if (!row.lastRun) return EMPTY
  const key = `enum.runStatus.${row.lastRun.state.toLowerCase()}`
  const label = te(key) ? t(key) : row.lastRun.state
  return `${row.lastRun.runId} · ${label}`
}

function capability(row: ExecutionNodeView): string {
  const parts: string[] = []
  if (row.rfAvailable) parts.push('RF')
  if (row.pytestAvailable) parts.push('pytest')
  return parts.length > 0 ? parts.join(' + ') : t('nodesView.noCapability')
}

const columns = computed<DataTableColumns<ExecutionNodeView>>(() => [
  { title: t('nodesView.name'), key: 'name', minWidth: 140 },
  {
    title: t('nodesView.statusLabel'),
    key: 'status',
    width: 110,
    render: (row) =>
      h(
        'span',
        { class: statusClass(row.status), 'data-testid': 'node-status' },
        statusLabel(row.status),
      ),
  },
  {
    title: t('nodesView.capabilities'),
    key: 'capabilities',
    width: 150,
    render: (row) => capability(row),
  },
  { title: t('nodesView.maxConcurrency'), key: 'maxConcurrency', width: 120, align: 'right' },
  { title: t('nodesView.runningRuns'), key: 'runningRuns', width: 100, align: 'right' },
  {
    title: t('nodesView.lastHeartbeat'),
    key: 'lastHeartbeatAt',
    minWidth: 180,
    render: (row) =>
      h(
        'span',
        { title: row.lastHeartbeatAt ?? '', 'data-testid': 'node-heartbeat' },
        heartbeatDisplay(row),
      ),
  },
  {
    title: t('nodesView.lastRun'),
    key: 'lastRun',
    minWidth: 220,
    render: (row) =>
      h('span', { 'data-testid': 'node-last-run' }, lastRunDisplay(row)),
  },
  {
    title: '',
    key: 'actions',
    width: 110,
    render: (row) =>
      h(
        NButton,
        {
          size: 'small',
          quaternary: true,
          'data-testid': 'node-toggle',
          onClick: () => toggleStatus(row),
        },
        { default: () => (row.status === 'DISABLED'
            ? t('nodesView.enable')
            : t('nodesView.disable')) },
      ),
  },
])

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">{{ t('nodesView.title') }}</h1>
    </div>

    <div class="toolbar">
      <NButton type="primary" @click="openCreate">{{ t('nodesView.register') }}</NButton>
      <NButton quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
    </div>

    <p class="muted">{{ t('nodesView.subtitle') }}</p>

    <!-- 节点健康（W4）：在线率 / 队列深度 / 失败率 / 分片恢复，超阈值直接列告警 -->
    <section v-if="metrics" class="health-strip" data-testid="node-health">
      <div class="health-item">
        <span class="health-label">{{ t('nodesView.healthOnlineRate') }}</span>
        <strong>{{ rateText(metrics.nodes.onlineRate) }}</strong>
        <span class="muted">
          {{ t('nodesView.healthOnlineDetail', { online: metrics.nodes.online, total: metrics.nodes.total }) }}
        </span>
      </div>
      <div class="health-item">
        <span class="health-label">{{ t('nodesView.healthQueueDepth') }}</span>
        <strong>{{ metrics.queue.depth }}</strong>
        <span class="muted">
          {{ t('nodesView.healthQueueDetail', { queued: metrics.queue.queued, preparing: metrics.queue.preparing }) }}
        </span>
      </div>
      <div class="health-item">
        <span class="health-label">{{ t('nodesView.healthFailureRate') }}</span>
        <strong>{{ rateText(metrics.runs.failureRate) }}</strong>
        <span class="muted">
          {{ t('nodesView.healthFailureDetail', { failed: metrics.runs.failed, finished: metrics.runs.finished }) }}
        </span>
      </div>
      <div class="health-item">
        <span class="health-label">{{ t('nodesView.healthRecovered') }}</span>
        <strong>{{ metrics.shards.recoveredAttempts }}</strong>
        <span class="muted">{{ t('nodesView.healthRecoveredDetail', { lost: metrics.shards.lost }) }}</span>
      </div>
      <ul v-if="metrics.alerts.length" class="health-alerts">
        <li v-for="alert in metrics.alerts" :key="alert.key" class="health-alert">
          {{ alert.message }}
        </li>
      </ul>
    </section>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <form v-if="formOpen" class="card form-card" @submit.prevent="submit">
      <h2 class="card-title">{{ t('nodesView.registerTitle') }}</h2>
      <label class="form-field">
        <span>{{ t('nodesView.name') }}</span>
        <NInput v-model:value="form.name" :placeholder="t('nodesView.namePlaceholder')" />
      </label>
      <div class="form-field">
        <span>{{ t('nodesView.capabilities') }}</span>
        <NCheckbox v-model:checked="form.rfAvailable">
          {{ t('nodesView.rfAvailable') }}
        </NCheckbox>
        <NCheckbox v-model:checked="form.pytestAvailable">
          {{ t('nodesView.pytestAvailable') }}
        </NCheckbox>
      </div>
      <label class="form-field">
        <span>{{ t('nodesView.maxConcurrency') }}</span>
        <NInputNumber v-model:value="form.maxConcurrency" :min="1" :max="64" />
      </label>
      <label class="form-field">
        <span>{{ t('nodesView.agentBaseUrl') }}</span>
        <NInput
          v-model:value="form.agentBaseUrl"
          :placeholder="t('nodesView.agentBaseUrlPlaceholder')"
        />
        <span class="field-hint">{{ t('nodesView.agentBaseUrlHint') }}</span>
      </label>
      <div class="toolbar">
        <NButton type="primary" attr-type="submit" :loading="saving" :disabled="!form.name.trim()">
          {{ t('common.save') }}
        </NButton>
        <NButton quaternary @click="formOpen = false">{{ t('common.cancel') }}</NButton>
      </div>
    </form>

    <DataTable
      :columns="columns"
      :data="nodes"
      :loading="loading"
      :row-key="(row: ExecutionNodeView) => row.id"
      :empty-title="t('nodesView.empty')"
      :empty-description="t('nodesView.emptyHint')"
      empty-icon="boxes"
      :error="Boolean(error)"
    >
      <!-- v5.6.0 / FR-v5.6-03：节点是「远程分发」才需要的对象；先把设备登记好，注册节点才有意义。 -->
      <template #emptyAction>
        <EmptyGuide to="/assets?tab=devices" :label="t('emptyGuide.action.assets')" />
      </template>
    </DataTable>

    <NModal :show="issuedToken !== null" preset="card" :title="t('nodesView.tokenTitle')"
            style="max-width: 520px" @update:show="issuedToken = null">
      <p class="muted">{{ t('nodesView.tokenOnce') }}</p>
      <code class="token-box" data-testid="node-token">{{ issuedToken }}</code>
      <div class="toolbar">
        <NButton type="primary" @click="issuedToken = null">
          {{ t('nodesView.tokenCopied') }}
        </NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.health-strip {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  margin: var(--space-3) 0;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
}

.health-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 140px;
}

.health-label {
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.health-item strong {
  font-size: var(--font-size-lg);
  font-variant-numeric: tabular-nums;
}

.health-alerts {
  flex-basis: 100%;
  margin: 0;
  padding-left: 18px;
  color: var(--warn);
  font-size: var(--font-size-xs);
}

.form-card {
  margin-bottom: var(--space-4);
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-3);
}

.form-field > span {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.node-status {
  font-weight: 600;
}

.node-status.is-online {
  color: var(--ok);
}

.node-status.is-offline {
  color: var(--fail);
}

.node-status.is-disabled {
  color: var(--text-muted);
}

.field-hint {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.token-box {
  display: block;
  margin: var(--space-2) 0;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  font-family: var(--font-mono);
  word-break: break-all;
}
</style>
