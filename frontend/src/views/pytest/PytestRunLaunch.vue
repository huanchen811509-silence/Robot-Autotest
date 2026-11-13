<script setup lang="ts">
/**
 * 执行发起台（v5.6.0 / FR-v5.6-11、12、14）。
 *
 * 一件事：从界面上把一次执行发出去，并且看得见它跑到哪。
 *
 * 三处刻意的设计：
 * 1. **预检在提交前**。台架没配齐就点「发起」，用户拿到的是一个看不懂的失败；
 *    预检把「缺什么、去哪儿补」提前说完，提交按钮直接禁用并写明原因。
 * 2. **real 必须打勾**。勾选框文案是「我确认会登录真实设备」，不是「同意条款」——
 *    要让人在勾的一瞬间想起来这会 SSH 到机器上跑命令。
 * 3. **进度轮询按 1 秒**，且组件卸载立刻停。轮询不停会在后台永远打接口，
 *    用户切走页面却还在消耗后端。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NInput } from 'naive-ui'
import EmptyState from '@/components/EmptyState.vue'
import { pytestProjectApi, type CaseScriptBinding } from '@/api/pytestProject'
import { pytestRunApi, type PrecheckResult, type RunProgress } from '@/api/pytestRuns'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const precheck = ref<PrecheckResult | null>(null)
const precheckLoading = ref(false)
const bindings = ref<CaseScriptBinding[]>([])
const mode = ref<'fake' | 'real'>('fake')
const confirmReal = ref(false)
// 失败复测轮数（FR-v5.6-24）。默认 0：复测会把失败用例再跑几遍，让用户显式选，
// 不替他决定「这次运行要不要变慢」。
const retry = ref(0)
const onlySelected = ref(false)
const selected = ref<string[]>([])
const runIdInput = ref('')
const submitting = ref(false)
const errorText = ref('')
// 不用 naive-ui 的 useMessage：它要求外层有 NMessageProvider，工作台没有，
// 在缺 provider 的页面上 useMessage() 会直接抛错，整个执行台打不开。
const notice = ref('')

const runId = ref<string | null>(null)
const progress = ref<RunProgress | null>(null)
const polling = ref(false)
let timer: number | null = null

const boundNodes = computed(() =>
  bindings.value.map((one) => one.nodeId).filter((one): one is string => Boolean(one)),
)

/** 实际要提交的 nodeIds：空数组 = 整库（后端口径）。 */
const targetNodes = computed(() => (onlySelected.value ? selected.value : []))

const blockingChecks = computed(() =>
  (precheck.value?.checks ?? []).filter((one) => one.required && !one.ok),
)

const canSubmit = computed(() => {
  if (submitting.value || polling.value) return false
  if (precheck.value && !precheck.value.ready) return false
  if (mode.value === 'real' && !confirmReal.value) return false
  if (onlySelected.value && selected.value.length === 0) return false
  return true
})

const percent = computed(() => {
  const one = progress.value
  if (!one || one.total <= 0) return 0
  return Math.min(100, Math.round((one.finished / one.total) * 100))
})

const stageLabel = computed(() => {
  const stage = progress.value?.stage
  if (!stage) return ''
  return t(`pytestLaunch.stage.${stage}`)
})

function stopPolling(): void {
  polling.value = false
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
}

async function loadPrecheck(): Promise<void> {
  precheckLoading.value = true
  try {
    precheck.value = await pytestRunApi.precheck(projectId.value, mode.value, targetNodes.value)
  } catch (error) {
    errorText.value = String(error)
  } finally {
    precheckLoading.value = false
  }
}

async function loadBindings(): Promise<void> {
  try {
    const stats = await pytestProjectApi.bindings(projectId.value)
    bindings.value = stats.items
  } catch {
    bindings.value = []
  }
}

async function poll(): Promise<void> {
  if (!runId.value) return
  try {
    const one = await pytestRunApi.progress(projectId.value, runId.value)
    progress.value = one
    if (one.terminal) {
      stopPolling()
      // 客户端校验单调性只做提示不做回写：数字回退说明后端有问题，
      // 前端悄悄改数会把这个 bug 藏起来。
      return
    }
    timer = window.setTimeout(poll, 1000)
  } catch (error) {
    errorText.value = String(error)
    stopPolling()
  }
}

async function submit(): Promise<void> {
  submitting.value = true
  errorText.value = ''
  progress.value = null
  try {
    const accepted = await pytestRunApi.startAsync(projectId.value, {
      runId: runIdInput.value.trim() || undefined,
      mode: mode.value,
      nodeIds: targetNodes.value,
      confirmReal: mode.value === 'real' ? confirmReal.value : false,
      retry: retry.value,
    })
    runId.value = accepted.runId
    progress.value = {
      runId: accepted.runId,
      stage: 'PREPARING',
      total: accepted.total,
      finished: 0,
      passed: 0,
      failed: 0,
      elapsedMs: 0,
      cancelRequested: false,
      terminal: false,
      source: 'live',
    }
    polling.value = true
    timer = window.setTimeout(poll, 800)
    notice.value = t('pytestLaunch.submitted', { runId: accepted.runId })
  } catch (error) {
    errorText.value = String(error)
  } finally {
    submitting.value = false
  }
}

async function cancel(): Promise<void> {
  if (!runId.value) return
  try {
    await pytestRunApi.cancel(projectId.value, runId.value)
    notice.value = t('pytestLaunch.cancelRequested')
  } catch (error) {
    errorText.value = String(error)
  }
}

function toggleNode(nodeId: string | null, checked: boolean): void {
  if (!nodeId) return
  if (checked) {
    if (!selected.value.includes(nodeId)) selected.value = [...selected.value, nodeId]
  } else {
    selected.value = selected.value.filter((one) => one !== nodeId)
  }
}

onMounted(async () => {
  await Promise.all([loadPrecheck(), loadBindings()])
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="card launch" data-test="pytest-launch">
    <div class="card-head">
      <h2>{{ t('pytestLaunch.title') }}</h2>
      <button class="ghost" :disabled="precheckLoading" data-test="precheck-refresh" @click="loadPrecheck">
        {{ t('pytestLaunch.recheck') }}
      </button>
    </div>
    <p class="muted small">{{ t('pytestLaunch.subtitle') }}</p>

    <!-- ① 预检 -->
    <div v-if="precheck" class="precheck" data-test="precheck">
      <p :class="['precheck-state', precheck.ready ? 'ok' : 'blocked']" data-test="precheck-state">
        {{ precheck.ready
          ? t('pytestLaunch.precheckReady')
          : t('pytestLaunch.precheckBlocked', { count: blockingChecks.length }) }}
      </p>
      <ul class="precheck-list">
        <li
          v-for="item in precheck.checks"
          :key="item.key"
          :class="['precheck-item', item.ok ? 'ok' : item.required ? 'blocked' : 'warn']"
          :data-test="`precheck-${item.key}`"
        >
          <span class="precheck-key">
            {{ t(`pytestLaunch.check.${item.key}`) }}
            <em v-if="!item.required" class="muted">{{ t('pytestLaunch.optional') }}</em>
          </span>
          <span class="precheck-detail">{{ item.detail }}</span>
          <RouterLink v-if="!item.ok && item.action" class="precheck-link" :to="item.action">
            {{ t('pytestLaunch.fix') }}
          </RouterLink>
        </li>
      </ul>
    </div>
    <EmptyState
      v-else-if="precheckLoading"
      :title="t('pytestLaunch.precheckLoading')"
      icon="loader"
    />

    <!-- ② 目标与模式 -->
    <div class="form-row">
      <label class="field">
        <span>{{ t('pytestLaunch.mode') }}</span>
        <select v-model="mode" data-test="mode" class="pick" @change="loadPrecheck">
          <option value="fake">fake</option>
          <option value="real">real</option>
        </select>
      </label>
      <label class="field">
        <span>{{ t('pytestLaunch.runId') }}</span>
        <NInput v-model:value="runIdInput" :placeholder="t('pytestLaunch.runIdPlaceholder')" />
      </label>
      <label class="field">
        <span>{{ t('pytestLaunch.retry') }}</span>
        <select v-model.number="retry" data-test="retry" class="pick">
          <option :value="0">{{ t('pytestLaunch.retryNone') }}</option>
          <option :value="1">1</option>
          <option :value="2">2</option>
          <option :value="3">3</option>
        </select>
      </label>
    </div>

    <label class="check-line">
      <input v-model="onlySelected" type="checkbox" data-test="only-selected" />
      <span>{{ t('pytestLaunch.onlySelected', { count: boundNodes.length }) }}</span>
    </label>

    <div v-if="onlySelected" class="node-list" data-test="node-list">
      <label v-for="one in bindings" :key="one.id" class="node-row">
        <input
          type="checkbox"
          :checked="one.nodeId ? selected.includes(one.nodeId) : false"
          :disabled="!one.nodeId"
          @change="toggleNode(one.nodeId, ($event.target as HTMLInputElement).checked)"
        />
        <code>{{ one.nodeId ?? t('pytestLaunch.unbound') }}</code>
      </label>
    </div>

    <label v-if="mode === 'real'" class="check-line danger" data-test="confirm-real">
      <input v-model="confirmReal" type="checkbox" />
      <span>{{ t('pytestLaunch.confirmReal') }}</span>
    </label>

    <p v-if="errorText" class="notice notice-fail" data-test="launch-error">{{ errorText }}</p>
    <p v-if="notice" class="notice notice-ok" data-test="launch-notice">{{ notice }}</p>

    <div class="actions">
      <button class="btn primary" :disabled="!canSubmit" data-test="submit" @click="submit">
        {{ submitting ? t('pytestLaunch.submitting') : t('pytestLaunch.submit') }}
      </button>
      <button v-if="polling" class="btn" data-test="cancel" @click="cancel">
        {{ t('pytestLaunch.cancel') }}
      </button>
      <span v-if="!precheck?.ready" class="muted small" data-test="blocked-hint">
        {{ t('pytestLaunch.blockedHint') }}
      </span>
    </div>

    <!-- ③ 进度 -->
    <div v-if="progress" class="progress" data-test="progress">
      <p class="progress-head">
        <code data-test="progress-run">{{ progress.runId }}</code>
        <span class="badge" data-test="progress-stage">{{ stageLabel }}</span>
        <span class="muted small">
          {{ t('pytestLaunch.counters', {
            finished: progress.finished,
            total: progress.total,
            passed: progress.passed,
            failed: progress.failed,
          }) }}
        </span>
        <span class="muted small">{{ (progress.elapsedMs / 1000).toFixed(1) }}s</span>
      </p>
      <div class="bar" role="progressbar" :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">
        <div class="bar-fill" :style="{ width: percent + '%' }" />
      </div>
      <p v-if="progress.terminal" class="muted small" data-test="progress-terminal">
        <RouterLink to="/reports">{{ t('pytestLaunch.viewReport') }}</RouterLink>
      </p>
    </div>
  </section>
</template>

<style scoped>
.launch {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.precheck-list,
.node-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin: var(--space-2) 0 0;
  padding: 0;
  list-style: none;
}

.precheck-item {
  display: grid;
  grid-template-columns: 8.5rem 1fr auto;
  gap: var(--space-3);
  align-items: baseline;
  padding: var(--space-1) var(--space-2);
  border-left: 3px solid var(--border);
  font-size: var(--font-size-sm);
}

.precheck-item.ok {
  border-left-color: var(--ok, var(--accent));
}

.precheck-item.warn {
  border-left-color: var(--warn, var(--accent));
}

.precheck-item.blocked {
  border-left-color: var(--fail, var(--accent));
}

.precheck-state.ok {
  color: var(--ok, var(--accent));
}

.precheck-state.blocked {
  color: var(--fail, var(--accent));
}

.precheck-link {
  color: var(--accent);
}

.node-list {
  max-height: 14rem;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: var(--space-2);
}

.node-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  font-size: var(--font-size-sm);
}

.form-row {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--font-size-sm);
  min-width: 10rem;
}

.check-line {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  font-size: var(--font-size-sm);
}

.check-line.danger {
  color: var(--fail, var(--accent));
}

.bar {
  height: 8px;
  border-radius: var(--radius-sm);
  background: var(--surface-hover);
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--accent);
  transition: width 0.3s ease;
}

.progress-head {
  display: flex;
  gap: var(--space-3);
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: var(--space-2);
}
</style>
