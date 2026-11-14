<script setup lang="ts" generic="T extends object">
/**
 * 全站列表 CRUD 骨架（W57-32/35~37/43）。
 *
 * 定位是「组合」而不是「再实现一遍表格」：表格本体仍是 `DataTable`，
 * 本组件只负责六个槽位的编排与三条列表级纪律：
 *  ① 六槽位：标题 / 筛选 / 批量条 / 表格 / 空态 / 分页；没给的动作一律不渲染；
 *  ② 空态三态：加载中（DataTable 复用真实表头画骨架）、筛选无结果（no-result）、
 *     首次无数据（no-data）——三者文案/插图不能混用；
 *  ③ 批量条与筛选区互斥：有批量选择时隐藏筛选区，避免两块工具条叠在同一行；
 *     写操作后由 `@refresh` 就地刷新，不整页重载。
 *
 * 颜色全部走 `theme.css` 的语义 token；标题容器限两行，1280/1440 两档都不撑高头部。
 *
 * **接入范围（2026-11-14 复核 P2 订正）**：v5.7.0 只有 `/test-reports`
 * （`TestReportsView.vue`）接了本骨架，**不是「全站统一」**；其余列表页仍用各自结构，
 * 未接入的七条在 `docs/evidence/v5.7.0/list-crud-matrix.md` 缺口白名单里逐条登记理由，
 * 发布说明与 CHANGELOG 已同步改写（L-57-05）。
 */
import { computed } from 'vue'
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import DataTable from './DataTable.vue'
import type { IllustrationName } from './EmptyIllustration.vue'
import type { IconName } from './iconMap'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    columns: DataTableColumns<T>
    data: T[]
    loading?: boolean
    rowKey?: (row: T) => DataTableRowKey
    /** 其余 DataTable 属性（maxHeight / flexHeight / rowClassName / emptyIcon / error …） */
    tableProps?: Record<string, unknown>
    /** 筛选是否生效：决定空态是「筛选无结果」还是「首次无数据」 */
    filtered?: boolean
    /** 批量选择是否激活：激活时渲染批量条并隐藏筛选区 */
    batchActive?: boolean
    error?: boolean
    emptyTitle?: string
    emptyDescription?: string
    emptyIcon?: IconName
    emptyIllustration?: IllustrationName
    /** 是否渲染内置「刷新」按钮（发出 refresh 事件，由宿主就地重载） */
    showRefresh?: boolean
    title?: string
  }>(),
  {
    loading: false,
    rowKey: undefined,
    tableProps: () => ({}),
    filtered: false,
    batchActive: false,
    error: false,
    emptyTitle: undefined,
    emptyDescription: '',
    emptyIcon: 'inbox',
    emptyIllustration: 'no-data',
    showRefresh: false,
    title: '',
  },
)

const emit = defineEmits<{
  refresh: []
}>()

/** 合并出传给 DataTable 的属性：宿主给的 tableProps 打底，三态空态由骨架覆盖。 */
const resolvedTableProps = computed<Record<string, unknown>>(() => {
  const merged: Record<string, unknown> = { ...props.tableProps }
  merged.loading = props.loading
  if (props.rowKey) merged.rowKey = props.rowKey
  if (props.error) merged.error = true
  if (props.emptyTitle && merged.emptyTitle === undefined) merged.emptyTitle = props.emptyTitle
  if (props.emptyDescription && merged.emptyDescription === undefined) {
    merged.emptyDescription = props.emptyDescription
  }
  if (merged.emptyIcon === undefined) merged.emptyIcon = props.emptyIcon
  if (props.error) return merged

  if (props.filtered) {
    // 被筛空 ≠ 本来没数据；DataTable 见到 no-result 会显示「没有匹配的记录」而不是列表专属文案
    merged.emptyIllustration = 'no-result'
  } else if (merged.emptyIllustration === undefined) {
    merged.emptyIllustration = props.emptyIllustration
  }
  return merged
})

/**
 * 空态三态（给 `#table` 插槽用）：宿主若自己渲染 DataTable（例如要保留 `.list-fill`
 * 的 flex 链），`v-bind="emptyProps"` 即可复用同一套口径，不必各写一份 empty 文案。
 * 加载中由 DataTable 用真实表头画骨架，这里不改 empty（骨架优先于空态）。
 */
const emptyProps = computed<Record<string, unknown>>(() => ({
  'empty-illustration': props.filtered ? 'no-result' : props.emptyIllustration,
}))

function refresh(): void {
  emit('refresh')
}
</script>

<template>
  <div class="list-scaffold" data-check-semantic>
    <div v-if="$slots.title || title || showRefresh" class="list-scaffold__head">
      <slot name="title">
        <h2 class="list-scaffold__title">{{ title }}</h2>
      </slot>
      <button
        v-if="showRefresh"
        type="button"
        class="list-scaffold__refresh"
        data-testid="list-refresh"
        @click="refresh"
      >
        {{ t('w57.listScaffold.refresh') }}
      </button>
    </div>

    <!-- 批量条与筛选区互斥：同一时刻只出现一块 -->
    <div v-if="batchActive && $slots.batch" class="list-scaffold__batch" data-testid="list-batch">
      <slot name="batch" />
    </div>
    <div v-else-if="$slots.filters" class="list-scaffold__filters" data-testid="list-filters">
      <slot name="filters" />
    </div>

    <div class="list-scaffold__table">
      <slot name="table" :emptyProps="emptyProps">
        <DataTable :columns="columns" :data="data" v-bind="resolvedTableProps">
          <template v-if="$slots.empty" #empty>
            <slot name="empty" />
          </template>
        </DataTable>
      </slot>
    </div>

    <div v-if="$slots.pagination" class="list-scaffold__pagination">
      <slot name="pagination" />
    </div>
  </div>
</template>

<style scoped>
.list-scaffold {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.list-scaffold__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

/* 标题容器 ≤2 行：长标题截断，不把头部撑成三行导致列表区被挤出视口 */
.list-scaffold__title {
  margin: 0;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  color: var(--text);
  font-size: var(--font-size-md);
  font-weight: 600;
  word-break: break-all;
}

.list-scaffold__refresh {
  flex: 0 0 auto;
  padding: 2px var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.list-scaffold__refresh:hover {
  color: var(--text);
  border-color: var(--border-strong);
}

.list-scaffold__refresh:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.list-scaffold__batch,
.list-scaffold__filters {
  margin-bottom: var(--space-2);
  min-width: 0;
}

.list-scaffold__batch {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--accent-soft);
  color: var(--text);
  font-size: var(--font-size-sm);
}

.list-scaffold__table {
  min-width: 0;
  flex: 1 1 auto;
}

.list-scaffold__pagination {
  margin-top: var(--space-2);
  flex: 0 0 auto;
}
</style>
