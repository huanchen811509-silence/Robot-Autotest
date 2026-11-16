<script setup lang="ts">
/**
 * 403 页（NAV-01 / FR-v2.0-08；v5.21.0 / FR-v5.21-03 重写）。
 *
 * 原版只有一句「无权限访问」：用户不知道自己是谁、缺什么、该找谁。
 * v5.21 的判据要求文案含**角色名**与**申请路径**——所以这里读 `auth.user.roles`
 * 显示当前角色，并把「找谁开权限」写清楚（管理员 / 项目负责人）。
 *
 * 为什么角色从 auth store 取而不是路由参数：参数可以被手工拼，而这个页面本身
 * 不承担判权（判权在守卫与后端），显示的必须是"服务端认的"那份角色。
 *
 * 角色名走 i18n（`memberRole.*`，与成员管理页同一套词），不在这里写死中文——
 * `check_i18n` 会把 script 里的中文字面量判红，也确实该判：文案要能跟着语言切。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

/**
 * 角色码 → i18n 键；未收录的码原样显示（不吞掉，便于排障）。
 *
 * 键在 `enum.memberRole.*`（与成员管理页同一套词），不是顶层 `memberRole.*`——
 * 写错前缀时 `t()` 会原样返回键名，然后在页面上悄悄退回角色码，
 * 看起来"有角色名"，其实翻译没生效（本次实测就是这么发现的）。
 */
function roleLabel(role: string): string {
  const key = `enum.memberRole.${role.toLowerCase()}`
  const text = t(key)
  return text === key ? role : text
}

const currentRole = computed(() => {
  const roles = auth.user?.roles ?? []
  if (roles.length === 0) return t('forbidden.roleNone')
  return roles.map(roleLabel).join(' · ')
})

/** 被拦下来的目标路径（守卫携带；直接访问 /403 时为空）。 */
const attempted = computed(() => (typeof route.query.from === 'string' ? route.query.from : ''))
</script>

<template>
  <div class="forbidden" data-testid="forbidden-page">
    <strong class="code">403</strong>
    <h1>{{ t('forbidden.title') }}</h1>
    <p class="muted">{{ t('forbidden.hint') }}</p>
    <dl class="facts">
      <dt>{{ t('forbidden.role') }}</dt>
      <dd data-testid="forbidden-role">{{ currentRole }}</dd>
      <template v-if="attempted">
        <dt>{{ t('forbidden.target') }}</dt>
        <dd data-testid="forbidden-target">{{ attempted }}</dd>
      </template>
    </dl>
    <p class="muted apply">{{ t('forbidden.apply') }}</p>
    <button class="btn" type="button" @click="router.push('/workbench')">
      {{ t('forbidden.back') }}
    </button>
  </div>
</template>

<style scoped>
.forbidden { padding: 56px 24px; display: grid; justify-items: center; gap: 12px; text-align: center; }
.code { font-size: 56px; line-height: 1; opacity: .35; font-family: var(--font-mono, monospace); }
h1 { margin: 0; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.facts { margin: 4px 0 0; display: grid; grid-template-columns: auto auto; gap: 4px 12px; font-size: 13px; }
.facts dt { opacity: .7; text-align: right; }
.facts dd { margin: 0; text-align: left; font-variant-numeric: tabular-nums; }
.apply { max-width: 40ch; line-height: 1.6; }
.btn { margin-top: 12px; padding: 8px 18px; border-radius: 6px; cursor: pointer; font: inherit; }
</style>
