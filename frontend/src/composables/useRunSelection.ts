/**
 * 执行选择唯一真源（W57-29/30）。
 *
 * 缺陷背景（既有 ExecutionPlansView.toggleSet）：取消集合时用「剩余集合并集」重建
 * `selectedCaseIds`，会连**手工单独勾选、且不属于任何剩余集合**的用例一起删掉。
 * 例：集 A 有 3 条 + 手工勾 1 条 → 取消 A → 现行实现剩 0 条（应为 1 条）。
 *
 * 修法：不再用「集合勾选态反推已选」，而是记 `Map<caseId, Set<source>>`，
 * source 形如 `set:<id>` / `case:<id>`。一个用例可以来自多个来源；
 * **取消集合只摘掉那一个 `set:<id>` 来源**，摘完后该用例还有别的来源就继续留着。
 *
 * 四种模式（ALL / SET / CASE / MIXED）各自保存草稿：切走再切回不丢选择。
 */
import { computed, ref } from 'vue'

export type RunMode = 'ALL' | 'SET' | 'CASE' | 'MIXED'
export type RunSource = `set:${number}` | `case:${number}`

export interface RunSelectionSnapshot {
  map: Map<number, Set<RunSource>>
  all: boolean
}

/** 来源下钻的一行（SelectedSourcesDrawer / RunSelectionPanel 共用）。 */
export interface SourceEntry {
  caseId: number
  sources: RunSource[]
}

/** 交给 runLaunch.preview / 旧 runCases 的选择载荷。 */
export interface RunPayload {
  mode: RunMode
  caseIds: number[]
  setIds: number[]
  /** 逐用例来源下钻（SelectedSourcesDrawer 用）。 */
  sources: SourceEntry[]
  /** ALL 模式「全量」标记；其余模式恒 false。 */
  all: boolean
}

function cloneMap(src: Map<number, Set<RunSource>>): Map<number, Set<RunSource>> {
  const out = new Map<number, Set<RunSource>>()
  for (const [key, value] of src) out.set(key, new Set(value))
  return out
}

function sourceSet(setId: number): RunSource {
  return `set:${setId}`
}

function sourceCase(caseId: number): RunSource {
  return `case:${caseId}`
}

export function useRunSelection(initialMode: RunMode = 'MIXED') {
  const mode = ref<RunMode>(initialMode)
  /** 唯一真源：改一次换一个新 Map，保证所有 computed 一定重算。 */
  const current = ref<Map<number, Set<RunSource>>>(new Map())
  /** 仅 ALL 模式有意义：表示「全部用例」而非逐条明细。 */
  const allSelected = ref(false)

  const drafts: Record<RunMode, RunSelectionSnapshot> = {
    ALL: { map: new Map(), all: false },
    SET: { map: new Map(), all: false },
    CASE: { map: new Map(), all: false },
    MIXED: { map: new Map(), all: false },
  }

  function mutate(fn: (next: Map<number, Set<RunSource>>) => void): void {
    const next = cloneMap(current.value)
    fn(next)
    current.value = next
  }

  function isCaseSelected(caseId: number): boolean {
    return current.value.has(caseId)
  }

  function sourcesOf(caseId: number): RunSource[] {
    return [...(current.value.get(caseId) ?? [])]
  }

  /** 集合是否被「整体勾选」：它的每条用例都带该集合来源。空集合恒不算选中。 */
  function isSetSelected(setId: number, caseIds: readonly number[]): boolean {
    if (caseIds.length === 0) return false
    const src = sourceSet(setId)
    return caseIds.every((id) => current.value.get(id)?.has(src) === true)
  }

  /** 手工勾选一条用例：只增删 `case:<id>` 来源，不碰集合来源。 */
  function toggleCase(caseId: number): void {
    const src = sourceCase(caseId)
    mutate((next) => {
      const bucket = next.get(caseId) ?? new Set<RunSource>()
      if (bucket.has(src)) {
        bucket.delete(src)
        if (bucket.size === 0) next.delete(caseId)
        else next.set(caseId, bucket)
      } else {
        bucket.add(src)
        next.set(caseId, bucket)
      }
    })
  }

  /**
   * 勾选/取消集合：
   * - 勾选 = 给它的每条用例加 `set:<id>`；
   * - 取消 = **只删 `set:<id>`**，其它来源（手工勾选、另一个集合）原样保留。
   */
  function toggleSet(setId: number, caseIds: readonly number[]): void {
    const src = sourceSet(setId)
    const active = isSetSelected(setId, caseIds)
    mutate((next) => {
      for (const id of caseIds) {
        const bucket = next.get(id) ?? new Set<RunSource>()
        if (active) {
          bucket.delete(src)
          if (bucket.size === 0) next.delete(id)
          else next.set(id, bucket)
        } else {
          bucket.add(src)
          next.set(id, bucket)
        }
      }
    })
  }

  /** 切换模式：先把当前态存进旧模式草稿，再载入新模式草稿。 */
  function setMode(next: RunMode): void {
    if (next === mode.value) return
    drafts[mode.value] = { map: cloneMap(current.value), all: allSelected.value }
    const draft = drafts[next]
    current.value = cloneMap(draft.map)
    allSelected.value = draft.all
    mode.value = next
  }

  function setAll(on: boolean): void {
    allSelected.value = on
  }

  /** 清空当前模式（连同它的草稿），其它模式草稿不动。 */
  function clear(): void {
    current.value = new Map()
    allSelected.value = false
    drafts[mode.value] = { map: new Map(), all: false }
  }

  /** 兼容计算属性：老断言（`selectedCaseIds`）不删只补，仍拿到一份去重后的用例 id。 */
  const selectedCaseIds = computed<number[]>(() =>
    [...current.value.keys()].sort((a, b) => a - b),
  )
  const selectedCount = computed(() => current.value.size)

  const selectedSetIds = computed<number[]>(() => {
    const out = new Set<number>()
    for (const bucket of current.value.values()) {
      for (const src of bucket) {
        if (src.startsWith('set:')) out.add(Number(src.slice(4)))
      }
    }
    return [...out].sort((a, b) => a - b)
  })

  const manualCaseIds = computed<number[]>(() => {
    const out = new Set<number>()
    for (const bucket of current.value.values()) {
      for (const src of bucket) {
        if (src.startsWith('case:')) out.add(Number(src.slice(5)))
      }
    }
    return [...out].sort((a, b) => a - b)
  })

  function toPayload(): RunPayload {
    return {
      mode: mode.value,
      caseIds: selectedCaseIds.value,
      setIds: selectedSetIds.value,
      sources: [...current.value.entries()].map(([caseId, bucket]) => ({
        caseId,
        sources: [...bucket],
      })),
      all: mode.value === 'ALL' && allSelected.value,
    }
  }

  return {
    mode,
    allSelected,
    current,
    selectedCaseIds,
    selectedCount,
    selectedSetIds,
    manualCaseIds,
    isCaseSelected,
    isSetSelected,
    sourcesOf,
    toggleSet,
    toggleCase,
    clear,
    setMode,
    setAll,
    toPayload,
  }
}
