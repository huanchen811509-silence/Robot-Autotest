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
import { PROJECT_SETTINGS_NAV, SETTINGS_NAV, projectSettingsPath } from './settingsNav'

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
  /**
   * 该 path 的**默认叶子**（v5.9.0 / FR-v5.9-01）。
   *
   * 同一个 path 下可能挂多个叶子（靠 `query` 区分页签）。当路由的 query 不指向
   * 其中任何一个时（裸路径、非法值、详情页），必须由**一个**默认叶子兜底，
   * 否则要么「全灭」（一个都不亮），要么「齐亮」（无 query 的叶子恒真）。
   *
   * 只对「同 path 多叶」有意义；不参与能力过滤。
   */
  defaultOf?: true
  /**
   * 除 path 外的额外匹配（详情页/编辑器保持父项点亮）。
   *
   * **只匹配更深的路径段**（v5.9.0 / FR-v5.9-01）：`/g/cases/` 命中
   * `/g/cases/12`，**不再**命中 `/g/cases` 自身。旧实现按 `normalizePath` 去掉尾
   * 斜杠后当裸前缀用，等于让「设备」页签在「凭据」页签下也亮。
   */
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

/* --------------------------------------------------------------------------
 * 设置项派生（v4.9.2 / OPT-01 → v4.11.0 / FR-v4.11-08）
 *
 * 图标与 adminOnly 属渲染 / 旧口径信息，不进单一数据源，这里做一次平行映射；
 * key / labelKey / path / capability 一律取自 settingsNav.ts。
 * -------------------------------------------------------------------------- */

const SETTINGS_ICONS: Record<string, IconName> = {
  'settings-platform': 'admin',
  'settings-ai': 'sparkles',
  'settings-users': 'users',
  'settings-me': 'users',
  'settings-audit': 'audit',
  'settings-about': 'help',
  'settings-changelog': 'reports',
}

/** 全局「系统设置」6 项（v4.11.0）：路径用 `/g/*` 原名，能力沿用 `SETTINGS_NAV`。 */
const globalSettingsChildren: NavLeaf[] = SETTINGS_NAV.map((item) => {
  const leaf: NavLeaf = {
    key: item.key,
    labelKey: item.labelKey,
    icon: SETTINGS_ICONS[item.key] ?? 'admin',
    path: item.path,
    capability: item.capability,
  }
  // 审计沿用旧口径 adminOnly（在能力过滤里等价于要求 audit:read）。
  if (item.key === 'settings-audit') leaf.adminOnly = true
  return leaf
})

/**
 * 项目「系统设置」2 项（v4.11.0 / FR-v4.11-08）：用户管理（仅当前用户和关联项目）+ 关于。
 *
 * 本版把平台级配置全量迁到全局侧栏；项目侧只留「与当前人有关」的两项，
 * 因此 `SETTINGS_NAV` 不再与项目侧 diff = 0（R-50-06）。
 */
const projectSettingsChildren: NavLeaf[] = PROJECT_SETTINGS_NAV.map((item) => ({
  key: item.key,
  labelKey: item.labelKey,
  icon: SETTINGS_ICONS[item.key] ?? 'admin',
  path: projectSettingsPath(item.path),
  capability: item.capability,
}))

/**
 * 全局模式导航（v4.5.0 / FR-v4.5-03；v4.11.0 / FR-v4.11-01 改两级）。
 *
 * v4.11.0 之前这里 5 项全是叶子，与项目模式的「7 组 / 32 叶」形态不对称；
 * 本版改为 **5 组 14 叶，每组至少 2 项**（PRD §4.2）：
 *
 * | 组 | 叶 | 数据面 |
 * |---|---|---|
 * | 项目 | 全部项目 / 我参与的（`?scope=mine`） | `/api/projects/cards` 的 `own`/`joined` |
 * | 公共用例 | 用例清单 / 导入记录（`?view=imports`） | 既有列表与批次端点 |
 * | 公共资产 | 设备 / 凭据（`?tab=devices\|credentials`） | 既有 `type` 筛选 |
 * | 模板 | 内置 / 自定义（`?kind=builtin\|custom`） | `TemplateRow.builtin` |
 * | 系统设置 | `SETTINGS_NAV` 6 项 | 与全局命名空间一致 |
 *
 * **同 path 多 query 的默认值约定**：每组第一个叶子是该组默认值。`/g/projects` 与
 * `/g/cases` 的第一项不带 query（裸路径即默认态）；`/g/assets` 与 `/g/templates` 两项
 * 都带 query，所以页面必须在无 query / 非法值时 `router.replace` 到默认值，
 * 否则 `isLeafActive` 的严格比较会让两个叶子都不亮（见 PRD §4.2 与 R-50-08）。
 *
 * 详情页（`/g/assets/:id`、`/g/templates/:id`）走 `alsoMatch` 点亮父项，不计入叶数。
 * v5.11.0 / FR-v5.11-08：`/g/cases/:id` 与 `/g/case-sets/:id` 已下架，成员/用例详情改在
 * 列表页内展开（`?detail=<id>`），因此这两条不再需要 `alsoMatch`。
 */
export const GLOBAL_NAVIGATION: NavNode[] = [
  {
    key: 'global-projects',
    labelKey: 'menu.globalProjects',
    icon: 'dashboard',
    children: [
      {
        key: 'global-projects-all',
        labelKey: 'menu.globalProjectsAll',
        icon: 'dashboard',
        path: '/g/projects',
        // W57-09：裸 `/g/projects` 按前缀会命中 `/g/projects/new`，不排除就会
        // 「点新建项目、全部项目也跟着亮」。exclude 按分段边界比较，不影响本项自身。
        exclude: ['/g/projects/new'],
        // v5.9.0 / FR-v5.9-01：本 path 的默认叶子。`?scope=mine` 命中时由兄弟胜出，
        // 裸路径 / 非法 scope 由本项兜底，不会再出现「两个都亮」。
        defaultOf: true,
      },
      {
        // v4.11.0 / FR-v4.11-29：不能靠 `myRole` 判——全局 admin 无成员关系时该值也是
        // `admin`（A14），对最需要这个筛子的人恒真。卡片接口本版补了 `own`/`joined`。
        key: 'global-projects-mine',
        labelKey: 'menu.globalProjectsMine',
        icon: 'users',
        path: '/g/projects',
        query: { scope: 'mine' },
      },
      {
        // W57-09：独立 path `/g/projects/new`（不复用项目树的 `/projects/new`），
        // 避免两棵树在「新建项目」入口上同时点亮；labelKey 用独立 i18n 键，
        // 经 t() 正常渲染，两棵树不因同名键而同时点亮。
        key: 'global-projects-new',
        labelKey: 'w57.nav.newProject',
        icon: 'plus',
        path: '/g/projects/new',
        capability: 'project:create',
      },
    ],
  },
  {
    key: 'global-cases',
    labelKey: 'menu.globalCases',
    icon: 'cases',
    children: [
      {
        key: 'global-cases-all',
        labelKey: 'menu.globalCasesList',
        icon: 'cases',
        path: '/g/cases',
        defaultOf: true,
      },
      {
        // v4.11.0 / FR-v4.11-26：把页内 `<details class="batches">` 提级为独立分支。
        key: 'global-cases-imports',
        labelKey: 'menu.globalCasesImports',
        icon: 'download',
        path: '/g/cases',
        query: { view: 'imports' },
      },
      {
        // W57-09：全局用例集二级入口。
        // v5.11.0 / FR-v5.11-08：成员详情改在页内展开（`?detail=<id>`），
        // `/g/case-sets/:id` 路由已删，故本叶不再需要 alsoMatch。
        key: 'global-cases-sets',
        labelKey: 'w57.nav.globalCaseSets',
        icon: 'cases',
        path: '/g/case-sets',
      },
    ],
  },
  {
    key: 'global-assets',
    labelKey: 'menu.globalAssets',
    icon: 'database',
    children: [
      {
        key: 'global-assets-devices',
        labelKey: 'assets.tabs.devices',
        icon: 'device',
        path: '/g/assets',
        query: { tab: 'devices' },
        alsoMatch: ['/g/assets/'],
        // 裸 `/g/assets` 与 `/g/assets/:id` 都落在「设备」——另两个页签必须靠 query 才亮。
        defaultOf: true,
      },
      {
        key: 'global-assets-credentials',
        labelKey: 'assets.tabs.credentials',
        icon: 'credential',
        path: '/g/assets',
        query: { tab: 'credentials' },
      },
    ],
  },
  {
    key: 'global-templates',
    labelKey: 'menu.globalTemplates',
    icon: 'boxes',
    children: [
      {
        key: 'global-templates-builtin',
        labelKey: 'menu.globalTemplatesBuiltin',
        icon: 'boxes',
        path: '/g/templates',
        query: { kind: 'builtin' },
        // v5.10.0 / W60-15：`alsoMatch: '/g/templates/'` 会把 `/g/templates/new` 也算进来，
        // 出现「点模板向导、内置模板也亮」。与项目组同一处理：按分段边界排除。
        alsoMatch: ['/g/templates/'],
        exclude: ['/g/templates/new'],
        defaultOf: true,
      },
      {
        key: 'global-templates-custom',
        labelKey: 'menu.globalTemplatesCustom',
        icon: 'copy',
        path: '/g/templates',
        query: { kind: 'custom' },
      },
      {
        // v5.10.0 / FR-v5.10-05：新建模板向导。一级组数不变、叶数 +1（W60-15 判据）。
        key: 'global-templates-new',
        labelKey: 'templateWizard.nav',
        icon: 'plus',
        path: '/g/templates/new',
      },
    ],
  },
  {
    // v5.9.0 / FR-v5.9-04：用户与权限独立成一级（D-3）。
    // 原「系统设置 → 用户管理」把角色/组/用户/项目关联挤在同一个 746 行页面里，
    // 并且那是「管理域」不是「个人设置」——放进系统设置会让该组撑到 10 项。
    // 旧入口 `/g/admin` 改为重定向到 `/g/iam/users`（不保留第二套写入口）。
    key: 'global-iam',
    labelKey: 'menu.globalIam',
    icon: 'users',
    // 组级能力：整组只对具备 user:write 的角色出现。不给组级能力而只给子项，
    // 过滤后会留下一个「点开是空的」折叠组（只剩标题、没有可点的项）。
    capability: 'user:write',
    children: [
      {
        key: 'global-iam-roles',
        labelKey: 'menu.globalIamRoles',
        icon: 'audit',
        path: '/g/iam/roles',
      },
      {
        key: 'global-iam-groups',
        labelKey: 'menu.globalIamGroups',
        icon: 'users',
        path: '/g/iam/groups',
        alsoMatch: ['/g/iam/groups/'],
      },
      {
        key: 'global-iam-users',
        labelKey: 'menu.globalIamUsers',
        icon: 'admin',
        path: '/g/iam/users',
        alsoMatch: ['/g/iam/users/'],
      },
      {
        key: 'global-iam-projects',
        labelKey: 'menu.globalIamProjects',
        icon: 'link',
        path: '/g/iam/projects',
        alsoMatch: ['/g/iam/projects/'],
      },
    ],
  },
  {
    key: 'global-settings',
    labelKey: 'menu.globalSettings',
    icon: 'admin',
    children: globalSettingsChildren,
  },
]

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
      {
        // v4.3.0 / FR-v4.3-01：项目创建从「系统设置」口径独立出来。
        // 原先 `/projects/new` 复用 `settings:read`，等于「能看设置就能建项目」；
        // 现按 PROJECT/CREATE 授予（ADMIN + PM），工程/观察者不再出现入口。
        key: 'workbench-create',
        labelKey: 'menu.newProject',
        icon: 'plus',
        path: '/projects/new',
        capability: 'project:create',
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
        // v4.11.0 / FR-v4.11-12：即时发起台。与下面「测试计划」（cron 定时编排）是两件事：
        // 这里「现在就跑这一批」，那里「按计划周期自动跑」。侧栏把发起放首位，
        // 页面副标题写明「即时发起，不设定时」，避免新老用户混淆（R-50-04）。
        key: 'execution-launch',
        labelKey: 'menu.executionPlans',
        icon: 'plus',
        path: '/execution-plans',
        capability: 'execution:read',
        section: 'run',
      },
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
        // v5.2.0 / W52-08：内核页此前「有路由、无入口」，只能手输 URL。
        // 它回答的是「这台机器上 RF/pytest 到底能不能跑」，属于执行前的必看页，
        // 因此标准工程与 pytest 工程都挂入口。
        key: 'execution-kernels',
        labelKey: 'menu.kernels',
        icon: 'system',
        path: '/kernels',
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
        // v5.9.0 / FR-v5.9-01：`/cases/sets` 是同级叶子，靠「精确 path 优先于前缀」
        // + 本项的默认叶子身份消歧；不再需要手写 exclude。
        defaultOf: true,
      },
      {
        key: 'cases-sets',
        labelKey: 'menu.casesSets',
        icon: 'cases',
        path: '/cases/sets',
        // 集合详情 `/cases/sets/:id` 点亮本项而不是「用例清单」。
        alsoMatch: ['/cases/sets/'],
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
        alsoMatch: ['/assets/'],
        defaultOf: true,
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
    // v5.15.0 / FR-v5.15-08：6 个只读二级页收敛为 4 个；prepare / analysis 旧深链保留 redirect。
    // 6 个业务能力域不建一级菜单，降为用例 domain_tag 筛选标签（FR-UI-30 仍成立）。
    key: 'environment',
    labelKey: 'menu.environment',
    icon: 'system',
    children: [
      { key: 'environment-overview', labelKey: 'menu.envOverview', icon: 'dashboard', path: '/environment/overview' },
      { key: 'environment-collect', labelKey: 'menu.envCollect', icon: 'database', path: '/environment/collect' },
      { key: 'environment-topology', labelKey: 'menu.envTopology', icon: 'link', path: '/environment/topology' },
        {
        // v4.3.0 / FR-v4.3-07：连接与拓扑（只读派生页），数据来自 `/asset-info`。
        // 不做连接编辑：连接由链路定义，编辑入口仍在「测试资产 → 链路」。
        key: 'environment-connections',
        labelKey: 'menu.envConnections',
        icon: 'link',
        path: '/environment/connections',
      },
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
      {
        // v4.9.0 / FR-v4.9-01：存量同步。挂在「维护与恢复」下，不新增一级菜单。
        key: 'maintenance-sync',
        labelKey: 'menu.mntSync',
        icon: 'download',
        path: '/sync',
      },
    ],
  },
  {
    // 「系统设置」独立成一级（v2.0 / W0.8）：平台配置 + 用户/审计 + 关于/版本迭代。
    // 顺序上后置（v2.3 / FR-UI-01）：它是兜底域，不该排在日常闭环前面。
    key: 'settings',
    labelKey: 'menu.settings',
    icon: 'admin',
    // v4.11.0 / FR-v4.11-08：项目侧收敛为 2 项（用户管理 + 关于），平台级 6 项在全局侧栏。
    children: projectSettingsChildren,
  },
]

/**
 * pytest 用例库工程的增量叶子（v5.6.0 / FR-v5.6-01 / W56-01）。
 *
 * **第二棵树已删除**（v5.6.0 / FR-v5.6-02）。原实现给 `pytest-suite` 单独维护一棵
 * 4 组 9 项的树，理由是「pytest 工程没有台架资产、没有环境拓扑，这些入口是噪音」。
 * 这个前提是错的：pytest 工程同样要先登记 DUT 与网口、配 SSH 凭据、拉环境，
 * 才谈得上执行；把资产页藏起来，用户反而找不到唯一能配 DUT 的地方。
 *
 * 现在只有一套菜单：`NAVIGATION`。工程类型的唯一差别是这个叶子——
 * 它把用户直接送到该工程类型的落地面（工程健康 / 覆盖率 / 绑定 / 证据），
 * 并入「工作台」组首位，而不是另立一个组。
 *
 * 为什么是「并入工作台组首位」而不是「放最后」：这个工程的日常入口就是工作台，
 * 首屏拿不到就得多点一次。分组数量不变（项目一级仍 7 组），层级仍 ≤2。
 */
export const pytestWorkbenchLeaf: NavLeaf = {
  key: 'pytest-workbench-health',
  labelKey: 'menu.pytestHealth',
  icon: 'dashboard',
  path: '/pytest-workbench',
  capability: 'execution:read',
}

/**
 * 给标准树挂上 pytest 增量叶子的结果（memo 化单例）。
 *
 * 为什么 memo：`App.vue` 与单测都用 `toBe` 断言「同一工程类型每次拿到同一个对象」，
 * 每次调用新建数组会让引用相等断言失效，还会让侧栏在每次渲染时重算 diff。
 * 树是模块级常量、不会变，所以缓存一次就够。
 */
let pytestTreeCache: NavNode[] | null = null

export function withPytestWorkbench(base: NavNode[] = NAVIGATION): NavNode[] {
  if (base === NAVIGATION) {
    if (pytestTreeCache) return pytestTreeCache
    pytestTreeCache = base.map((node) =>
      isGroup(node) && node.key === 'workbench'
        ? { ...node, children: [pytestWorkbenchLeaf, ...node.children] }
        : node,
    )
    return pytestTreeCache
  }
  // 非标准树（单测传自定义树）不缓存，避免把测试数据钉死在模块状态里。
  return base.map((node) =>
    isGroup(node) && node.key === 'workbench'
      ? { ...node, children: [pytestWorkbenchLeaf, ...node.children] }
      : node,
  )
}

/** 仅测试用：清掉 memo，避免用例之间互相污染。 */
export function resetPytestTreeCache(): void {
  pytestTreeCache = null
}

/** 工程类型取值（与后端 `ProjectKind` 对齐）。 */
export type ProjectKind = 'standard' | 'platform' | 'pytest-suite'

/**
 * 按「界面模式 + 工程类型」选树。
 *
 * 这是唯一入口：`App.vue` 不再自己三元判断。把选择逻辑收成函数，才能在单测里
 * 穷举三种工程类型 × 两种模式，而不是靠人工点界面确认。
 *
 * v5.6.0 / FR-v5.6-02：只有 global / project 两种模式，不再按工程类型分树。
 *
 * @param mode  `global`（跨项目）/ `project`（项目内）
 * @param kind  工程类型；空或未知一律按标准工程处理（宁可多显示，不要少显示入口）
 */
export function navTreeFor(mode: 'global' | 'project', kind?: string | null): NavNode[] {
  if (mode === 'global') {
    return GLOBAL_NAVIGATION
  }
  // v5.6.0 / FR-v5.6-01：全量菜单。工程类型只决定「是否多一个落地面叶子」，
  // 不再决定「看不看得到资产/环境/维护」——那套差异化已被证伪。
  return kind === 'pytest-suite' ? withPytestWorkbench() : NAVIGATION
}

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

/** `alsoMatch` 只匹配**更深**的路径段：`/g/cases/` 命中 `/g/cases/12`，不命中 `/g/cases`。 */
function hitsDeeper(path: string, prefix: string): boolean {
  const base = normalizePath(prefix)
  return path.startsWith(`${base}/`)
}

function isExcluded(leaf: NavLeaf, path: string): boolean {
  return (leaf.exclude ?? []).some((prefix) => hitsPrefix(path, prefix))
}

/** 同一批候选里选唯一命中者：query 命中 > 默认叶子 > 无 query 叶子 > 首个。 */
function pickOne(candidates: NavLeaf[], route: NavRouteLike): NavLeaf[] {
  if (candidates.length <= 1) return candidates
  const byQuery = candidates.filter((leaf) => leaf.query && queryMatches(leaf, route))
  if (byQuery.length) return byQuery.slice(0, 1)
  const byDefault = candidates.filter((leaf) => leaf.defaultOf)
  if (byDefault.length) return byDefault.slice(0, 1)
  const plain = candidates.filter((leaf) => !leaf.query)
  if (plain.length) return plain.slice(0, 1)
  return candidates.slice(0, 1)
}

/** 候选里各叶子命中的最长前缀长度（`alsoMatch` 分支用）。 */
function longestAlsoMatch(leaf: NavLeaf, path: string): number {
  return (leaf.alsoMatch ?? [])
    .filter((prefix) => hitsDeeper(path, prefix))
    .reduce((max, prefix) => Math.max(max, normalizePath(prefix).length), -1)
}

/**
 * **同组消歧后的**选中叶子（v5.9.0 / FR-v5.9-01，唯一判定入口）。
 *
 * 分三级取候选，前一级非空就不看后一级：
 * 1. **精确 path**：`/cases/sets` 只命中「用例集」，不再连带 `/cases` 的前缀匹配；
 * 2. **前缀 path**：取**路径最长**者（`/cases/sets` 胜过 `/cases`），详情页因此点亮最近的父项；
 * 3. **`alsoMatch`**：只匹配更深的路径段，同样取最长者。
 *
 * 每级内部若仍多于一个（同 path 多叶），交给 {@link pickOne} 按 query / 默认叶子消歧。
 * 返回值长度恒 ≤ 1——「同一时刻只能有一个二级项高亮」是硬不变量。
 */
export function activeLeaves(leaves: readonly NavLeaf[], route: NavRouteLike): NavLeaf[] {
  const path = normalizePath(route.path)

  const exact = leaves.filter((leaf) => !isExcluded(leaf, path) && normalizePath(leaf.path) === path)
  if (exact.length) return pickOne(exact, route)

  const prefixed = leaves.filter((leaf) => !isExcluded(leaf, path) && hitsPrefix(path, leaf.path))
  if (prefixed.length) {
    const longest = Math.max(...prefixed.map((leaf) => normalizePath(leaf.path).length))
    return pickOne(
      prefixed.filter((leaf) => normalizePath(leaf.path).length === longest),
      route,
    )
  }

  const viaAlso = leaves.filter((leaf) => longestAlsoMatch(leaf, path) >= 0)
  if (viaAlso.length) {
    const longest = Math.max(...viaAlso.map((leaf) => longestAlsoMatch(leaf, path)))
    return pickOne(
      viaAlso.filter((leaf) => longestAlsoMatch(leaf, path) === longest),
      route,
    )
  }

  return []
}

/** 同组消歧后的选中叶子 key 集合（渲染与单测都读它）。 */
export function activeLeafKeys(leaves: readonly NavLeaf[], route: NavRouteLike): string[] {
  return activeLeaves(leaves, route).map((leaf) => leaf.key)
}

/**
 * 叶子是否处于选中态（**单叶**判定，不含同组消歧）。
 *
 * 无 `query` 的叶子只要 path 命中即算；有 `query` 的必须连查询参数一起相等。
 * 渲染请用 {@link isLeafActiveInGroup} 或 {@link activeLeaves}——单叶判定看不出
 * 「同 path 的两个兄弟都自认为命中」这件事。
 */
export function isLeafActive(leaf: NavLeaf, route: NavRouteLike): boolean {
  const path = normalizePath(route.path)
  if (!isExcluded(leaf, path) && hitsPrefix(path, leaf.path) && queryMatches(leaf, route)) {
    return true
  }
  return longestAlsoMatch(leaf, path) >= 0
}

/** 叶子在**它所在的兄弟组**里是否胜出（侧栏渲染用这个，不用 {@link isLeafActive}）。 */
export function isLeafActiveInGroup(
  leaf: NavLeaf,
  leaves: readonly NavLeaf[],
  route: NavRouteLike,
): boolean {
  return activeLeaves(leaves, route).some((winner) => winner.key === leaf.key)
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
  // v5.9.0：按同组消歧后的结果判，避免「两个叶子各亮一半」时组头也算亮两次。
  return activeLeaves(group.children, route).length > 0
}

export function isNodeActive(node: NavNode, route: NavRouteLike): boolean {
  return isGroup(node) ? isGroupActive(node, route) : isLeafActive(node, route)
}

/**
 * 当前路由所属分组的 key；不在任何分组里返回 null（用于自动展开）。
 *
 * v4.11.0 / FR-v4.11-02：原来硬编码只遍历项目 `NAVIGATION`，全局模式就算改成分组
 * 也不会自动展开（组头永远不亮）。现在由调用方把**当前模式的树**传进来：
 * `AppNav` 传 `props.nodes`，单测可传任意树。
 *
 * 默认值仍是 `NAVIGATION`，保证既有调用方（命令面板、旧单测）行为不变。
 */
export function activeGroupKey(
  route: NavRouteLike,
  nodes: readonly NavNode[] = NAVIGATION,
): string | null {
  for (const node of nodes) {
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

/** 后端角色码 → 四角色；未知角色按最小权限 viewer 处理（不放大权限）。 */
export function roleFromCodes(codes: string[] | undefined | null): NavRole {
  const normalized = (codes ?? []).map((code) => code.toLowerCase())
  if (normalized.includes('admin')) return 'admin'
  if (normalized.includes('pm')) return 'pm'
  if (normalized.includes('engineer')) return 'engineer'
  return 'viewer'
}

/**
 * v4.9.2 / OPT-08（FR-49-31）：删除了硬编码能力表。
 *
 * 导航可见性的**唯一真源**是服务端能力集（`stores/menuCapabilities` 的 `has()`），
 * `App.vue` 的侧栏渲染与路由守卫都据此过滤。这里保留的只是「服务端能力集
 * 尚未就绪 / 单测 / 命令面板等非导航消费者」用的按角色兜底口径——纯函数，
 * 不再有导出的常量表，源码中也不再出现该表的字面量。
 */
export function capabilitiesForRole(role: NavRole): string[] {
  switch (role) {
    case 'admin':
      return ['execution:read', 'asset:read', 'audit:read', 'settings:read', 'user:write', 'case:write', 'ai:config', 'project:create']
    case 'pm':
      return ['execution:read', 'asset:read', 'settings:read', 'case:write', 'project:create']
    case 'engineer':
      return ['execution:read', 'asset:read', 'case:write']
    case 'viewer':
      return ['asset:read']
    default:
      return ['asset:read']
  }
}

/**
 * 能力判据单一真源（v4.12.0 / FR-v4.12-21）。
 *
 * 修复的是一处**回归缺陷**：`/settings/users` 的路由 meta 标了 `capability: 'public'`，
 * 但路由守卫用的是 `capabilitiesForRole(role).includes(capability)` —— 那张表里从来没有
 * 字面量 `'public'`，于是任何角色进这一页都被踢到 `/403`；而导航过滤这边另有 public 特判，
 * 两套口径各写各的，症状就是「菜单里有、点进去 403」。
 *
 * 现在 public 特判只允许存在这一处：路由守卫与导航过滤都调它。
 * 第二个参数是「怎么判一个普通能力」，由调用方给（服务端能力集 / 按角色兜底），
 * 本函数只负责「无能力 = 放行」与「public = 放行」这两条与来源无关的规则。
 */
export function capabilitySatisfied(
  capability: string | undefined | null,
  has: (capability: string) => boolean,
): boolean {
  if (!capability) return true
  if (capability === 'public') return true
  return has(capability)
}

/**
 * 按能力谓词过滤节点（v4.9.2 / OPT-08 权威口径）。
 *
 * `has` 通常由 `stores/menuCapabilities.has` 提供；`capability === 'public'`
 * 对所有角色可见。分组自身能力不满足时整组隐藏，子项过滤后为空也整组隐藏。
 */
export function filterNavByCapabilityPredicate(
  nodes: NavNode[],
  has: (capability: string) => boolean,
): NavNode[] {
  const keep = (node: NavNode): boolean => {
    if (!capabilitySatisfied(node.capability, has)) return false
    if (node.adminOnly && !has('audit:read')) return false
    return true
  }
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

/** 按能力集合过滤（数组口径，供旧调用方 / 单测使用）。 */
export function filterNavByCapabilities(nodes: NavNode[], capabilities: string[]): NavNode[] {
  const owned = new Set(capabilities)
  return filterNavByCapabilityPredicate(nodes, (capability) => owned.has(capability))
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
