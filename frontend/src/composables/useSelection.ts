/**
 * 列表批量选择模型（UX-02，FR-v0.2-35/36）。
 *
 * 契约：
 * - 选择以 id 集合持有，<b>跨页保留</b>：翻页/重新加载不清空，计数含跨页项；
 * - 「本页全选」只作用于当前页，不触碰其他页已选项；
 * - 半选态（indeterminate）= 本页有选中但未全选；
 * - 批量操作后用 {@link retain} 只保留失败项，重试自然只作用于它们（FR-v0.2-36）。
 */
import { computed, ref, type Ref } from 'vue'

/** 逐条结果（与后端 BatchItemResult 对齐）。 */
export interface BatchItemResult {
  id: number
  ok: boolean
  code: string | null
  message: string | null
}

/** 批量结果（与后端 BatchResult 对齐，部分成功语义）。 */
export interface BatchResult {
  total: number
  succeeded: number
  failed: number
  items: BatchItemResult[]
  /** 条件选择解析出的条数（后端回显）：与界面命中数不一致时提示刷新。 */
  resolvedCount?: number
}

export function useSelection(pageIds: Ref<number[]> = computed(() => [])) {
  // 整体替换 Set 而非原地改：保证 computed / 模板可靠重渲染
  const selectedIds = ref(new Set<number>())

  const count = computed(() => selectedIds.value.size)
  const isEmpty = computed(() => selectedIds.value.size === 0)

  /** 本页选中数。 */
  const selectedOnPage = computed(
    () => pageIds.value.filter((id) => selectedIds.value.has(id)).length,
  )

  /** 本页全选态（表头勾选框）。 */
  const pageAllSelected = computed(
    () => pageIds.value.length > 0 && selectedOnPage.value === pageIds.value.length,
  )

  /** 半选态：本页有选中但未全选。 */
  const pageIndeterminate = computed(
    () => selectedOnPage.value > 0 && !pageAllSelected.value,
  )

  /** 跨页保留的条数（选中总数 − 本页选中数），> 0 时批量条提示「含跨页」。 */
  const crossPageCount = computed(() => selectedIds.value.size - selectedOnPage.value)

  function isSelected(id: number): boolean {
    return selectedIds.value.has(id)
  }

  /** 勾/去勾一行。 */
  function toggle(id: number): void {
    const next = new Set(selectedIds.value)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    selectedIds.value = next
  }

  /**
   * 表头勾选框：本页未全选 → 只把本页加入；已全选 → 只移除本页。
   * 跨页已选项不受影响（FR-v0.2-35：全选仅本页）。
   */
  function togglePage(): void {
    const next = new Set(selectedIds.value)
    if (pageAllSelected.value) {
      for (const id of pageIds.value) {
        next.delete(id)
      }
    } else {
      for (const id of pageIds.value) {
        next.add(id)
      }
    }
    selectedIds.value = next
  }

  /** 批量操作后：只保留给定 id（失败项），成功项自然取消选中。 */
  function retain(ids: number[]): void {
    selectedIds.value = new Set(ids)
  }

  /** 一键清空。 */
  function clear(): void {
    selectedIds.value = new Set()
  }

  return {
    selectedIds,
    count,
    isEmpty,
    selectedOnPage,
    pageAllSelected,
    pageIndeterminate,
    crossPageCount,
    isSelected,
    toggle,
    togglePage,
    retain,
    clear,
  }
}
