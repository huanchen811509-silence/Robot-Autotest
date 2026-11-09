<script setup lang="ts">
/**
 * 错误态原语：统一标题 / 说明 / 重试。
 *
 * 解决什么问题：各页面错误文案与布局各写一套，有的只有一行英文报错。
 * `role="alert"` 让读屏器在请求失败时立即播报，而不是等用户摸索。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'

const props = withDefaults(
  defineProps<{
    title?: string
    description?: string
    retryLabel?: string
    showRetry?: boolean
  }>(),
  { title: '', description: '', retryLabel: '', showRetry: true },
)

const emit = defineEmits<{ retry: [] }>()

const { t } = useI18n()
const resolvedTitle = computed(() => props.title || t('ui.errorTitle'))
const resolvedDescription = computed(() => props.description || t('ui.errorHint'))
const resolvedRetry = computed(() => props.retryLabel || t('ui.retry'))
</script>

<template>
  <div class="error-state" role="alert">
    <AppIcon name="alert" :size="20" class="error-state__icon" />
    <div class="error-state__text">
      <p class="error-state__title">{{ resolvedTitle }}</p>
      <p class="error-state__desc">{{ resolvedDescription }}</p>
    </div>
    <button
      v-if="showRetry"
      type="button"
      class="btn error-state__retry"
      @click="emit('retry')"
    >
      {{ resolvedRetry }}
    </button>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--fail-border);
  border-radius: var(--radius-lg);
  background: var(--fail-soft);
  color: var(--text);
}

.error-state__icon {
  color: var(--fail);
}

.error-state__text {
  flex: 1;
  min-width: 0;
}

.error-state__title {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--fail);
}

.error-state__desc {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.error-state__retry {
  flex: none;
}

@media (width <= 480px) {
  .error-state {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
