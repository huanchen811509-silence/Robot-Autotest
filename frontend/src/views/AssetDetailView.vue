<script setup lang="ts">
/**
 * 项目资产详情（v4.9.2 / OPT-04 / FR-49-13）。
 * 与公共资产详情共用同一 `AssetDetailPanel`，只差 `source` 参数；URL 可分享。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AssetDetailPanel from '@/components/AssetDetailPanel.vue'

const { t } = useI18n()
const route = useRoute()
const deviceId = computed(() => String(route.params.id ?? ''))
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ t("assetDetail.title") }}</h1>
      <p class="muted">{{ t("assetDetail.projectHint") }}</p>
    </header>
    <AssetDetailPanel v-if="deviceId" :device-id="deviceId" source="project" />
    <p v-else class="muted">{{ t("assetDetail.noId") }}</p>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-3);
}
.muted {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
</style>
