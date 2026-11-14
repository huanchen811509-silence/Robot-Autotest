/**
 * 服务端选择模型（W57-16）。
 *
 * `useSelection` 把选中项装进内存 Set，适合「一页几十条、全选只选本页」。但列表一旦
 * 服务端分页，「全选」就不能再是「把当前页的 id 都记住」——用户要的是「符合当前筛选
 * 条件的全部记录」，可能上万条、根本没加载过。这里把「全选」表达成**筛选条件**而不是
 * id 列表：
 *
 *  - 精确模式：仍是 `useSelection` 那套 id 集合（跨页保留），逐条勾选；
 *  - 条件模式：`matchAll=true` + `filters` + `excludeIds`，`toDescriptor()` 交给后端，
 *    后端按同一套筛选条件执行批量动作；用户在当前页取消勾选，只是往 exclude 里加 id；
 *  - 筛选条件变化必须 `resetForFilters()` 重置：换一组条件还留着上一组「全选」是灾难；
 *  - 命中数 > 10000：`requestSelectAllMatching` 不直接生效，置 `awaitingConfirm`，
 *    由宿主弹 `ConfirmAction` 二次确认后才 `confirmSelectAllMatching()`。
 *
 * **兼容**：返回对象完整保留 `useSelection` 的字段与方法名（selectedIds / count /
 * isEmpty / selectedOnPage / pageAllSelected / pageIndeterminate / crossPageCount /
 * isSelected / toggle / togglePage / retain / clear），既有调用方零改动即可换成它。
 *
 * **当前接入边界（2026-11-14 复核 P2 登记，L-57-09）**：条件模式只走到「界面状态」——
 * `matchAll / excludeIds / matchedTotal` 用于计数、二次确认与排除集展示，
 * **`toDescriptor()` 目前没有生产调用方**，后端批量端点（`GenericBatchService`）只接受显式
 * `ids`，所以「全选所有命中」还不能真的下发。发布说明与 PRD 已按此改写，不得再声称
 * 「服务端跨页全选已兑现」；接后端批量条件入参排 v5.8.0。
 */
import { computed, ref, type Ref } from 'vue'
import { useSelection, type BatchItemResult, type BatchResult } from './useSelection'

export type { BatchItemResult, BatchResult }

/** 交给后端的批量选择描述：要么显式 id，要么筛选条件 + 排除集。 */
export type SelectionDescriptor =
  | { mode: 'ids'; ids: number[] }
  | { mode: 'filter'; filters: Record<string, string | undefined>; excludeIds: number[] }

export interface UseServerSelectionOptions {
  /** 触发二次确认的命中数阈值，默认 10000 */
  confirmThreshold?: number
}

interface PendingAll {
  filters: Record<string, string | undefined>
  total: number
}

export function useServerSelection(
  pageIds: Ref<number[]> = computed(() => []),
  options: UseServerSelectionOptions = {},
) {
  const base = useSelection(pageIds)
  const threshold = options.confirmThreshold ?? 10000

  /** 条件模式开关 */
  const matchAll = ref(false)
  const activeFilters = ref<Record<string, string | undefined>>({})
  const excludeIds = ref<Set<number>>(new Set())
  const matchedTotal = ref(0)
  const awaitingConfirm = ref(false)
  let pending: PendingAll | null = null

  const selectionMode = computed<'ids' | 'filter'>(() => (matchAll.value ? 'filter' : 'ids'))

  /** 条件模式下本页选中数 = 本页未被排除的条数。 */
  const selectedOnPage = computed(() =>
    matchAll.value
      ? pageIds.value.filter((id) => !excludeIds.value.has(id)).length
      : base.selectedOnPage.value,
  )
  const pageAllSelected = computed(() =>
    matchAll.value
      ? pageIds.value.length > 0 && selectedOnPage.value === pageIds.value.length
      : base.pageAllSelected.value,
  )
  const pageIndeterminate = computed(() =>
    matchAll.value
      ? selectedOnPage.value > 0 && !pageAllSelected.value
      : base.pageIndeterminate.value,
  )
  /** 条件模式的跨页数 = 命中总数 − 本页选中数（与 useSelection 同口径）。 */
  const crossPageCount = computed(() =>
    matchAll.value
      ? Math.max(0, count.value - selectedOnPage.value)
      : base.crossPageCount.value,
  )

  const count = computed(() =>
    matchAll.value
      ? Math.max(0, matchedTotal.value - excludeIds.value.size)
      : base.count.value,
  )
  const isEmpty = computed(() => count.value === 0)

  function isSelected(id: number): boolean {
    return matchAll.value ? !excludeIds.value.has(id) : base.isSelected(id)
  }

  /** 精确模式委托给 useSelection；条件模式把「取消」表达成加入排除集。 */
  function toggle(id: number): void {
    if (!matchAll.value) {
      base.toggle(id)
      return
    }
    const next = new Set(excludeIds.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    excludeIds.value = next
  }

  /**
   * 表头全选：精确模式仍只作用本页（useSelection 语义）；
   * 条件模式则把本页全部 id 从排除集里补回/移除，达到「本页全选/全不选」。
   */
  function togglePage(): void {
    if (!matchAll.value) {
      base.togglePage()
      return
    }
    const next = new Set(excludeIds.value)
    const allExcluded = pageIds.value.every((id) => next.has(id))
    for (const id of pageIds.value) {
      if (allExcluded) next.delete(id)
      else next.add(id)
    }
    excludeIds.value = next
  }

  /**
   * 用筛选条件表达「全选」。命中数 > 阈值时不生效，返回 'needs-confirm'；
   * 宿主应展示二次确认，确认后调 `confirmSelectAllMatching()`。
   */
  function requestSelectAllMatching(
    filters: Record<string, string | undefined>,
    total: number,
  ): 'applied' | 'needs-confirm' {
    if (total > threshold) {
      pending = { filters, total }
      awaitingConfirm.value = true
      return 'needs-confirm'
    }
    applyAll(filters, total)
    return 'applied'
  }

  function applyAll(filters: Record<string, string | undefined>, total: number): void {
    base.clear()
    matchAll.value = true
    activeFilters.value = { ...filters }
    matchedTotal.value = Math.max(0, total)
    excludeIds.value = new Set()
    awaitingConfirm.value = false
    pending = null
  }

  function confirmSelectAllMatching(): void {
    if (!pending) return
    applyAll(pending.filters, pending.total)
  }

  function cancelSelectAllMatching(): void {
    awaitingConfirm.value = false
    pending = null
  }

  /** 筛选条件变化：整组选择必须作废，否则会把上一组条件的「全选」带到新结果上。 */
  function resetForFilters(filters: Record<string, string | undefined> = {}): void {
    base.clear()
    matchAll.value = false
    activeFilters.value = { ...filters }
    matchedTotal.value = 0
    excludeIds.value = new Set()
    awaitingConfirm.value = false
    pending = null
  }

  /** 批量回执后只保留失败项：条件模式无法枚举「全部命中」，退回精确 id 模式。 */
  function retain(ids: number[]): void {
    matchAll.value = false
    activeFilters.value = {}
    matchedTotal.value = 0
    excludeIds.value = new Set()
    awaitingConfirm.value = false
    pending = null
    base.retain(ids)
  }

  function clear(): void {
    resetForFilters()
  }

  /**
   * 生成后端批量动作的选择描述（影响面 count 由后端按同一条件回算）。
   *
   * **未接入**：后端批量端点只收显式 `ids`，条件模式（`mode: 'filter'`）尚无消费方，
   * 详见文件头的 L-57-09。接入前不得用它对外承诺「跨页全选可下发」。
   */
  function toDescriptor(): SelectionDescriptor {
    if (matchAll.value) {
      return {
        mode: 'filter',
        filters: { ...activeFilters.value },
        excludeIds: [...excludeIds.value],
      }
    }
    return { mode: 'ids', ids: [...base.selectedIds.value] }
  }

  return {
    // —— useSelection 兼容面（同名字段，条件模式按服务端语义覆盖）——
    selectedIds: base.selectedIds,
    selectedOnPage,
    pageAllSelected,
    pageIndeterminate,
    crossPageCount,
    // —— 以下同名字段按服务端选择语义覆盖 ——
    count,
    isEmpty,
    isSelected,
    toggle,
    togglePage,
    retain,
    clear,
    // —— 服务端选择新增面 ——
    selectionMode,
    matchAll,
    activeFilters,
    excludeIds,
    matchedTotal,
    awaitingConfirm,
    confirmThreshold: threshold,
    requestSelectAllMatching,
    confirmSelectAllMatching,
    cancelSelectAllMatching,
    resetForFilters,
    toDescriptor,
  }
}
