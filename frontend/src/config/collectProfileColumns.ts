/**
 * 采集画像展示口径（v5.13.0 / W63-09、14）。
 *
 * 唯一真值：按钮能不能点、为什么不能点，必须与服务端 collect() 的 400 条件一一对应。
 * 字符串一律是 i18n key 或英文码，不在 .ts 里写中文（check_i18n 会判红）。
 */
import type { CollectProfile } from '@/api/collectProfiles'

export interface CollectableRow {
  managementIp?: string | null
  managementCredentialId?: number | null
  bmcCredentialId?: number | null
}

export type CollectBlockReason = 'ok' | 'missingIp' | 'missingCredential' | 'missingBoth'

export function hasManagementIp(row: CollectableRow): boolean {
  return Boolean((row.managementIp ?? '').trim())
}

export function hasCredential(row: CollectableRow): boolean {
  return Boolean(row.managementCredentialId || row.bmcCredentialId)
}

/** 前端判定对齐后端：有管理 IP 且有凭证。只绑 BMC 但没 IP 的设备一律禁用。 */
export function canCollect(row: CollectableRow): boolean {
  return hasManagementIp(row) && hasCredential(row)
}

export function collectBlockReason(row: CollectableRow): CollectBlockReason {
  const ip = hasManagementIp(row)
  const credential = hasCredential(row)
  if (ip && credential) return 'ok'
  if (!ip && !credential) return 'missingBoth'
  return ip ? 'missingCredential' : 'missingIp'
}

export function collectBlockLabelKey(reason: CollectBlockReason): string {
  switch (reason) {
    case 'missingIp': return 'w63.missingIp'
    case 'missingCredential': return 'w63.missingCredential'
    case 'missingBoth': return 'w63.missingBoth'
    default: return ''
  }
}

export function stateLabelKey(profile: CollectProfile | undefined): string {
  if (!profile) return 'w63.unbound'
  switch (profile.profileState) {
    case 'READY': return 'w63.stateReady'
    case 'PARTIAL': return 'w63.statePartial'
    default: return 'w63.stateBlocked'
  }
}

export function blockedReasonLabelKey(reason: string | null | undefined): string {
  switch (reason) {
    case 'NO_MANAGEMENT_IP': return 'w63.reasonNoIp'
    case 'NO_CREDENTIAL': return 'w63.reasonNoCredential'
    case 'CREDENTIAL_REVOKED': return 'w63.reasonRevoked'
    default: return ''
  }
}

/** 「凭证」列：管理面优先，其次 BMC；都没有就是「未关联」（不显示空白）。 */
export function credentialLabelKey(profile: CollectProfile | undefined): string {
  return profile && (profile.managementCredentialId || profile.bmcCredentialId)
    ? 'w63.boundCredentialLabel'
    : 'w63.unbound'
}

/** 「采集」列：已采集次数 + 最近结果，不把空白当「没采过」。 */
export function collectSummary(profile: CollectProfile | undefined): { count: number; status: string | null } {
  return { count: profile?.collectCount ?? 0, status: profile?.lastCollectStatus ?? null }
}
