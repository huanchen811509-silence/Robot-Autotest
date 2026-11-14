<script setup lang="ts" generic="T extends object">
import { computed, h } from 'vue'
import { useI18n } from 'vue-i18n'
import { NDataTable } from 'naive-ui'
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import EmptyState from './EmptyState.vue'
import ColumnFilter from './ColumnFilter.vue'
import type { TableFilterColumn } from '@/composables/useTableFilters'
import type { IllustrationName } from './EmptyIllustration.vue'
import type { IconName } from './iconMap'
import { computeScrollX } from '@/utils/tableWidth'
import { shouldVirtualize } from '@/utils/tableVirtual'

/**
 * UX-05 表格规范壳。
 *
 * <p>迁库（UX-04b）之后，12 处表格各自写 `:scroll-x="1000"`、`:bordered="false"`、
 * `size="small"` 和一段几乎一样的 `#empty`。四处重复带来的不是麻烦，而是**四份容易走偏的
 * 规范**：改表头样式要动 12 处，加一个状态要记得 12 个 `v-if`。壳把这些收在一处，
 * 视图只保留「这个表有几列、数据是什么、空态说什么」。
 *
 * <p>三个刻意不做的事：
 * ① **不接管错误态** —— 各页已有 `ErrorLineView`，在表格里再画一遍错误会让同一句话出现两次；
 * ② **不改写 columns** —— 改写会打乱 render 函数与列的泛型类型，对齐/列宽仍由列自己声明，
 * 壳只负责「总宽 = 列宽之和」这一件列自己算不了的事（见 `utils/tableWidth.ts`）；
 * ③ **不引图表库式配置项** —— 只暴露调用方真的会用到的入参，避免壳变成第二个组件库。
 *
 * <p>粘性表头不需要 CSS：给 `max-height` 之后 naive-ui 会把表头渲染成独立元素
 * （`.n-data-table-base-table-header`），滚动发生在 body 里，表头天然不动。
 * 这也是默认给 `maxHeight` 的原因 —— 长列表滚到第 50 行还能看见列名。
 */
const props = withDefaults(
  defineProps<{
    columns: DataTableColumns<T>
    data: T[]
    loading?: boolean
    /** 行唯一键；不传时按 `row.id` 取 */
    rowKey?: (row: T) => DataTableRowKey
    rowClassName?: string | ((row: T, index: number) => string)
    rowProps?: (row: T, index: number) => Record<string, unknown>
    /** 表格最小内容宽度，列宽之和更大时以列宽为准 */
    minScrollX?: number
    /** 超过此高度表体内滚、表头吸顶；传 `null` 表示不限制（整页滚动） */
    maxHeight?: string | number | null
    /**
     * 撑满父容器高度（v3.9.0 / FR-v3.9-04）。列表页把表格区设成 flex:1 之后，
     * 表格必须跟着长高，否则记录少时分页条上方会留一大片空白。
     * 与 `maxHeight` 互斥：给了 flex-height 就不再限高，高度由父容器决定。
     */
    flexHeight?: boolean
    /** 骨架行数：加载中且无数据时显示 */
    skeletonRows?: number
    emptyTitle?: string
    emptyDescription?: string
    emptyIcon?: IconName
    /** 空态插图，默认 `no-data`；搜索/筛选滤空时传 `no-result` */
    emptyIllustration?: IllustrationName
    /**
     * 数据加载失败。传给壳而不是让视图自己 `v-if`：空表 + 错误提示并列时，
     * 「暂无数据」会被读成「一切正常，只是没有记录」，与事实相反。
     * 传 true 时空态改用 `error` 插图与「加载失败」文案。
     */
    error?: boolean
    /**
     * 窗口化（FR-3.2-06）：不传按行数自动（≥200 行开启）；false 关闭；true 强制。
     * 整页滚动（maxHeight=null）时始终关闭。
     */
    virtualScroll?: boolean
    /**
     * W57-17：可选列筛选。默认 false —— 不传时组件行为与 v5.6 完全一致，
     * 既有页面不渲染筛选弹层、不新增任何事件监听。
     */
    filterable?: boolean
    /** 可筛选列声明；`filterable` 为 true 且本数组非空才渲染筛选条 */
    filterColumns?: TableFilterColumn[]
    /** 受控筛选值：列 key → 选中值数组（text 模式用单元素数组承载文本） */
    filters?: Record<string, string[]>
  }>(),
  {
    loading: false,
    rowKey: undefined,
    rowClassName: undefined,
    rowProps: undefined,
    minScrollX: 0,
    maxHeight: 'calc(100vh - 340px)',
    skeletonRows: 6,
    emptyTitle: undefined,
    emptyDescription: '',
    emptyIcon: 'inbox',
    emptyIllustration: 'no-data',
    error: false,
    virtualScroll: undefined,
    flexHeight: false,
    filterable: false,
    filterColumns: () => [],
    filters: () => ({}),
  },
)

const emit = defineEmits<{
  'update:filters': [value: Record<string, string[]>]
  'filter-change': [value: Record<string, string[]>]
}>()

const { t } = useI18n()

const scrollX = computed(() => computeScrollX(props.columns, props.minScrollX))
const virtualScroll = computed(() =>
  shouldVirtualize(props.data.length, props.maxHeight, props.virtualScroll),
)

/** 加载中且一行数据都没有：画骨架；已有数据（翻页、刷新）保留旧行，避免闪成骨架 */
const showSkeleton = computed(() => props.loading && props.data.length === 0)

/**
 * 空态语义三选一：出错 / 搜不到 / 本来就没有。
 * 三者混用的后果是「数据取不到」被读成「没有数据」，用户不会去重试。
 */
const emptyMode = computed<IllustrationName>(() => {
  if (props.error) return 'error'
  return props.emptyIllustration
})

const emptyTitle = computed(() => {
  if (props.error) return t('common.loadFailed')
  // 搜不到 ≠ 这一页没有数据：此时标题必须由「搜索」这件事决定，
  // 而不是沿用列表自己的专属文案（那会说成「暂无数据」或「还没有计划」）
  if (props.emptyIllustration === 'no-result') return t('common.noResult')
  return props.emptyTitle ?? t('common.noData')
})

const emptyDescription = computed(() => {
  if (props.error) return props.emptyDescription || t('common.loadFailedHint')
  if (props.emptyIllustration === 'no-result') {
    return props.emptyDescription || t('common.noResultHint')
  }
  return props.emptyDescription
})

/** W57-17：筛选条只在显式开启 + 有列声明时出现，绝不改变默认渲染。 */
const showFilterBar = computed(
  () => props.filterable && props.filterColumns.length > 0,
)
const hasActiveFilters = computed(() =>
  Object.values(props.filters).some((values) => values.length > 0),
)

function filterText(key: string): string {
  return props.filters[key]?.[0] ?? ''
}

function updateFilter(key: string, values: string[]): void {
  const next: Record<string, string[]> = { ...props.filters }
  if (values.length === 0) delete next[key]
  else next[key] = values
  emit('update:filters', next)
  emit('filter-change', next)
}

function clearAllFilters(): void {
  emit('update:filters', {})
  emit('filter-change', {})
}

const resolvedRowKey = computed<((row: T) => DataTableRowKey) | undefined>(() => {
  if (props.rowKey) return props.rowKey
  return (row: T) => {
    const id = (row as { id?: unknown }).id
    return typeof id === 'string' || typeof id === 'number' ? id : ''
  }
})

/**
 * 骨架复用真实表头：只把每列的 render 换成灰条，表头文案、列宽、对齐全部照旧。
 * 这样骨架态不会「跳一下」再变成表格，用户也能看出正在加载的是哪一张表。
 */
const skeletonColumns = computed<DataTableColumns<T>>(() =>
  props.columns.map((column) => ({
    ...column,
    render: (_row: T, index: number) =>
      h('span', {
        class: 'data-table__bar',
        style: { width: `${55 + (index % 4) * 10}%` },
      }),
  })),
)

const skeletonData = computed<T[]>(() =>
  Array.from({ length: props.skeletonRows }, (_, index) => ({ id: `__skeleton_${index}` }) as T),
)
</script>

<template>
  <div class="data-table" :class="{ 'is-flex': flexHeight }" :aria-busy="loading ? 'true' : 'false'">
    <div v-if="showFilterBar" class="data-table__filters" data-check-semantic>
      <ColumnFilter
        v-for="column in filterColumns"
        :key="column.key"
        :column-key="column.key"
        :title="column.title"
        :mode="column.mode ?? 'multi'"
        :options="column.options ?? []"
        :selected="filters[column.key] ?? []"
        :text="filterText(column.key)"
        @update:selected="(values: string[]) => updateFilter(column.key, values)"
        @update:text="(value: string) => updateFilter(column.key, value ? [value] : [])"
        @clear="updateFilter(column.key, [])"
      />
      <button
        v-if="hasActiveFilters"
        type="button"
        class="data-table__filter-clear"
        @click="clearAllFilters"
      >
        {{ t('w57.dataTable.clearFilter') }}
      </button>
    </div>

    <NDataTable
      v-if="showSkeleton"
      class="data-table__skeleton"
      :columns="skeletonColumns"
      :data="skeletonData"
      :row-key="resolvedRowKey"
      :scroll-x="scrollX"
      :max-height="flexHeight ? undefined : (maxHeight ?? undefined)"
      :flex-height="flexHeight"
      :bordered="false"
      size="small"
      :aria-label="t('common.loading')"
    />

    <NDataTable
      v-else
      :columns="columns"
      :data="data"
      :loading="loading"
      :row-key="resolvedRowKey"
      :row-class-name="rowClassName"
      :row-props="rowProps"
      :scroll-x="scrollX"
      :max-height="flexHeight ? undefined : (maxHeight ?? undefined)"
      :flex-height="flexHeight"
      :virtual-scroll="virtualScroll"
      :bordered="false"
      size="small"
    >
      <template #empty>
        <slot name="empty">
          <EmptyState
            :title="emptyTitle"
            :description="emptyDescription"
            :icon="emptyIcon"
            :illustration="emptyMode"
            :illustration-width="64"
          >
            <!-- v5.6.0 / FR-v5.6-03：空态要能给出「下一步去哪」。EmptyState 本来就有
                 action 插槽，只是 DataTable 没透传，页面只能各写一份 #empty 覆盖——
                 覆盖会连带把 error 态一起顶掉，所以这里补透传而不是让调用方绕。 -->
            <template v-if="$slots.emptyAction" #action>
              <slot name="emptyAction" />
            </template>
          </EmptyState>
        </slot>
      </template>
    </NDataTable>
  </div>
</template>

<style scoped>
.data-table {
  width: 100%;
}

/* flex-height 模式下容器必须先有确定高度，naive-ui 才能把表体按剩余空间算 */
.data-table.is-flex {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.data-table.is-flex :deep(.n-data-table) {
  flex: 1 1 auto;
  min-height: 0;
}

/*
 * 表头底色与弱化色由 `naiveTheme.ts` 的 DataTable 覆盖项接管（暗色下表头曾因
 * 库默认 `tableHeaderColor` 是亮色而发白）。
 */

/*
 * 行高双档（UX-06 密度）：舒适 44 / 紧凑 36，取 theme.css 的 `--table-row-h`，
 * 这里只"用"不"定"。上下 padding 归零是刻意的——naive-ui 默认 td 有上下内边距，
 * 留着会变成 44 + 16，档位就不可控；内容靠 vertical-align 居中，两行文本的单元格
 * 仍会按内容撑高（没有 overflow 裁切），不会出现"字被切掉"。
 * 选择器多写一层 `.n-data-table-table`，是为了压过库运行时注入的 `.n-data-table-td`
 * （同权重时谁后注入谁赢，库是运行时插 <style>，不能赌顺序）。
 */
.data-table :deep(.n-data-table-table .n-data-table-th),
.data-table :deep(.n-data-table-table .n-data-table-td) {
  height: var(--table-row-h);
  padding-top: 0;
  padding-bottom: 0;
  vertical-align: middle;
}

/* 骨架条 */
.data-table__bar {
  display: block;
  height: 10px;
  border-radius: 4px;
  background: var(--surface-2);
  opacity: 0.6;
}

.data-table__skeleton {
  pointer-events: none;
}

/* W57-17：筛选条。flex-height 下不参与拉伸，避免把表格区挤矮。 */
.data-table__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  flex: 0 0 auto;
  padding: 0 0 var(--space-2);
}

.data-table__filter-clear {
  padding: 2px var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.data-table__filter-clear:hover {
  color: var(--text);
  border-color: var(--border-strong);
}

.data-table__filter-clear:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}
</style>
