<script setup lang="ts">
/**
 * 状态徽标：在 StatusTag 之上统一尺寸，但保留图标 + 文字双编码。
 *
 * 解决什么问题：表格、详情、搜索结果里的状态徽标大小不一，扫读时节奏乱；
 * 这里只做尺寸包装，不复制状态色或图标映射，语义仍由 StatusTag 决定。
 */
import { computed } from 'vue'
import StatusTag from '@/components/StatusTag.vue'
import type { IconName } from '@/components/iconMap'
import type { StatusTone } from '@/utils/statusLabels'

const props = withDefaults(
  defineProps<{
    label: string
    tone?: StatusTone
    icon?: IconName
    size?: 'sm' | 'md'
  }>(),
  { tone: 'info', size: 'md' },
)

const classes = computed(() => `status-badge status-badge--${props.size}`)
</script>

<template>
  <span :class="classes">
    <StatusTag :label="label" :tone="tone" :icon="icon" />
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  line-height: 1;
}

.status-badge--sm :deep(.status-tag) {
  padding: 0 var(--space-1);
  gap: 2px;
  font-size: var(--font-size-xs);
}

.status-badge--sm :deep(.status-tag svg) {
  width: 12px;
  height: 12px;
}
</style>
