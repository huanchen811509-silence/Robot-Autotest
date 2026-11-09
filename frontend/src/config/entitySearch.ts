/**
 * 全局实体搜索的前端纯逻辑（v3.1.0「顺手」）。
 *
 * <p>三件事被抽到这里，避免命令面板组件里堆满难以测试的时序代码：
 * ① 结果按「用例 → 运行 → 报告 → 计划 → 资产」固定分组；
 * ② 防抖 + 竞态：快速连续输入时只认最后一次响应；
 * ③ 统一实体 key，供最近记录复用旧 localStorage 结构。
 */

import type { EntityType, SearchHit, SearchResponse } from '@/api/search'

export const ENTITY_TYPE_ORDER: EntityType[] = ['case', 'run', 'report', 'plan', 'asset']

export const ENTITY_SEARCH_MIN_CHARS = 2
export const ENTITY_SEARCH_DEBOUNCE_MS = 200

export interface EntityGroup {
  type: EntityType
  hits: SearchHit[]
}

/** 按固定类型顺序分组，组内保持后端返回顺序；空组不出现。 */
export function groupHits(hits: SearchHit[]): EntityGroup[] {
  const buckets = new Map<EntityType, SearchHit[]>()
  for (const hit of hits) {
    const bucket = buckets.get(hit.type)
    if (bucket) bucket.push(hit)
    else buckets.set(hit.type, [hit])
  }
  return ENTITY_TYPE_ORDER.flatMap((type) => {
    const bucket = buckets.get(type)
    return bucket && bucket.length > 0 ? [{ type, hits: bucket }] : []
  })
}

/**
 * 展平成与界面分组一致的顺序。
 *
 * <p>键盘上下键必须和眼睛看到的顺序一致，否则「按了三次下键却跳到第三组」。
 */
export function flattenHits(hits: SearchHit[]): SearchHit[] {
  return groupHits(hits).flatMap((group) => group.hits)
}

export function entityKey(hit: Pick<SearchHit, 'type' | 'id'>): string {
  return `entity:${hit.type}:${hit.id}`
}

export interface EntitySearchState {
  hits: SearchHit[]
  loading: boolean
  error: boolean
}

export interface EntitySearchRequest {
  q: string
  projectId?: number | null
  scope: SearchResponse['scope']
}

interface EntitySearchOptions {
  fetch: (request: EntitySearchRequest) => Promise<SearchResponse>
  onState: (state: EntitySearchState) => void
  debounceMs?: number
  minChars?: number
}

export interface EntitySearchRunner {
  /** 输入变化时调用；内部做防抖、竞态与空关键字重置 */
  search: (request: EntitySearchRequest) => void
  /** 关闭面板 / 组件卸载时调用，让在途响应失效 */
  dispose: () => void
}

export function createEntitySearch(options: EntitySearchOptions): EntitySearchRunner {
  const debounceMs = options.debounceMs ?? ENTITY_SEARCH_DEBOUNCE_MS
  const minChars = options.minChars ?? ENTITY_SEARCH_MIN_CHARS
  let seq = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function clearTimer() {
    if (timer !== undefined) {
      clearTimeout(timer)
      timer = undefined
    }
  }

  function search(request: EntitySearchRequest) {
    if (disposed) return
    // 每次输入都推进序号：旧请求即使后返回也拿不到最新 token。
    seq += 1
    const token = seq
    clearTimer()

    const term = request.q.trim()
    if (term.length < minChars) {
      options.onState({ hits: [], loading: false, error: false })
      return
    }

    options.onState({ hits: [], loading: true, error: false })
    timer = setTimeout(() => {
      void (async () => {
        try {
          const response = await options.fetch({ ...request, q: term })
          if (disposed || token !== seq) return
          options.onState({ hits: response.items ?? [], loading: false, error: false })
        } catch {
          if (disposed || token !== seq) return
          options.onState({ hits: [], loading: false, error: true })
        }
      })()
    }, debounceMs)
  }

  function dispose() {
    disposed = true
    seq += 1
    clearTimer()
  }

  return { search, dispose }
}
