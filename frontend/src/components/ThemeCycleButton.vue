<script setup lang="ts">
/**
 * 顶栏主题循环快捷按钮（v4.9.2 / OPT-03 / FR-49-10~12）。
 *
 * 复用 useTheme 既有 cycle()（system → light → dark → system），沿用 preferenceApi.put
 * 持久化到服务端；不新增 localStorage 键（cycle 仍写既有 'theme'），不新写状态机。
 * 与账户菜单里 AppearanceControls 读的是同一个 useTheme 单例，天然双向同步。
 */
import { useI18n } from 'vue-i18n'
import { useTheme, type ThemeMode } from '@/composables/useTheme'
import { preferenceApi } from '@/api/preferences'
import AppIcon from '@/components/AppIcon.vue'
import type { IconName } from '@/components/iconMap'

const { t } = useI18n()
const theme = useTheme()

const ICONS: Record<ThemeMode, IconName> = { system: 'system', light: 'sun', dark: 'moon' }

function onCycle(): void {
  const next = theme.cycle()
  void preferenceApi.put({ theme: next }).catch(() => undefined)
}
</script>

<template>
  <button
    class="btn btn-ghost app-quick-btn"
    type="button"
    :aria-label="t('nav.themeCycle')"
    :title="t('nav.themeCycle')"
    @click="onCycle"
  >
    <AppIcon :name="ICONS[theme.mode.value]" :size="16" />
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
</style>
