<script setup lang="ts">
/**
 * 账户菜单（FR-v4.0-03）。
 *
 * 背景：v3.x 顶栏平铺了 9 个控件（外观 / 主题 / 语言 / 登出各自一枚按钮），
 * v4.0 目标是把「与当前人相关」的东西收进一个菜单，顶栏只留
 * 项目 / 检索 / 通知 / AI / 账户 五枚（AI 关闭时四枚）。
 *
 * 菜单固定四分区，顺序不随角色变化：
 *   状态（只读）→ 外观 → 管理（按能力显隐）→ 会话
 *
 * 三条约束：
 * 1. 状态区只读：渲染 span，不出现 input/select，避免「看着能改、其实不生效」；
 * 2. 管理区按能力过滤，不是按角色硬编码——pm 有 settings:read 也能看到设置入口；
 * 3. 后端 health / build-info 拉取失败时降级为「降级」，菜单本身仍可用。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { confirmAction } from '@/composables/useConfirm'
import { useFocusTrap } from '@/composables/useFocusTrap'
import { capabilitiesForRole, roleFromCodes } from '@/config/navigation'
import { settingsApi } from '@/api/settings'
import { healthPing } from '@/api/http'
import AppIcon from '@/components/AppIcon.vue'
import AppearanceControls from '@/components/AppearanceControls.vue'

const emit = defineEmits<{
  (event: 'logout'): void
  (event: 'open-shortcuts'): void
}>()

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const open = ref(false)
const root = ref<HTMLElement | null>(null)
const panel = ref<HTMLElement | null>(null)

const ROLE_KEYS = ['owner', 'admin', 'pm', 'engineer', 'member', 'viewer'] as const

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '')
const initial = computed(() => displayName.value.trim().charAt(0).toUpperCase() || '?')
const roleLabel = computed(() => {
  const codes = (auth.user?.roles ?? []).map((code) => code.toLowerCase())
  const hit = ROLE_KEYS.find((key) => codes.includes(key))
  return hit ? t(`enum.memberRole.${hit}`) : ''
})
const currentProject = computed(
  () => auth.projects.find((project) => project.id === auth.projectId) ?? null,
)
const capabilities = computed(() =>
  capabilitiesForRole(roleFromCodes(auth.user?.roles)),
)
const canSeeSettings = computed(() => capabilities.value.includes('settings:read'))
const canSeeAudit = computed(() => capabilities.value.includes('audit:read'))
const canSeeOps = computed(() => auth.isAdmin)

const health = ref<'unknown' | 'ok' | 'degraded'>('unknown')
const version = ref('')

useFocusTrap(panel, open)

let onPointerDown: ((event: MouseEvent) => void) | null = null

function close(): void {
  open.value = false
}

function toggle(): void {
  open.value = !open.value
}

async function loadStatus(): Promise<void> {
  try {
    const state = await settingsApi.health()
    health.value = state.status === 'ok' ? 'ok' : 'degraded'
  } catch {
    health.value = 'degraded'
  }
  try {
    const ping = await healthPing()
    version.value = ping.version || ''
  } catch {
    // 版本拿不到就不显示，不编造
  }
}

watch(open, (value) => {
  if (value) {
    void loadStatus()
    return
  }
})

onMounted(() => {
  // 点面板外关闭：顶栏其他控件仍可点（浮层不铺满屏，见 FR-v4.0-02）
  onPointerDown = (event: MouseEvent) => {
    if (!open.value) return
    const target = event.target as Node | null
    if (target && root.value?.contains(target)) return
    close()
  }
  document.addEventListener('pointerdown', onPointerDown, true)
})

onBeforeUnmount(() => {
  if (onPointerDown) document.removeEventListener('pointerdown', onPointerDown, true)
})

function go(path: string): void {
  close()
  void nextTick(() => router.push(path))
}

async function askLogout(): Promise<void> {
  const ok = await confirmAction(t('app.logoutConfirm'), { tone: 'danger' })
  if (ok) {
    close()
    emit('logout')
  }
}
</script>

<template>
  <div ref="root" class="account-menu">
    <button
      class="btn btn-ghost account-trigger"
      type="button"
      :aria-label="t('accountMenu.trigger')"
      :aria-expanded="open"
      aria-haspopup="menu"
      :title="displayName"
      @click="toggle"
    >
      <span class="account-avatar" aria-hidden="true">{{ initial }}</span>
      <span v-if="displayName" class="account-trigger__name">{{ displayName }}</span>
      <AppIcon name="chevronDown" :size="16" aria-hidden="true" />
    </button>

    <section
      v-if="open"
      ref="panel"
      class="account-panel"
      role="menu"
      :aria-label="t('accountMenu.ariaLabel')"
      tabindex="-1"
      @keydown.esc.stop="close"
    >
      <!-- 分区 1：状态（只读） -->
      <div class="account-section">
        <div class="account-identity">
          <span class="account-avatar account-avatar--lg" aria-hidden="true">{{ initial }}</span>
          <div>
            <p class="account-name">{{ displayName || t('accountMenu.anonymous') }}</p>
            <p class="account-role">{{ roleLabel || t('accountMenu.roleUnknown') }}</p>
          </div>
        </div>
        <dl class="account-status">
          <div>
            <dt>{{ t('accountMenu.project') }}</dt>
            <dd>{{ currentProject?.name || t('accountMenu.noProject') }}</dd>
          </div>
          <div>
            <dt>{{ t('accountMenu.health') }}</dt>
            <dd class="account-health" :data-state="health">
              <span class="account-health__dot" aria-hidden="true" />
              <span>
                {{
                  health === 'ok'
                    ? t('accountMenu.healthOk')
                    : health === 'degraded'
                      ? t('accountMenu.healthDegraded')
                      : t('accountMenu.healthUnknown')
                }}
              </span>
            </dd>
          </div>
          <div>
            <dt>{{ t('accountMenu.version') }}</dt>
            <dd class="account-version">{{ version || t('accountMenu.versionUnknown') }}</dd>
          </div>
        </dl>
      </div>

      <!-- 分区 2：外观 -->
      <div class="account-section">
        <h3 class="account-section__title">{{ t('accountMenu.appearance') }}</h3>
        <AppearanceControls />
      </div>

      <!-- 分区 3：管理（按能力显隐；帮助入口对所有角色可见） -->
      <div class="account-section">
        <h3 class="account-section__title">{{ t('accountMenu.admin') }}</h3>
        <div class="account-links">
          <button
            v-if="canSeeSettings"
            type="button"
            class="account-link"
            role="menuitem"
            @click="go('/projects/new')"
          >
            <AppIcon name="plus" :size="16" />
            <span>{{ t('accountMenu.newProject') }}</span>
          </button>
          <button
            v-if="canSeeSettings"
            type="button"
            class="account-link"
            role="menuitem"
            @click="go('/settings')"
          >
            <AppIcon name="admin" :size="16" />
            <span>{{ t('accountMenu.settings') }}</span>
          </button>
          <button
            v-if="canSeeAudit"
            type="button"
            class="account-link"
            role="menuitem"
            @click="go('/admin/audit')"
          >
            <AppIcon name="audit" :size="16" />
            <span>{{ t('accountMenu.audit') }}</span>
          </button>
          <button
            v-if="canSeeOps"
            type="button"
            class="account-link"
            role="menuitem"
            @click="go('/admin/ops')"
          >
            <AppIcon name="sliders" :size="16" />
            <span>{{ t('accountMenu.ops') }}</span>
          </button>
          <button
            type="button"
            class="account-link"
            role="menuitem"
            @click="go('/about')"
          >
            <AppIcon name="info" :size="16" />
            <span>{{ t('about.open') }}</span>
          </button>
          <button
            type="button"
            class="account-link"
            role="menuitem"
            @click="
              close();
              emit('open-shortcuts')
            "
          >
            <AppIcon name="help" :size="16" />
            <span>{{ t('shortcuts.title') }}</span>
          </button>
          <button
            type="button"
            class="account-link"
            role="menuitem"
            @click="go('/changelog')"
          >
            <AppIcon name="reports" :size="16" />
            <span>{{ t('accountMenu.changelog') }}</span>
          </button>
        </div>
      </div>

      <!-- 分区 4：会话 -->
      <div class="account-section account-section--last">
        <h3 class="account-section__title">{{ t('accountMenu.session') }}</h3>
        <div class="account-links">
          <button type="button" class="account-link" role="menuitem" @click="go('/login')">
            <AppIcon name="users" :size="16" />
            <span>{{ t('accountMenu.switchAccount') }}</span>
          </button>
          <button
            type="button"
            class="account-link account-link--danger"
            role="menuitem"
            @click="askLogout"
          >
            <AppIcon name="logout" :size="16" />
            <span>{{ t('app.logout') }}</span>
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.account-menu {
  position: relative;
  display: inline-flex;
}

.account-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.account-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--accent);
  background: var(--accent-soft);
  border-radius: 50%;
  font-size: var(--font-size-sm);
  font-weight: 700;
}

.account-avatar--lg {
  width: 36px;
  height: 36px;
  font-size: var(--font-size-md);
}

.account-trigger__name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-panel {
  position: absolute;
  top: calc(100% + var(--space-2));
  right: 0;
  z-index: var(--z-overlay);
  width: 360px;
  max-width: calc(100vw - var(--space-4));
  max-height: 70vh;
  overflow-y: auto;
  padding: var(--space-4);
  color: var(--text);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow);
}

.account-panel:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.account-section {
  padding-bottom: var(--space-4);
  margin-bottom: var(--space-4);
  border-bottom: 1px solid var(--border);
}

.account-section--last {
  padding-bottom: 0;
  margin-bottom: 0;
  border-bottom: 0;
}

.account-section__title {
  margin: 0 0 var(--space-2);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.account-identity {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.account-name {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: 600;
}

.account-role {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.account-status {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
  margin: 0;
  padding: var(--space-2);
  background: var(--surface-2);
  border-radius: var(--radius-lg);
}

.account-status dt {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.account-status dd {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-size-xs);
}

.account-health {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.account-health__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--warn);
}

.account-health[data-state='ok'] .account-health__dot {
  background: var(--ok);
}

.account-version {
  font-family: var(--font-mono, monospace);
}

.account-links {
  display: grid;
  gap: var(--space-1);
}

.account-link {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-2);
  color: var(--text);
  background: transparent;
  border: 0;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  text-align: left;
  cursor: pointer;
}

.account-link:hover {
  background: var(--surface-hover);
}

.account-link:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.account-link--danger {
  color: var(--fail);
}

@media (prefers-reduced-motion: reduce) {
  .account-link,
  .account-panel {
    transition: none;
  }
}

@media (max-width: 760px) {
  .account-trigger__name {
    display: none;
  }

  .account-panel {
    width: 320px;
  }
}
</style>
