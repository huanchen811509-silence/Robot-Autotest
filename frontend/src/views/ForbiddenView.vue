<script setup lang="ts">
/** 403 页（NAV-01 / FR-v2.0-08）：路由能力守卫把无权限直达送到这里。 */
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <div class="forbidden">
    <strong class="code">403</strong>
    <h1>{{ t('forbidden.title') }}</h1>
    <p class="muted">{{ t('forbidden.hint') }}</p>
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
.btn { margin-top: 12px; padding: 8px 18px; border-radius: 6px; cursor: pointer; font: inherit; }
</style>
