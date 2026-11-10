<script setup lang="ts">
/**
 * 全局项目列表（v4.5.0 / FR-v4.5-05）。
 *
 * 复用工作台的项目卡片与创建入口：全局模式与项目模式的「项目」是同一份数据，
 * 差别只在导航上下文；卡片上的 own/joined/readonly 标注由 /api/global/projects 提供，
 * 4.6 起替换为带标注的列表。这里先保证路由、导航与鉴权链路可用。
 */
import WorkbenchView from '../WorkbenchView.vue'
</script>

<template>
  <WorkbenchView />
</template>
