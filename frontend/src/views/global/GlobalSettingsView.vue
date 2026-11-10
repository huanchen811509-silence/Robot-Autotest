<script setup lang="ts">
/** 全局系统设置入口（v4.5.0 / FR-v4.5-03）：管理入口从账户菜单迁到这里。 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()

const links = computed(() => {
  const items = [
    { to: '/settings', label: t('menu.platformSettings'), desc: t('global.settingsPlatform') },
    { to: '/about', label: t('menu.about'), desc: t('global.settingsAbout') },
    { to: '/changelog', label: t('menu.changelog'), desc: t('global.settingsChangelog') },
    { to: '/nodes', label: t('menu.nodes'), desc: t('global.settingsNodes') },
  ]
  if (auth.isAdmin) {
    items.push(
      { to: '/admin', label: t('menu.users'), desc: t('global.settingsUsers') },
      { to: '/admin/audit', label: t('menu.audit'), desc: t('global.settingsAudit') },
      { to: '/admin/ops', label: t('menu.ops'), desc: t('global.settingsOps') },
      { to: '/admin/archive', label: t('menu.archive'), desc: t('global.settingsArchive') },
    )
  }
  return items
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ t('global.settingsTitle') }}</h1>
      <p class="muted">{{ t('global.settingsHint') }}</p>
    </header>
    <div class="global-settings-grid">
      <RouterLink v-for="link in links" :key="link.to" class="global-settings-card" :to="link.to">
        <strong>{{ link.label }}</strong>
        <span class="muted">{{ link.desc }}</span>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.global-settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--space-3);
}

.global-settings-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text);
  text-decoration: none;
}

.global-settings-card:hover {
  border-color: var(--border-strong);
}
</style>
