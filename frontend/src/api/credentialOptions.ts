/**
 * 凭证选择器数据源 / 手工采集（W57-41 / 42）。
 *
 * 端点按后端 `GlobalAssetCredentialController` 核实：
 *  - `GET  /api/global/assets/{id}/credentials/options?keyword=` → 四级排序后的候选；
 *  - `POST /api/global/assets/{id}/collect/manual`              → 一次性凭证 / 另存为凭据。
 *
 * `tier` 语义（后端 `CredentialVerificationService#optionOf`）：
 *   0 = 本设备成功  1 = 同型号/同域成功  2 = 已配置  3 = 其它。
 * `lastResult` 为凭据最近一次验证结果（`SUCCESS` / 失败标记），前端据此把失败项排末位。
 */
import { request, toQuery } from './http'

/** 与后端 `CredentialVerificationService.CredentialOption` 对齐。 */
export interface CredentialOption {
  credentialId: number
  name: string
  credType: string
  username: string
  /** 0 本设备成功 / 1 同型号或同域成功 / 2 已配置 / 3 其它 */
  tier: number
  /** 最近一次验证结果；空串表示无记录 */
  lastResult: string
  /** 最近验证时间（ISO / 后端时间串），无记录为空串 */
  lastVerifiedAt: string
  boundDevices: number
  configured: boolean
}

export interface CredentialOptions {
  items: CredentialOption[]
  total: number
  truncated: boolean
}

/** 与后端 `ManualCredentialCollectService.ManualCollectRequest` 对齐。 */
export interface ManualCollectPayload {
  credentialId?: number | null
  username?: string
  password?: string
  privateKey?: string
  credType?: string
  connectOptions?: string
  confirm?: boolean
  saveAsCredential?: boolean
  saveAsName?: string
}

export interface ManualCollectOutcome {
  assetId: number
  oneShot: boolean
  mode: string
  status: string
  channel: string
}

export const credentialOptionsApi = {
  /** 设备选凭证下拉；keyword 由后端做服务端搜索（>50 条截断时必须带 keyword）。 */
  options(assetId: number, keyword = ''): Promise<CredentialOptions> {
    return request<CredentialOptions>(
      `/global/assets/${assetId}/credentials/options${toQuery({ keyword })}`,
    )
  },

  /** 手工提交：走一次性凭证或显式另存为凭据；响应不含任何材料位。 */
  collectManual(assetId: number, payload: ManualCollectPayload): Promise<ManualCollectOutcome> {
    return request<ManualCollectOutcome>(`/global/assets/${assetId}/collect/manual`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
}
