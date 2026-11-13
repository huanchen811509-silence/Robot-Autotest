<script setup lang="ts">
/**
 * 空态的「下一步」入口（v5.6.0 / FR-v5.6-03）。
 *
 * FR-v5.6-03 要求：当前工程没有对象的页面不能说「0 条」了事，必须回答两件事——
 * **为什么没有**（由 EmptyState 的 description 承担）与**能做什么**（就是这个组件）。
 *
 * 为什么不直接用 `NButton` + `router.push`：链接可以被中键/右键打开、可以被键盘聚焦、
 * 能被读屏软件识别为「链接」而不是「按钮」。空态里的「下一步」本质是导航，不是动作。
 */
withDefaults(
  defineProps<{
    to: string
    label: string
  }>(),
  {},
)
</script>

<template>
  <RouterLink class="empty-guide-link" :to="to" data-testid="empty-guide-action">
    {{ label }}
  </RouterLink>
</template>

<style scoped>
.empty-guide-link {
  display: inline-flex;
  align-items: center;
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--accent);
  font-size: var(--font-size-sm);
  line-height: 1.6;
  text-decoration: none;
}

.empty-guide-link:hover,
.empty-guide-link:focus-visible {
  background: var(--surface-hover);
  border-color: var(--border-strong);
}
</style>
