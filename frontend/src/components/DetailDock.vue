<script setup lang="ts">
/**
 * 列表行详情停靠区（v5.11.0 / W61-01 / FR-v5.11-01）。
 *
 * 位置约定（**不是**弹窗、抽屉、新页面、fixed 浮层）：
 * - 必须放在「列表容器内部、表格之后」，因此与表格同宽是自然结果，不靠 CSS 硬凑。
 * - 打开时用 `scrollIntoView({ block: 'nearest' })`：只保证它进入视野，
 *   不会把页面拽到底部（`scrollTo(bottom)` 是明令禁止的）。
 * - 组件实例最多一个：同页切行是「原地替换」，靠父级只留一个选中 id 保证。
 */
import { nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    /** 是否展示（父级由选中 id 推导）。 */
    open: boolean
    /** 标题（通常是用例编号 + 标题）。 */
    title?: string
  }>(),
  { title: '' },
)

const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const root = ref<HTMLElement | null>(null)

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    // 等详情内容渲染完再滚，否则量到的是占位高度，滚了个空
    await nextTick()
    root.value?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  },
  { immediate: true },
)
</script>

<template>
  <section v-if="open" ref="root" class="detail-dock" data-testid="detail-dock">
    <header class="detail-dock-head">
      <h2 class="detail-dock-title" data-testid="detail-dock-title">
        {{ title || t('w61.dock.title') }}
      </h2>
      <!-- 可选操作区（如成员详情的上/下一条）：不传时头部与原先完全一致 -->
      <div v-if="$slots.actions" class="detail-dock-actions">
        <slot name="actions" />
      </div>
      <button type="button" class="link" data-testid="detail-dock-close" @click="emit('close')">
        {{ t('w61.dock.collapse') }}
      </button>
    </header>
    <div class="detail-dock-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.detail-dock {
  /* 与表格同宽：同容器、零外边距、border-box，不写固定宽度 */
  box-sizing: border-box;
  width: 100%;
  margin: var(--space-3) 0 0;
  padding: var(--space-3);
  background: var(--panel);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
}
.detail-dock-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

/* 操作区贴着标题右侧；无操作时该节点不渲染，不影响原有布局 */
.detail-dock-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-left: auto;
}
.detail-dock-title {
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text);
}
.link {
  border: none;
  background: none;
  color: var(--accent);
  cursor: pointer;
  font-size: var(--font-size-sm);
  padding: 0;
}
.detail-dock-body {
  min-width: 0;
}
</style>
