/**
 * 列表筛选状态 URL 化（W57-15）。
 *
 * 设计要点（都是被实际页面坑过之后才定下来的）：
 *  ① **URL 是唯一真源**：筛选写进 query，刷新 / 分享链接 / 前进后退都能还原；
 *  ② **参数命名**：`q` 全局模糊、`f_<列>` 多值（逗号分隔）、`t_<列>` 列内模糊；
 *     同页多表用 `prefix` 原样前置（如 `member_f_state`）隔离，互不覆盖；
 *  ③ **只管自己的键**：写回时保留 `page` / `size` / `sort` / `tab` 等别人的参数，
 *     因此与分页、排序、页签天然共存；空值直接从 URL 剔除，不留 `?f_state=`；
 *  ④ **非法值降级**：未声明的列、不在枚举白名单里的值、控制字符、超长值一律丢弃，
 *     不让脏 URL 把页面带进一个显示不出来的状态；
 *  ⑤ **vue-router 兼容**：有 router 注入时用 `router.replace({query})` 写（避免与库状态分叉），
 *     没有注入（单测 / 直接挂载）时退回 `history.replaceState`；两者都以
 *     `window.location.search` 为读源，`popstate` 负责前进后退还原。
 *
 * 本模块不引 naive-ui、不读业务接口，纯状态 + 纯函数，便于组合层单测。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type Router } from 'vue-router'

/** 一列的可筛选声明；`mode='multi'` 走枚举多值，`mode='text'` 走模糊。 */
export interface TableFilterColumn {
  key: string
  title: string
  mode?: 'multi' | 'text'
  options?: { label: string; value: string }[]
}

export interface TableFilterState {
  /** 全局模糊 */
  q: string
  /** 列 → 多值精确匹配 */
  facets: Record<string, string[]>
  /** 列 → 列内模糊 */
  texts: Record<string, string>
}

/** query 读写适配器：单测可注入内存实现，生产按运行环境自动选择。 */
export interface FilterQueryAdapter {
  /** 当前完整 query（已归一成 string 值） */
  read(): Record<string, string>
  /** 用给定完整 query 覆盖当前 URL 的查询串 */
  write(params: Record<string, string>): void
  /** 订阅外部 URL 变化（前进后退），返回取消订阅函数 */
  subscribe(cb: () => void): () => void
}

export interface UseTableFiltersOptions {
  /** 同页多表隔离前缀，原样前置；建议形如 `'member_'` */
  prefix?: string
  /** 允许出现的列白名单；给了就丢弃未声明的列（非法值降级） */
  columns?: readonly string[]
  /** 列 → 合法枚举值白名单；给了就丢弃不在表内的值 */
  allowedFacets?: Record<string, readonly string[]>
  /** 注入适配器（单测用）；不传自动选择 */
  adapter?: FilterQueryAdapter
}

const MAX_TEXT = 200
const MAX_VALUES = 50
const MAX_VALUE = 64
// URL 参数净化必须剔除 C0 控制字符与 DEL，否则筛选值可夹带不可见字符绕过列白名单。
// 这里刻意匹配控制字符，故对 no-control-regex 做单行豁免（有理由、不关规则）。
// eslint-disable-next-line no-control-regex
const CONTROL_RE = /[\u0000-\u001f\u007f]/g

function cleanText(raw: string | null | undefined): string {
  if (typeof raw !== 'string') return ''
  return raw.replace(CONTROL_RE, '').trim().slice(0, MAX_TEXT)
}

function cleanMulti(raw: string | null | undefined, allowed?: readonly string[]): string[] {
  if (typeof raw !== 'string') return []
  const parts = raw
    .split(',')
    .map((part) => part.replace(CONTROL_RE, '').trim().slice(0, MAX_VALUE))
    .filter((part) => part.length > 0)
  const unique = [...new Set(parts)].slice(0, MAX_VALUES)
  return allowed ? unique.filter((value) => allowed.includes(value)) : unique
}

function qKey(prefix: string): string {
  return `${prefix}q`
}
function fPrefix(prefix: string): string {
  return `${prefix}f_`
}
function tPrefix(prefix: string): string {
  return `${prefix}t_`
}

/** 属于本筛选器管辖的键：用于写回时把「已清空」的旧键从 URL 里剔除。 */
function ownedKeys(current: Record<string, string>, prefix: string): Set<string> {
  const owned = new Set<string>()
  for (const key of Object.keys(current)) {
    if (
      key === qKey(prefix) ||
      key.startsWith(fPrefix(prefix)) ||
      key.startsWith(tPrefix(prefix))
    ) {
      owned.add(key)
    }
  }
  return owned
}

/** 纯函数：从 query 解析出筛选状态；空值 / 非法列名 / 非法枚举值全部降级剔除。 */
export function parseFilterParams(
  current: Record<string, string>,
  options: UseTableFiltersOptions = {},
): TableFilterState {
  const prefix = options.prefix ?? ''
  const columns = options.columns
  const state: TableFilterState = { q: '', facets: {}, texts: {} }
  state.q = cleanText(current[qKey(prefix)])

  for (const [key, raw] of Object.entries(current)) {
    if (key.startsWith(fPrefix(prefix))) {
      const column = key.slice(fPrefix(prefix).length)
      if (!column || (columns && !columns.includes(column))) continue
      const values = cleanMulti(raw, options.allowedFacets?.[column])
      if (values.length > 0) state.facets[column] = values
    } else if (key.startsWith(tPrefix(prefix))) {
      const column = key.slice(tPrefix(prefix).length)
      if (!column || (columns && !columns.includes(column))) continue
      const text = cleanText(raw)
      if (text) state.texts[column] = text
    }
  }
  return state
}

/** 纯函数：把筛选状态序列化成本筛选器管辖的 query 片段（不含别人的分页/排序键）。 */
export function serializeFilterParams(
  state: TableFilterState,
  options: UseTableFiltersOptions = {},
): Record<string, string> {
  const prefix = options.prefix ?? ''
  const out: Record<string, string> = {}
  if (state.q) out[qKey(prefix)] = state.q
  for (const [column, values] of Object.entries(state.facets)) {
    if (values.length > 0) out[`${fPrefix(prefix)}${column}`] = values.join(',')
  }
  for (const [column, text] of Object.entries(state.texts)) {
    if (text) out[`${tPrefix(prefix)}${column}`] = text
  }
  return out
}

function sameState(a: TableFilterState, b: TableFilterState): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function emptyState(): TableFilterState {
  return { q: '', facets: {}, texts: {} }
}

function readWindowQuery(): Record<string, string> {
  const out: Record<string, string> = {}
  const params = new URLSearchParams(window.location.search)
  params.forEach((value, key) => {
    out[key] = value
  })
  return out
}

function writeWindowQuery(params: Record<string, string>): void {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== '') search.set(key, value)
  }
  const text = search.toString()
  const url = `${window.location.pathname}${text ? `?${text}` : ''}${window.location.hash}`
  // 保留 vue-router 写入的 history.state，避免把它清空成 null 影响库的回退位置
  window.history.replaceState(window.history.state, '', url)
}

/** 有 router 时优先用库写，避免 `route.query` 与实际 URL 分叉；否则退回原生 history。 */
function safeRouter(): Router | null {
  try {
    // 没有注入 vue-router 时 useRoute() 返回 undefined（不抛），useRouter() 同理。
    const route = useRoute()
    const router = useRouter()
    if (!route || !router || typeof router.replace !== 'function') return null
    return router
  } catch {
    return null
  }
}

function defaultAdapter(router: Router | null): FilterQueryAdapter {
  return {
    read: readWindowQuery,
    write(params) {
      if (router) {
        // vue-router 的 replace 返回 Promise；被守卫拒绝 / 重复导航时用原生兜底，
        // 保证 URL 与内存状态不分离。某些单测桩返回 undefined，这里按有无 catch 兼容。
        const result = router.replace({ query: params }) as unknown
        if (result && typeof (result as Promise<unknown>).catch === 'function') {
          void (result as Promise<unknown>).catch(() => writeWindowQuery(params))
        }
      } else {
        writeWindowQuery(params)
      }
    },
    subscribe(cb) {
      window.addEventListener('popstate', cb)
      return () => window.removeEventListener('popstate', cb)
    },
  }
}

/** 内存适配器：单测用，完全不碰真实 URL。 */
export function createMemoryQueryAdapter(initial: Record<string, string> = {}): FilterQueryAdapter & {
  current(): Record<string, string>
  set(params: Record<string, string>): void
} {
  let params: Record<string, string> = { ...initial }
  const listeners = new Set<() => void>()
  return {
    read: () => ({ ...params }),
    write(next) {
      params = { ...next }
      for (const cb of listeners) cb()
    },
    subscribe(cb) {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    current: () => ({ ...params }),
    set(next) {
      params = { ...next }
      for (const cb of listeners) cb()
    },
  }
}

/**
 * 列表筛选组合式函数。
 *
 * 返回值里 `revision` 是给宿主用的：筛选一变就自增，宿主 `watch(revision, …)` 把
 * `page` 重置回 1（否则带着第 9 页的页码去套新条件，会得到空列表）。
 */
export function useTableFilters(options: UseTableFiltersOptions = {}) {
  const adapter = options.adapter ?? defaultAdapter(safeRouter())
  const state = ref<TableFilterState>(parseFilterParams(adapter.read(), options))
  const revision = ref(0)
  let restoring = false

  function writeUrl(): void {
    const current = adapter.read()
    const base: Record<string, string> = {}
    const owned = ownedKeys(current, options.prefix ?? '')
    for (const [key, value] of Object.entries(current)) {
      if (!owned.has(key)) base[key] = value
    }
    adapter.write({ ...base, ...serializeFilterParams(state.value, options) })
  }

  // sync flush：restore() 里置 restoring 后同步赋值，watcher 同步触发时仍在保护区内，
  // 不会把「外部 URL 变化」误当成「用户改筛选」再写一次。
  watch(
    state,
    () => {
      revision.value += 1
      if (!restoring) writeUrl()
    },
    { deep: true, flush: 'sync' },
  )

  function restoreFromUrl(): void {
    const next = parseFilterParams(adapter.read(), options)
    if (sameState(state.value, next)) return
    restoring = true
    state.value = next
    restoring = false
  }

  const unsubscribe = adapter.subscribe(restoreFromUrl)

  // 显式 stop：组合式函数被手动销毁时（非组件上下文）也能解绑 popstate。
  function stop(): void {
    unsubscribe()
  }

  function setQuery(value: string): void {
    state.value.q = cleanText(value)
  }

  function setFacet(column: string, values: readonly string[]): void {
    if (options.columns && !options.columns.includes(column)) return
    const cleaned = cleanMulti(values.join(','), options.allowedFacets?.[column])
    if (cleaned.length === 0) delete state.value.facets[column]
    else state.value.facets[column] = cleaned
  }

  function toggleFacet(column: string, value: string): void {
    const current = state.value.facets[column] ?? []
    setFacet(column, current.includes(value) ? current.filter((v) => v !== value) : [...current, value])
  }

  function setText(column: string, value: string): void {
    if (options.columns && !options.columns.includes(column)) return
    const cleaned = cleanText(value)
    if (!cleaned) delete state.value.texts[column]
    else state.value.texts[column] = cleaned
  }

  function clearColumn(column: string): void {
    delete state.value.facets[column]
    delete state.value.texts[column]
  }

  function clearAll(): void {
    state.value = emptyState()
  }

  const q = computed<string>({
    get: () => state.value.q,
    set: (value) => setQuery(value),
  })
  const facets = computed<Record<string, string[]>>(() => state.value.facets)
  const texts = computed<Record<string, string>>(() => state.value.texts)

  /** 是否有任何生效中的筛选（用于空态三态：区分「本来就没数据」与「被筛空」）。 */
  const isFiltered = computed(
    () =>
      state.value.q.length > 0 ||
      Object.keys(state.value.facets).length > 0 ||
      Object.keys(state.value.texts).length > 0,
  )

  /** 交给 API 的规范 query：canonical 键（`q` / `f_<列>` / `t_<列>`），宿主再叠加 page/size/sort。 */
  const queryParams = computed<Record<string, string>>(() => {
    const canonical = serializeFilterParams(state.value, {})
    // serializeFilterParams 默认 prefix='' 正好产出 canonical 键
    return canonical
  })

  return {
    state,
    q,
    facets,
    texts,
    isFiltered,
    queryParams,
    revision,
    setQuery,
    setFacet,
    toggleFacet,
    setText,
    clearColumn,
    clearAll,
    restoreFromUrl,
    stop,
  }
}
