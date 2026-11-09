<script setup lang="ts">
/**
 * 右侧抽屉壳：统一焦点、Esc、遮罩与读屏语义。
 *
 * 解决什么问题：详情面板各自监听 keydown 容易忘记移除监听；关闭后焦点丢到 body，
 * 键盘用户得从头 Tab。这里在打开时记录来源元素、关闭/卸载时归还焦点。
 */
import { ref, toRef } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { useFocusTrap } from '@/composables/useFocusTrap'

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: string
  }>(),
  { title: '', width: '380px' },
)

const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { t } = useI18n()
const panelRef = ref<HTMLElement | null>(null)

function close() {
  emit('update:open', false)
}

useFocusTrap(panelRef, toRef(props, 'open'), { onEscape: close })
</script>

<template>
  <Teleport to="body">
    <Transition name="side-panel">
      <div v-if="open" class="side-panel">
        <div class="side-panel__mask" @click="close" />
        <aside
          ref="panelRef"
          class="side-panel__body"
          role="dialog"
          aria-modal="true"
          :aria-label="title || t('ui.panelTitle')"
          tabindex="-1"
          :style="{ width }"
        >
          <header class="side-panel__head">
            <h2 class="side-panel__title">{{ title || t('ui.panelTitle') }}</h2>
            <button
              type="button"
              class="side-panel__close"
              :aria-label="t('ui.closePanel')"
              @click="close"
            >
              <AppIcon name="close" :size="16" />
            </button>
          </header>
          <div class="side-panel__content">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="side-panel__footer">
            <slot name="footer" />
          </footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.side-panel {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
}

.side-panel__mask {
  position: absolute;
  inset: 0;
  background: var(--overlay);
}

.side-panel__body {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  max-width: 100vw;
  background: var(--panel);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow);
  outline: none;
}

.side-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border);
}

.side-panel__title {
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text);
}

.side-panel__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text-muted);
  cursor: pointer;
}

.side-panel__close:hover {
  color: var(--text);
  background: var(--surface-hover);
}

.side-panel__close:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.side-panel__content {
  flex: 1;
  overflow: auto;
  padding: var(--space-4) var(--space-5);
}

.side-panel__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--border);
}

/* 不做位移动画：窄屏拉抽屉横移容易晕，统一退化为透明度过渡 */
.side-panel-enter-active,
.side-panel-leave-active {
  transition: opacity 160ms ease;
}

.side-panel-enter-from,
.side-panel-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .side-panel-enter-active,
  .side-panel-leave-active {
    transition: none;
  }
}
</style>
