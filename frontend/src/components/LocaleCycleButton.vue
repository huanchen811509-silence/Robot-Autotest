<script setup lang="ts">
/**
 * 顶栏语言循环快捷按钮（v4.9.2 / OPT-03 / FR-49-10~12）。
 *
 * zh-CN ↔ en-US 两态循环（2 次回初态），沿用 localeStore.setLocale（写既有 'locale' 键）
 * 与 preferenceApi.put 持久化；不新增 localStorage 键、不新写状态机。
 * 与账户菜单里 AppearanceControls 读的是同一个 locale store，天然双向同步。
 */
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/stores/locale'
import { preferenceApi } from '@/api/preferences'
import type { AppLocale } from '@/locales'
import AppIcon from '@/components/AppIcon.vue'

const { t } = useI18n()
const localeStore = useLocaleStore()

function onCycle(): void {
  const next: AppLocale = localeStore.locale === 'zh-CN' ? 'en-US' : 'zh-CN'
  localeStore.setLocale(next)
  void preferenceApi.put({ locale: next }).catch(() => undefined)
}
</script>

<template>
  <button
    class="btn btn-ghost app-quick-btn"
    type="button"
    :aria-label="t('nav.localeCycle')"
    :title="t('nav.localeCycle')"
    @click="onCycle"
  >
    <AppIcon name="language" :size="16" />
    <span class="app-quick-lang">{{ localeStore.locale === 'zh-CN' ? 'ZH' : 'EN' }}</span>
  </button>
</template>

<style scoped>
.app-quick-btn {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.app-quick-lang {
  font-size: 11px;
  line-height: 1;
}
</style>
