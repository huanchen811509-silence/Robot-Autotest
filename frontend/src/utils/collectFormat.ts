/**
 * 采集结果的结构化展示（v5.20.0 / FR-v5.20-05）。
 *
 * 问题：采集页的值列原来对非字符串直接 `JSON.stringify`，于是 CPU 那一行显示
 * `{"cores":16,"model":"13th Gen Intel(R) Core(TM) i5-13500H"}`——把接口原文贴给用户看。
 *
 * 口径：
 * - **按类别给一句人话**（CPU → 「16 核 · 型号」，内存 → 「总量（已用 / 可用）」…）；
 * - **缺值说清是"没有"还是"没采到"**：GPU 是 `null` 且 `reason` 有值时显示原因，
 *   两者都不是就显示 `—`，都不能显示成 `null`；
 * - **原始值不丢**：调用方把原文塞进「原始值」折叠，排障时还要对字段。
 *
 * 这里只做"值 → 一句人话"，不碰 i18n 之外的东西；`t` 由调用方传入，
 * 便于单测里换成直返 key 的假函数（断言结构而不是文案）。
 */
export type TranslateFn = (key: string, params?: Record<string, unknown>) => string

export interface CollectValueLike {
  category: string
  value: unknown
  reason?: string | null
}

/** 把字节数说成人话（1 位小数够用，采集精度本来就只有 GB 级）。 */
function gb(mb: number): string {
  if (!Number.isFinite(mb)) return '—'
  return `${(mb / 1024).toFixed(1)} GB`
}

function num(v: unknown): number | null {
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 值 → 一句人话。
 *
 * 未知类别**不吞掉**：退回 JSON 预览（截断），而不是显示"无法展示"——
 * 至少让人看到里面有什么，再决定要不要展开原始值。
 */
export function collectValueText(item: CollectValueLike, t: TranslateFn): string {
  const { category, value } = item

  if (value === null || value === undefined) {
    return item.reason ? `${t('env.common.notCollected')} · ${item.reason}` : '—'
  }

  switch (category) {
    case 'CPU': {
      const v = value as { cores?: unknown; model?: unknown }
      const cores = num(v.cores)
      const model = typeof v.model === 'string' ? v.model : ''
      if (cores !== null && model) return `${t('env.collect.cpuSummary', { cores, model })}`
      if (cores !== null) return `${cores}`
      return model || '—'
    }
    case 'MEMORY': {
      const v = value as { totalMb?: unknown; usedMb?: unknown; availableMb?: unknown }
      const total = num(v.totalMb)
      if (total === null) return '—'
      const used = num(v.usedMb)
      const avail = num(v.availableMb)
      if (used === null || avail === null) return gb(total)
      return t('env.collect.memorySummary', { total: gb(total), used: gb(used), free: gb(avail) })
    }
    case 'DISK': {
      const v = value as {
        root?: { usePercent?: unknown; size?: unknown }
        disks?: unknown[]
      }
      const pct = v.root?.usePercent ? String(v.root.usePercent) : ''
      const count = Array.isArray(v.disks) ? v.disks.length : 0
      return t('env.collect.diskSummary', { percent: pct || '—', count })
    }
    case 'NIC': {
      const list = Array.isArray(value) ? (value as { state?: string }[]) : []
      const up = list.filter((n) => (n.state ?? '').toUpperCase() === 'UP').length
      return t('env.collect.nicSummary', { total: list.length, up })
    }
    case 'IP': {
      const list = Array.isArray(value) ? (value as { addresses?: unknown[] }[]) : []
      const withAddr = list.filter((n) => Array.isArray(n.addresses) && n.addresses.length > 0).length
      return t('env.collect.ipSummary', { total: list.length, withAddr })
    }
    case 'ROCE': {
      const list = Array.isArray(value) ? value : []
      return t('env.collect.roceSummary', { count: list.length })
    }
    case 'ROUTE': {
      const v = value as { defaultRoute?: unknown; routes?: unknown[] }
      const gw = typeof v.defaultRoute === 'string' ? v.defaultRoute : ''
      const count = Array.isArray(v.routes) ? v.routes.length : 0
      // 默认路由那一长串里真正有用的是 `via x.x.x.x`，取不到就整条给
      const via = /via\s+(\S+)/.exec(gw)?.[1] ?? gw
      return t('env.collect.routeSummary', { gateway: via || '—', count })
    }
    default: {
      if (typeof value === 'string') return value
      const json = JSON.stringify(value)
      return json.length > 120 ? `${json.slice(0, 120)}…` : json
    }
  }
}

/** 原始值文本（折叠区用）。字符串原样，其它 JSON 美化两格。 */
export function collectRawText(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 是否值得给「原始值」折叠：字符串本身已经完整展示，折叠反而是噪音。 */
export function hasRawValue(item: CollectValueLike): boolean {
  const v = item.value
  return v !== null && v !== undefined && typeof v !== 'string'
}
