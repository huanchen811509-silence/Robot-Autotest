/**
 * 保存视图的读写与回填（v3.1.0「顺手」）。
 *
 * <p>把「列表加载 / 同名冲突 / 默认视图切换 / payload 补默认值」收在一处：
 * 组件只负责弹窗与按钮，规则可以脱离 DOM 单测。后端把 payload 当不透明 JSON，
 * 所以任何读回来的视图都必须先过 `normalizeViewPayload`，升级字段时才不会崩。
 */

import { computed, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  viewsApi,
  type CaseViewPayload,
  type SavedView,
  type SavedViewInput,
} from '@/api/views'

export const DEFAULT_CASE_COLUMNS = [
  'caseCode',
  'title',
  'priority',
  'safetyLevel',
  'reviewStatus',
  'domainTag',
  'capability',
]

export const DEFAULT_CASE_VIEW_PAYLOAD: CaseViewPayload = {
  keyword: '',
  domain: '',
  priority: '',
  reviewStatus: '',
  safetyLevel: '',
  size: 10,
  columns: [...DEFAULT_CASE_COLUMNS],
  sort: null,
}

const MAX_PAGE_SIZE = 200

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

/** 后端存的是不透明 JSON：这里逐字段校验并补默认值，脏值不会污染表格状态。 */
export function normalizeViewPayload(input: unknown): CaseViewPayload {
  const raw = input && typeof input === 'object' && !Array.isArray(input)
    ? (input as Record<string, unknown>)
    : {}
  const size = typeof raw.size === 'number' && Number.isFinite(raw.size) && raw.size > 0
    ? Math.min(Math.floor(raw.size), MAX_PAGE_SIZE)
    : DEFAULT_CASE_VIEW_PAYLOAD.size
  const columns = Array.isArray(raw.columns)
    ? raw.columns.filter((item): item is string => typeof item === 'string')
    : [...DEFAULT_CASE_COLUMNS]
  const sortRaw = raw.sort
  const sort = sortRaw && typeof sortRaw === 'object'
    && typeof (sortRaw as Record<string, unknown>).columnKey === 'string'
    && ((sortRaw as Record<string, unknown>).order === 'asc' || (sortRaw as Record<string, unknown>).order === 'desc')
      ? {
          columnKey: (sortRaw as Record<string, unknown>).columnKey as string,
          order: (sortRaw as Record<string, unknown>).order as 'asc' | 'desc',
        }
      : null

  return {
    keyword: text(raw.keyword),
    domain: text(raw.domain),
    priority: text(raw.priority),
    reviewStatus: text(raw.reviewStatus),
    safetyLevel: text(raw.safetyLevel),
    size,
    columns,
    sort,
  }
}

export interface CaseViewState {
  keyword: string
  domain: string
  priority: string
  reviewStatus: string
  safetyLevel: string
  size: number
  columns: string[]
  /** 视图只描述筛选条件、不描述「第几页」，应用后一律回第 1 页。 */
  page: number
}

/**
 * 把 payload 摊平成页面可直接写回的状态。
 *
 * <p>抽成纯函数是为了单测能覆盖「应用视图后筛选/页大小/列回填，页码归 1」，
 * 不必为了一个赋值序列去挂载整个用例页。
 */
export function applyViewState(payload: CaseViewPayload): CaseViewState {
  return {
    keyword: payload.keyword,
    domain: payload.domain,
    priority: payload.priority,
    reviewStatus: payload.reviewStatus,
    safetyLevel: payload.safetyLevel,
    size: payload.size,
    columns: [...payload.columns],
    page: 1,
  }
}

/** 同名冲突：去空格、忽略大小写，中文名称不会被误判。 */
export function findDuplicateView(views: SavedView[], name: string): SavedView | undefined {
  const target = name.trim().toLocaleLowerCase()
  if (!target) return undefined
  return views.find((view) => view.name.trim().toLocaleLowerCase() === target)
}

export function canDeleteView(view: SavedView, userId: number | null | undefined): boolean {
  return userId !== null && userId !== undefined && view.userId === userId
}

export function columnsStorageKey(projectId: number, scope: string): string {
  return `robot.views.columns.${projectId}.${scope}`
}

export function loadLocalColumns(projectId: number, scope: string): string[] | null {
  if (!projectId) return null
  try {
    const raw = localStorage.getItem(columnsStorageKey(projectId, scope))
    const parsed: unknown = raw ? JSON.parse(raw) : null
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : null
  } catch {
    return null
  }
}

export function saveLocalColumns(projectId: number, scope: string, columns: string[]): void {
  if (!projectId) return
  try {
    localStorage.setItem(columnsStorageKey(projectId, scope), JSON.stringify(columns))
  } catch {
    // 隐私模式写不进：本机偏好丢失，不影响页面
  }
}

export interface ViewsApiLike {
  list: (projectId: number, scope: string) => Promise<SavedView[]>
  create: (projectId: number, body: SavedViewInput) => Promise<SavedView>
  update: (projectId: number, id: number, body: SavedViewInput) => Promise<SavedView>
  remove: (projectId: number, id: number) => Promise<void>
}

export type SaveViewResult =
  | { ok: true; view: SavedView }
  | { ok: false; reason: 'empty' | 'conflict' | 'failed'; view?: SavedView; error?: unknown }

function isConflict(error: unknown): boolean {
  return error instanceof ApiError
    && (error.code === 409 || error.codeName === 'CONFLICT' || error.codeName === 'DUPLICATE')
}

export function useSavedViews(
  projectId: () => number,
  scope = 'case',
  api: ViewsApiLike = viewsApi,
) {
  const views = ref<SavedView[]>([])
  const loading = ref(false)
  const error = ref<unknown>(null)
  const defaultView = computed(() => views.value.find((view) => view.isDefault))

  async function load(): Promise<SavedView[]> {
    const id = projectId()
    if (!id) {
      views.value = []
      return views.value
    }
    loading.value = true
    error.value = null
    try {
      views.value = await api.list(id, scope)
      return views.value
    } catch (e) {
      error.value = e
      views.value = []
      return views.value
    } finally {
      loading.value = false
    }
  }

  /** 应用视图：只回填 payload，不触发额外请求；分页归 1 由页面负责。 */
  function resolve(view: SavedView | null | undefined): CaseViewPayload {
    return normalizeViewPayload(view?.payload)
  }

  function markDefault(id: number): void {
    views.value = views.value.map((view) => ({ ...view, isDefault: view.id === id }))
  }

  function replace(updated: SavedView): void {
    views.value = views.value.map((view) => (view.id === updated.id ? updated : view))
  }

  async function save(
    name: string,
    payload: CaseViewPayload,
    isDefault = false,
  ): Promise<SaveViewResult> {
    const clean = name.trim()
    if (!clean) return { ok: false, reason: 'empty' }

    const duplicate = findDuplicateView(views.value, clean)
    if (duplicate) return { ok: false, reason: 'conflict', view: duplicate }

    const id = projectId()
    if (!id) return { ok: false, reason: 'failed' }

    try {
      const created = await api.create(id, {
        name: clean,
        scope,
        payload,
        isDefault,
      })
      views.value = [...views.value, created]
      if (isDefault) markDefault(created.id)
      return { ok: true, view: created }
    } catch (e) {
      error.value = e
      // 后端也做了唯一校验时，同样按冲突提示，而不是笼统「保存失败」。
      return { ok: false, reason: isConflict(e) ? 'conflict' : 'failed', error: e }
    }
  }

  /** 默认视图具有排他性：本地同步把其他视图的 isDefault 置 false。 */
  async function makeDefault(id: number): Promise<boolean> {
    const view = views.value.find((item) => item.id === id)
    const project = projectId()
    if (!view || !project) return false
    try {
      const updated = await api.update(project, id, {
        name: view.name,
        scope: view.scope,
        payload: normalizeViewPayload(view.payload),
        isDefault: true,
      })
      replace(updated)
      markDefault(id)
      return true
    } catch (e) {
      error.value = e
      return false
    }
  }

  /** 列设置改动写回当前视图 payload（后端只存 JSON）。 */
  async function updatePayload(id: number, payload: CaseViewPayload): Promise<boolean> {
    const view = views.value.find((item) => item.id === id)
    const project = projectId()
    if (!view || !project) return false
    try {
      const updated = await api.update(project, id, {
        name: view.name,
        scope: view.scope,
        payload,
        isDefault: view.isDefault,
      })
      replace(updated)
      return true
    } catch (e) {
      error.value = e
      return false
    }
  }

  async function remove(id: number): Promise<boolean> {
    const project = projectId()
    if (!project) return false
    try {
      await api.remove(project, id)
      views.value = views.value.filter((view) => view.id !== id)
      return true
    } catch (e) {
      error.value = e
      return false
    }
  }

  return {
    views,
    loading,
    error,
    defaultView,
    load,
    resolve,
    save,
    makeDefault,
    updatePayload,
    remove,
  }
}
