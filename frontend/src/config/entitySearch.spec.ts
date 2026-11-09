/** 实体搜索纯逻辑：固定分组、展平顺序与「只认最后一次响应」的竞态。 */
import { describe, expect, it } from 'vitest'
import type { SearchHit, SearchResponse, EntityType } from '@/api/search'
import {
  createEntitySearch,
  entityKey,
  flattenHits,
  groupHits,
  type EntitySearchState,
} from '@/config/entitySearch'

function hit(type: EntityType, id: number): SearchHit {
  return {
    type,
    id,
    title: `${type}-${id}`,
    subtitle: null,
    projectId: 1,
    projectName: 'Project A',
    path: `/${type}/${id}`,
    updatedAt: null,
    badge: null,
  }
}

function response(items: SearchHit[]): SearchResponse {
  return { query: 'q', scope: 'current', items, counts: {}, truncated: false }
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('实体结果分组', () => {
  it('按用例/运行/报告/计划/资产固定顺序分组，组内保留后端顺序', () => {
    const grouped = groupHits([
      hit('asset', 1),
      hit('run', 2),
      hit('case', 3),
      hit('run', 4),
      hit('plan', 5),
    ])
    expect(grouped.map((group) => group.type)).toEqual(['case', 'run', 'plan', 'asset'])
    expect(grouped[1].hits.map((item) => item.id)).toEqual([2, 4])
    expect(grouped.every((group) => group.hits.length > 0)).toBe(true)
  })

  it('展平顺序与分组展示一致，键盘索引不会跳组', () => {
    const hits = [hit('report', 9), hit('case', 7)]
    expect(flattenHits(hits).map((item) => item.id)).toEqual([7, 9])
  })

  it('实体 key 带命名空间，可兼容旧最近记录里的纯页面 key', () => {
    expect(entityKey(hit('case', 12))).toBe('entity:case:12')
    expect(entityKey(hit('case', 12))).not.toBe('cases')
  })
})

describe('实体搜索竞态', () => {
  it('快速连续输入时，先发后到的响应不会覆盖最后一次', async () => {
    const states: EntitySearchState[] = []
    let requestCount = 0
    let resolveFirst!: (value: SearchResponse) => void
    const first = new Promise<SearchResponse>((resolve) => {
      resolveFirst = resolve
    })

    const runner = createEntitySearch({
      debounceMs: 0,
      fetch: () => {
        requestCount += 1
        return requestCount === 1 ? first : Promise.resolve(response([hit('run', 2)]))
      },
      onState: (state) => states.push(state),
    })

    runner.search({ q: 'aa', scope: 'current' })
    await tick()
    runner.search({ q: 'bb', scope: 'current' })
    await tick()
    await tick()

    expect(states.at(-1)?.hits.map((item) => item.id)).toEqual([2])
    expect(states.at(-1)?.loading).toBe(false)

    // 迟到的第一条响应必须被丢弃
    resolveFirst(response([hit('case', 1)]))
    await tick()
    expect(states.at(-1)?.hits.map((item) => item.id)).toEqual([2])
  })

  it('关键字不足 2 字符立即清空且不请求', async () => {
    let calls = 0
    const states: EntitySearchState[] = []
    const runner = createEntitySearch({
      debounceMs: 0,
      fetch: () => {
        calls += 1
        return Promise.resolve(response([]))
      },
      onState: (state) => states.push(state),
    })

    runner.search({ q: 'a', scope: 'current' })
    await tick()
    expect(calls).toBe(0)
    expect(states.at(-1)).toEqual({ hits: [], loading: false, error: false })
  })
})
