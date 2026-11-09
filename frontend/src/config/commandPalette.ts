/**
 * 命令面板的纯逻辑（NAV-04 / FR-v2.0-09）。
 *
 * 抽成纯函数是为了能脱离 DOM 单测：中英文搜索、最近 10 条、收藏、折叠持久化
 * 都是「规则」，不该埋在组件里靠点击验证。localStorage 不可用时（隐私模式 / SSR）
 * 一律降级为内存态，不让整个界面挂掉。
 */

export interface PaletteEntry {
  key: string
  label: string
  /** 英文标签：中文界面也要能用英文搜到，反之亦然 */
  alias: string
  path: string
  query?: Record<string, string>
  /** 能力过滤用；空 = 所有人可见 */
  capability?: string
}

const RECENT_KEY = 'robot.palette.recent'
const FAVORITE_KEY = 'robot.palette.favorites'
const COLLAPSED_KEY = 'robot.nav.expanded'
const SCOPE_KEY = 'robot.palette.scope'
export const RECENT_LIMIT = 10

/** 实体搜索范围：只看当前项目，还是全租户都搜。 */
export type PaletteScope = 'current' | 'all'

/**
 * 作用域持久化：记住上次选择；从没选过时默认「当前项目」。
 * 旧值不是合法枚举时也回落到 current，避免前端拿到脏数据。
 */
export function loadPaletteScope(): PaletteScope {
  try {
    return localStorage.getItem(SCOPE_KEY) === 'all' ? 'all' : 'current'
  } catch {
    return 'current'
  }
}

export function savePaletteScope(scope: PaletteScope): void {
  try {
    localStorage.setItem(SCOPE_KEY, scope)
  } catch {
    // 隐私模式写不进：本次会话仍可用内存态
  }
}

function readList(key: string): string[] {
  try {
    const raw = localStorage.getItem(key)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

function writeList(key: string, values: string[]): void {
  try {
    localStorage.setItem(key, JSON.stringify(values))
  } catch {
    // 隐私模式写不进：本次会话仍可用内存态，不抛
  }
}

/** 大小写不敏感的子串匹配；中英文标签与路径都参与，空关键词返回按能力过滤后的全量。 */
export function searchEntries(entries: PaletteEntry[], keyword: string): PaletteEntry[] {
  const term = keyword.trim().toLowerCase()
  if (!term) return entries
  return entries.filter((entry) =>
    [entry.label, entry.alias, entry.path, entry.key]
      .some((field) => field.toLowerCase().includes(term)),
  )
}

export function loadRecent(): string[] {
  return readList(RECENT_KEY).slice(0, RECENT_LIMIT)
}

/** 最近访问去重保序，最多 10 条；新访问插到最前。key 仍是字符串，旧数据可直接读。 */
export function pushRecent(key: string, current: string[] = loadRecent()): string[] {
  const next = [key, ...current.filter((item) => item !== key)].slice(0, RECENT_LIMIT)
  writeList(RECENT_KEY, next)
  return next
}

export function loadFavorites(): string[] {
  return readList(FAVORITE_KEY)
}

export function toggleFavorite(key: string, current: string[] = loadFavorites()): string[] {
  const next = current.includes(key)
    ? current.filter((item) => item !== key)
    : [...current, key]
  writeList(FAVORITE_KEY, next)
  return next
}

/** 菜单折叠态：与 App.vue 共用同一个 key，刷新后保留。 */
export function loadExpanded(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : {}
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, boolean>)
      : {}
  } catch {
    return {}
  }
}

export function saveExpanded(values: Record<string, boolean>): void {
  try {
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify(values))
  } catch {
    // 同上：写不进不影响本次会话
  }
}
