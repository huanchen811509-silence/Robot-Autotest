/**
 * EHT P2（EHT-10..12）：八张页面的真实数据源。
 *
 * 每个方法都指向一个已存在的后端端点；**没有 mock 数据、没有本地兜底数组**。
 * 端点真实性与响应结构取证见 docs/evidence/v5.5.0/eht/raw/eht-endpoint-probe.json。
 */
import { request } from './http'

const base = (projectId: number) => `/projects/${projectId}`

export type EhtRecord = Record<string, unknown>

export interface EhtCasePage {
  items: EhtRecord[]
  page: number
  size: number
  total: number
}

/** 分页查询的唯一实现，供 cases / casePriorities 共用，避免两处各拼一遍查询串。 */
function casesPage(projectId: number, params: Record<string, string | number> = {}): Promise<EhtCasePage> {
  const query = new URLSearchParams()
  Object.entries({ page: 1, size: 20, ...params }).forEach(([k, v]) => query.set(k, String(v)))
  return request<EhtCasePage>(`${base(projectId)}/cases?${query.toString()}`)
}

/** 优先级字典按 projectId 缓存：只在首次进入页面时翻页汇总一次。 */
const priorityCache = new Map<number, string[]>()

export const ehtViewsApi = {
  /** 时长基线（六页 1）。 */
  durationBaselines(projectId: number): Promise<EhtRecord[]> {
    return request<EhtRecord[]>(`${base(projectId)}/duration-baselines`)
  },
  /** 处置闭环台账（六页 2）。 */
  dispositionLedger(projectId: number): Promise<EhtRecord> {
    return request<EhtRecord>(`${base(projectId)}/dispositions/ledger`)
  },
  /** 环境雷达（六页 3）。 */
  environmentRadar(projectId: number, days = 7): Promise<EhtRecord> {
    return request<EhtRecord>(`${base(projectId)}/environment-radar?days=${days}`)
  },
  /** 双内核统一视图（六页 4）。 */
  kernelUnifiedView(projectId: number, days = 7): Promise<EhtRecord> {
    return request<EhtRecord>(`${base(projectId)}/kernel-view?days=${days}`)
  },
  /** 手工执行（六页 5）：runId 为必填，空库传 0 返回空列表。 */
  manualExecutions(projectId: number, runId = 0): Promise<EhtRecord[]> {
    return request<EhtRecord[]>(`${base(projectId)}/manual-executions?runId=${runId}`)
  },
  /** 手工/自动分离统计（六页 6）。 */
  resultSplit(projectId: number, days = 30): Promise<EhtRecord> {
    return request<EhtRecord>(`${base(projectId)}/result-split?days=${days}`)
  },
  /** 失败归因章节：执行进度 / 错误原因的数据源。 */
  failureChapter(projectId: number, runId = 0): Promise<EhtRecord> {
    return request<EhtRecord>(
      `${base(projectId)}/runs/${encodeURIComponent(String(runId))}/failure-breakdown/chapter`)
  },
  /** 用例管理页：分页用例 + 筛选。 */
  cases(projectId: number, params: Record<string, string | number> = {}): Promise<EhtCasePage> {
    return casesPage(projectId, params)
  },
  /**
   * 优先级筛选字典：逐页汇总 /cases 真实数据里的 distinct priority。
   * 不写死任何业务枚举字面——字典完全由库内数据决定，中文界面既不硬编码中文，
   * 也不会把业务筛选值误译成另一种语言。单页上限 100（PageQuery.MAX_SIZE），
   * 按 total 翻完即可覆盖全量；结果按 projectId 缓存。
   */
  async casePriorities(projectId: number): Promise<string[]> {
    const cached = priorityCache.get(projectId)
    if (cached) {
      return cached
    }
    const values = new Set<string>()
    const collect = (items: EhtRecord[]): void => {
      for (const row of items ?? []) {
        const value = String(row.priority ?? '').trim()
        if (value) {
          values.add(value)
        }
      }
    }
    const first = await casesPage(projectId, { page: 1, size: 100 })
    collect(first.items ?? [])
    const pageCount = Math.ceil((first.total ?? 0) / 100)
    for (let page = 2; page <= pageCount; page += 1) {
      const next = await casesPage(projectId, { page, size: 100 })
      collect(next.items ?? [])
    }
    const result = [...values].sort()
    priorityCache.set(projectId, result)
    return result
  },
  /** 执行准入（便捷操作预检）。 */
  admission(projectId: number): Promise<EhtRecord> {
    return request<EhtRecord>(`${base(projectId)}/execution-coordination/admission`)
  },
}
