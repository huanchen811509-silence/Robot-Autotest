/**
 * 当前用户菜单能力集（v4.9.2 / OPT-08 / FR-49-31~34）。
 *
 * 能力集合由服务端下发，前端不再按角色硬编码；导航只按 capability 做入口显隐，
 * 真正的资源级越权仍由后端 403 兜底。
 *
 * 注意：URL 不自带 `/api` 前缀——`http.ts` 的 `request()` 会统一拼上 BASE_URL
 * （默认 `/api`），因此最终请求是 `GET /api/me/menu-capabilities`。
 */
import { request } from './http'

/** 扁平的能力码集合（`resource:action`），如 `['settings:read', 'ai:config']`。 */
export type MenuCapabilities = string[]

export const menuCapabilitiesApi = {
  get(): Promise<MenuCapabilities> {
    return request<MenuCapabilities>('/me/menu-capabilities')
  },
}
