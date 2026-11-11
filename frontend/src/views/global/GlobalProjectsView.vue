<script setup lang="ts">
/**
 * 全局项目列表（v4.5.0 / FR-v4.5-05；v4.11.0 / FR-v4.11-29 加「我参与的」）。
 *
 * 复用工作台的项目卡片与创建入口：全局模式与项目模式的「项目」是同一份数据，
 * 差别只在导航上下文。本版新增 `?scope=mine`——只显示「我创建或我加入」的项目。
 *
 * 为什么不在本页写过滤：工作台已有搜索 / 排序 / 空态 / 停用删除全链路，
 * 复制一份只会让两处慢慢走偏。这里只做「读 query → 传 prop」，过滤在同一处。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import WorkbenchView from '../WorkbenchView.vue'

const route = useRoute()

/**
 * 裸路径 `/g/projects` 即「全部项目」（该组默认态），所以**不需要** `router.replace`；
 * 只有 `?scope=mine` 才切到「我参与的」。非法值（如 `?scope=xx`）按默认处理。
 */
const scope = computed<'all' | 'mine'>(() => (route.query.scope === 'mine' ? 'mine' : 'all'))
</script>

<template>
  <WorkbenchView :scope="scope" />
</template>
