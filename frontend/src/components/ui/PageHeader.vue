<script setup lang="ts">
/**
 * 页面头部原语：统一「面包屑 + 标题/副标题 + 右侧动作」的间距与层级。
 *
 * 解决什么问题：各视图各写一套 `<div class="page-head">`，标题字号、上下留白、
 * 动作按钮对齐经常不一致；集中到这里后新增页面不会再漂移。
 */
import { RouterLink } from 'vue-router'

export interface PageBreadcrumb {
  label: string
  /** 有 to 才渲染为链接；末级通常省略，避免「点自己」 */
  to?: string
}

withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    breadcrumbs?: PageBreadcrumb[]
  }>(),
  { subtitle: '', breadcrumbs: () => [] },
)
</script>

<template>
  <header class="page-header">
    <nav v-if="breadcrumbs.length" class="page-header__crumbs" aria-label="breadcrumb">
      <template v-for="(item, index) in breadcrumbs" :key="`${index}-${item.label}`">
        <span v-if="index > 0" class="page-header__sep" aria-hidden="true">/</span>
        <RouterLink v-if="item.to" :to="item.to" class="page-header__crumb">
          {{ item.label }}
        </RouterLink>
        <span v-else class="page-header__crumb page-header__crumb--current">{{ item.label }}</span>
      </template>
    </nav>

    <div class="page-header__main">
      <div class="page-header__text">
        <h1 class="page-header__title">{{ title }}</h1>
        <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="page-header__actions">
        <slot name="actions" />
      </div>
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-5);
}

.page-header__crumbs {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.page-header__sep {
  opacity: 0.6;
}

.page-header__crumb {
  color: var(--text-muted);
  text-decoration: none;
}

.page-header__crumb:hover {
  color: var(--accent);
}

.page-header__crumb--current {
  color: var(--text);
}

.page-header__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-5);
  flex-wrap: wrap;
}

.page-header__text {
  min-width: 0;
}

.page-header__title {
  margin: 0;
  font-size: var(--font-size-lg);
  line-height: 1.3;
  color: var(--text);
}

.page-header__subtitle {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
</style>
