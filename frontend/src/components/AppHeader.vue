<script setup lang="ts">
/**
 * 顶栏（v2.3 / UI-04，FR-UI-15..19）。
 *
 * 为什么从 App.vue 抽出来：顶栏现在有 4 段、8 个控件、3 条分隔线与「28px 栅格」的
 * 硬约束，再挤在外壳组件里就没人能单独验它。抽出来之后 `AppHeader.spec.ts` 能直接
 * 断 DOM 结构与交互，外壳只负责布局。
 *
 * 四段排序（从左到右，按「离用户多近」）：
 *   上下文（当前项目）→ 工具（检索 / AI）→ 偏好（外观 / 主题 / 语言）→ 账户（身份 / 登出）
 *
 * 三条不做的事：
 * 1. 不再有「关于」按钮——侧栏「系统设置 > 关于」是同一入口，顶栏留着只是占位。
 * 2. 登出不裸奔：`danger` 视觉 + 二次确认，误点不再直接退出。
 * 3. 主题快捷按钮只做 light ↔ dark 二态；`system` 归外观弹层（弹层里有全部三选）。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useLocaleStore } from '@/stores/locale'
import { useTheme, type ResolvedTheme } from '@/composables/useTheme'
import { useAiDrawer } from '@/composables/useAiDrawer'
import { confirmAction } from '@/composables/useConfirm'
import { preferenceApi } from '@/api/preferences'
import AppIcon from '@/components/AppIcon.vue'
import type { IconName } from '@/components/iconMap'

const props = defineProps<{ aiEnabled: boolean }>()
const emit = defineEmits<{
  (event: 'open-palette'): void
  (event: 'open-appearance'): void
  (event: 'logout'): void
}>()

const { t } = useI18n()
const auth = useAuthStore()
const localeStore = useLocaleStore()
const theme = useTheme()
const { toggleDrawer } = useAiDrawer()

/** 角色码 → 词条键。后端可能给全局角色（admin/engineer…），项目角色多了 owner/member。 */
const ROLE_KEYS = ['owner', 'admin', 'pm', 'engineer', 'member', 'viewer'] as const

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '')
const roleLabel = computed(() => {
  const codes = (auth.user?.roles ?? []).map((code) => code.toLowerCase())
  const hit = ROLE_KEYS.find((key) => codes.includes(key))
  return hit ? t(`enum.memberRole.${hit}`) : ''
})

/** 当前项目：只用来显示名称与启用状态点，不在这里改数据。 */
const currentProject = computed(() =>
  auth.projects.find((project) => project.id === auth.projectId) ?? null,
)
const projectMissing = computed(() => auth.projectId === null || !currentProject.value)

function changeProject(event: Event): void {
  const raw = (event.target as HTMLSelectElement).value
  auth.setProject(raw ? Number(raw) : null)
}

function changeLocale(event: Event): void {
  localeStore.setLocale((event.target as HTMLSelectElement).value as 'zh-CN' | 'en-US')
}

/**
 * 二态切换：目标值取当前**解析后**主题的反面。
 *
 * 用 `resolved` 而不是 `mode`：`system` 下用户看到的可能是暗色，此时点按钮应该是
 * 「切到亮色」，而不是「先跳到 system 的下一档」——后者会让按钮看起来没反应。
 */
const themeTarget = computed<ResolvedTheme>(() =>
  theme.resolved.value === 'dark' ? 'light' : 'dark',
)
const themeIcon = computed<IconName>(() => (themeTarget.value === 'dark' ? 'moon' : 'sun'))
const themeLabel = computed(() =>
  t('theme.switchTo', { mode: t(`theme.${themeTarget.value}`) }),
)

async function toggleTheme(): Promise<void> {
  const next = themeTarget.value
  theme.setMode(next)
  if (!auth.authenticated) return
  try {
    await preferenceApi.put({ theme: next })
  } catch {
    // 静默：主题是纯展示偏好，同步失败不回滚本地（否则用户点了没反应）
  }
}

async function askLogout(): Promise<void> {
  const ok = await confirmAction(t('app.logoutConfirm'), { tone: 'danger' })
  if (ok) emit('logout')
}
</script>

<template>
  <header class="app-header">
    <span class="app-header-title">{{ t('app.subtitle') }}</span>

    <div class="app-header-right" data-testid="header-actions">
      <!-- 段 1 上下文：当前项目。原生 select 保留（键盘与移动端都熟），但造型独立。 -->
      <div class="app-seg" data-seg="context">
        <label
          class="app-project"
          :class="{ 'is-empty': projectMissing }"
          :title="t('projectPicker.select')"
        >
          <AppIcon name="dashboard" :size="16" />
          <select
            class="app-project-select"
            :value="auth.projectId ?? ''"
            :aria-label="t('projectPicker.select')"
            @change="changeProject"
          >
            <option value="" disabled>{{ t('projectPicker.choose') }}</option>
            <option v-for="project in auth.projects" :key="project.id" :value="project.id">
              {{ project.name }}
            </option>
          </select>
          <span
            class="app-project-dot"
            :class="{ 'is-on': currentProject?.enabled !== false }"
            aria-hidden="true"
          />
        </label>
      </div>

      <span class="app-sep" role="presentation" aria-hidden="true" />

      <!-- 段 2 工具：检索 / AI。AI 受 ai.enabled 门控，关闭时整项不渲染。 -->
      <div class="app-seg" data-seg="tools">
        <button
          class="btn btn-ghost app-icon-btn"
          type="button"
          :aria-label="t('palette.open')"
          :title="`${t('palette.open')} (${t('palette.shortcut')})`"
          @click="emit('open-palette')"
        >
          <AppIcon name="search" :size="16" />
        </button>
        <button
          v-if="props.aiEnabled"
          class="btn btn-ghost app-icon-btn"
          type="button"
          :aria-label="t('aiPanel.toggle')"
          :title="t('aiPanel.toggle')"
          @click="toggleDrawer()"
        >
          <AppIcon name="sparkles" :size="16" />
        </button>
      </div>

      <span class="app-sep" role="presentation" aria-hidden="true" />

      <!-- 段 3 偏好：外观（全量选项）/ 主题（一键亮暗）/ 语言 -->
      <div class="app-seg" data-seg="prefs">
        <button
          class="btn btn-ghost app-icon-btn"
          type="button"
          :aria-label="t('appearance.open')"
          :title="t('appearance.open')"
          @click="emit('open-appearance')"
        >
          <AppIcon name="palette" :size="16" />
        </button>
        <button
          class="btn btn-ghost app-icon-btn app-theme-btn"
          type="button"
          :aria-label="themeLabel"
          :title="themeLabel"
          @click="toggleTheme"
        >
          <AppIcon :name="themeIcon" :size="16" />
        </button>
        <label class="app-lang" :title="t('app.localeSwitch')">
          <AppIcon name="language" :size="16" />
          <select
            class="app-lang-select"
            :value="localeStore.locale"
            :aria-label="t('app.localeSwitch')"
            @change="changeLocale"
          >
            <option value="zh-CN">{{ t('app.langZh') }}</option>
            <option value="en-US">{{ t('app.langEn') }}</option>
          </select>
        </label>
      </div>

      <span class="app-sep" role="presentation" aria-hidden="true" />

      <!-- 段 4 账户：身份 + 登出（danger + 二次确认） -->
      <div class="app-seg" data-seg="account">
        <span v-if="displayName" class="app-user">
          <span class="app-user-name">{{ displayName }}</span>
          <span v-if="roleLabel" class="app-user-role">{{ roleLabel }}</span>
        </span>
        <button
          class="btn btn-ghost danger app-logout"
          type="button"
          :aria-label="t('app.logout')"
          :title="t('app.logout')"
          @click="askLogout"
        >
          <AppIcon name="logout" :size="16" />
          <span>{{ t('app.logout') }}</span>
        </button>
      </div>
    </div>
  </header>
</template>
