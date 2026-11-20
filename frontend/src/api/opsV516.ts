/**
 * v5.16.0 operations console contract (read-only endpoints).
 *
 * No new top-level menu: the console reuses the existing /admin/ops entry.
 * All writes are explicit and default to dry-run; the backend never runs a real upgrade.
 */
import { request, toQuery } from './http'

const OPS = '/ops'

export const opsPaths = {
  health: (projectId: number) => `${OPS}/health${toQuery({ projectId })}`,
  backups: (projectId: number) => `${OPS}/backups${toQuery({ projectId })}`,
  /** v5.33.0 / FR-v5.33-06：删除单份备份（后端校验文件名白名单）。 */
  deleteBackup: (projectId: number, name: string) =>
    `${OPS}/backups/${encodeURIComponent(name)}${toQuery({ projectId })}`,
  retention: (projectId: number) => `${OPS}/backups/retention${toQuery({ projectId })}`,
  tasks: (projectId: number, limit = 20) => `${OPS}/tasks${toQuery({ projectId, limit })}`,
  task: (projectId: number, id: number) => `${OPS}/tasks/${id}${toQuery({ projectId })}`,
  createTask: (projectId: number) => `${OPS}/tasks${toQuery({ projectId })}`,
  abortTask: (projectId: number, id: number) => `${OPS}/tasks/${id}/abort${toQuery({ projectId })}`,
  sweep: (projectId: number) => `${OPS}/tasks/sweep${toQuery({ projectId })}`,
  restoreDrill: (projectId: number) => `${OPS}/restore-drill${toQuery({ projectId })}`,
  upgradePrecheck: (projectId: number) => `${OPS}/upgrade/precheck${toQuery({ projectId })}`,
  guardrails: (projectId: number) => `${OPS}/guardrails${toQuery({ projectId })}`,
}

export interface OpsTask {
  id: number
  projectId: number
  taskType: string
  phase: string
  progress: number
  status: string
  logText: string | null
  resultJson: string | null
}

export interface CreateTaskResult {
  dryRun: boolean
  taskId: number
  status: string
  plan: string[]
}

export interface TaskPayload {
  taskType: string
  paramsJson?: string
  operatorId?: number | null
  /** Defaults to true on the backend; keep it explicit on the client too. */
  dryRun?: boolean
}

export function buildTaskBody(payload: TaskPayload): TaskPayload & { dryRun: boolean } {
  return { ...payload, dryRun: payload.dryRun ?? true }
}

export const opsApi = {
  health(projectId: number): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(opsPaths.health(projectId))
  },
  backups(projectId: number): Promise<Record<string, unknown>[]> {
    return request<Record<string, unknown>[]>(opsPaths.backups(projectId))
  },
  /** 删除单份备份：后端只接受 backups 目录内的 `<name>.gz`，其余一律 400。 */
  deleteBackup(projectId: number, name: string): Promise<string[]> {
    return request<string[]>(opsPaths.deleteBackup(projectId, name), { method: 'DELETE' })
  },
  retention(projectId: number): Promise<{
    retainCount: number
    total: number
    overRetention: string[]
  }> {
    return request(opsPaths.retention(projectId))
  },
  tasks(projectId: number, limit = 20): Promise<OpsTask[]> {
    return request<OpsTask[]>(opsPaths.tasks(projectId, limit))
  },
  task(projectId: number, id: number): Promise<OpsTask> {
    return request<OpsTask>(opsPaths.task(projectId, id))
  },
  createTask(projectId: number, payload: TaskPayload): Promise<CreateTaskResult> {
    return request<CreateTaskResult>(opsPaths.createTask(projectId), {
      method: 'POST',
      body: JSON.stringify(buildTaskBody(payload)),
    })
  },
  abortTask(projectId: number, id: number): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(opsPaths.abortTask(projectId, id), {
      method: 'POST',
      body: '{}',
    })
  },
  sweep(projectId: number): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(opsPaths.sweep(projectId), {
      method: 'POST',
      body: '{}',
    })
  },
  restoreDrill(projectId: number, targetDb: string, dryRun = true): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(opsPaths.restoreDrill(projectId), {
      method: 'POST',
      body: JSON.stringify({ targetDb, dryRun }),
    })
  },
  upgradePrecheck(projectId: number): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(opsPaths.upgradePrecheck(projectId))
  },
  guardrails(projectId: number): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(opsPaths.guardrails(projectId))
  },
}


// ---------------------------------------------------------------- 只读事实类型
// v5.16.0 / FR-v5.16-04~07：六类健康指标与 L1 事实全部真实取数，
// 取不到时后端给 null 并带 hint —— 前端渲染空态，不用 0 冒充「正常」。

export interface ServiceHealth {
  backend: string
  frontend: string
  database: string
  jdbcUrl: string | null
  uptimeSeconds: number | null
}

export interface DatabaseHealth {
  up: boolean
  sizeMb: number | null
  connections: number | null
  tables: number | null
  hint: string | null
}

export interface DiskHealth {
  totalBytes: number
  usableBytes: number
  usablePercent: number
  level: string
  hint: string | null
}

export interface RecentCollect {
  result: string | null
  at: string | null
  hint: string | null
}

export interface BackupItem {
  name: string
  sizeBytes: number
  modifiedAt: string
  sha256State: string
  verifyState: string
}

export interface HealthOverview {
  service: ServiceHealth
  database: DatabaseHealth
  disk: DiskHealth
  recentCollect: RecentCollect
  recentBackup: BackupItem | null
  lastDoctor: string | null
  version: string
  backupFiles: number
  recentTasks: number
}

export interface RetainedRelease {
  name: string
  modifiedAt: string
}

export interface FactsView {
  version: string
  manifestVersion: string | null
  versionMatch: boolean
  lastUpgradeAt: string | null
  lastUpgradeResult: string | null
  installRoot: string
  dataDir: string | null
  disk: DiskHealth
  releases: RetainedRelease[]
  doctorCommand: string
  doctorConclusion: string | null
}

export interface TableRows {
  table: string
  sourceRows: number | null
  targetRows: number | null
  delta: number
}

export interface CompareView {
  sourceDb: string
  targetDb: string
  consistent: boolean
  comparedTables: number
  mismatched: TableRows[]
  at: string
}

export interface PrecheckIssue {
  kind: string
  level: string
  detail: string
}

export interface PrecheckView {
  packagePresent: boolean
  passed: boolean
  issues: PrecheckIssue[]
  command: string
  note: string
}

export interface AuditRow {
  taskId: number
  taskType: string
  status: string
  operator: string
  createdAt: string | null
  durationMs: number | null
  result: string | null
}

export const opsConsole = {
  healthOverview(projectId: number): Promise<HealthOverview> {
    return request<HealthOverview>(opsPaths.health(projectId))
  },
  facts(projectId: number): Promise<FactsView> {
    return request<FactsView>(`${OPS}/facts${toQuery({ projectId })}`)
  },
  audit(projectId: number, limit = 20): Promise<AuditRow[]> {
    return request<AuditRow[]>(`${OPS}/audit${toQuery({ projectId, limit })}`)
  },
  compare(projectId: number, sourceDb: string, targetDb: string): Promise<CompareView> {
    return request<CompareView>(`${OPS}/backup/compare${toQuery({ projectId })}`, {
      method: 'POST',
      body: JSON.stringify({ sourceDb, targetDb }),
    })
  },
  precheck(projectId: number, packagePath = ''): Promise<PrecheckView> {
    return request<PrecheckView>(`${OPS}/upgrade/precheck${toQuery({ projectId, packagePath })}`)
  },
}
