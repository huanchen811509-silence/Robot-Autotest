/**
 * W57-15 · 列表筛选 URL 化单测。
 *
 * 覆盖：q / f_ / t_ 三类键的解析与序列化、空值与非法值降级、prefix 多表隔离、
 * 与分页/排序参数共存、revision / isFiltered / queryParams、外部 URL 回填。
 * 全部用内存适配器，不碰真实 URL、不打网络。
 */
import { describe, expect, it } from 'vitest'
import {
  parseFilterParams,
  serializeFilterParams,
  createMemoryQueryAdapter,
  useTableFilters,
  type TableFilterState,
} from './useTableFilters'

function emptyState(): TableFilterState {
  return { q: '', facets: {}, texts: {} }
}

describe('parseFilterParams 解析与降级', () => {
  it('解析 q 全局模糊、f_<列> 多值、t_<列> 列内模糊', () => {
    const state = parseFilterParams({ q: 'rdma', f_state: 'PASS,FAIL', t_name: 'smoke' })
    expect(state.q).toBe('rdma')
    expect(state.facets.state).toEqual(['PASS', 'FAIL'])
    expect(state.texts.name).toBe('smoke')
  })

  it('空值直接剔除，不留空 facets / texts', () => {
    const state = parseFilterParams({ q: '', f_state: '', t_name: '', f_priority: ' , ' })
    expect(state).toEqual(emptyState())
  })

  it('未声明的列被丢弃（columns 白名单）', () => {
    const state = parseFilterParams(
      { f_state: 'PASS', f_hack: 'X', t_name: 'smoke', t_hack: 'Y' },
      { columns: ['state'] },
    )
    expect(state.facets).toEqual({ state: ['PASS'] })
    expect(state.texts).toEqual({})
  })

  it('不在枚举白名单里的值被丢弃', () => {
    const state = parseFilterParams(
      { f_state: 'PASS,BOGUS,FAIL' },
      { allowedFacets: { state: ['PASS', 'FAIL'] } },
    )
    expect(state.facets.state).toEqual(['PASS', 'FAIL'])
  })

  it('控制字符被剔除、超长文本被截断', () => {
    const dirty = `ok\u0007${'x'.repeat(400)}`
    const state = parseFilterParams({ q: dirty, t_name: dirty })
    expect(state.q).not.toContain('\u0007')
    expect(state.q.length).toBeLessThanOrEqual(200)
    expect(state.texts.name.length).toBeLessThanOrEqual(200)
  })

  it('多值去重且有条数上限', () => {
    const raw = Array.from({ length: 80 }, (_, i) => `V${i}`).join(',')
    const state = parseFilterParams({ f_state: `A,A,${raw}` })
    expect(state.facets.state[0]).toBe('A')
    expect(new Set(state.facets.state).size).toBe(state.facets.state.length)
    expect(state.facets.state.length).toBeLessThanOrEqual(50)
  })

  it('prefix 只解析自己管辖的键', () => {
    const state = parseFilterParams(
      { q: 'global', member_q: 'm', member_f_priority: 'HIGH', sets_t_name: 'x' },
      { prefix: 'member_', columns: ['priority'] },
    )
    expect(state.q).toBe('m')
    expect(state.facets.priority).toEqual(['HIGH'])
    expect(state.texts).toEqual({})
  })
})

describe('serializeFilterParams 序列化', () => {
  it('产出 canonical 键（无 prefix）', () => {
    const out = serializeFilterParams({ q: 'rdma', facets: { state: ['PASS', 'FAIL'] }, texts: { name: 's' } })
    expect(out).toEqual({ q: 'rdma', f_state: 'PASS,FAIL', t_name: 's' })
  })

  it('prefix 原样前置', () => {
    const out = serializeFilterParams(
      { q: '', facets: { priority: ['HIGH'] }, texts: {} },
      { prefix: 'member_' },
    )
    expect(out).toEqual({ member_f_priority: 'HIGH' })
  })

  it('空值不写进 query', () => {
    expect(serializeFilterParams(emptyState(), { prefix: 'sets_' })).toEqual({})
  })

  it('parse(serialize(state)) 可完整还原', () => {
    const state: TableFilterState = {
      q: 'rdma',
      facets: { state: ['PASS', 'FAIL'] },
      texts: { name: 'smoke' },
    }
    expect(parseFilterParams(serializeFilterParams(state))).toEqual(state)
  })
})

describe('useTableFilters 组合式行为', () => {
  it('写回 URL 时保留 page / sort 等非本筛选器管辖参数', () => {
    const adapter = createMemoryQueryAdapter({ page: '3', sort: 'startedAt', tab: 'devices' })
    const f = useTableFilters({ columns: ['state'], adapter })
    f.setFacet('state', ['PASS'])
    expect(adapter.current()).toMatchObject({
      page: '3', sort: 'startedAt', tab: 'devices', f_state: 'PASS',
    })
  })

  it('清空后把旧键从 URL 剔除', () => {
    const adapter = createMemoryQueryAdapter({ page: '2', f_state: 'PASS' })
    const f = useTableFilters({ columns: ['state'], adapter })
    expect(f.facets.value.state).toEqual(['PASS'])
    f.clearAll()
    expect(adapter.current()).toEqual({ page: '2' })
  })

  it('同页两个 prefix 互不覆盖', () => {
    const adapter = createMemoryQueryAdapter({ page: '3' })
    const a = useTableFilters({ prefix: 'member_', columns: ['priority'], adapter })
    const b = useTableFilters({ prefix: 'sets_', columns: ['name'], adapter })
    a.setFacet('priority', ['HIGH'])
    b.setText('name', 'smoke')
    expect(adapter.current()).toMatchObject({
      page: '3', member_f_priority: 'HIGH', sets_t_name: 'smoke',
    })
    expect(a.facets.value.priority).toEqual(['HIGH'])
    expect(b.texts.value.name).toBe('smoke')
  })

  it('revision 随筛选变化自增，isFiltered / queryParams 同步', () => {
    const adapter = createMemoryQueryAdapter({})
    const f = useTableFilters({ columns: ['state'], adapter })
    expect(f.isFiltered.value).toBe(false)
    f.setQuery('rdma')
    f.setFacet('state', ['PASS'])
    expect(f.revision.value).toBeGreaterThan(0)
    expect(f.isFiltered.value).toBe(true)
    expect(f.queryParams.value).toEqual({ q: 'rdma', f_state: 'PASS' })
    f.clearAll()
    expect(f.isFiltered.value).toBe(false)
    expect(f.queryParams.value).toEqual({})
  })

  it('setFacet / toggleFacet / setText / clearColumn 都落到 URL', () => {
    const adapter = createMemoryQueryAdapter({})
    const f = useTableFilters({ columns: ['state', 'name'], adapter })
    f.setFacet('state', ['PASS'])
    f.toggleFacet('state', 'FAIL')
    f.setText('name', 'smoke')
    expect(adapter.current()).toEqual({ f_state: 'PASS,FAIL', t_name: 'smoke' })
    f.clearColumn('state')
    expect(adapter.current()).toEqual({ t_name: 'smoke' })
    f.setText('name', '')
    expect(adapter.current()).toEqual({})
  })

  it('非法列无法写入（setFacet / setText 守卫）', () => {
    const adapter = createMemoryQueryAdapter({})
    const f = useTableFilters({ columns: ['state'], adapter })
    f.setFacet('hack', ['X'])
    f.setText('hack', 'Y')
    expect(adapter.current()).toEqual({})
  })

  it('外部 URL 变化（前进后退）经 adapter.set 回填状态', () => {
    const adapter = createMemoryQueryAdapter({})
    const f = useTableFilters({ columns: ['state'], adapter })
    adapter.set({ q: 'rdma', f_state: 'PASS', page: '5' })
    expect(f.q.value).toBe('rdma')
    expect(f.facets.value.state).toEqual(['PASS'])
    // 回填不应把别人的参数吃掉
    expect(adapter.current().page).toBe('5')
  })

  it('stop() 后不再响应外部 URL 变化', () => {
    const adapter = createMemoryQueryAdapter({})
    const f = useTableFilters({ columns: ['state'], adapter })
    f.stop()
    adapter.set({ f_state: 'PASS' })
    expect(f.facets.value.state).toBeUndefined()
  })
})
