/**
 * 分类树三态勾选（W57-10）。
 *
 * 唯一真源是一个 `Set<caseId>`：一级/二级只是「对它名下那批用例做批量选/不选」的入口，
 * 不另存一份「哪些分类被勾了」。这样才不会出现「分类显示已勾、用例却没进已选」的两份状态。
 *
 * 三条约定：
 * 1. 搜索只影响可见项（`isVisible`），绝不改动 `selected`——已选不随筛选消失；
 * 2. 上限（默认 1000）只拦「新增」，不拦取消，也不静默截断已选；
 * 3. `toggleL1` / `toggleL2` 接收**明确的用例 id 列表**：用例是展开时懒加载的，
 *    组合式函数不该自己猜某个分类里有哪些用例，由组件加载后再传进来。
 */
import { computed, ref } from 'vue'

export interface CategoryL2 {
  l2Id: number
  l2Name: string
  count: number
}

/** `GET /api/global/cases/grouped` 的一行（一级分类 + 其下二级概览）。 */
export interface CategoryGroup {
  l1Id: number
  l1Name: string
  l2s: CategoryL2[]
  total: number
  uncategorized: number
}

/** 展开二级节点后懒加载到的用例。 */
export interface TreeCase {
  id: number
  title: string
  caseCode?: string
}

export type TriState = 'none' | 'some' | 'all'

export interface CategorySelectionOptions {
  maxSelect?: number
}

export function useCategorySelection(options: CategorySelectionOptions = {}) {
  const maxSelect = options.maxSelect ?? 1000
  const selected = ref<Set<number>>(new Set())
  const keyword = ref('')

  const selectedIds = computed<number[]>(() => [...selected.value].sort((a, b) => a - b))
  const count = computed(() => selected.value.size)
  const atLimit = computed(() => selected.value.size >= maxSelect)

  function isCaseChecked(id: number): boolean {
    return selected.value.has(id)
  }

  /** 一批用例的勾选态：全没选 / 部分选（indeterminate）/ 全选。 */
  function stateOf(ids: readonly number[]): TriState {
    if (ids.length === 0) return 'none'
    let hit = 0
    for (const id of ids) if (selected.value.has(id)) hit += 1
    if (hit === 0) return 'none'
    return hit === ids.length ? 'all' : 'some'
  }

  function isChecked(ids: readonly number[]): boolean {
    return stateOf(ids) === 'all'
  }

  function isIndeterminate(ids: readonly number[]): boolean {
    return stateOf(ids) === 'some'
  }

  function toggleCase(id: number): void {
    const next = new Set(selected.value)
    if (next.has(id)) {
      next.delete(id)
    } else {
      if (next.size >= maxSelect) return
      next.add(id)
    }
    selected.value = next
  }

  /** 批量开/关；到达上限时跳过新增，但已选中的项保持。 */
  function setMany(ids: readonly number[], on: boolean): void {
    const next = new Set(selected.value)
    for (const id of ids) {
      if (on) {
        if (!next.has(id) && next.size >= maxSelect) continue
        next.add(id)
      } else {
        next.delete(id)
      }
    }
    selected.value = next
  }

  function toggleL1(ids: readonly number[]): void {
    setMany(ids, stateOf(ids) !== 'all')
  }

  function toggleL2(ids: readonly number[]): void {
    setMany(ids, stateOf(ids) !== 'all')
  }

  function clear(): void {
    selected.value = new Set()
  }

  /** 搜索判据：命中关键词才可见；空关键词全可见。不影响 `selected`。 */
  function isVisible(label: string): boolean {
    const key = keyword.value.trim().toLowerCase()
    return key.length === 0 || label.toLowerCase().includes(key)
  }

  return {
    selected,
    selectedIds,
    count,
    atLimit,
    maxSelect,
    keyword,
    isCaseChecked,
    stateOf,
    isChecked,
    isIndeterminate,
    toggleCase,
    toggleL1,
    toggleL2,
    setMany,
    clear,
    isVisible,
  }
}
