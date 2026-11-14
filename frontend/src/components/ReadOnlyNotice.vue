<script setup lang="ts">
/**
 * 只读提示（W57-35）。
 *
 * B/C 类列表是系统产物或只读派生，界面上不能出现「新增 / 编辑」却点了没反应。
 * 统一用这一句把原因写在明处；`reason` 允许补一句更具体的来源说明。
 */
import { computed } from 'vue'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    text?: string
    reason?: string
    compact?: boolean
  }>(),
  { text: '', reason: '', compact: false },
)

const fullText = computed(() => {
  const base = props.text || t('w57.readOnlyNotice.systemGenerated')
  return props.reason ? `${base}（${props.reason}）` : base
})
</script>

<template>
  <div
    class="readonly-notice"
    :class="{ 'is-compact': compact }"
    data-check-semantic
    role="note"
    data-testid="readonly-notice"
  >
    <span class="readonly-notice__dot" aria-hidden="true" />
    <span>{{ fullText }}</span>
  </div>
</template>

<style scoped>
.readonly-notice {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.5;
}

.readonly-notice.is-compact {
  padding: 0 var(--space-2);
  font-size: var(--font-size-xs);
}

.readonly-notice__dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
}
</style>
