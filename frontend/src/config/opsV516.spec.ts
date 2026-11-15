import { describe, expect, it } from 'vitest'
import { GLOBAL_NAVIGATION, NAVIGATION, isGroup } from './navigation'
import { buildTaskBody, opsPaths } from '@/api/opsV516'

/**
 * v5.16.0 运维控制台的契约与边界（W66-04 / W66-16 / D-7）。
 *
 * 三条不能回退的口径：
 * ① 运维是**项目树**独立一级（D-7 已拍板），四页 + 存量同步共 5 叶；
 * ② 全局侧栏仍是 6 组 —— D-7 只动项目树，不动全局；
 * ③ 任务创建默认 dry-run —— 前端不传 dryRun 也不能变成真写。
 */
describe('v5.16.0 运维控制台契约', () => {
  it('运维是项目树独立一级（D-7），四页 + 存量同步共 5 叶', () => {
    const opsGroup = NAVIGATION.filter(isGroup).find((group) => group.key === 'ops')
    expect(opsGroup).toBeTruthy()
    const paths = (opsGroup?.children ?? []).map((child) => ('path' in child ? child.path : ''))
    expect(paths).toContain('/ops/health')
    expect(paths).toContain('/ops/backup')
    expect(paths).toContain('/ops/archive')
    expect(paths).toContain('/ops/upgrade')
    expect(paths).toContain('/sync')
    expect(opsGroup?.children).toHaveLength(5)
  })

  it('全局侧栏不受影响：仍 6 组，且原 maintenance 组已不存在', () => {
    expect(GLOBAL_NAVIGATION).toHaveLength(6)
    expect(NAVIGATION.map((node) => node.key)).not.toContain('maintenance')
  })

  it('端点前缀统一 /ops 且带 projectId', () => {
    expect(opsPaths.health(95)).toBe('/ops/health?projectId=95')
    expect(opsPaths.backups(95)).toBe('/ops/backups?projectId=95')
    expect(opsPaths.task(95, 3)).toBe('/ops/tasks/3?projectId=95')
    expect(opsPaths.sweep(95)).toBe('/ops/tasks/sweep?projectId=95')
    expect(opsPaths.restoreDrill(95)).toBe('/ops/restore-drill?projectId=95')
    expect(opsPaths.upgradePrecheck(95)).toBe('/ops/upgrade/precheck?projectId=95')
    expect(opsPaths.guardrails(95)).toBe('/ops/guardrails?projectId=95')
  })

  it('任务创建默认 dryRun=true，显式 false 才透传', () => {
    expect(buildTaskBody({ taskType: 'BACKUP' }))
      .toEqual({ taskType: 'BACKUP', dryRun: true })
    expect(buildTaskBody({ taskType: 'ARCHIVE', dryRun: false }).dryRun).toBe(false)
  })
})
