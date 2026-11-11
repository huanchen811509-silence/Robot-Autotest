<script setup lang="ts">
/**
 * 用户管理（仅当前用户和关联项目）—— v4.11.0 / FR-v4.11-30；v4.12.0 增改密与外观。
 *
 * 这是**只读**页，回答两个问题：「我是谁」「我加入了哪些项目」。
 * v4.12.0 起多了两个「改自己」的区块（改口令、外观偏好）—— 它们仍是「关于我自己」，
 * 不是「管别人」，所以放在这里而不放 `/admin`。
 *
 * 三条边界：
 * 1. **不是** `/admin`。后者是系统级账号 CRUD（能力 `user:write`，可停用、改角色），
 *    与这里既不同语义也不同越权面（`00-RESEARCH.md` B6）。本版不复用它。
 * 2. 没有新增后端端点：身份取 `auth` store，关联项目取 `/api/projects/cards` 后按
 *    `own || joined` 过滤——不用 `myRole` 判，因为全局 admin 无成员关系时该值也是 `admin`。
 * 3. 页面上没有任何**对他人**的写操作控件（无新增 / 停用 / 改角色），只有打开项目的链接；
 *    改口令改的是会话里的自己，外观是纯本地偏好。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { meApi } from '@/api/iam'
import { projectCardsApi, type ProjectCard } from '@/api/projectCards'
import AppearanceControls from '@/components/AppearanceControls.vue'

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const cards = ref<ProjectCard[]>([])
const loading = ref(false)
const failed = ref(false)

const user = computed(() => auth.user)

/** 只保留「我创建或我加入」的项目；`own`/`joined` 缺省按 false 处理。 */
const linked = computed(() => cards.value.filter((card) => card.own === true || card.joined === true))

const roleText = computed(() => {
  const roles = user.value?.roles ?? []
  return roles.length > 0 ? roles.join(' · ') : '—'
})

function roleLabel(role: string | null): string {
  if (!role) return '—'
  const key = `memberRole.${role}`
  const text = t(key)
  // vue-i18n 缺键时回退成 key 本身，此时直接显示原始角色码
  return text === key ? role : text
}

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    cards.value = await projectCardsApi.list('7d', auth.projectId)
  } catch {
    failed.value = true
    cards.value = []
  } finally {
    loading.value = false
  }
}

/** 打开关联项目：先把它设为当前项目，再进项目看板（项目域页面都按当前项目取数）。 */
function openProject(card: ProjectCard): void {
  auth.setProject(card.id)
  void router.push('/overview')
}

/* ------------------------------------------------ 改自己的口令（FR-v4.12-23） */

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordBusy = ref(false)
const passwordError = ref('')
const passwordDone = ref('')

/**
 * 提交改密。
 *
 * 三条本地前置：都填了、两次新口令一致、新旧不同。前两条是纯手误防护，
 * 第三条后端也会拦——这里先拦是为了让用户在**还没发请求**时就看到原因。
 * 成功后立刻清空三个输入框：口令不长留在 DOM 里，截屏与录屏都不会带走它。
 */
async function submitPassword(): Promise<void> {
  passwordError.value = ''
  passwordDone.value = ''
  if (!oldPassword.value || !newPassword.value) {
    passwordError.value = t('settingsUsers.passwordRequired')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = t('settingsUsers.passwordMismatch')
    return
  }
  if (newPassword.value === oldPassword.value) {
    passwordError.value = t('settingsUsers.passwordSame')
    return
  }
  passwordBusy.value = true
  try {
    await meApi.changePassword(oldPassword.value, newPassword.value)
    passwordDone.value = t('settingsUsers.passwordDone')
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
  } catch (error) {
    // 后端消息在这里是有意义的（「原口令不正确」「口令必须包含大写字母」），原样透出
    passwordError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    passwordBusy.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('settingsUsers.title') }}</h1>
        <p class="muted">{{ t('settingsUsers.hint') }}</p>
      </div>
    </header>

    <section class="card">
      <h2>{{ t('settingsUsers.currentUser') }}</h2>
      <dl class="kv">
        <div>
          <dt>{{ t('settingsUsers.username') }}</dt>
          <dd class="mono" data-testid="su-username">{{ user?.username ?? '—' }}</dd>
        </div>
        <div>
          <dt>{{ t('settingsUsers.displayName') }}</dt>
          <dd data-testid="su-display-name">{{ user?.displayName ?? '—' }}</dd>
        </div>
        <div>
          <dt>{{ t('settingsUsers.roles') }}</dt>
          <dd data-testid="su-roles">{{ roleText }}</dd>
        </div>
        <div>
          <dt>{{ t('settingsUsers.projectCount') }}</dt>
          <dd data-testid="su-project-count">{{ linked.length }}</dd>
        </div>
      </dl>
    </section>

    <section class="card">
      <h2>{{ t('settingsUsers.linkedProjects') }}（{{ linked.length }}）</h2>
      <p v-if="failed" class="error">{{ t('global.loadFailed') }}</p>
      <p v-else-if="loading" class="muted">{{ t('global.loading') }}</p>
      <ul v-else-if="linked.length > 0" class="linked" data-testid="su-linked-projects">
        <li v-for="card in linked" :key="card.id" class="linked-row">
          <RouterLink to="/overview" class="linked-name" @click="openProject(card)">
            {{ card.name }}
          </RouterLink>
          <span class="mono muted">{{ card.code }}</span>
          <span class="badge">{{ roleLabel(card.myRole) }}</span>
        </li>
      </ul>
      <p v-else class="muted" data-testid="su-linked-empty">
        {{ t('settingsUsers.noProjects') }}
      </p>
    </section>

    <!-- FR-v4.12-23：改自己的口令。路径 `/api/me/password`，请求体里没有 userId。 -->
    <section class="card" data-testid="su-password">
      <h2>{{ t('settingsUsers.changePassword') }}</h2>
      <p class="muted">{{ t('settingsUsers.changePasswordHint') }}</p>
      <div class="password-grid">
        <label class="field">
          <span>{{ t('settingsUsers.oldPassword') }}</span>
          <input
            v-model="oldPassword"
            type="password"
            autocomplete="current-password"
            data-testid="su-old-password"
          />
        </label>
        <label class="field">
          <span>{{ t('settingsUsers.newPassword') }}</span>
          <input
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            data-testid="su-new-password"
          />
        </label>
        <label class="field">
          <span>{{ t('settingsUsers.confirmPassword') }}</span>
          <input
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            data-testid="su-confirm-password"
          />
        </label>
      </div>
      <p v-if="passwordError" class="error">{{ passwordError }}</p>
      <p v-if="passwordDone" class="success">{{ passwordDone }}</p>
      <div class="card-actions">
        <button
          type="button"
          class="btn btn-primary"
          :disabled="passwordBusy"
          data-testid="su-password-submit"
          @click="submitPassword"
        >
          {{ t('settingsUsers.submitPassword') }}
        </button>
      </div>
    </section>

    <!-- FR-v4.12-24：页面内切主题与强调色，与账户菜单里的外观弹窗共用同一份控件 -->
    <section class="card" data-testid="su-appearance">
      <h2>{{ t('appearance.title') }}</h2>
      <p class="muted">{{ t('settingsUsers.appearanceHint') }}</p>
      <AppearanceControls />
    </section>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.card {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.card h2 {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
}

.kv {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
  margin: 0;
}

.kv div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kv dt {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.kv dd {
  margin: 0;
}

.linked {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.linked-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 8px 4px;
  border-bottom: 1px solid var(--border);
}

.linked-row:last-child {
  border-bottom: 0;
}

.linked-name {
  color: var(--accent);
  text-decoration: none;
  min-width: 200px;
}

.linked-name:hover {
  text-decoration: underline;
}

.mono {
  font-family: var(--font-mono, monospace);
}

.badge {
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  font-size: 11px;
  color: var(--text-muted);
}

.error {
  color: var(--fail);
}

.success {
  color: var(--ok);
}

.password-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.field input {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  color: var(--text);
  font: inherit;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-3);
}

.btn {
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text);
  font: inherit;
  cursor: pointer;
}

.btn-primary {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
