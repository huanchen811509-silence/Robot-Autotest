<script setup lang="ts" generic="T extends object">
import { computed, h } from 'vue'
import { useI18n } from 'vue-i18n'
import { NDataTable } from 'naive-ui'
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import EmptyState from './EmptyState.vue'
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
  },
)

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
          />
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
</style>
