<script setup lang="ts">
/**
 * 筛选条件容器：显示已生效条件数量、一键清空、可折叠。
 *
 * 解决什么问题：筛选条件一多，页面首屏被输入框占满；用户又看不出「到底生效了几个」。
 * 把计数与清空集中在容器层，各页面不用重复写「重置」逻辑。
 */
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'

withDefaults(
  defineProps<{
    /** 已生效条件数；> 0 才显示清空与计数 */
    count?: number
    collapsed?: boolean
    collapsible?: boolean
    label?: string
  }>(),
  { count: 0, collapsed: false, collapsible: true, label: '' },
)

const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  clear: []
}>()

const { t } = useI18n()
</script>

<template>
  <section class="filter-bar" aria-label="filters">
    <div class="filter-bar__head">
      <span class="filter-bar__title">{{ label || t('ui.filters') }}</span>
      <span v-if="count > 0" class="filter-bar__count">
        {{ t('ui.activeFilters', { count }) }}
      </span>
      <div class="filter-bar__head-actions">
        <button
          v-if="count > 0"
          type="button"
          class="filter-bar__link"
          @click="emit('clear')"
        >
          {{ t('ui.clearFilters') }}
        </button>
        <button
          v-if="collapsible"
          type="button"
          class="filter-bar__link filter-bar__toggle"
          :aria-expanded="!collapsed"
          @click="emit('update:collapsed', !collapsed)"
        >
          <AppIcon
            name="chevronDown"
            :size="16"
            :class="{ 'filter-bar__chevron--collapsed': collapsed }"
          />
          {{ collapsed ? t('ui.expand') : t('ui.collapse') }}
        </button>
      </div>
    </div>
    <div v-show="!collapsed" class="filter-bar__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.filter-bar {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-4);
}

.filter-bar__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.filter-bar__title {
  font-size: var(--font-size-sm);
  color: var(--text);
}

.filter-bar__count {
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.filter-bar__head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-left: auto;
}

.filter-bar__link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  font: inherit;
  font-size: var(--font-size-xs);
  cursor: pointer;
}

.filter-bar__link:hover {
  color: var(--accent-hover);
}

.filter-bar__link:active {
  color: var(--accent-active);
}

.filter-bar__link:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
  border-radius: var(--radius-sm);
}

.filter-bar__chevron--collapsed {
  transform: rotate(-90deg);
}

.filter-bar__body {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

@media (prefers-reduced-motion: reduce) {
  .filter-bar__link {
    transition: none;
  }
}
</style>
