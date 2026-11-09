<script setup lang="ts">
/**
 * 批量操作条（UX-02，FR-v0.2-36）。
 *
 * 通用组件：计数 + 跨页提示 + 动作插槽 + 回执。动作按钮由父级通过默认插槽传入
 * （用例页是六个动作；UX-02b 接入其余列表时复用同一契约）。
 * 回执遵循「成功 N / 失败 M」；失败项由父级保留选中，重试只作用于它们。
 *
 * v3.1.0「顺手」：批量成功后若响应带 undoToken，展示「已影响 N 项 · 5 分钟内可撤销」
 * 与倒计时。撤销只在可逆动作出现；删除明确标注不可撤销，避免给了撤销按钮却恢复不了。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { BatchResult } from '@/composables/useSelection'
import {
  readUndoHandle,
  isReversibleAction,
  undoBatch,
  UNDO_WINDOW_MS,
  type BatchUndoResult,
  type UndoHandle,
} from '@/api/undo'
import { formatCountdown } from '@/utils/eta'

const props = defineProps<{
  count: number
  crossPage: number
  result: BatchResult | null
  loading?: boolean
  projectId?: number | null
  action?: string | null
}>()

const emit = defineEmits<{
  (e: 'clear'): void
  (e: 'undone', result: BatchUndoResult): void
}>()

const { t } = useI18n()

const undoHandle = ref<UndoHandle | null>(null)
const undoRemaining = ref(0)
const undoBusy = ref(false)
const undoError = ref('')
const undoDone = ref<BatchUndoResult | null>(null)

let undoInterval: ReturnType<typeof setInterval> | undefined

const affectedCount = computed(() => props.result?.succeeded ?? 0)
const showUndo = computed(
  () =>
    Boolean(undoHandle.value) &&
    isReversibleAction(props.action) &&
    undoRemaining.value > 0 &&
    !undoDone.value,
)
const failedUndoItems = computed(() =>
  (undoDone.value?.items ?? []).filter((item) => !item.ok).slice(0, 3),
)

function stopCountdown(): void {
  if (undoInterval) {
    clearInterval(undoInterval)
    undoInterval = undefined
  }
}

/** 后端给了 undoUntil 就用绝对时间；没给则按 5 分钟窗口，刷新页面后不续期。 */
function startCountdown(handle: UndoHandle): void {
  stopCountdown()
  const parsed = handle.until ? new Date(handle.until).getTime() : NaN
  const deadline = Number.isFinite(parsed) ? parsed : Date.now() + UNDO_WINDOW_MS
  const tick = () => {
    undoRemaining.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
    if (undoRemaining.value <= 0) stopCountdown()
  }
  tick()
  undoInterval = setInterval(tick, 1_000)
}

async function undo(): Promise<void> {
  if (!undoHandle.value || !props.projectId || undoBusy.value) return
  undoBusy.value = true
  undoError.value = ''
  try {
    const result = await undoBatch(props.projectId, undoHandle.value.token)
    undoDone.value = result
    undoRemaining.value = 0
    stopCountdown()
    emit('undone', result)
  } catch {
    undoError.value = t('cases.batch.undoFailed')
  } finally {
    undoBusy.value = false
  }
}

watch(
  () => [props.result, props.action] as const,
  () => {
    undoDone.value = null
    undoError.value = ''
    const handle = isReversibleAction(props.action)
      ? readUndoHandle(props.result)
      : null
    undoHandle.value = handle
    if (handle) startCountdown(handle)
    else stopCountdown()
  },
  { immediate: true },
)

onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="selection-bar" role="region" :aria-label="t('cases.batch.barLabel')">
    <div v-if="count > 0" class="selection-main">
      <span class="selection-count">{{ t('cases.batch.selected', { count }) }}</span>
      <span v-if="crossPage > 0" class="selection-cross muted">
        {{ t('cases.batch.crossPage', { count: crossPage }) }}
      </span>
      <div class="toolbar selection-actions">
        <slot />
      </div>
      <button class="link-btn" type="button" @click="emit('clear')">
        {{ t('cases.batch.clear') }}
      </button>
    </div>

    <p v-if="result" class="selection-result" :class="{ 'has-failed': result.failed > 0 }">
      <template v-if="result.failed > 0">
        {{ t('cases.batch.resultPartial', { succeeded: result.succeeded, failed: result.failed }) }}
        <span class="muted">{{ t('cases.batch.retryHint') }}</span>
      </template>
      <template v-else>
        {{ t('cases.batch.resultAll', { succeeded: result.succeeded }) }}
      </template>
    </p>

    <div v-if="showUndo" class="selection-undo" role="status">
      <span>
        {{ t('cases.batch.undoHint', { count: affectedCount, time: formatCountdown(undoRemaining) }) }}
      </span>
      <button type="button" class="btn btn-ghost selection-undo__btn" :disabled="undoBusy" @click="undo">
        {{ undoBusy ? t('cases.batch.undoBusy') : t('cases.batch.undo', { time: formatCountdown(undoRemaining) }) }}
      </button>
    </div>

    <p v-else-if="result && action === 'delete'" class="selection-irreversible muted">
      {{ t('cases.batch.deleteIrreversible') }}
    </p>

    <p v-if="undoDone" class="selection-undo-result" :class="{ 'has-failed': undoDone.failed > 0 }">
      <template v-if="undoDone.failed > 0">
        {{ t('cases.batch.undoPartial', { restored: undoDone.restored, failed: undoDone.failed }) }}
      </template>
      <template v-else>
        {{ t('cases.batch.undoDone', { restored: undoDone.restored }) }}
      </template>
    </p>
    <ul v-if="failedUndoItems.length" class="selection-undo-failures">
      <li v-for="item in failedUndoItems" :key="item.id">
        {{ t('cases.batch.undoFailureItem', { id: item.id, message: item.message || t('cases.batch.undoFailureUnknown') }) }}
      </li>
    </ul>
    <p v-if="undoError" class="selection-undo-error" role="alert">{{ undoError }}</p>
  </div>
</template>

<style scoped>
.selection-bar {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 14px;
  margin: 12px 0;
}

.selection-main {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.selection-count {
  font-weight: 600;
}

.selection-cross {
  font-size: 12px;
}

.selection-actions {
  margin-bottom: 0;
  flex: 1;
}

.selection-result {
  margin: 8px 0 0;
  font-size: 13px;
}

.selection-result.has-failed {
  color: var(--fail);
}

.selection-undo {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: var(--radius-md);
  background: var(--accent-soft);
  color: var(--text);
  font-size: 13px;
}

.selection-undo__btn {
  font-variant-numeric: tabular-nums;
}

.selection-irreversible {
  margin: 8px 0 0;
  font-size: 12px;
}

.selection-undo-result {
  margin: 8px 0 0;
  font-size: 13px;
}

.selection-undo-result.has-failed {
  color: var(--fail);
}

.selection-undo-failures {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--text-muted);
  font-size: 12px;
}

.selection-undo-error {
  margin: 8px 0 0;
  color: var(--fail);
  font-size: 12px;
}
</style>
