/**
 * 维护与恢复（FR-v3.11-03）的数据入口：**三个页面全部只读，一个写请求都没有**。
 *
 * - 执行原则：静态原则文案（i18n） + 真实项目/平台设置（GET /system/settings、
 *   GET /projects/{id}/settings），不把策略写死在前端；
 * - 恢复记录：审计日志里 action 以 `case.batch.undo.` 开头的真实撤销/恢复动作；
 *   刻意**不取 detail 字段**——那里面可能有 token 与命令痕迹（v2.2 安全口径）；
 * - 只读找回：全局实体搜索只查 `type=run`（GET /search），不创建 run、不重放执行。
 */
import { auditApi, type AuditEntry } from './audit'
import { settingsApi } from './settings'
import { searchEntities, type SearchHit } from './search'

/** 恢复记录只保留可对外展示的字段；命令/凭据类字段一律不进这个对象。 */
export interface RecoveryRecord {
  id: number
  occurredAt: string
  action: string
  target: string
  operator: string | null
}

export const UNDO_ACTION_PREFIX = 'case.batch.undo.'

export function isRecoveryAction(action: string): boolean {
  return action.startsWith(UNDO_ACTION_PREFIX) || action === 'batch.undo'
}

/** 从真实审计页里挑恢复动作。返回 0 条也是真值，不补造数据。 */
export async function fetchRecoveryRecords(projectId: number, size = 50):
  Promise<{ rows: RecoveryRecord[]; total: number }> {
  const page = await auditApi.page({ projectId, size })
  const rows = page.items.filter((item: AuditEntry) => isRecoveryAction(item.action))
    .map((item: AuditEntry) => ({
      id: item.id,
      occurredAt: item.occurredAt,
      action: item.action.replace(UNDO_ACTION_PREFIX, ''),
      target: [item.resourceType, item.resourceId].filter(Boolean).join(' · ') || '—',
      operator: item.operator,
    }))
  return { rows, total: page.total }
}

/** 执行原则页的真实策略值：项目设置优先，同键以项目级覆盖平台级。 */
export async function fetchPrincipleSettings(projectId: number):
  Promise<{ key: string; value: string; scope: 'project' | 'platform' }[]> {
  const [project, platform] = await Promise.all([
    settingsApi.project(projectId).catch(() => ({} as Record<string, string>)),
    settingsApi.platform().catch(() => ({} as Record<string, string>)),
  ])
  const keys = new Set([...Object.keys(platform), ...Object.keys(project)])
  return [...keys].sort().map((key) => ({
    key,
    value: project[key] ?? platform[key] ?? '',
    scope: key in project ? ('project' as const) : ('platform' as const),
  }))
}

/** 只读找回：只发 GET /search，且只取 run 类型。 */
export function findHistoricalRuns(projectId: number, keyword: string): Promise<SearchHit[]> {
  return searchEntities({
    q: keyword,
    projectId,
    scope: 'current',
    types: ['run'],
    limit: 20,
  }).then((response) => response.items.filter((hit) => hit.type === 'run'))
}
