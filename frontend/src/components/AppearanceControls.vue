<script setup lang="ts">
/**
 * 外观控制（FR-v4.0-03）：主题三态 / 六强调色 / 双密度 / 语言。
 *
 * 为什么从 AppearanceDialog 抽出来：v4.0 把外观入口收进账户菜单，菜单里需要同一套
 * 控件。复制一份必然会「主题能选、语言漏掉」地漂移，所以只保留这一份实现。
 * 六枚色板变量仍静态列在 SWATCH_VARS：拼 `var(--preset-${name})` 会被 check_tokens.py
 * 当成悬空引用，是守门假红，也确实让「变量到底有没有定义」无法机检。
 */
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/stores/locale'
import { useAuthStore } from '@/stores/auth'
import { ACCENTS, useAccent, type AccentName } from '@/composables/useAccent'
import { DENSITIES, useDensity, type DensityName } from '@/composables/useDensity'
import { useTheme, type ThemeMode } from '@/composables/useTheme'
import { preferenceApi } from '@/api/preferences'
import AppIcon from './AppIcon.vue'
import type { IconName } from './iconMap'

const { t } = useI18n()
const auth = useAuthStore()
const localeStore = useLocaleStore()
const accent = useAccent()
const density = useDensity()
const theme = useTheme()

const THEME_MODES: ThemeMode[] = ['system', 'light', 'dark']
const THEME_ICONS: Record<ThemeMode, IconName> = {
  system: 'system',
  light: 'sun',
  dark: 'moon',
}

const SWATCH_VARS: Record<AccentName, string> = {
  crimson: 'var(--preset-crimson)',
  blue: 'var(--preset-blue)',
  gold: 'var(--preset-gold)',
  orange: 'var(--preset-orange)',
  violet: 'var(--preset-violet)',
  jade: 'var(--preset-jade)',
}

async function chooseAccent(name: AccentName): Promise<void> {
  if (!accent.setAccent(name)) return
  if (!auth.authenticated) return
  try {
    await preferenceApi.put({ accent: name })
  } catch {
    // 纯展示偏好：同步失败不影响本次使用
  }
}

async function chooseTheme(mode: ThemeMode): Promise<void> {
  theme.setMode(mode)
  if (!auth.authenticated) return
  try {
    await preferenceApi.put({ theme: mode })
  } catch {
    // 同上
  }
}

async function chooseDensity(name: DensityName): Promise<void> {
  if (!density.setDensity(name)) return
  if (!auth.authenticated) return
  try {
    await preferenceApi.put({ density: name })
  } catch {
    // 同上
  }
}

function changeLocale(event: Event): void {
  localeStore.setLocale((event.target as HTMLSelectElement).value as 'zh-CN' | 'en-US')
}
</script>

<template>
  <div class="appearance-controls">
    <div class="appearance-section">
      <h3>{{ t('appearance.theme') }}</h3>
      <div class="appearance-modes">
        <button
          v-for="mode in THEME_MODES"
          :key="mode"
          class="mode-btn"
          :class="{ selected: theme.mode.value === mode }"
          type="button"
          :aria-pressed="theme.mode.value === mode"
          @click="chooseTheme(mode)"
        >
          <AppIcon :name="THEME_ICONS[mode]" :size="16" />
          <span>{{ t(`theme.${mode}`) }}</span>
        </button>
      </div>
    </div>

    <div class="appearance-section">
      <h3>{{ t('appearance.accent') }}</h3>
      <div class="appearance-swatches">
        <button
          v-for="name in ACCENTS"
          :key="name"
          class="swatch"
          :class="{ selected: accent.accent.value === name }"
          type="button"
          :aria-pressed="accent.accent.value === name"
          :aria-label="t(`appearance.presets.${name}`)"
          @click="chooseAccent(name)"
        >
          <span class="swatch-chip" :style="{ background: SWATCH_VARS[name] }" />
          <span class="swatch-label">{{ t(`appearance.presets.${name}`) }}</span>
        </button>
      </div>
    </div>

    <div class="appearance-section">
      <h3>{{ t('appearance.density') }}</h3>
      <div class="appearance-modes">
        <button
          v-for="name in DENSITIES"
          :key="name"
          class="mode-btn"
          :class="{ selected: density.density.value === name }"
          type="button"
          :aria-pressed="density.density.value === name"
          @click="chooseDensity(name)"
        >
          <span>{{ t(`appearance.densities.${name}`) }}</span>
        </button>
      </div>
    </div>

    <div class="appearance-section">
      <h3>{{ t('app.localeSwitch') }}</h3>
      <label class="appearance-lang">
        <AppIcon name="language" :size="16" />
        <select :value="localeStore.locale" :aria-label="t('app.localeSwitch')" @change="changeLocale">
          <option value="zh-CN">{{ t('app.langZh') }}</option>
          <option value="en-US">{{ t('app.langEn') }}</option>
        </select>
      </label>
    </div>
  </div>
</template>

<style scoped>
.appearance-controls {
  display: block;
}

.appearance-section {
  margin-bottom: var(--space-4);
}

.appearance-section:last-child {
  margin-bottom: 0;
}

.appearance-section h3 {
  margin: 0 0 var(--space-2);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.appearance-modes,
.appearance-swatches {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.mode-btn,
.swatch {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  color: var(--text);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, transform 0.1s;
}

.mode-btn:hover,
.swatch:hover {
  background: var(--surface-hover);
  border-color: var(--border-strong);
}

.mode-btn:active,
.swatch:active {
  transform: translateY(1px);
  background: var(--surface-selected);
}

.mode-btn:focus-visible,
.swatch:focus-visible,
.appearance-lang select:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.mode-btn.selected,
.swatch.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.swatch-chip {
  width: 16px;
  height: 16px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
}

.swatch-label {
  white-space: nowrap;
}

.appearance-lang {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text);
  font-size: var(--font-size-sm);
}

.appearance-lang select {
  padding: var(--space-1) var(--space-2);
  color: var(--text);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
</style>
