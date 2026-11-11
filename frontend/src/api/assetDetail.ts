/**
 * 资产详情契约（v4.9.2 / OPT-04 / FR-49-13~16）。
 *
 * 覆盖参考表「当前测试环境」12 字段 + 采集四态 + 四层证据链。
 * 派生字段（网口数 / 网卡配置汇总）由 device_port 与 spec_json 在前端计算，
 * 不新增数据库列；凭据类字段只以 keyMasked 前缀出现，不含明文。
 *
 * 注意：并行 Agent 实装的 `GET /asset-host-inventory/detail` 返回的是**嵌套三分区**
 * `AssetDetailResponse`（hardware / softwareAndOutOfBand / status / evidence / derived）。
 * 本模块用纯函数 `toAssetDetailView` 把它适配成面板消费的扁平只读契约，
 * 使「未采集」判定与四层证据渲染都落在真实数据上，而不是整屏占位。
 *
 * URL 一律不带 `/api` 前缀 —— `http.ts` 的 `request()` 会统一拼 BASE_URL。
 */
import { request, toQuery } from './http'

export type AssetSource = 'global' | 'project'

/** 未采集 / 采集中 / PARTIAL / FAILED / OK —— 失败后仍保留上次成功快照。 */
export type CollectState = 'NOT_COLLECTED' | 'COLLECTING' | 'PARTIAL' | 'FAILED' | 'OK'

const COLLECT_STATES: readonly CollectState[] = [
  'NOT_COLLECTED', 'COLLECTING', 'PARTIAL', 'FAILED', 'OK',
]

/** 凭据候选：只回掩码名，逐条以 keyMasked 前缀出现。 */
export interface MaskedCredentialOption {
  id: number
  name: string
  keyMasked: string
}

/** device_port 派生「网口数 / 网卡配置汇总」的最小事实集。 */
export interface DevicePortFact {
  portName: string
  nicName: string | null
  speedGbps: number | null
}

/** 四层证据：collectedAt / 命令原文 / 原始输出折叠区 / 结构化值。 */
export interface HostInventoryItemDto {
  category: string
  label: string
  command: string
  collectedAt: string | null
  rawOutput: string | null
  value: unknown
  reason: string | null
}

/** 「当前测试环境」12 字段 + 采集态 + 证据链。可空字段允许 null，缺失由 UI 显式标「未采集」。 */
export interface AssetDetailView {
  deviceId: number
  source: AssetSource
  name: string | null
  hostname: string | null
  managementIp: string | null
  role: string | null
  model: string | null
  os: string | null
  kernel: string | null
  bmc: string | null
  login: string | null
  credentialRef: string | null
  vfVm: string | null
  lifecycleStatus: string | null
  nicCount: number | null
  nicSummary: string | null
  collectState: CollectState
  status: string | null
  collectedAt: string | null
  specJson: string | null
  devicePorts: DevicePortFact[]
  credentials: MaskedCredentialOption[]
  evidence: HostInventoryItemDto[]
}

export interface AssetCollectResult {
  deviceId: number
  status: string
  collectedAt: string | null
  nicCount: number
  rocePorts: number
  roceMaxGbps: number | null
}

/** 后端 `AssetDetailResponse` 的嵌套三分区结构（第 4 个 Agent 实装）。 */
export interface AssetDetailResponse {
  deviceId: number
  projectId: number
  name: string | null
  hardware: {
    deviceType: string | null
    ports: {
      portName: string | null
      portType: string | null
      macAddress: string | null
      speedGbps: number | null
    }[]
    specCategories: string[]
  } | null
  softwareAndOutOfBand: {
    operatingSystem: string | null
    kernelVersion: string | null
    bmcAddress: string | null
    loginMode: string | null
  } | null
  status: {
    deviceStatus: string | null
    assetStatus: string | null
    specStatus: string | null
    collectedAt: string | null
    specHash: string | null
  } | null
  evidence: {
    category: string | null
    label: string | null
    collectedAt: string | null
    command: string | null
    rawOutput: string | null
    structuredValue: unknown
    reason: string | null
  }[] | null
  derived: {
    nicCount?: number | null
    portCount?: number | null
    rocePorts?: number | null
    roceMaxGbps?: number | null
    evidenceItemCount?: number | null
  } | null
  credential: { keyMasked: string | null } | null
}

/** 公共资产库列表行（用于凭据候选；后端 GlobalAssetService.AssetRow）。 */
interface GlobalAssetRow {
  id: number
  name: string | null
}

function maskPrefix(value: string | null | undefined): string {
  const text = (value ?? '').trim()
  return text ? `${text.slice(0, 2)}***` : '***'
}

/**
 * 纯函数：把后端嵌套聚合映射成面板的扁平只读契约。
 * 后端未提供的参考表列（managementIp / role / vfVm）保持 null，
 * 由 UI 显式渲染「未采集」，不伪造事实、不静默留空白。
 */
export function toAssetDetailView(raw: AssetDetailResponse, source: AssetSource): AssetDetailView {
  const hw = raw.hardware
  const sw = raw.softwareAndOutOfBand
  const st = raw.status
  const evidence = raw.evidence ?? []
  const specStatus = (st?.specStatus ?? '').toUpperCase()
  const collectState: CollectState = COLLECT_STATES.includes(specStatus as CollectState)
    ? (specStatus as CollectState)
    : evidence.length ? 'OK' : 'NOT_COLLECTED'

  const derivedNic = raw.derived?.nicCount ?? null
  const portCount = (hw?.ports ?? []).length
  const nicCount = derivedNic && derivedNic > 0 ? derivedNic : (portCount || null)
  const masked = raw.credential?.keyMasked ?? null

  return {
    deviceId: raw.deviceId,
    source,
    name: raw.name,
    hostname: raw.name,
    managementIp: null,
    role: null,
    model: hw?.deviceType ?? null,
    os: sw?.operatingSystem ?? null,
    kernel: sw?.kernelVersion ?? null,
    bmc: sw?.bmcAddress ?? null,
    login: sw?.loginMode ?? null,
    credentialRef: masked,
    vfVm: null,
    lifecycleStatus: st?.assetStatus ?? null,
    nicCount,
    nicSummary: null,
    collectState,
    status: st?.deviceStatus ?? null,
    collectedAt: st?.collectedAt ?? null,
    specJson: null,
    devicePorts: (hw?.ports ?? []).map((port) => ({
      portName: port.portName ?? '',
      // device_port 没有 nic_name 列，网卡配置汇总按 port_name 分组派生。
      nicName: port.portName ?? null,
      speedGbps: port.speedGbps ?? null,
    })),
    credentials: masked ? [{ id: 0, name: 'credential', keyMasked: masked }] : [],
    evidence: evidence.map((item) => ({
      category: item.category ?? '',
      label: item.label ?? '',
      command: item.command ?? '',
      collectedAt: item.collectedAt ?? null,
      rawOutput: item.rawOutput ?? null,
      value: item.structuredValue ?? null,
      reason: item.reason ?? null,
    })),
  }
}

export const assetDetailApi = {
  /** 4th Agent 约定：GET /api/asset-host-inventory/detail?deviceId= （source 后端忽略）。 */
  detail(deviceId: string | number, source: AssetSource) {
    return request<AssetDetailResponse>(
      `/asset-host-inventory/detail${toQuery({ deviceId, source })}`,
    ).then((raw) => toAssetDetailView(raw, source))
  },
  /** 公共凭据库候选（真实端点 GET /api/global/assets?type=CREDENTIAL），只回掩码名。 */
  listCredentials() {
    return request<GlobalAssetRow[]>(`/global/assets${toQuery({ type: 'CREDENTIAL' })}`).then(
      (rows) => rows.map((row) => ({
        id: row.id,
        name: row.name ?? 'credential',
        keyMasked: maskPrefix(row.name),
      })),
    )
  },
  /**
   * 真实端点 POST /api/global/assets/{id}/collect?credentialId=&confirm= 。
   * `confirm: true` 由 UI 的二次确认保证，凭据 ID 必选。
   */
  collect(deviceId: string | number, params: { credentialId: number; confirm: boolean }) {
    return request<AssetCollectResult>(
      `/global/assets/${deviceId}/collect${toQuery(params)}`,
      { method: 'POST' },
      180_000,
    )
  },
}
