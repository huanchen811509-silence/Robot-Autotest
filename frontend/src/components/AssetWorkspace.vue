<script setup lang="ts">
/**
 * 资产工作台（v5.12 / W62-08 / FR-v5.12-06）。
 *
 * 项目资产页与公共资产库**共用这一份**设备 / 凭证表格：
 * - 列可见性由 `assetWorkspaceMatrix` 唯一决定（标 ❌ 的差异列在这里被过滤掉）；
 * - 动作条以 slot props 把「该 scope 可见的动作」交给页面，页面只负责绑处理函数；
 * - 模板里没有任何按 scope 复制的整块分支：差异全部落在配置数据上。
 *
 * 页签头仍由各页渲染（两页既有 DOM 形态不同，统一会破坏既有回归），
 * 但「显示哪几个页签」一律读 `visibleTabs(scope)`，不在页面里另写一份清单。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from '@/components/DataTable.vue'
import {
  filterColumnsByMatrix,
  visibleActions,
  type AssetScope,
} from '@/config/assetWorkspaceMatrix'

const props = withDefaults(defineProps<{
  scope: AssetScope
  columns: any[]
  rows: any[]
  rowKey: (row: any) => string | number
  loading?: boolean
  error?: boolean
  emptyTitle?: string
  emptyDescription?: string
  emptyIcon?: any
  emptyIllustration?: any
  maxHeight?: number
  flexHeight?: boolean
}>(), {
  loading: false,
  error: false,
  emptyTitle: '',
  emptyDescription: '',
  emptyIcon: 'boxes',
  emptyIllustration: '',
  maxHeight: undefined,
  flexHeight: false,
})

const { t } = useI18n()
const filteredColumns = computed(() => filterColumnsByMatrix(props.scope, props.columns))
const actions = computed(() => visibleActions(props.scope))
</script>

<template>
  <div class="asset-workspace" :data-scope="scope" data-testid="asset-workspace">
    <div v-if="$slots.actions" class="asset-workspace-actions" data-testid="asset-workspace-actions">
      <slot name="actions" :actions="actions" />
    </div>
    <div class="list-fill" data-testid="assets-grid">
      <DataTable
        :columns="filteredColumns"
        :data="rows"
        :loading="loading"
        :row-key="rowKey"
        :empty-title="emptyTitle || t('common.noData')"
        :empty-description="emptyDescription"
        :empty-icon="emptyIcon"
        :empty-illustration="emptyIllustration || undefined"
        :error="error"
        :max-height="maxHeight"
        :flex-height="flexHeight"
      />
    </div>
    <slot name="panels" />
  </div>
</template>

<style scoped>
.asset-workspace { display: block; min-width: 0; }
.asset-workspace-actions { margin-bottom: 8px; }
.list-fill { min-width: 0; }
</style>
