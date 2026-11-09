<script setup lang="ts">
/**
 * 快捷键面板（FR-3.1-09）。
 *
 * 解决什么问题：快捷键只写在文档里，实际用户找不到。`?` 与页头帮助按钮都能打开，
 * 复用 SidePanel 的焦点陷阱与 Esc 关闭，键盘用户不会被困在遮罩后面。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SidePanel from '@/components/ui/SidePanel.vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { t } = useI18n()

const items = computed(() => [
  { key: 'search', keys: ['Ctrl / ⌘', 'K'], label: t('shortcuts.search') },
  { key: 'help', keys: ['?'], label: t('shortcuts.help') },
  { key: 'close', keys: ['Esc'], label: t('shortcuts.close') },
  { key: 'navigate', keys: ['↑', '↓'], label: t('shortcuts.navigate') },
  { key: 'confirm', keys: ['Enter'], label: t('shortcuts.confirm') },
])
</script>

<template>
  <SidePanel
    :open="open"
    :title="t('shortcuts.title')"
    width="420px"
    @update:open="emit('update:open', $event)"
  >
    <section :aria-label="t('shortcuts.ariaLabel')">
      <ul class="shortcut-list">
        <li v-for="item in items" :key="item.key" class="shortcut-item">
          <span class="shortcut-item__label">{{ item.label }}</span>
          <span class="shortcut-item__keys">
            <kbd v-for="key in item.keys" :key="key" class="shortcut-key">{{ key }}</kbd>
          </span>
        </li>
      </ul>
      <p class="shortcut-hint muted">{{ t('shortcuts.hint') }}</p>
    </section>
  </SidePanel>
</template>

<style scoped>
.shortcut-list {
  display: grid;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.shortcut-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--border);
}

.shortcut-item__label {
  font-size: var(--font-size-sm);
  color: var(--text);
}

.shortcut-item__keys {
  display: inline-flex;
  gap: var(--space-1);
  flex: none;
}

.shortcut-key {
  display: inline-flex;
  align-items: center;
  min-width: 24px;
  justify-content: center;
  padding: 2px var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  font-family: var(--font-mono);
  font-size: var(--font-size-xs);
  color: var(--text);
}

.shortcut-hint {
  margin: var(--space-4) 0 0;
  font-size: var(--font-size-xs);
}
</style>
