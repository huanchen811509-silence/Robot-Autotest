<script setup lang="ts">
/**
 * 顶栏（v4.0 / FR-v4.0-03）。
 *
 * v3.x 顶栏是 4 段 9 控件：项目 / 检索 / 帮助 / 通知 / AI / 外观 / 主题 / 语言 / 用户 / 登出。
 * v4.0 把「与当前人相关」的外观、主题、语言、关于、快捷键、登出全部收进账户菜单，
 * 顶栏只保留 5 个目标控件（AI 关闭时 4 个）：
 *   项目（上下文）→ 检索 / 通知 / AI（工具）→ 账户（当前人）
 *
 * 计数口径（自动化测试也按这个断）：只数可聚焦控件，不数分隔线、不数用户展示。
 * 帮助按钮不在顶栏另起入口——快捷键面板仍可用 `?` 或账户菜单打开。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useAiDrawer } from '@/composables/useAiDrawer'
import AppIcon from '@/components/AppIcon.vue'
import AccountMenu from '@/components/AccountMenu.vue'
import NotificationCenter from '@/components/NotificationCenter.vue'

const props = defineProps<{ aiEnabled: boolean }>()
const emit = defineEmits<{
  (event: 'open-palette'): void
  (event: 'open-shortcuts'): void
  (event: 'logout'): void
}>()

const { t } = useI18n()
const auth = useAuthStore()
const { toggleDrawer } = useAiDrawer()

/** 当前项目：只用来显示名称与启用状态点，不在这里改数据。 */
const currentProject = computed(() =>
  auth.projects.find((project) => project.id === auth.projectId) ?? null,
)
const projectMissing = computed(() => auth.projectId === null || !currentProject.value)

function changeProject(event: Event): void {
  const raw = (event.target as HTMLSelectElement).value
  auth.setProject(raw ? Number(raw) : null)
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

      <!-- 段 2 工具：检索 / 通知 / AI。AI 受 ai.enabled 门控，关闭时整项不渲染。 -->
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
        <NotificationCenter />
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

      <!-- 段 3 账户：状态 / 外观 / 管理 / 会话四分区收在一个菜单里 -->
      <div class="app-seg" data-seg="account">
        <AccountMenu
          @logout="emit('logout')"
          @open-shortcuts="emit('open-shortcuts')"
        />
      </div>
    </div>
  </header>
</template>
