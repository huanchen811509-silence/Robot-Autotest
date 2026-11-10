/**
 * 侧边栏导航（两级菜单）—— 唯一真源。
 *
 * v2.0 / NAV-01（FR-v2.0-07、08）：一级 6 域，层级 ≤ 2，过滤为能力驱动（`resource:action`）。
 * v2.0 / W0.8（2026-11-07 大师新需求）：按实际使用重新划分：
 * - 「资产 / 用例」各自独立一级，二级项全部显式声明（不再用 tab 伪分组）；
 * - 「管理」拆解——平台设置 / 用户 / 审计 / 关于 / 版本迭代归入**独立的「系统设置」一级菜单**；
 * - 「执行」补齐「执行节点」，失败聚类与计划组/依赖图通过 alsoMatch 保持父项点亮。
 *
 * 三条约定：
 * 1. **分组不承载页面**。有多个子页的才立分组；单页模块直接做叶子。
 * 2. **选中判定只看 path + query**，不用 `router-link-active`。
 * 3. **权限有两层**：这里按能力过滤只决定「给不给入口」，越权一律由后端 403 兜底；
 *    路由上的 `meta.capability` 会把直达无权限路由的访客送去 403 页。
 */
import zhCN from '@/locales/zh-CN'
import type { IconName } from '@/components/iconMap'

/** 叶子节点：一个可点击的页面入口。 */
export interface NavLeaf {
  /** 稳定标识，同时用作 i18n 兜底与测试断言键 */
  key: string
  /** i18n 键；缺失时回退到 `key` */
  labelKey: string
  icon: IconName
  path: string
  /** 只在 path 相同时参与判定的查询参数（如 `/assets` 的三个页签） */
  query?: Record<string, string>
  /** 除 path 外的额外前缀匹配（详情页/编辑器保持父项点亮） */
  alsoMatch?: string[]
  /**
   * 前缀匹配的排除项（v2.3 / FR-UI-26 配套）。
   *
   * `/reports` 按前缀会命中 `/reports/templates`，而模板页是另一个二级项；
   * 不排除就会出现「点模板、报告列表也亮」。排除项同样按路径分段边界比较。
   */
  exclude?: string[]
  /**
   * 合并组内的子域分区（v2.3 / FR-UI-29）。
   *
   * 只影响渲染时是否画一条细分隔线，**不参与**选中判定与能力过滤——
   * 单测里有一条专门断言「带/不带 section 时 isLeafActive 结果相同」。
   */
  section?: 'run' | 'report'
  /** 旧口径：仅管理员可见。保留给 v1.0 的审计入口，新增项一律用 capability。 */
  adminOnly?: boolean
  /** v2.0 能力键（`resource:action`）；不填 = 所有登录用户可见。 */
  capability?: string
}

/** 分组节点：只有子项，自身不可导航。 */
export interface NavGroup {
  key: string
  labelKey: string
  icon: IconName
  adminOnly?: boolean
  capability?: string
  children: NavLeaf[]
}

export type NavNode = NavGroup | NavLeaf

export function isGroup(node: NavNode): node is NavGroup {
  return Array.isArray((node as NavGroup).children)
}

export const NAVIGATION: NavNode[] = [
  {
    // v2.3 / FR-UI-02：工作台从叶子升级为分组。
    // 落地面（`/workbench`）是跨项目总览；单项目看板（`/overview`）退为第二个子项。
    // 顺带修掉 v1.0 的「同页两名」：侧栏叫工作台、页头叫总览，其实是两回事。
    key: 'workbench',
    labelKey: 'menu.workspace',
    icon: 'dashboard',
    children: [
      {
        key: 'workbench-overview',
        labelKey: 'menu.workbenchOverview',
        icon: 'boxes',
        path: '/workbench',
      },
      {
        // v4.1 / FR-v4.1-05：项目工作区导出 / 导入 / 任务中心。
        key: 'workbench-workspace',
        labelKey: 'menu.projectWorkspace',
        icon: 'download',
        path: '/workspace',
      },
      {
        key: 'workbench-board',
        labelKey: 'menu.overview',
        icon: 'reports',
        path: '/overview',
      },
    ],
  },
  {
    // v2.3 / FR-UI-26：执行与报告合并为一个一级分组。
    // 原「报告」是**叶子冒充分组**：列表/模板/详情/打印 4 个页面只立了 1 个叶子，
    // 靠 alsoMatch 硬关联高亮；而且 `/test-reports` 一直没有任何菜单入口（孤儿页）。
    // 合并后 6 项按 section 分「运行态 / 报告态」，渲染时只画 1 条细分隔线，不引三级层级。
    key: 'execution',
    labelKey: 'menu.execution',
    icon: 'runs',
    // FR-UI-27：**组级不设 capability**。原「报告」叶子本不需要 execution:read，
    // 若给合并组加回该能力，viewer 会静默失去报告入口（合并前的 6 项可见性就会倒退）。
    // 能力只设到子项：3 个运行态项要 execution:read，3 个报告态项不要。
    children: [
      {
        key: 'execution-runs',
        labelKey: 'menu.runsList',
        icon: 'runs',
        path: '/runs',
        alsoMatch: ['/runs/failure-clusters'],
        capability: 'execution:read',
        section: 'run',
      },
      {
        // FR-UI-04：独立词条，不再借用 `plans.title`（借用会让「计划」这种泛词跟着
        // 计划页标题一起改，界面与导航互相拴住）。
        key: 'execution-plans',
        labelKey: 'menu.runsPlans',
        icon: 'cases',
        path: '/plans',
        alsoMatch: ['/plans/groups', '/plans/graph'],
        capability: 'execution:read',
        section: 'run',
      },
      {
        key: 'execution-nodes',
        labelKey: 'menu.runsNodes',
        icon: 'admin',
        path: '/nodes',
        capability: 'execution:read',
        section: 'run',
      },
      {
        // `exclude` 是必须的：`/reports` 按前缀会命中 `/reports/templates`，
        // 不排除就会出现「点模板，报告列表也亮」。
        key: 'execution-reports',
        labelKey: 'menu.reports',
        icon: 'reports',
        path: '/reports',
        exclude: ['/reports/templates'],
        section: 'report',
      },
      {
        key: 'execution-report-templates',
        labelKey: 'menu.reportTemplates',
        icon: 'copy',
        path: '/reports/templates',
        section: 'report',
      },
      {
        // FR-UI-28：`/test-reports` 此前路由与视图都在，但导航树里没有任何一项指向它，
        // 用户只能手输 URL。这里补上唯一入口。
        key: 'execution-external-reports',
        labelKey: 'menu.externalReports',
        icon: 'external',
        path: '/test-reports',
        section: 'report',
      },
    ],
  },
  {
    key: 'cases',
    labelKey: 'menu.cases',
    icon: 'cases',
    children: [
      {
        key: 'cases-list',
        labelKey: 'menu.casesList',
        icon: 'cases',
        path: '/cases',
        alsoMatch: ['/editor', '/viewer'],
      },
      {
        key: 'cases-sets',
        labelKey: 'menu.casesSets',
        icon: 'cases',
        path: '/cases/sets',
      },
      {
        // MENU-03：测试总表看板（历史 / 当前双计数），挂在「用例」组下。
        key: 'cases-board',
        labelKey: 'menu.casesBoard',
        icon: 'reports',
        path: '/cases/board',
      },
    ],
  },
  {
    key: 'assets',
    labelKey: 'menu.assets',
    icon: 'boxes',
    children: [
      {
        key: 'assets-devices',
        labelKey: 'assets.tabs.devices',
        icon: 'device',
        path: '/assets',
        query: { tab: 'devices' },
      },
      {
        key: 'assets-links',
        labelKey: 'assets.tabs.links',
        icon: 'link',
        path: '/assets',
        query: { tab: 'links' },
      },
      {
        key: 'assets-credentials',
        labelKey: 'assets.tabs.credentials',
        icon: 'credential',
        path: '/assets',
        query: { tab: 'credentials' },
      },
    ],
  },
  {
    // v2.2 / MENU-01 + MENU-02：环境与拓扑（5 个只读二级页）。
    // 6 个业务能力域不建一级菜单，降为用例 domain_tag 筛选标签（FR-UI-30 仍成立）。
    key: 'environment',
    labelKey: 'menu.environment',
    icon: 'system',
    children: [
      { key: 'environment-overview', labelKey: 'menu.envOverview', icon: 'dashboard', path: '/environment/overview' },
      { key: 'environment-collect', labelKey: 'menu.envCollect', icon: 'database', path: '/environment/collect' },
      { key: 'environment-topology', labelKey: 'menu.envTopology', icon: 'link', path: '/environment/topology' },
      { key: 'environment-prepare', labelKey: 'menu.envPrepare', icon: 'sliders', path: '/environment/prepare' },
      { key: 'environment-analysis', labelKey: 'menu.envAnalysis', icon: 'reports', path: '/environment/analysis' },
    ],
  },
  {
    // v2.2 / MENU-01 + MENU-04：维护与恢复（3 个只读二级页）。
    key: 'maintenance',
    labelKey: 'menu.maintenance',
    icon: 'audit',
    children: [
      { key: 'maintenance-principles', labelKey: 'menu.mntPrinciples', icon: 'info', path: '/maintenance/principles' },
      { key: 'maintenance-records', labelKey: 'menu.mntRecords', icon: 'database', path: '/maintenance/records' },
      { key: 'maintenance-recover', labelKey: 'menu.mntRecover', icon: 'search', path: '/maintenance/recover' },
    ],
  },
  {
    // 「系统设置」独立成一级（v2.0 / W0.8）：平台配置 + 用户/审计 + 关于/版本迭代。
    // 顺序上后置（v2.3 / FR-UI-01）：它是兜底域，不该排在日常闭环前面。
    key: 'settings',
    labelKey: 'menu.settings',
    icon: 'admin',
    children: [
      {
        key: 'settings-platform',
        labelKey: 'menu.settingsPlatform',
        icon: 'admin',
        path: '/settings',
        capability: 'settings:read',
      },
      {
        // v2.5：AI 助手配置。用独立能力键 `ai:config`（四角色里只有 admin 持有），
        // 不使用 adminOnly：那个旧口径在能力过滤里等价于「要求 audit:read」，
        // 语义是审计入口专用，拿来管 AI 配置会让人以为「看审计的人也能改密钥」。
        key: 'settings-ai',
        labelKey: 'menu.aiAssistants',
        icon: 'sparkles',
        path: '/settings/ai',
        capability: 'ai:config',
      },
      {
        key: 'settings-users',
        labelKey: 'menu.adminUsers',
        icon: 'users',
        path: '/admin',
      },
      {
        key: 'settings-audit',
        labelKey: 'audit.title',
        icon: 'audit',
        path: '/admin/audit',
        adminOnly: true,
        capability: 'audit:read',
      },
      {
        key: 'settings-about',
        labelKey: 'menu.about',
        icon: 'help',
        path: '/about',
      },
      {
        key: 'settings-changelog',
        labelKey: 'menu.changelog',
        icon: 'reports',
        path: '/changelog',
      },
    ],
  },
]

/** 路由上参与判定所需的字段（避免依赖完整 RouteLocation，便于单测构造）。 */
export interface NavRouteLike {
  path: string
  /** 与 vue-router 的 `LocationQuery` 兼容：值可能是 string / null / 数组（数组元素可为 null） */
  query?: Record<string, string | null | (string | null)[] | undefined>
}

function normalizePath(path: string): string {
  if (path.length > 1 && path.endsWith('/')) return path.slice(0, -1)
  return path
}

/** 前缀匹配必须落在路径分段边界上：`/reports` 匹配 `/reports/abc`，不匹配 `/reportsX`。 */
function hitsPrefix(path: string, prefix: string): boolean {
  const base = normalizePath(prefix)
  return path === base || path.startsWith(`${base}/`)
}

function queryMatches(leaf: NavLeaf, route: NavRouteLike): boolean {
  if (!leaf.query) return true
  const actual = route.query ?? {}
  return Object.entries(leaf.query).every(([key, expected]) => {
    const value = actual[key]
    return Array.isArray(value) ? value[0] === expected : value === expected
  })
}

/**
 * 叶子是否处于选中态。
 * 无 `query` 的叶子只要 path 命中即算；有 `query` 的必须连查询参数一起相等。
 */
export function isLeafActive(leaf: NavLeaf, route: NavRouteLike): boolean {
  const path = normalizePath(route.path)
  const excluded = (leaf.exclude ?? []).some((prefix) => hitsPrefix(path, prefix))
  if (!excluded && hitsPrefix(path, leaf.path) && queryMatches(leaf, route)) {
    return true
  }
  return (leaf.alsoMatch ?? []).some((prefix) => hitsPrefix(path, prefix))
}

/**
 * 该子项之前是否要画一条分区细分隔线（v2.3 / FR-UI-29）。
 *
 * 判据是「与上一项的 `section` 不同且自身有 section」——`section` 只是渲染信息，
 * 不参与选中判定与能力过滤（单测有一条专门锁这个不变量）。
 */
export function isSectionBreak(leaves: readonly NavLeaf[], index: number): boolean {
  if (index <= 0) return false
  const current = leaves[index]
  const previous = leaves[index - 1]
  if (!current || !previous) return false
  return Boolean(current.section) && current.section !== previous.section
}

export function isGroupActive(group: NavGroup, route: NavRouteLike): boolean {
  return group.children.some((child) => isLeafActive(child, route))
}

export function isNodeActive(node: NavNode, route: NavRouteLike): boolean {
  return isGroup(node) ? isGroupActive(node, route) : isLeafActive(node, route)
}

/** 当前路由所属分组的 key；不在任何分组里返回 null（用于自动展开）。 */
export function activeGroupKey(route: NavRouteLike): string | null {
  for (const node of NAVIGATION) {
    if (isGroup(node) && isGroupActive(node, route)) return node.key
  }
  return null
}

/**
 * 旧口径过滤：只按 `adminOnly`。保留是因为 v1.0 既有单测按这个签名写，
 * 新代码请用 {@link visibleNavByCapabilities}。
 */
export function filterNav(nodes: NavNode[], isAdmin: boolean): NavNode[] {
  const keep = (node: NavNode): boolean => !node.adminOnly || isAdmin
  const result: NavNode[] = []
  for (const node of nodes) {
    if (!keep(node)) continue
    if (isGroup(node)) {
      const children = node.children.filter(keep)
      if (children.length === 0) continue
      result.push({ ...node, children })
    } else {
      result.push(node)
    }
  }
  return result
}

/** 导航树按当前身份过滤后的结果（旧口径）。 */
export function visibleNav(isAdmin: boolean): NavNode[] {
  return filterNav(NAVIGATION, isAdmin)
}

/* --------------------------------------------------------------------------
 * v2.0 能力驱动过滤（FR-v2.0-08）
 * -------------------------------------------------------------------------- */

export type NavRole = 'admin' | 'pm' | 'engineer' | 'viewer'

/** 四角色能力集：与后端角色口径一致，前端只做入口显隐。 */
export const ROLE_CAPABILITIES: Record<NavRole, string[]> = {
  admin: ['execution:read', 'audit:read', 'settings:read', 'user:write', 'case:write', 'ai:config'],
  pm: ['execution:read', 'settings:read', 'case:write'],
  engineer: ['execution:read', 'case:write'],
  viewer: [],
}

/** 后端角色码 → 四角色；未知角色按最小权限 viewer 处理（不放大权限）。 */
export function roleFromCodes(codes: string[] | undefined | null): NavRole {
  const normalized = (codes ?? []).map((code) => code.toLowerCase())
  if (normalized.includes('admin')) return 'admin'
  if (normalized.includes('pm')) return 'pm'
  if (normalized.includes('engineer')) return 'engineer'
  return 'viewer'
}

export function capabilitiesForRole(role: NavRole): string[] {
  return ROLE_CAPABILITIES[role]
}

/**
 * 按能力过滤节点。带 `capability` 的节点要求调用方持有该能力；
 * 分组自身能力不满足时整组隐藏，子项过滤后为空也整组隐藏。
 */
export function filterNavByCapabilities(nodes: NavNode[], capabilities: string[]): NavNode[] {
  const owned = new Set(capabilities)
  const keep = (node: NavNode): boolean =>
    (!node.capability || owned.has(node.capability))
    && (!node.adminOnly || owned.has('audit:read'))
  const result: NavNode[] = []
  for (const node of nodes) {
    if (!keep(node)) continue
    if (isGroup(node)) {
      const children = node.children.filter(keep)
      if (children.length === 0) continue
      result.push({ ...node, children })
    } else {
      result.push(node)
    }
  }
  return result
}

export function visibleNavByCapabilities(capabilities: string[]): NavNode[] {
  return filterNavByCapabilities(NAVIGATION, capabilities)
}

/** 4 角色菜单快照：测试与走查都用它，避免各写一份角色映射。 */
export function navKeysForRole(role: NavRole): string[] {
  return visibleNavByCapabilities(capabilitiesForRole(role)).flatMap((node) =>
    isGroup(node) ? [node.key, ...node.children.map((child) => child.key)] : [node.key],
  )
}

/** 叶子节点 → router 的 to 对象（path + 可选 query）。 */
export function leafTarget(leaf: NavLeaf): { path: string; query?: Record<string, string> } {
  return leaf.query ? { path: leaf.path, query: leaf.query } : { path: leaf.path }
}

/** 全部叶子（含分组内），命令面板与路由守卫共用。 */
export function allNavLeaves(): NavLeaf[] {
  return NAVIGATION.flatMap((node) => (isGroup(node) ? node.children : [node]))
}

/* --------------------------------------------------------------------------
 * v2.2 / MENU-01（FR-v2.2-01、06）：业务域隔离机检
 * v2.3 / FR-UI-01 把一级项从 8 组调成 7 组（执行 + 报告合并），这条隔离约束不变。
 * -------------------------------------------------------------------------- */

/**
 * demo 的 6 组业务能力域：只作用例 domain_tag / capability 筛选标签，不建一级菜单。
 * 供导航机检与用例库筛选共用（唯一真源）。
 */
export const BUSINESS_DOMAIN_LABELS = [
  zhCN.businessDomains.ethernet,
  zhCN.businessDomains.rdma,
  zhCN.businessDomains.qos,
  zhCN.businessDomains.virtualization,
  zhCN.businessDomains.offload,
  zhCN.businessDomains.gpuStorage,
] as const

/**
 * 机检谓词：true = 一级项里混入了业务能力域名（应判失败）。
 * 只查一级项 key / labelKey 的字面量，不下钻 children，也不依赖 i18n 渲染结果
 *（vue-i18n 缺键时 t() 回退到 key 本身，故中文 labelKey 就是显示文案）。
 */
export function hasBusinessDomainAsTopLevel(
  nodes: NavNode[],
  domains: readonly string[] = BUSINESS_DOMAIN_LABELS,
): boolean {
  return nodes.some((node) =>
    domains.some((domain) =>
      `${node.key} ${node.labelKey}`.toLowerCase().includes(domain.toLowerCase()),
    ),
  )
}

/** 一级项的二级入口：分组 = children；叶子 = 自身（单页模块本身就是一个可导航入口）。 */
export function secondLevelEntries(node: NavNode): NavLeaf[] {
  return isGroup(node) ? node.children : [node]
}
