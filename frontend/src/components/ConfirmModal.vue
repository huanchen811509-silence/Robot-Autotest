<script setup lang="ts">
/**
 * UX-04 步骤 5：统一确认 Modal（替换所有 nativeConfirm）。
 *
 * 组件库提供 Modal 壳（NModal），语义、按钮与双语文案由本项目决定。
 * Esc / 遮罩点击 / 取消按钮都走 `settle(false)`，与原生 confirm 的「取消」等价。
 */
import { computed } from 'vue'
import { NModal } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { useConfirmHost } from '@/composables/useConfirm'
import AppIcon from './AppIcon.vue'

const { t } = useI18n()
const { current, settle } = useConfirmHost()
const show = computed(() => current.value !== null)

function onUpdateShow(next: boolean): void {
  if (!next) settle(false)
}
</script>

<template>
  <NModal :show="show" :mask-closable="true" :close-on-esc="true" @update:show="onUpdateShow">
    <section class="confirm-card" role="alertdialog" aria-modal="true" :aria-label="t('appearance.confirmTitle')">
      <div class="confirm-head">
        <AppIcon :name="current?.tone === 'danger' ? 'alert' : 'help'" :size="20" />
        <h2>{{ t('appearance.confirmTitle') }}</h2>
      </div>
      <p class="confirm-message">{{ current?.message }}</p>
      <footer class="confirm-actions">
        <button class="btn btn-ghost" type="button" @click="settle(false)">
          {{ t('common.cancel') }}
        </button>
        <button
          class="btn"
          :class="current?.tone === 'danger' ? 'danger' : ''"
          type="button"
          @click="settle(true)"
        >
          {{ t('appearance.confirm') }}
        </button>
      </footer>
    </section>
  </NModal>
</template>

<style scoped>
.confirm-card {
  width: 420px;
  max-width: 90vw;
  padding: var(--space-5);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow);
}

.confirm-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--accent);
}

.confirm-head h2 {
  margin: 0;
  color: var(--text);
  font-size: var(--font-size-md);
}

.confirm-message {
  margin: var(--space-4) 0 var(--space-5);
  font-size: var(--font-size-base);
  line-height: 1.6;
  white-space: pre-wrap;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
</style>
