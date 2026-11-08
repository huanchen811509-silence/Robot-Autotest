/** W1 系统页 API（NAV-02 / NAV-03 / ENVX-02）。 */
import { request } from './http'

export interface BuildDependency {
  name: string
  version: string
  source: string
}

export interface BuildInfo {
  app: string
  version: string
  /** env / file / pom / build / runtime：版本号到底是哪儿来的，页面要写清楚 */
  versionSource?: string
  gitCommit: string
  buildTime: string
  source: 'build' | 'runtime' | string
  entryPort: string
  backendPort: string
  devPort: string
  license: string
  dependencies: BuildDependency[]
  selfCheck: Record<string, string>
}

export interface HealthState {
  status: string
  app?: string
  check?: string
  database?: string
  reason?: string
}

export const settingsApi = {
  platform: () => request<Record<string, string>>('/system/settings'),
  putPlatform: (values: Record<string, string>) =>
    request<Record<string, string>>('/system/settings', {
      method: 'PUT',
      body: JSON.stringify(values),
    }),
  project: (projectId: number) =>
    request<Record<string, string>>(`/projects/${projectId}/settings`),
  putProject: (projectId: number, values: Record<string, string>) =>
    request<Record<string, string>>(`/projects/${projectId}/settings`, {
      method: 'PUT',
      body: JSON.stringify(values),
    }),
  buildInfo: () => request<BuildInfo>('/system/build-info'),
  health: () => request<HealthState>('/health'),
}
