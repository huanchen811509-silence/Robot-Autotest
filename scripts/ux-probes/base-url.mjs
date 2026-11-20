/**
 * ux-probes 共用的应用基址归一化。
 *
 * 为什么需要：`APP_BASE` 在多个脚本里被直接拼成 `${APP}/api/...` 交给 fetch，
 * 只要有人按记忆里的写法 `APP_BASE=8001` 启动一次门禁，就会拿到
 * `ERR_INVALID_URL: input: '8001/api/auth/login'`——脚本不该把这个错误抛给运维。
 *
 * 接受三种写法，统一成 `http(s)://host[:port]`（去掉尾部斜杠）：
 *   1. 完整 URL：`http://127.0.0.1:8001`
 *   2. host:port：`127.0.0.1:8001`
 *   3. 纯端口：`8001` → `http://127.0.0.1:8001`
 */

export const DEFAULT_APP = 'http://localhost:8001'

export function normalizeBaseUrl(raw) {
  const value = (raw ?? '').trim()
  if (!value) return DEFAULT_APP
  if (/^https?:\/\//i.test(value)) return value.replace(/\/+$/, '')
  const withHost = /^\d+$/.test(value) ? `127.0.0.1:${value}` : value
  return `http://${withHost}`.replace(/\/+$/, '')
}

export const APP_BASE = normalizeBaseUrl(process.env.APP_BASE)
