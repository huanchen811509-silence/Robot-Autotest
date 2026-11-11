<script setup lang="ts">
/**
 * 全局系统设置（v4.5.0 迁移 → v4.9.2 / OPT-01 / FR-49-01~04）。
 *
 * 旧实现是 8 张卡片，且漏了「AI 助手」、混入了执行节点 / 运维 / 归档等无关项。
 * 现改为与项目侧栏一致的「二级菜单树」：6 项直接取自 config/settingsNav.ts 单一数据源，
 * 每项按服务端能力集过滤（public 对所有角色可见），点击后进入 /g/... 命名空间。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { SETTINGS_NAV } from '@/config/settingsNav'
import { useMenuCapabilitiesStore } from '@/stores/menuCapabilities'

const { t } = useI18n()
const menuCaps = useMenuCapabilitiesStore()

// 能力集未就绪时不渲染菜单，避免「先全显再收起」闪烁；就绪后按 capability 过滤。
const links = computed(() =>
  menuCaps.ready
    ? SETTINGS_NAV.filter((item) => menuCaps.has(item.capability))
    : [],
)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ t('global.settingsTitle') }}</h1>
      <p class="muted">{{ t('global.settingsHint') }}</p>
    </header>
    <nav class="global-settings-menu" :aria-label="t('global.settingsTitle')">
      <RouterLink
        v-for="link in links"
        :key="link.key"
        class="global-settings-item"
        :to="link.path"
      >
        <span class="global-settings-item-label">{{ t(link.labelKey) }}</span>
      </RouterLink>
    </nav>
  </section>
</template>

<style scoped>
.global-settings-menu {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  max-width: 420px;
}

.global-settings-item {
  display: flex;
  align-items: center;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text);
  text-decoration: none;
}

.global-settings-item:hover {
  border-color: var(--border-strong);
}

.global-settings-item.router-link-active {
  border-color: var(--accent);
}
</style>
