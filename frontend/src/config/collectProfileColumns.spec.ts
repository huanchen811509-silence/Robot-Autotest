import { describe, expect, it } from 'vitest'
import {
  blockedReasonLabelKey,
  canCollect,
  collectBlockLabelKey,
  collectBlockReason,
  credentialLabelKey,
  stateLabelKey,
} from '@/config/collectProfileColumns'
import type { CollectProfile } from '@/api/collectProfiles'

const base: CollectProfile = {
  deviceId: 9, projectId: 1, ownerScope: 'PLATFORM', name: 'gpu-01',
  managementIp: '10.0.0.1', managementCredentialId: 3, bmcCredentialId: null,
  boundCredentialName: 'rd-01', boundCredentialMasked: 'rd***',
  lastVerifiedCredentialId: 3, lastVerifiedAt: null, verifyResult: 'SUCCESS',
  verifyFailureKind: null, lastCollectAt: null, lastCollectStatus: 'OK',
  collectCount: 2, usable: true, profileState: 'READY', blockedReason: null,
}

describe('采集按钮与三列文案（W63-09/14）', () => {
  it('四种组合枚举：只有「有 IP + 有凭证」可点', () => {
    expect(canCollect({ managementIp: '10.0.0.1', managementCredentialId: 3 })).toBe(true)
    expect(canCollect({ managementIp: '10.0.0.1', bmcCredentialId: 5 })).toBe(true)
    expect(canCollect({ managementIp: null, bmcCredentialId: 5 })).toBe(false)
    expect(canCollect({ managementIp: '10.0.0.1' })).toBe(false)
    expect(canCollect({ managementIp: '', managementCredentialId: 3 })).toBe(false)
  })

  it('只绑 BMC 且无 IP → 缺管理 IP，文案与服务端 400 条件一致', () => {
    const reason = collectBlockReason({ managementIp: null, bmcCredentialId: 5 })
    expect(reason).toBe('missingIp')
    expect(collectBlockLabelKey(reason)).toBe('w63.missingIp')
  })

  it('两者都缺 → missingBoth；只有凭证缺 → missingCredential', () => {
    expect(collectBlockReason({})).toBe('missingBoth')
    expect(collectBlockReason({ managementIp: '10.0.0.1' })).toBe('missingCredential')
  })

  it('三态文案与 BLOCKED 原因枚举不互换', () => {
    expect(stateLabelKey(base)).toBe('w63.stateReady')
    expect(stateLabelKey({ ...base, profileState: 'PARTIAL' })).toBe('w63.statePartial')
    expect(stateLabelKey({ ...base, profileState: 'BLOCKED' })).toBe('w63.stateBlocked')
    expect(blockedReasonLabelKey('NO_MANAGEMENT_IP')).toBe('w63.reasonNoIp')
    expect(blockedReasonLabelKey('NO_CREDENTIAL')).toBe('w63.reasonNoCredential')
    expect(blockedReasonLabelKey('CREDENTIAL_REVOKED')).toBe('w63.reasonRevoked')
  })

  it('未关联不是空白，有可点的文案 key', () => {
    expect(credentialLabelKey(undefined)).toBe('w63.unbound')
    expect(credentialLabelKey(base)).toBe('w63.boundCredentialLabel')
  })
})
