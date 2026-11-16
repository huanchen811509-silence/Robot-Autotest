<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { NButton, NCheckbox, NInput, NModal } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { LOGOUT_INTENT_KEY } from '@/composables/useLogout'
import AppLogo from '@/components/AppLogo.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import FormField from '@/components/FormField.vue'
import { healthPing } from '@/api/http'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')

/**
 * 记住账号名 / 记住密码（自动登录）。
 *
 * 安全取舍（明确记录）：「记住密码」把凭据明文存在 localStorage——这是**内网测试
 * 平台**在「自动登录」诉求下的有意选择，不做混淆伪装（伪加密只提供安全感不提供
 * 安全性）。登录态本身仍走 auth_token（服务端会话，过期前 5 分钟自动续），记住的
 * 密码只在 token 失效后的重新登录时使用。
 *
 * 自动登录失败即清掉记住的密码：口令失效后留着它只会反复 401，平台的锁定策略是
 * 连续 5 次锁 15 分钟，放任自动重试会把人锁在门外（本次 admin 被锁同因）。账号名
 * 记忆保留——它无凭据语义。
 *
 * 例外（v2.6.1）：**刚点过退出**（sessionStorage 退出意图标记）时不自动登录也不预填
 * 密码——退出的语义是结束本次会话（可能要换账号），不该被自动登回旧账号。
 * 401 被动过期不走退出路径，自动登录照常。见 `useLogout.ts` 的 LOGOUT_INTENT_KEY。
 */
const REMEMBER_USER_KEY = 'login_remember_username'
const SAVED_CREDENTIALS_KEY = 'login_saved_credentials'
const rememberUsername = ref(false)
const rememberPassword = ref(false)
// 挂载级（非模块级）：每次进登录页只允许自动尝试一次，且随组件重建重置
let autoLoginAttempted = false

function savedCredentials(): { username: string; password: string } | null {
  try {
    const raw = localStorage.getItem(SAVED_CREDENTIALS_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as { username?: string; password?: string }
    if (parsed.username && parsed.password) {
      return { username: parsed.username, password: parsed.password }
    }
  } catch { /* 损坏的存储当作没有 */ }
  return null
}

function persistRemember(name: string, pass: string): void {
  if (rememberPassword.value) {
    localStorage.setItem(SAVED_CREDENTIALS_KEY, JSON.stringify({ username: name, password: pass }))
    localStorage.setItem(REMEMBER_USER_KEY, name)
    return
  }
  localStorage.removeItem(SAVED_CREDENTIALS_KEY)
  if (rememberUsername.value) {
    localStorage.setItem(REMEMBER_USER_KEY, name)
  } else {
    localStorage.removeItem(REMEMBER_USER_KEY)
  }
}

function forgetPassword(): void {
  localStorage.removeItem(SAVED_CREDENTIALS_KEY)
  rememberPassword.value = false
}

/**
 * 不写 `v-model:value`：`NInput` 的回调值类型是 `string | null`，直接绑到 `ref<string>`
 * 会被 `vue-tsc` 判成不兼容赋值（同 UX-04b 第五批 `NSelect` 的处理）。收敛在一个 setter 里。
 */
function setUsername(value: string): void {
  username.value = value
}

function setPassword(value: string): void {
  password.value = value
}

const submitting = ref(false)
const error = ref<ErrorLine | null>(null)
/**
 * v5.20.0 / FR-v5.20-07：登录页页脚显示版本号（取自 `/_ping`，与「关于」同源）。
 *
 * 取不到就不显示——登录页不该因为探活失败而多出一条错误提示，用户此刻只关心能不能登进去。
 */
const version = ref('')
const helpOpen = ref(false)

async function submit(): Promise<void> {
  error.value = null
  if (!username.value.trim() || !password.value) {
    error.value = { summary: t('login.required'), detail: null }
    return
  }
  submitting.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    persistRemember(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/workbench'
    await router.replace(redirect)
  } catch (e) {
    error.value = localizeError(e)
    if (autoLoginAttempted) {
      // 自动登录那一次失败的：清掉失效密码，别让下次自动重试去撞锁定
      forgetPassword()
      autoLoginAttempted = false
    }
  } finally {
    submitting.value = false
  }
}

/** 版本号取自 `/_ping`，与「关于」对话框同源；失败静默（登录页不该多一条错误条）。 */
async function loadVersion(): Promise<void> {
  try {
    const ping = await healthPing()
    version.value = (ping?.version ?? '').trim()
  } catch {
    version.value = ''
  }
}

onMounted(() => {
  void loadVersion()
  const savedUser = localStorage.getItem(REMEMBER_USER_KEY)
  if (savedUser) {
    username.value = savedUser
    rememberUsername.value = true
  }
  // 刚点过退出（本标签页一次性标记）：**不自动登录、也不预填密码**——退出后常要换账号，
  // 预填旧账号密码 + 新用户名会撞 401，还可能去撞「连续 5 次锁 15 分钟」。账号名记忆保留：
  // 它无凭据语义。标记消费即失效，手动登录成功后凭据按勾选重新落盘，自动登录自然恢复。
  if (sessionStorage.getItem(LOGOUT_INTENT_KEY)) {
    sessionStorage.removeItem(LOGOUT_INTENT_KEY)
    return
  }
  const saved = savedCredentials()
  if (saved) {
    username.value = saved.username
    password.value = saved.password
    rememberUsername.value = true
    rememberPassword.value = true
    // token 还有效时路由守卫不会进本页；进了说明需要重新登录 → 自动提交一次
    if (!auth.authenticated && !autoLoginAttempted) {
      autoLoginAttempted = true
      void submit()
    }
  }
})

</script>

<template>
  <div class="login-wrap">
    <form class="login-card" @submit.prevent="submit">
      <!-- 品牌区（UX-05）：登录页是唯一没有侧边导航的整屏页面，标记 + 定位语是它唯一的身份线索 -->
      <div class="login-brand">
        <span class="login-brand-tile"><AppLogo :size="44" /></span>
        <h1 class="login-title">{{ t('app.title') }}</h1>
        <p class="login-tagline">{{ t('app.subtitle') }}</p>
      </div>
      <p class="muted">{{ t('login.subtitle') }}</p>

      <FormField v-slot="{ describedBy }" :label="t('login.username')" required>
        <NInput
          :value="username"
          :input-props="{ 'aria-describedby': describedBy, autocomplete: 'username' }"
          @update:value="setUsername"
        />
      </FormField>
      <FormField v-slot="{ describedBy }" :label="t('login.password')" required>
        <NInput
          :value="password"
          type="password"
          show-password-on="click"
          :input-props="{ 'aria-describedby': describedBy, autocomplete: 'current-password' }"
          @update:value="setPassword"
        />
      </FormField>

      <div class="login-remember">
        <NCheckbox v-model:checked="rememberUsername">
          {{ t('login.rememberUsername') }}
        </NCheckbox>
        <NCheckbox v-model:checked="rememberPassword">
          {{ t('login.rememberPassword') }}
        </NCheckbox>
      </div>

      <ErrorLineView v-if="error" :error="error" />
      <!--
        `attr-type="submit"` 而不是 `type`：`type` 是库按钮的语义色档（primary/default），
        HTML 的提交行为要用 `attr-type` 透传到原生 `<button>` 上。漏了它回车就提交不了。
      -->
      <NButton
        class="login-btn"
        type="primary"
        attr-type="submit"
        :loading="submitting"
        :disabled="submitting"
      >
        {{ submitting ? t('login.submitting') : t('login.submit') }}
      </NButton>
      <p class="muted">{{ t('login.hint') }}</p>
    </form>

    <!--
      v5.20.0 / FR-v5.20-07：登录页是唯一没有账户菜单的整屏页面，
      用户卡在登录这一步时最需要一个「找谁/怎么弄」的入口。
      版本号与「关于」同源（/_ping），不在这里另抄一份。
    -->
    <footer class="login-footer">
      <span v-if="version" class="muted" data-testid="login-version">v{{ version }}</span>
      <button type="button" class="login-help" data-testid="login-help" @click="helpOpen = true">
        {{ t('login.help') }}
      </button>
    </footer>

    <NModal v-model:show="helpOpen">
      <div class="login-help-card" role="dialog" aria-modal="true" :aria-label="t('login.helpTitle')">
        <h2 class="login-help-title">{{ t('login.helpTitle') }}</h2>
        <ul class="login-help-list">
          <li>{{ t('login.helpAccount') }}</li>
          <li>{{ t('login.helpPermission') }}</li>
          <li>{{ t('login.helpShortcut') }}</li>
        </ul>
        <NButton size="small" @click="helpOpen = false">{{ t('common.close') }}</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.login-footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
  font-size: var(--font-size-sm);
}
.login-help {
  padding: 0;
  border: none;
  background: none;
  color: var(--accent);
  cursor: pointer;
  font-size: var(--font-size-sm);
}
.login-help-card {
  box-sizing: border-box;
  width: min(520px, 92vw);
  padding: var(--space-5);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--panel);
}
.login-help-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
  color: var(--text);
}
.login-help-list {
  margin: 0 0 var(--space-4);
  padding-left: 1.1em;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.7;
}
.login-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  text-align: center;
}

.login-tagline {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

/* main.css 里 `.login-title` 还留着给别的场景用的下边距，品牌区靠 gap 排版，这里清掉 */
.login-brand-tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 18px;
  border: 1px solid var(--border);
  background: var(--panel);
  box-shadow: var(--shadow-sm);
}

.login-brand .login-title {
  margin: 0;
}

.login-remember {
  display: flex;
  gap: var(--space-4);
  margin-bottom: var(--space-3);
}
</style>
