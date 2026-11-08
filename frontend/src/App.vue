<script setup lang="ts">
/**
 * 应用外壳：侧栏 + 顶栏 + 工作区 + 抽屉。
 *
 * v2.3 / UI-01 + UI-04 之后外壳只做两件事：
 * 1. 侧栏（结构在 config/navigation.ts，渲染在 components/AppNav.vue；这里只做能力过滤）；
 * 2. 布局（顶栏与抽屉的 grid 区域）。
 *
 * 顶栏已抽成 `components/AppHeader.vue`：它有 4 段 / 8 控件 / 3 条分隔线的硬约束，
 * 留在外壳里没人能单独验它。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { NConfigProvider } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/stores/locale'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { useAccent } from '@/composables/useAccent'
import { useDensity } from '@/composables/useDensity'
import { useNaiveTheme } from '@/composables/naiveTheme'
import { preferenceApi } from '@/api/preferences'
import { aiPanelApi } from '@/api/aiPanel'
import AppIcon from '@/components/AppIcon.vue'
import AppLogo from '@/components/AppLogo.vue'
import AppHeader from '@/components/AppHeader.vue'
import AppNav from '@/components/AppNav.vue'
import AppearanceDialog from '@/components/AppearanceDialog.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import AiPanelDrawer from '@/components/AiPanelDrawer.vue'
import { useAiDrawer } from '@/composables/useAiDrawer'
import { performLogout } from '@/composables/useLogout'
import {
  capabilitiesForRole,
  roleFromCodes,
  visibleNavByCapabilities,
  type NavNode,
} from '@/config/navigation'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const localeStore = useLocaleStore()
const auth = useAuthStore()
const theme = useTheme()
const accent = useAccent()
const density = useDensity()
const { overrides } = useNaiveTheme()
// W7 / UIX-02: side rail collapse + AI drawer share one grid, never an overlay.
const { open: aiOpen, width: aiWidth, sideCollapsed, toggleSide,
        setWidth: setAiWidth } = useAiDrawer()

// index.html 的内联脚本已设过 data-theme / data-accent（防闪）；这里再跑一次是兜底：
// 万一内联脚本被 CSP 拦掉，或 localStorage 里的值与当前状态不一致，也需要纠正。
theme.init()
accent.init()
density.init()

const appearanceOpen = ref(false)

// 两级导航：结构在 config/navigation.ts，这里只管「按能力过滤 + 展开态」。
// v2.0 起不再是单一 adminOnly：四角色能力集决定入口显隐，越权仍由后端 403 兜底。
const nav = computed<NavNode[]>(() =>
  visibleNavByCapabilities(capabilitiesForRole(roleFromCodes(auth.user?.roles))),
)

// NAV-04：命令面板全局快捷键。打印页/登录页不响应，避免无头打印被快捷键干扰。
const paletteOpen = ref(false)
// FR-AI-07：顶栏入口与抽屉按 /api/ai/state 的 enabled 显隐（顶栏按钮已移到 AppHeader）。
const aiEnabled = ref(false)
function onGlobalKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    if (isStandalone.value) return
    event.preventDefault()
    paletteOpen.value = !paletteOpen.value
  }
}

const isLogin = computed(() => route.path === '/login')
// 打印页（RPT-05）必须无侧边栏/导航：由无头浏览器直接访问，样式要 A4 友好。
const isStandalone = computed(
  () => isLogin.value || Boolean((route.meta as Record<string, unknown>).print),
)

// 浏览器标签页标题跟随语言（NAM-01）：index.html 只能写一个静态标题，
// 不在这里同步的话，英文界面标签页仍写着中文产品名。
watch(
  () => localeStore.locale,
  () => {
    document.title = t('app.title')
  },
  { immediate: true },
)

async function logout(): Promise<void> {
  await performLogout(router)
}

onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onUnmounted(() => window.removeEventListener('keydown', onGlobalKeydown))

// 偏好跟随用户：同一浏览器换账号登录（App 不重挂）也要切到当前用户的服务端偏好，
// 只靠启动时拉一次的话，第二个登录的人会一直用第一个人的主题/强调色。
// 登出时本地已回默认（见 logout），这里在 user 置位后拉当前用户的值覆盖。
watch(
  () => auth.user?.id,
  async (id) => {
    if (!id) return
    try {
      const prefs = await preferenceApi.get()
      theme.applyRemote(prefs.theme)
      accent.applyRemote(prefs.accent)
      density.applyRemote(prefs.density)
    } catch {
      // 偏好单独兜住：它失败不该把人踢下线（否则后端少一个接口就登不进来了）
    }
  },
)

// FR-AI-07：state 是发现端点，ai.enabled=false 时也返回 enabled=false，据此隐藏入口。
watch(
  () => auth.projectId,
  async (pid) => {
    if (!pid) {
      aiEnabled.value = false
      return
    }
    try {
      const s = await aiPanelApi.state(pid)
      aiEnabled.value = s.enabled === true
    } catch {
      aiEnabled.value = false
    }
  },
  { immediate: true },
)

onMounted(async () => {
  if (!auth.authenticated) return
  try {
    await Promise.all([auth.loadMe(), auth.loadProjects()])
  } catch {
    // 令牌失效（401）或后端不可达：回登录页，避免半登录态继续发请求
    auth.clear()
    await router.replace('/login')
    return
  }
})
</script>

<template>
  <!-- abstract 保证不产生额外 DOM：不影响打印页 A4 版式与 .app-shell 的 flex 布局 -->
  <NConfigProvider abstract :theme-overrides="overrides">
    <RouterView v-if="isStandalone" />

    <!-- v2.6 / FR-AI-14：抽屉宽度由 CSS 变量驱动。改宽度只改这一个变量，
         不再像 v2.5 之前那样「状态改了、grid 里还硬编码 340px」，导致调宽按钮只改数字不改布局。 -->
    <div
      v-else
      class="app-shell"
      :class="{ 'has-drawer': aiOpen, 'side-collapsed': sideCollapsed }"
      :style="{ '--ai-drawer-w': `${aiWidth}px` }"
    >
      <aside class="app-side">
        <!-- v2.3 / FR-UI-18：折叠按钮从顶栏移到侧栏头部——它是外壳控制，不是页面动作。
             折叠后品牌区隐藏，按钮居中保留（否则侧栏折叠后无法展开，只能清缓存）。 -->
        <div class="app-side-head">
          <div class="app-brand"><AppLogo with-wordmark /></div>
          <button
            class="btn btn-ghost app-side-toggle"
            type="button"
            :aria-label="t('layout.toggleSide')"
            :title="t('layout.toggleSide')"
            @click="toggleSide()"
          >
            <AppIcon :name="sideCollapsed ? 'chevronRight' : 'chevronDown'" :size="16" />
          </button>
        </div>

        <AppNav :nodes="nav" />
      </aside>

      <AppHeader
        :ai-enabled="aiEnabled"
        @open-palette="paletteOpen = true"
        @open-appearance="appearanceOpen = true"
        @logout="logout"
      />

      <main class="app-main">
        <p v-if="auth.passwordExpired" class="banner-warn">{{ t('login.passwordExpired') }}</p>

        <section class="app-body">
          <RouterView />
        </section>
      </main>

      <AppearanceDialog v-model:open="appearanceOpen" />
      <ConfirmModal />
      <CommandPalette v-model:open="paletteOpen" />
      <AiPanelDrawer v-if="aiEnabled" v-model:open="aiOpen" :width="aiWidth" @update:width="setAiWidth" :project-id="auth.projectId ?? 0" />
    </div>
  </NConfigProvider>
</template>
