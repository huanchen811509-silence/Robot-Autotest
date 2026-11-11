/**
 * 系统设置二级导航 —— 唯一真源（v4.9.2 / OPT-01 / FR-49-01~04）。
 *
 * 全局模式与项目模式共用这一份固定顺序的数组：
 *   - `navigation.ts` 的项目侧栏「系统设置」组由它派生（路径去掉 /g 前缀）；
 *   - 全局侧栏同一组也由它派生（保留 /g 前缀）。
 * 因此两模式 6 项名称、顺序、能力始终 diff = 0，不会再出现「全局漏了 AI 助手」。
 *
 * <p>v4.12.0 起不再有「全局设置索引页」渲染这份数组：`/g/settings` 直接进平台配置表单，
 * 6 个目标由侧栏承担（FR-v4.12-20）。数组仍是唯一真源，只是少了那个中间页消费者。
 *
 * `path` 以全局命名空间（/g/...）为规范；派生项目路径时统一去掉 /g。
 * `capability` 为服务端能力码；`public` 是保留字，对所有角色可见。
 */
export interface SettingsNavItem {
  key: string
  labelKey: string
  path: string
  capability: string
}

/** 全局命名空间前缀；与 router/index.ts 的 /g/* 路由保持一致。 */
export const GLOBAL_SETTINGS_PREFIX = '/g'

/** 顺序固定：平台设置 / AI 助手 / 用户管理 / 审计日志 / 关于 / 版本迭代。 */
export const SETTINGS_NAV: readonly SettingsNavItem[] = [
  { key: 'settings-platform', labelKey: 'menu.settingsPlatform', path: '/g/settings', capability: 'settings:read' },
  { key: 'settings-ai', labelKey: 'menu.aiAssistants', path: '/g/settings/ai', capability: 'ai:config' },
  { key: 'settings-users', labelKey: 'menu.adminUsers', path: '/g/admin', capability: 'user:write' },
  { key: 'settings-audit', labelKey: 'audit.title', path: '/g/admin/audit', capability: 'audit:read' },
  { key: 'settings-about', labelKey: 'menu.about', path: '/g/about', capability: 'public' },
  { key: 'settings-changelog', labelKey: 'menu.changelog', path: '/g/changelog', capability: 'public' },
]

/**
 * 项目模式「系统设置」组（v4.11.0 / FR-v4.11-08）。
 *
 * 本版把平台级配置收敛到全局：全局侧栏承载 `SETTINGS_NAV` 全部 6 项，项目侧只留
 * 两件「与当前人有关」的事——「用户管理（仅当前用户和关联项目）」与「关于」。
 * 因此两模式不再 diff = 0，`SETTINGS_NAV` 的职责收窄为「全局 6 项真源」；
 * 项目侧的真源是下面这个数组，断言改为「2 项均能找到同源视图」。
 *
 * 为什么不复用 `/admin`：那是系统级账号 CRUD（能力 `user:write`），与「我是谁、我加入
 * 了哪些项目」既不同语义也不同越权面（见 `00-RESEARCH.md` B6）。
 */
export const PROJECT_SETTINGS_NAV: readonly SettingsNavItem[] = [
  {
    key: 'settings-me',
    labelKey: 'menu.settingsUsersMe',
    path: '/settings/users',
    capability: 'public',
  },
  {
    key: 'settings-about',
    labelKey: 'menu.about',
    path: '/about',
    capability: 'public',
  },
]

/** 全局路径 → 项目路径（/g/settings/ai → /settings/ai）。非 /g 路径原样返回。 */
export function projectSettingsPath(globalPath: string): string {
  return globalPath.startsWith(`${GLOBAL_SETTINGS_PREFIX}/`)
    ? globalPath.slice(GLOBAL_SETTINGS_PREFIX.length)
    : globalPath
}

/** 项目模式设置项：名称 / 顺序 / 能力与 SETTINGS_NAV 一致，仅路径换成项目命名空间。 */
export function projectSettingsNav(): SettingsNavItem[] {
  return SETTINGS_NAV.map((item) => ({ ...item, path: projectSettingsPath(item.path) }))
}

/** 全局模式设置项：直接用 SETTINGS_NAV 的 /g/... 路径。 */
export function globalSettingsNav(): SettingsNavItem[] {
  return SETTINGS_NAV.map((item) => ({ ...item }))
}
