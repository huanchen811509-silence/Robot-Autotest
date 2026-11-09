<script setup lang="ts">
/**
 * 列设置弹窗：勾选可见列 + 上移/下移调整顺序。
 *
 * 解决什么问题：用例表列多，不同角色关注的列不同；把草稿放在本地，
 * 点「确定」才提交，避免用户每勾一下就打一次接口。
 */
import { ref, watch } from 'vue'
import { NModal, NCheckbox, NButton } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { moveColumn, type ColumnOption } from './columnSettings'

const props = withDefaults(
  defineProps<{
    show: boolean
    columns: ColumnOption[]
    title?: string
  }>(),
  { title: '' },
)

const emit = defineEmits<{
  'update:show': [value: boolean]
  confirm: [columns: ColumnOption[]]
}>()

const { t } = useI18n()
const draft = ref<ColumnOption[]>([])

function resetDraft() {
  draft.value = props.columns.map((column) => ({ ...column }))
}

watch(
  () => props.show,
  (show) => {
    if (show) resetDraft()
  },
  { immediate: true },
)

function toggle(index: number) {
  const column = draft.value[index]
  if (!column || column.alwaysVisible) return
  draft.value[index] = { ...column, visible: !column.visible }
}

function move(index: number, delta: number) {
  draft.value = moveColumn(draft.value, index, delta)
}

function confirm() {
  emit('confirm', draft.value.map((column) => ({ ...column })))
  emit('update:show', false)
}

function cancel() {
  emit('update:show', false)
}
</script>

<template>
  <NModal
    :show="show"
    :mask-closable="true"
    :close-on-esc="true"
    @update:show="(value: boolean) => emit('update:show', value)"
  >
    <div class="column-dialog" role="dialog" aria-modal="true" :aria-label="title || t('ui.columnSettings')">
      <h2 class="column-dialog__title">{{ title || t('ui.columnSettings') }}</h2>
      <p class="column-dialog__hint">{{ t('ui.columnSettingsHint') }}</p>

      <ul class="column-dialog__list">
        <li v-for="(column, index) in draft" :key="column.key" class="column-dialog__item">
          <NCheckbox
            :checked="column.visible"
            :disabled="column.alwaysVisible"
            @update:checked="toggle(index)"
          >
            {{ column.label }}
          </NCheckbox>
          <span class="column-dialog__moves">
            <button
              type="button"
              class="column-dialog__move"
              :aria-label="t('ui.moveUp')"
              :disabled="index === 0"
              @click="move(index, -1)"
            >
              <AppIcon name="chevronDown" :size="16" class="column-dialog__up" />
            </button>
            <button
              type="button"
              class="column-dialog__move"
              :aria-label="t('ui.moveDown')"
              :disabled="index === draft.length - 1"
              @click="move(index, 1)"
            >
              <AppIcon name="chevronDown" :size="16" />
            </button>
          </span>
        </li>
      </ul>

      <div class="column-dialog__actions">
        <NButton quaternary size="small" @click="resetDraft">{{ t('ui.reset') }}</NButton>
        <span class="column-dialog__spacer" />
        <NButton quaternary size="small" @click="cancel">{{ t('ui.cancel') }}</NButton>
        <NButton type="primary" size="small" @click="confirm">{{ t('ui.confirm') }}</NButton>
      </div>
    </div>
  </NModal>
</template>

<style scoped>
.column-dialog {
  width: min(420px, calc(100vw - 32px));
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--panel);
  box-shadow: var(--shadow);
}

.column-dialog__title {
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text);
}

.column-dialog__hint {
  margin: var(--space-1) 0 var(--space-4);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.column-dialog__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 50vh;
  overflow: auto;
}

.column-dialog__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-2);
  border-radius: var(--radius-md);
}

.column-dialog__item:hover {
  background: var(--surface-hover);
}

.column-dialog__moves {
  display: inline-flex;
  gap: var(--space-1);
}

.column-dialog__move {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text-muted);
  cursor: pointer;
}

.column-dialog__move:hover:not(:disabled) {
  color: var(--text);
  background: var(--surface-hover);
}

.column-dialog__move:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.column-dialog__move:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.column-dialog__up {
  transform: rotate(180deg);
}

.column-dialog__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.column-dialog__spacer {
  flex: 1;
}

@media (prefers-reduced-motion: reduce) {
  .column-dialog__item {
    transition: none;
  }
}
</style>
