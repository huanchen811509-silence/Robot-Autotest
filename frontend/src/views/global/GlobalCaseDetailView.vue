<script setup lang="ts">
/**
 * 公共用例详情（v4.9.2 / OPT-02 / FR-49-08）。
 *
 * 只从 `route.params.id` 取主键，mounted 即由 CaseDetailPanel 拉取详情，
 * 不依赖列表页的任何选中态 —— URL 复制后可直接打开。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'

const { t } = useI18n()
const route = useRoute()
const caseId = computed(() => String(route.params.id ?? ''))
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ t("caseDetail.title") }}</h1>
      <p class="muted">{{ t("caseDetail.globalHint") }}</p>
    </header>
    <CaseDetailPanel v-if="caseId" :case-id="caseId" source="global" />
    <p v-else class="muted">{{ t("caseDetail.noId") }}</p>
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
