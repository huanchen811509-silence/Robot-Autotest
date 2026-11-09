/** 保存视图：payload 补默认值、应用回填、同名冲突、默认切换与本机列持久化。 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/http'
import type { CaseViewPayload, SavedView } from '@/api/views'
import {
  DEFAULT_CASE_COLUMNS,
  DEFAULT_CASE_VIEW_PAYLOAD,
  applyViewState,
  canDeleteView,
  columnsStorageKey,
  findDuplicateView,
  loadLocalColumns,
  normalizeViewPayload,
  saveLocalColumns,
  useSavedViews,
} from '@/composables/useSavedViews'

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    id: 1,
    projectId: 1,
    userId: 7,
    name: 'View 1',
    scope: 'case',
    payload: { ...DEFAULT_CASE_VIEW_PAYLOAD },
    isDefault: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function makeApi(views: SavedView[]) {
  return {
    list: vi.fn(async () => views),
    create: vi.fn(async (_projectId: number, body: { name: string; isDefault: boolean }) => {
      return makeView({ id: 9, name: body.name, isDefault: body.isDefault })
    }),
    update: vi.fn(async (_projectId: number, id: number, body: { name: string; isDefault: boolean }) => {
      return makeView({ id, name: body.name, isDefault: body.isDefault })
    }),
    remove: vi.fn(async (_projectId: number, id: number) => {
      const index = views.findIndex((view) => view.id === id)
      if (index >= 0) views.splice(index, 1)
    }),
  }
}

beforeEach(() => localStorage.clear())

describe('normalizeViewPayload', () => {
  it('缺字段/脏字段回落到默认值', () => {
    expect(normalizeViewPayload(null)).toEqual(DEFAULT_CASE_VIEW_PAYLOAD)
    const payload = normalizeViewPayload({
      keyword: 1,
      domain: 'roce',
      size: -3,
      columns: ['title', 2, 'priority'],
      sort: { columnKey: 'title', order: 'sideways' },
    })
    expect(payload.keyword).toBe('')
    expect(payload.domain).toBe('roce')
    expect(payload.size).toBe(DEFAULT_CASE_VIEW_PAYLOAD.size)
    expect(payload.columns).toEqual(['title', 'priority'])
    expect(payload.sort).toBeNull()
  })

  it('页大小夹到上限，合法排序保留', () => {
    const payload = normalizeViewPayload({ size: 9999, sort: { columnKey: 'title', order: 'desc' } })
    expect(payload.size).toBe(200)
    expect(payload.sort).toEqual({ columnKey: 'title', order: 'desc' })
  })
})

describe('applyViewState', () => {
  it('回填筛选/页大小/列，页码固定回 1', () => {
    const payload: CaseViewPayload = {
      keyword: 'roce',
      domain: 'network',
      priority: 'P1',
      reviewStatus: 'pending',
      safetyLevel: 'L1',
      size: 20,
      columns: ['title', 'priority'],
      sort: null,
    }
    const state = applyViewState(payload)
    expect(state).toEqual({
      keyword: 'roce',
      domain: 'network',
      priority: 'P1',
      reviewStatus: 'pending',
      safetyLevel: 'L1',
      size: 20,
      columns: ['title', 'priority'],
      page: 1,
    })
    // 拷贝列数组：页面改动不应反向污染视图 payload
    state.columns.push('extra')
    expect(payload.columns).toEqual(['title', 'priority'])
  })
})

describe('冲突与删除权限', () => {
  it('同名判断去空格、忽略大小写', () => {
    const views = [makeView({ name: 'High Priority' })]
    expect(findDuplicateView(views, '  high priority ')?.id).toBe(1)
    expect(findDuplicateView(views, '   ')).toBeUndefined()
    expect(findDuplicateView(views, 'other')).toBeUndefined()
  })

  it('只有本人的视图可删除；未登录不可删', () => {
    const view = makeView({ userId: 7 })
    expect(canDeleteView(view, 7)).toBe(true)
    expect(canDeleteView(view, 8)).toBe(false)
    expect(canDeleteView(view, null)).toBe(false)
    expect(canDeleteView(makeView({ userId: null }), 7)).toBe(false)
  })
})

describe('本机列设置持久化', () => {
  it('按 projectId + scope 分键，读回顺序一致', () => {
    saveLocalColumns(1, 'case', ['title', 'priority'])
    expect(localStorage.getItem(columnsStorageKey(1, 'case'))).toBe(
      JSON.stringify(['title', 'priority']),
    )
    expect(loadLocalColumns(1, 'case')).toEqual(['title', 'priority'])
    expect(loadLocalColumns(2, 'case')).toBeNull()
    expect(loadLocalColumns(0, 'case')).toBeNull()
  })

  it('脏 JSON 或非数组不炸，直接当作未配置', () => {
    localStorage.setItem(columnsStorageKey(1, 'case'), '{not-json')
    expect(loadLocalColumns(1, 'case')).toBeNull()
    localStorage.setItem(columnsStorageKey(1, 'case'), '{"a":1}')
    expect(loadLocalColumns(1, 'case')).toBeNull()
  })
})

describe('useSavedViews', () => {
  it('加载列表并解析默认视图', async () => {
    const api = makeApi([makeView({ id: 1 }), makeView({ id: 2, isDefault: true })])
    const views = useSavedViews(() => 1, 'case', api)
    await views.load()
    expect(api.list).toHaveBeenCalledWith(1, 'case')
    expect(views.views.value).toHaveLength(2)
    expect(views.defaultView.value?.id).toBe(2)
  })

  it('项目为空时不请求，直接返回空列表', async () => {
    const api = makeApi([])
    const views = useSavedViews(() => 0, 'case', api)
    expect(await views.load()).toEqual([])
    expect(api.list).not.toHaveBeenCalled()
  })

  it('空名 / 本地同名 / 后端 409 都给出可区分的失败原因', async () => {
    const api = makeApi([makeView({ name: 'existing' })])
    const views = useSavedViews(() => 1, 'case', api)
    await views.load()

    expect(await views.save('   ', DEFAULT_CASE_VIEW_PAYLOAD)).toEqual({
      ok: false,
      reason: 'empty',
    })
    const duplicate = await views.save('Existing', DEFAULT_CASE_VIEW_PAYLOAD)
    expect(duplicate.ok).toBe(false)
    if (!duplicate.ok) expect(duplicate.reason).toBe('conflict')
    expect(api.create).not.toHaveBeenCalled()

    api.create.mockRejectedValueOnce(new ApiError(409, 'CONFLICT', 'duplicate'))
    const remote = await views.save('Other', DEFAULT_CASE_VIEW_PAYLOAD)
    expect(remote.ok).toBe(false)
    if (!remote.ok) expect(remote.reason).toBe('conflict')
  })

  it('保存默认视图后本地只保留一个默认', async () => {
    const api = makeApi([makeView({ id: 1 }), makeView({ id: 2 })])
    const views = useSavedViews(() => 1, 'case', api)
    await views.load()

    const result = await views.save('New default', DEFAULT_CASE_VIEW_PAYLOAD, true)
    expect(result.ok).toBe(true)
    expect(views.defaultView.value?.name).toBe('New default')
    expect(views.views.value.filter((view) => view.isDefault)).toHaveLength(1)
  })

  it('切换默认视图：调用 PUT，排他地更新本地 isDefault', async () => {
    const first = makeView({ id: 1, isDefault: true })
    const second = makeView({ id: 2 })
    const api = makeApi([first, second])
    const views = useSavedViews(() => 1, 'case', api)
    await views.load()

    expect(await views.makeDefault(2)).toBe(true)
    expect(api.update).toHaveBeenCalledWith(
      1,
      2,
      expect.objectContaining({ isDefault: true }),
    )
    expect(views.views.value.filter((view) => view.isDefault).map((view) => view.id)).toEqual([2])
  })

  it('删除后从列表移除；解析视图时脏 payload 已补默认值', async () => {
    const broken = makeView({ id: 3, payload: { keyword: 'x', columns: ['title'] } })
    const api = makeApi([broken])
    const views = useSavedViews(() => 1, 'case', api)
    await views.load()

    expect(views.resolve(broken)).toMatchObject({
      keyword: 'x',
      columns: ['title'],
      size: DEFAULT_CASE_VIEW_PAYLOAD.size,
    })

    expect(await views.remove(3)).toBe(true)
    expect(views.views.value).toEqual([])
  })

  it('默认列清单与 payload 默认值保持同源', () => {
    expect(DEFAULT_CASE_VIEW_PAYLOAD.columns).toEqual(DEFAULT_CASE_COLUMNS)
  })
})
