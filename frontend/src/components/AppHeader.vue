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
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiModeStore, type UiMode } from '@/stores/uiMode'
import { useAiDrawer } from '@/composables/useAiDrawer'
import AppIcon from '@/components/AppIcon.vue'
import AccountMenu from '@/components/AccountMenu.vue'
import NotificationCenter from '@/components/NotificationCenter.vue'
import ThemeCycleButton from '@/components/ThemeCycleButton.vue'
import LocaleCycleButton from '@/components/LocaleCycleButton.vue'

const props = defineProps<{ aiEnabled: boolean }>()
const emit = defineEmits<{
  (event: 'open-palette'): void
  (event: 'open-shortcuts'): void
  (event: 'logout'): void
}>()

const { t } = useI18n()
const auth = useAuthStore()
const uiMode = useUiModeStore()
const router = useRouter()
const { toggleDrawer } = useAiDrawer()

/** 当前项目：只用来显示名称与启用状态点，不在这里改数据。 */
const currentProject = computed(() =>
  auth.projects.find((project) => project.id === auth.projectId) ?? null,
)
const projectMissing = computed(() => auth.projectId === null || !currentProject.value)

/** 双模式入口只对 ADMIN / PM 渲染；工程师完全看不到（FR-v4.5-06）。 */
const canGlobal = computed(() => {
  const roles = auth.user?.roles ?? []
  return roles.includes('admin') || roles.includes('pm')
})

async function switchMode(mode: UiMode): Promise<void> {
  if (mode === uiMode.mode) return
  const ok = await uiMode.setMode(mode)
  if (!ok) return
  await router.push(mode === 'global' ? '/g/projects' : uiMode.projectPath())
}

async function changeProject(event: Event): Promise<void> {
  const raw = (event.target as HTMLSelectElement).value
  auth.setProject(raw ? Number(raw) : null)
  // 在全局模式里选项目 = 进入该项目的项目模式（FR-v4.5-04）。
  if (uiMode.mode === 'global') {
    await uiMode.setMode('project')
    await router.push('/workbench')
  }
}
</script>

<template>
  <header class="app-header">
    <span class="app-header-brand">
      <span class="app-header-title">{{ t('app.subtitle') }}</span>
    </span>

    <div class="app-header-right" data-testid="header-actions">
      <!-- 段 0 模式：全局 / 项目（v4.5.0 / FR-v4.5-04；v4.12.0 / FR-v4.12-25 改为「全局在左、项目在右」）。
           顺序即优先级：全局是跨项目的总览视图，项目是下钻后的上下文，从左到右正好是「先总后分」。
           无权限时整组不渲染。 -->
      <div v-if="canGlobal" class="app-seg app-mode-switch" data-seg="mode" role="group"
           :aria-label="t('mode.switch')">
        <button
          class="app-mode-btn"
          type="button"
          :class="{ 'is-active': uiMode.mode === 'global' }"
          :aria-pressed="uiMode.mode === 'global'"
          @click="void switchMode('global')"
        >
          {{ t('mode.global') }}
        </button>
        <button
          class="app-mode-btn"
          type="button"
          :class="{ 'is-active': uiMode.mode === 'project' }"
          :aria-pressed="uiMode.mode === 'project'"
          @click="void switchMode('project')"
        >
          {{ t('mode.project') }}
        </button>
      </div>

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
            @change="void changeProject($event)"
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

      <!-- 段 2 工具：检索 / 通知 / AI / 主题 / 语言（v4.11.0 重排：账户必须在最右）。
           主题与语言原在 `header-quick` 容器里、位于账户之后，导致「最右是主题而不是账户」；
           现在统一进本段，顺序即「上下文 → 工具 → 身份」的直觉顺序。 -->
      <div class="app-seg app-tools" data-seg="tools">
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
        <ThemeCycleButton />
        <LocaleCycleButton />
      </div>

      <span class="app-sep" role="presentation" aria-hidden="true" />

      <!-- 段 3 账户：状态 / 外观 / 管理 / 会话四分区收在一个菜单里。必须在最右。 -->
      <div class="app-seg" data-seg="account">
        <AccountMenu
          @logout="emit('logout')"
          @open-shortcuts="emit('open-shortcuts')"
        />
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.app-mode-switch {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px;
  background: var(--surface-2);
}

.app-mode-btn {
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  padding: 2px var(--space-3);
  border-radius: 999px;
  cursor: pointer;
}

.app-mode-btn.is-active {
  background: var(--panel);
  color: var(--text);
  box-shadow: var(--shadow-sm);
}

.app-mode-btn:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}

/* v4.11.0 / FR-v4.11-09：主题 / 语言并入工具段后，段内间距在这里统一控制。
   顶栏本身已在 main.css 开启 flex-wrap:wrap，空间不足时整组换行而非横向溢出。 */
.app-tools {
  gap: 8px;
}

@media (max-width: 1080px) {
  .app-tools { gap: 4px; }
}

@media (max-width: 760px) {
  .app-tools { gap: 4px; }
}
</style>
