<script setup lang="ts">
/**
 * 全局项目列表（v4.5.0 / FR-v4.5-05；v4.11.0 / FR-v4.11-29 加「我参与的」）。
 *
 * 复用工作台的项目卡片与创建入口：全局模式与项目模式的「项目」是同一份数据，
 * 差别只在导航上下文。本版新增 `?scope=mine`——只显示「我创建或我加入」的项目。
 *
 * W57-12：页头补「新建项目」主按钮，跳独立的 `/g/projects/new`（不复用项目树的
 * `/projects/new`，避免两棵树同时点亮）。过滤本身仍在 WorkbenchView，本页只加一个入口。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkbenchView from '../WorkbenchView.vue'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const route = useRoute()
const router = useRouter()

/**
 * 裸路径 `/g/projects` 即「全部项目」（该组默认态），所以**不需要** `router.replace`；
 * 只有 `?scope=mine` 才切到「我参与的」。非法值（如 `?scope=xx`）按默认处理。
 */
const scope = computed<'all' | 'mine'>(() => (route.query.scope === 'mine' ? 'mine' : 'all'))

function goCreate(): void {
  void router.push('/g/projects/new')
}
</script>

<template>
  <div class="global-projects">
    <div class="global-projects-head" data-testid="global-new-project">
      <button type="button" class="new-project-btn" data-testid="new-project-btn" @click="goCreate">
        {{ t('w57.globalProjects.newProject') }}
      </button>
    </div>
    <WorkbenchView :scope="scope" />
  </div>
</template>

<style scoped>
.global-projects {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.global-projects-head {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--space-3);
}

.new-project-btn {
  padding: 8px 18px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--accent-contrast);
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.new-project-btn:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
