<script setup lang="ts">
/**
 * 页面工具栏原语：左侧筛选、右侧动作。
 *
 * 解决什么问题：原来的 `.toolbar` 把筛选和按钮混在一行，窄屏时要么横向溢出、
 * 要么按钮被挤成两行且对不齐。这里用 flex-wrap 明确「筛选优先换行、动作靠右」。
 */
</script>

<template>
  <div class="page-toolbar">
    <div v-if="$slots.filters" class="page-toolbar__filters">
      <slot name="filters" />
    </div>
    <div v-if="$slots.actions" class="page-toolbar__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-4);
}

.page-toolbar__filters {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1 1 420px;
  flex-wrap: wrap;
  min-width: 0;
}

.page-toolbar__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  flex-wrap: wrap;
}

@media (width <= 640px) {
  .page-toolbar__filters {
    flex-basis: 100%;
  }

  .page-toolbar__actions {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
