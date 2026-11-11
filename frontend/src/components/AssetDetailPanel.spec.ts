/**
 * AssetDetailPanel 三分区回归（v4.9.2 / OPT-04 / FR-49-13~16）。
 *
 * 判据：三分区覆盖「当前测试环境」12 字段、缺失显式标「未采集」（静默空白 0）、
 * 四层证据齐全率 100%、凭据只显掩码且逐条 keyMasked 前缀、派生字段由
 * device_port / spec_json 计算、采集必须「凭据 + 二次确认」、失败保留上次成功快照。
 *
 * API 用 vi.mock 隔离；测试用 i18n 以 flatJson 注入证据 JSON 的 zh-CN 取值。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import type { AssetDetailResponse, AssetDetailView } from '@/api/assetDetail'
import AssetDetailPanel from '@/components/AssetDetailPanel.vue'

const detailFn = vi.hoisted(() => vi.fn())
const listCredentials = vi.hoisted(() => vi.fn())
const collect = vi.hoisted(() => vi.fn())
vi.mock('@/api/assetDetail', () => ({
  assetDetailApi: { detail: detailFn, listCredentials, collect },
}))

const ZH: Record<string, string> = {
  'assetDetail.notCollected': '未采集',
  'assetDetail.rawOutputMissing': '未采集原始输出',
  'assetDetail.unassignedNic': '未归属网卡',
  'assetDetail.loading': '正在加载资产详情…',
  'assetDetail.loadFailed': '资产详情加载失败',
  'assetDetail.section.hardware': '硬件',
  'assetDetail.section.software': '软件与带外',
  'assetDetail.section.status': '状态',
  'assetDetail.field.hostname': '主机名',
  'assetDetail.field.managementIp': '管理IP',
  'assetDetail.field.role': '角色',
  'assetDetail.field.model': '型号',
  'assetDetail.field.nicCount': '网口数（派生）',
  'assetDetail.field.nicSummary': '网卡配置汇总（派生）',
  'assetDetail.field.os': '操作系统',
  'assetDetail.field.kernel': '内核',
  'assetDetail.field.bmc': 'BMC地址·映射地址·账户',
  'assetDetail.field.login': '登录方式·端口',
  'assetDetail.field.credentialRef': '账户·权限·凭据引用',
  'assetDetail.field.vfVm': 'VF测试虚拟机',
  'assetDetail.state.NOT_COLLECTED': '未采集',
  'assetDetail.state.COLLECTING': '采集中',
  'assetDetail.state.PARTIAL': 'PARTIAL',
  'assetDetail.state.FAILED': 'FAILED',
  'assetDetail.state.OK': 'OK',
  'assetDetail.collectedAt': '采集时间',
  'assetDetail.lastSnapshot': '采集失败，仍展示上次成功快照（{at}）',
  'assetDetail.credentialsTitle': '凭据（仅掩码）',
  'assetDetail.evidenceTitle': '四层证据链',
  'assetDetail.evidence.collectedAt': 'collectedAt',
  'assetDetail.evidence.command': '命令原文',
  'assetDetail.evidence.rawOutput': '原始输出（折叠区）',
  'assetDetail.evidence.value': '结构化值',
  'assetDetail.collectOpen': '触发采集',
  'assetDetail.collectTitle': '触发主机清单采集',
  'assetDetail.collectHint': '采集会在目标机执行只读命令；必须选择凭据并二次确认。',
  'assetDetail.collectCredential': '凭据',
  'assetDetail.collectConfirmAgain': '我已确认，允许触发采集（二次确认）',
  'assetDetail.collectSubmit': '确认采集',
  'assetDetail.collectDone': '采集完成',
  'assetDetail.collectFailed': '采集失败',
  'assetDetail.collectNoCredential': '公共凭据库为空，请先维护凭据再采集。',
  'assetDetail.close': '关闭',
}

const testI18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  flatJson: true,
  messages: { 'zh-CN': { ...ZH }, 'en-US': { ...ZH } },
})

const view: AssetDetailView = {
  deviceId: 11,
  source: 'global',
  name: 'host-a',
  hostname: 'host-a',
  managementIp: '10.0.0.1',
  role: 'DUT',
  model: 'XYZ-200G',
  os: 'Ubuntu 24.04',
  kernel: '6.8.0',
  bmc: '10.0.0.9',
  login: 'ssh:22',
  credentialRef: 'cred-1',
  vfVm: 'vm-01',
  lifecycleStatus: 'active',
  nicCount: 2,
  nicSummary: 'eth0 ×2',
  collectState: 'OK',
  status: 'OK',
  collectedAt: '2026-11-11T09:00:00',
  specJson: JSON.stringify({
    collectedAt: '2026-11-11T09:00:00',
    items: [{ category: 'NIC', label: '网口', command: 'lspci' }],
  }),
  devicePorts: [
    { portName: 'eth0', nicName: 'eth0', speedGbps: 200 },
    { portName: 'eth1', nicName: 'eth0', speedGbps: 200 },
  ],
  credentials: [{ id: 1, name: 'admin', keyMasked: '****' }],
  evidence: [{
    category: 'NIC',
    label: '网口信息',
    command: 'lspci | grep Eth',
    collectedAt: '2026-11-11T09:00:00',
    rawOutput: 'eth0: 200G',
    value: { speed: 200 },
    reason: null,
  }],
}

let app: App | null = null
let host: HTMLElement | null = null

async function flush(): Promise<void> {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

async function mountPanel(data: AssetDetailView = view): Promise<HTMLElement> {
  detailFn.mockResolvedValue(data)
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(AssetDetailPanel, { deviceId: 11, source: 'global' as const })
  app.use(testI18n)
  app.mount(root)
  await flush()
  return root
}

function pick(root: HTMLElement, testId: string): HTMLElement {
  return root.querySelector<HTMLElement>(`[data-testid="${testId}"]`)!
}

function valueOf(root: HTMLElement, field: string): string {
  return pick(root, `asset-field-${field}`).querySelector('.asset-detail-value')!.textContent!.trim()
}

beforeEach(() => {
  detailFn.mockReset()
  listCredentials.mockReset()
  collect.mockReset()
  listCredentials.mockResolvedValue([{ id: 1, name: 'admin', keyMasked: '****' }])
  collect.mockResolvedValue({
    deviceId: 11,
    status: 'OK',
    collectedAt: '2026-11-11T10:00:00',
    nicCount: 2,
    rocePorts: 1,
    roceMaxGbps: 200,
  })
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AssetDetailPanel 三分区 12 字段（FR-49-13）', () => {
  it('硬件 / 软件与带外 / 状态三区渲染 12 个字段，静默空白 0', async () => {
    const root = await mountPanel()

    expect(detailFn).toHaveBeenCalledWith(11, 'global')
    expect(root.querySelectorAll('[data-testid^="asset-section-"]')).toHaveLength(3)
    const fields = root.querySelectorAll('[data-testid^="asset-field-"]')
    expect(fields).toHaveLength(12)

    const cells = [...root.querySelectorAll('.asset-detail-value')]
    expect(cells.filter((cell) => cell.textContent!.trim() === '')).toHaveLength(0)
    expect(valueOf(root, 'hostname')).toBe('host-a')
    expect(valueOf(root, 'managementIp')).toBe('10.0.0.1')
    expect(valueOf(root, 'vfVm')).toBe('vm-01')
  })

  it('字段缺失显式标「未采集」并带 is-missing，而不是留空', async () => {
    const root = await mountPanel({ ...view, hostname: null, bmc: null, os: null })

    for (const key of ['hostname', 'bmc', 'os']) {
      const cell = pick(root, `asset-field-${key}`).querySelector('.asset-detail-value')!
      expect(cell.textContent!.trim()).toBe('未采集')
      expect(cell.classList.contains('is-missing')).toBe(true)
    }
    const blanks = [...root.querySelectorAll('.asset-detail-value')]
      .filter((cell) => cell.textContent!.trim() === '')
    expect(blanks).toHaveLength(0)
  })

  it('四态徽章默认显示 OK，采集时间可见', async () => {
    const root = await mountPanel()
    expect(pick(root, 'collect-state').textContent!.trim()).toBe('OK')
    expect(pick(root, 'collect-collected-at').textContent).toContain('2026-11-11T09:00:00')
  })
})

describe('AssetDetailPanel 四层证据齐全（FR-49-14）', () => {
  it('每项含 collectedAt / 命令原文 / 原始输出折叠区 / 结构化值', async () => {
    const root = await mountPanel()

    expect(pick(root, 'evidence-section')).toBeTruthy()
    const item = pick(root, 'evidence-item-0')
    for (const layer of ['collectedAt', 'command', 'raw', 'value']) {
      const node = pick(root, `evidence-${layer}`)
      expect(node).toBeTruthy()
      expect(node.textContent!.trim()).not.toBe('')
    }
    expect(pick(root, 'evidence-command').textContent).toContain('lspci | grep Eth')
    expect(pick(root, 'evidence-raw').querySelector('pre')!.textContent).toContain('eth0: 200G')
    expect(item.querySelector('details')!.getAttribute('data-testid')).toBe('evidence-raw')
  })

  it('无采集数据时也渲染占位的四层，齐全率 100%', async () => {
    const root = await mountPanel({
      ...view,
      evidence: [],
      specJson: null,
      devicePorts: [],
      collectState: 'NOT_COLLECTED',
      collectedAt: null,
    })

    expect(pick(root, 'collect-state').textContent!.trim()).toBe('未采集')
    for (const layer of ['collectedAt', 'command', 'raw', 'value']) {
      expect(pick(root, `evidence-${layer}`).textContent!.trim()).not.toBe('')
    }
    expect(pick(root, 'evidence-item-0')).toBeTruthy()
  })
})

describe('AssetDetailPanel 凭据掩码与派生字段（FR-49-15/16）', () => {
  it('凭据只显示掩码名，逐条带 keyMasked 前缀', async () => {
    const root = await mountPanel()

    const badge = pick(root, 'asset-credentials').querySelector<HTMLElement>('[data-key-masked]')!
    expect(badge.getAttribute('data-key-masked')).toBe('keyMaskedCredential')
    expect(badge.textContent).toContain('keyMasked-admin')
    expect(badge.textContent).toContain('****')
    expect(root.textContent).not.toContain('plain-password')
  })

  it('网口数 / 网卡配置汇总由 device_port 派生，不读新增库列', async () => {
    const root = await mountPanel({ ...view, nicCount: null, nicSummary: null })

    expect(valueOf(root, 'nicCount')).toBe('2')
    expect(valueOf(root, 'nicSummary')).toBe('eth0 ×2')
  })
})

describe('assetDetailApi 后端嵌套 DTO 适配（FR-49-13/14）', () => {
  it('toAssetDetailView 把三分区 / 四层证据 / 掩码凭据映射成扁平只读契约', async () => {
    const actual = await vi.importActual<typeof import('@/api/assetDetail')>('@/api/assetDetail')
    const raw: AssetDetailResponse = {
      deviceId: 11,
      projectId: 3,
      name: 'host-a',
      hardware: {
        deviceType: 'XYZ-200G',
        ports: [
          { portName: 'eth0', portType: 'RoCE', macAddress: 'aa', speedGbps: 200 },
          { portName: 'eth1', portType: 'RoCE', macAddress: 'bb', speedGbps: 200 },
        ],
        specCategories: ['NIC'],
      },
      softwareAndOutOfBand: {
        operatingSystem: 'Ubuntu 24.04',
        kernelVersion: '6.8.0',
        bmcAddress: '10.0.0.9',
        loginMode: 'ssh:22',
      },
      status: {
        deviceStatus: 'active',
        assetStatus: 'in_use',
        specStatus: 'OK',
        collectedAt: '2026-11-11T09:00:00',
        specHash: 'sha-1',
      },
      evidence: [{
        category: 'NIC',
        label: '网口',
        collectedAt: '2026-11-11T09:00:00',
        command: 'lspci',
        rawOutput: '{"speed":200}',
        structuredValue: { speed: 200 },
        reason: null,
      }],
      derived: { nicCount: 2, portCount: 2, rocePorts: 1, roceMaxGbps: 200, evidenceItemCount: 1 },
      credential: { keyMasked: 'ad***' },
    }

    const mapped = actual.toAssetDetailView(raw, 'global')
    expect(mapped.hostname).toBe('host-a')
    expect(mapped.model).toBe('XYZ-200G')
    expect(mapped.os).toBe('Ubuntu 24.04')
    expect(mapped.kernel).toBe('6.8.0')
    expect(mapped.bmc).toBe('10.0.0.9')
    expect(mapped.login).toBe('ssh:22')
    expect(mapped.collectState).toBe('OK')
    expect(mapped.collectedAt).toBe('2026-11-11T09:00:00')
    expect(mapped.nicCount).toBe(2)
    expect(mapped.devicePorts).toHaveLength(2)
    expect(mapped.credentials[0].keyMasked).toBe('ad***')
    expect(mapped.evidence[0].command).toBe('lspci')
    expect(mapped.evidence[0].rawOutput).toBe('{"speed":200}')
    // 后端未提供的参考表列保持 null，由 UI 显式标「未采集」，不伪造事实
    expect(mapped.managementIp).toBeNull()
    expect(mapped.role).toBeNull()
    expect(mapped.vfVm).toBeNull()
  })

  it('无采集数据时 collectState 回落为 NOT_COLLECTED 且凭据/证据为空', async () => {
    const actual = await vi.importActual<typeof import('@/api/assetDetail')>('@/api/assetDetail')
    const raw: AssetDetailResponse = {
      deviceId: 12,
      projectId: 3,
      name: 'host-b',
      hardware: null,
      softwareAndOutOfBand: null,
      status: null,
      evidence: [],
      derived: null,
      credential: null,
    }
    const mapped = actual.toAssetDetailView(raw, 'project')
    expect(mapped.collectState).toBe('NOT_COLLECTED')
    expect(mapped.evidence).toHaveLength(0)
    expect(mapped.credentials).toHaveLength(0)
    expect(mapped.devicePorts).toHaveLength(0)
  })
})

describe('AssetDetailPanel 采集四态与二次确认（FR-49-15）', () => {
  it('触发采集必须选凭据 + 二次确认，否则提交禁用', async () => {
    const root = await mountPanel()
    pick(root, 'collect-open').click()
    await flush()

    expect(pick(root, 'collect-modal')).toBeTruthy()
    expect((pick(root, 'collect-submit') as HTMLButtonElement).disabled).toBe(true)

    pick(root, 'collect-credential-1').click()
    await nextTick()
    expect((pick(root, 'collect-submit') as HTMLButtonElement).disabled).toBe(true)

    const confirm = pick(root, 'collect-confirm') as HTMLInputElement
    confirm.checked = true
    confirm.dispatchEvent(new Event('change'))
    await nextTick()
    expect((pick(root, 'collect-submit') as HTMLButtonElement).disabled).toBe(false)

    pick(root, 'collect-submit').click()
    await flush()
    expect(collect).toHaveBeenCalledTimes(1)
    expect(collect).toHaveBeenCalledWith(11, { credentialId: 1, confirm: true })
    expect(pick(root, 'collect-notice').textContent).toContain('采集完成')
  })

  it('采集失败置 FAILED，且仍展示上次成功快照', async () => {
    collect.mockRejectedValueOnce(new Error('采集失败：timeout'))
    const root = await mountPanel()

    pick(root, 'collect-open').click()
    await flush()
    pick(root, 'collect-credential-1').click()
    await nextTick()
    const confirm = pick(root, 'collect-confirm') as HTMLInputElement
    confirm.checked = true
    confirm.dispatchEvent(new Event('change'))
    await nextTick()
    pick(root, 'collect-submit').click()
    await flush()

    expect(pick(root, 'collect-state').textContent!.trim()).toBe('FAILED')
    expect(pick(root, 'last-snapshot')).toBeTruthy()
    expect(pick(root, 'last-snapshot').textContent).toContain('2026-11-11T09:00:00')
    expect(pick(root, 'collect-notice').textContent).toContain('采集失败')
  })
})
