<script setup lang="ts" generic="T extends object">
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
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import type { IconName } from '@/components/iconMap'
import type { IllustrationName } from '@/components/EmptyIllustration.vue'
import {
  filterColumnsByMatrix,
  visibleActions,
  type AssetScope,
} from '@/config/assetWorkspaceMatrix'

/** 矩阵只按 `key` 过滤；列对象运行时一定带 key，形状断言集中在这一处。 */
type KeyedColumn = { key: string }

const props = withDefaults(defineProps<{
  scope: AssetScope
  columns: DataTableColumns<T>
  rows: T[]
  rowKey?: (row: T) => DataTableRowKey
  loading?: boolean
  error?: boolean
  emptyTitle?: string
  emptyDescription?: string
  emptyIcon?: IconName
  emptyIllustration?: IllustrationName
  maxHeight?: number
  flexHeight?: boolean
}>(), {
  rowKey: undefined,
  loading: false,
  error: false,
  emptyTitle: '',
  emptyDescription: '',
  emptyIcon: 'boxes',
  emptyIllustration: undefined,
  maxHeight: undefined,
  flexHeight: false,
})

const { t } = useI18n()
/** 差异矩阵标 ❌ 的列在对应 scope 的渲染结果里必须消失。 */
const filteredColumns = computed(() =>
  filterColumnsByMatrix(props.scope, props.columns as unknown as KeyedColumn[]))
/** 回传给严格泛型的 DataTable：过滤只删元素，不改列对象本身的形状。 */
const tableColumns = computed(() => filteredColumns.value as unknown as DataTableColumns<T>)
const actions = computed(() => visibleActions(props.scope))
</script>

<template>
  <div class="asset-workspace" :data-scope="scope" data-testid="asset-workspace">
    <div v-if="$slots.actions" class="asset-workspace-actions" data-testid="asset-workspace-actions">
      <slot name="actions" :actions="actions" />
    </div>
    <div class="list-fill" data-testid="assets-grid">
      <DataTable
        :columns="tableColumns"
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
