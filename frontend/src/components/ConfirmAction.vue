<script setup lang="ts">
/**
 * 危险操作统一确认（W57-36）。
 *
 * 与 `useConfirm.confirmAction` 的区别：那个只有一句 message，适合「确定删除吗」；
 * 列表批量软删必须把**影响面写清楚**——`count` 来自后端（`/api/runs/{runId}/impact`
 * 或 `DeleteResult.impact`），前端不猜条数。`impacts` 可把「导出记录 N 条 / 产物 N 条」
 * 逐项展开，避免用户以为只删了一行表格。
 *
 * 文案直接中文字符串（新页面不引 i18n 新键），颜色只用语义 token。
 */
import { computed } from 'vue'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    show: boolean
    /** 影响记录条数，必须来自后端；0 表示后端确认无关联影响 */
    count: number
    title?: string
    /** 影响面明细；count 缺省时按明细求和 */
    impacts?: { label: string; count: number }[]
    description?: string
    confirmText?: string
    cancelText?: string
    loading?: boolean
  }>(),
  {
    title: '',
    impacts: () => [],
    description: '',
    confirmText: '',
    cancelText: '',
    loading: false,
  },
)

const emit = defineEmits<{
  'update:show': [value: boolean]
  confirm: []
  cancel: []
}>()

const displayTitle = computed(() => props.title || t('w57.confirmAction.confirmDelete'))
const displayConfirm = computed(() => props.confirmText || t('w57.confirmAction.confirmDelete'))
const displayCancel = computed(() => props.cancelText || t('w57.confirmAction.cancel'))

const effectiveCount = computed(() =>
  props.count > 0 ? props.count : props.impacts.reduce((sum, item) => sum + item.count, 0),
)

function close(): void {
  emit('update:show', false)
}
function cancel(): void {
  emit('cancel')
  close()
}
function confirm(): void {
  emit('confirm')
}
</script>

<template>
  <div v-if="show" class="confirm-mask" data-check-semantic @click.self="cancel">
    <div class="confirm-dialog" role="dialog" aria-modal="true" :aria-label="t('w57.confirmAction.dangerAria')">
      <h2 class="confirm-title">{{ displayTitle }}</h2>
      <!-- 具体条数必须出现在文案里，不能只说「此操作不可撤销」 -->
      <p class="confirm-lead">
        {{ t('w57.confirmAction.leadPrefix') }} <strong class="confirm-count">{{ effectiveCount }}</strong> {{ t('w57.confirmAction.leadSuffix') }}
      </p>
      <ul v-if="impacts.length > 0" class="confirm-impacts">
        <li v-for="item in impacts" :key="item.label">
          <span>{{ item.label }}</span>
          <strong>{{ item.count }}</strong>
        </li>
      </ul>
      <p v-if="description" class="confirm-desc">{{ description }}</p>
      <div class="confirm-actions">
        <button type="button" class="confirm-btn" data-testid="confirm-cancel" @click="cancel">
          {{ displayCancel }}
        </button>
        <button
          type="button"
          class="confirm-btn is-danger"
          data-testid="confirm-ok"
          :disabled="loading"
          @click="confirm"
        >
          {{ loading ? t('w57.confirmAction.processing') : displayConfirm }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--overlay);
}

.confirm-dialog {
  width: min(420px, 92vw);
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow);
}

.confirm-title {
  margin: 0 0 var(--space-3);
  color: var(--text);
  font-size: var(--font-size-md);
  font-weight: 600;
}

.confirm-lead {
  margin: 0;
  color: var(--text);
  font-size: var(--font-size-base);
  line-height: 1.6;
}

.confirm-count {
  color: var(--fail);
  font-size: var(--font-size-lg);
  font-variant-numeric: tabular-nums;
}

.confirm-impacts {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}

.confirm-impacts li {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 2px 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.confirm-impacts strong {
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.confirm-desc {
  margin: var(--space-3) 0 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.6;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.confirm-btn {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.confirm-btn:hover {
  background: var(--surface-hover);
}

.confirm-btn:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.confirm-btn.is-danger {
  border-color: var(--fail);
  background: var(--fail);
  color: var(--accent-contrast);
}

.confirm-btn.is-danger:hover {
  background: var(--fail-hover);
}

.confirm-btn.is-danger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
