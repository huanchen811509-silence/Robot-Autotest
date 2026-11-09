<script setup lang="ts">
/**
 * 骨架屏块：加载时占住布局，避免内容回来后页面跳动。
 *
 * 解决什么问题：只用一句「加载中」会让表格高度从 0 突然撑开，滚轮位置也跟着跳；
 * `aria-busy` 同时告诉读屏器这块区域正在更新。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    rows?: number
    /** 每行宽度；不足时按 92% → 78% → 86% 循环，模拟真实文本长短 */
    widths?: string[]
  }>(),
  { rows: 3, widths: () => [] },
)

const BAR_WIDTHS = ['92%', '78%', '86%', '64%']

const items = computed(() =>
  Array.from({ length: Math.max(1, props.rows) }, (_, index) => ({
    key: index,
    width: props.widths[index] ?? BAR_WIDTHS[index % BAR_WIDTHS.length],
  })),
)
</script>

<template>
  <div class="skeleton-block" aria-busy="true" aria-live="polite">
    <span
      v-for="item in items"
      :key="item.key"
      class="skeleton-block__row"
      :style="{ width: item.width }"
    />
  </div>
</template>

<style scoped>
.skeleton-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-3) 0;
}

.skeleton-block__row {
  display: block;
  height: 14px;
  border-radius: var(--radius-md);
  background: var(--surface-2);
  animation: skeleton-pulse 1.4s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-block__row {
    animation: none;
    opacity: 0.7;
  }
}
</style>
