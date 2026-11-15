/**
 * W63-09/12/13/16 接线机检：这些是「页面是否真的接上」的断点，
 * 纯函数测试证明不了；读源码断言，任何一处接线被删都会判红。
 */
import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

function source(rel: string): string {
  for (const base of [process.cwd(), join(process.cwd(), '..')]) {
    const full = join(base, rel)
    if (existsSync(full)) return readFileSync(full, 'utf-8')
  }
  throw new Error(`source not found: ${rel}`)
}

const globalView = () => source('frontend/src/views/global/GlobalAssetsView.vue')
const projectView = () => source('frontend/src/views/AssetsView.vue')

describe('采集画像三列两 scope 同源（W63-09）', () => {
  it.each([
    ['全局侧', globalView],
    ['项目侧', projectView],
  ])('%s 都渲染凭证 / 采集 / 状态三列，且都读同一个画像 Map', (_name, read) => {
    const src = read()
    for (const key of ['credential', 'collectProfile', 'profileState']) {
      expect(src).toContain(`key: '${key}'`)
    }
    expect(src).toContain('profilesByDevice.value.get(row.id)')
    expect(src).toContain("'data-testid': `profile-credential-")
    expect(src).toContain("'data-testid': `profile-state-")
  })

  it('两页都在 load() 成功后刷新画像', () => {
    expect(globalView()).toContain('void loadProfiles()')
    expect(projectView()).toContain('void loadProfiles(projectId.value)')
  })
})

describe('两个采集入口与设为默认（W63-13）', () => {
  it('主按钮用绑定凭证（不传 credentialId），未绑定时 disabled + title', () => {
    const src = globalView()
    expect(src).toContain("'data-testid': `direct-collect-")
    expect(src).toContain('disabled: !canCollect(row)')
    expect(src).toContain('title: collectDisabledReason(row)')
    expect(src).toContain("globalAssetApi.collect(row.id, { confirm: true })")
  })

  it('次按钮可指定凭证，勾选「设为默认」写绑定', () => {
    const src = globalView()
    expect(src).toContain("'data-testid': `specify-collect-")
    expect(src).toContain('data-testid="set-as-default"')
    expect(src).toContain('collectProfileApi.bindGlobal')
    expect(src).toContain('setAsDefault.value')
  })

  it('自动填空说明在界面上可见', () => {
    expect(globalView()).toContain('w63.autoBindHint')
  })
})

describe('一致性与安全（W63-12/14/16）', () => {
  it('canCollect 走唯一判定，不再只看绑定', () => {
    const src = globalView()
    expect(src).toContain('profileCanCollect(collectRowOf(row))')
    expect(src).not.toContain('return Boolean(row.managementCredentialId || row.bmcCredentialId)')
  })

  it('删除凭证前显示 referenceCount 影响面', () => {
    expect(globalView()).toContain('w63.deleteImpact')
    expect(globalView()).toContain('row.referenceCount')
  })

  it('详情不再合成 id=0 假凭据', () => {
    const src = source('frontend/src/api/assetDetail.ts')
    expect(src).not.toMatch(/id:\s*0/)
    expect(src).toContain('credentials: []')
  })

  it('采集历史面板接到真实只读端点，不回 specJson', () => {
    expect(globalView()).toContain('CollectHistoryPanel')
    expect(source('frontend/src/api/collectProfiles.ts')).toContain('/assets/collect-history')
    const controller = source(
      'backend/src/main/java/com/qa/autotest/api/asset/DeviceCollectProfileController.java')
    expect(controller).toContain('h.nic_count')
    expect(controller).not.toContain('h.spec_json')
  })
})
