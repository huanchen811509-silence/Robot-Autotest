import type { Component } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiModeStore } from '@/stores/uiMode'
import { capabilitiesForRole, roleFromCodes } from '@/config/navigation'

/* --------------------------------------------------------------------------
 * v4.9.2 / OPT-01（FR-49-10~12）：全局模式守卫的两条独立判据。
 *
 * 拆成两个纯函数是为了可独立测试、互不耦合：
 *   - shouldPreserveGlobalMode 只看 meta.global，决定「是否保持全局模式」；
 *   - canEnterGlobalMode 只看角色，决定「是否允许进入全局模式」。
 * 二者都不读对方关心的字段，改一处不会悄悄改变另一处的行为。
 * -------------------------------------------------------------------------- */
export interface GlobalModeRouteLike {
  meta?: Record<string, unknown>
}

/** 模式保持：只看 meta.global，严格等于 true 才保持全局模式。 */
export function shouldPreserveGlobalMode(route: GlobalModeRouteLike | null | undefined): boolean {
  return route?.meta?.global === true
}

/** 角色准入：只看角色码（大小写不敏感）；ADMIN / PM 允许，其余一律拒绝。 */
export function canEnterGlobalMode(role: string | null | undefined): boolean {
  const normalized = (role ?? '').toLowerCase()
  return normalized === 'admin' || normalized === 'pm'
}

/* --------------------------------------------------------------------------
 * v4.9.2：同批次其他 Agent 创建的视图（懒加载动态 import）。
 *
 * 用 import.meta.glob 生成懒加载器——Vite 会为每个匹配到的 .vue 文件生成
 * `() => import('./file.vue').then(m => m.default)`。文件暂不存在时 glob 返回空
 * 对象：既不报 TS2307，也不触发 vite:import-analysis 的「文件不存在」错误，
 * 因此现在（视图未落地）vue-tsc 与全量单测都能过；视图落地后自动生效，无需改此处。
 * 非空断言对应批次约定「最终都会存在」；万一缺失，该路由组件为 undefined，
 * router-view 渲染空内容，不影响其余路由与守卫。
 * -------------------------------------------------------------------------- */
const OPTIONAL_VIEWS = import.meta.glob<Component>([
  '../views/global/GlobalCaseDetailView.vue',
  '../views/global/GlobalAssetDetailView.vue',
  '../views/AssetDetailView.vue',
  '../views/global/TemplateWorkbenchView.vue',
  '../views/ScenariosView.vue',
], { import: 'default' })

/** 懒加载器：等价于 `() => import('@/views/...')`，路径相对本文件。 */
function optionalView(path: string): () => Promise<Component> {
  return OPTIONAL_VIEWS[path]!
}

// 7 个一级菜单（工作台 / 执行与报告 / 用例 / 资产 / 环境与拓扑 / 维护与恢复 / 系统设置）
// history 模式要求服务端回退到 index.html，否则刷新 404（TC-v0.1-041）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    // v2.3 / FR-UI-02：落地页改为跨项目总览（原先是单项目看板）。
    { path: '/', redirect: '/workbench' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/workbench',
      name: 'workbench',
      component: () => import('@/views/WorkbenchView.vue'),
    },
    {
      // 项目工作区（FR-v4.1-05）：导出 / 预检 / 导入 / 任务中心；写权限由后端校验。
      path: '/workspace',
      name: 'workspace',
      component: () => import('@/views/WorkspaceView.vue'),
    },
    {
      // 存量同步（v4.9.0 / FR-v4.9-01..07）：预览 → 冲突处理 → 执行 → 回滚。
      // 项目范围由顶部项目选择器决定，写权限由后端 403 兜底。
      path: '/sync',
      name: 'project-sync',
      component: () => import('@/views/ProjectSyncView.vue'),
    },
    {
      // 创建项目向导（FR-v4.1-04）：五步，模板来自 config/project-templates。
      // v4.3.0 / FR-v4.3-01：能力码由 `settings:read` 改为 `project:create`，
      // 与后端 PROJECT/CREATE（ADMIN + PM）对齐，工程/观察者直达会被守卫送 403。
      path: '/projects/new',
      name: 'project-new',
      component: () => import('@/views/ProjectWizardView.vue'),
      meta: { capability: 'project:create' },
    },
    {
      path: '/overview',
      name: 'overview',
      component: () => import('@/views/OverviewView.vue'),
    },
    {
      path: '/assets',
      name: 'assets',
      component: () => import('@/views/AssetsView.vue'),
    },
    {
      path: '/cases',
      name: 'cases',
      component: () => import('@/views/CasesView.vue'),
    },
    {
      // 用例集管理（CAS-02 前端部分）：仍属「用例」一级菜单的子页面，
      // 不新增一级菜单，入口在用例库工具栏与执行计划的用例集下拉旁。
      path: '/cases/sets',
      name: 'case-sets',
      component: () => import('@/views/CaseSetsView.vue'),
    },
    {
      // v4.11.0 / FR-v4.11-12：执行计划（即时发起台）。
      // 与下面的 `/plans`（cron 定时编排）是两件事：这里「现在就跑这一批」。
      // 未授权直达由 meta.capability 送去 403，菜单项同样按该能力过滤。
      path: '/execution-plans',
      name: 'execution-plans',
      component: () => import('@/views/ExecutionPlansView.vue'),
      meta: { capability: 'execution:read' },
    },
    {
      path: '/runs',
      name: 'runs',
      component: () => import('@/views/RunsView.vue'),
    },
    {
      // 执行计划（PLN-01 / PLN-02）：属于「执行」一级菜单下的子页面，
      // 不新增一级菜单，从运行列表与左侧「执行」进入。
      path: '/plans',
      name: 'plans',
      component: () => import('@/views/PlansView.vue'),
    },
    {
      // 计划组（ORCH-01，v2.1）：计划页的子页面，入口在计划页工具栏。
      // 独立页面而不是计划页里的弹层——组成员顺序是整页操作的场景，弹层放不下。
      path: '/plans/groups',
      name: 'plan-groups',
      component: () => import('@/views/PlanGroupsView.vue'),
    },
    {
      // 计划依赖图（ORCH-02，v2.1）：只读 SVG，不提供拖拽编辑——
      // 拖出来的边会绕过保存时的环检测。
      path: '/plans/graph',
      name: 'plan-graph',
      component: () => import('@/views/PlanGraphPage.vue'),
    },
    {
      // 失败聚类（ORCH-03，v2.1）：运行详情的下钻页，入口在运行列表的失败数。
      // 用 query 里的 runId 预填，深链可分享——贴一条链接就能让同事看到同一批失败。
      path: '/runs/failure-clusters',
      name: 'failure-clusters',
      component: () => import('@/views/FailureClustersView.vue'),
    },
    {
      path: '/reports',
      name: 'reports',
      component: () => import('@/views/ReportsView.vue'),
    },
    {
      // 报告模板（TPLX-01，v2.1）：报告页的子页面，入口在报告页工具栏。
      // 可选字段只来自后端白名单接口，页面不内联字段名单（见视图注释）。
      path: '/reports/templates',
      name: 'report-templates',
      component: () => import('@/views/ReportTemplatesView.vue'),
    },
    {
      // 报告打印页（RPT-05）：无侧边栏、A4 白底，供 PDF 无头打印；
      // 授权靠 URL 上的一次性 token，因此标记 public，不能要求登录态。
      path: '/reports/:runId/print',
      name: 'report-print',
      component: () => import('@/views/ReportPrintView.vue'),
      meta: { public: true, print: true },
    },
    {
      // 报告详情（RPT-02 / RPT-03）：仍是「报告」一级菜单下的子路由
      path: '/reports/:runId',
      name: 'report-detail',
      component: () => import('@/views/ReportDetailView.vue'),
    },
    {
      path: '/test-reports',
      name: 'test-reports',
      component: () => import('@/views/TestReportsView.vue'),
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('@/views/AdminView.vue'),
    },
    {
      // 审计查询（HRD-02）：挂在「管理」菜单下的子页面，不新增第 7 个一级菜单；
      // 入口仅对管理员显示，非管理员即便手输 URL 也会被后端 403 拦截。
      path: '/admin/audit',
      name: 'audit',
      component: () => import('@/views/AuditView.vue'),
    },
    {
      // 运维中心（v3.3.0 / FR-3.3-09..12）：SLO、告警、备份、发布、并发准入。
      // 仅管理员：capability 与审计页一致，后端另有 isAdmin 校验兜底。
      path: '/admin/ops',
      name: 'admin-ops',
      component: () => import('@/views/OpsCenterView.vue'),
      meta: { capability: 'audit:read' },
    },
    {
      // 归档中心（v3.4.0 / FR-3.4-01）：策略 / 预览 / 执行 / 冷查 / 恢复。
      path: '/admin/archive',
      name: 'admin-archive',
      component: () => import('@/views/ArchiveCenterView.vue'),
      meta: { capability: 'audit:read' },
    },
    {
      path: '/editor/:projectId',
      name: 'editor',
      component: () => import('@/views/EditorView.vue'),
    },
    {
      // 只读查看器：独立路由（FR-v0.3-18），不依赖编辑权限即可分享给只读用户
      path: '/viewer/:projectId',
      name: 'viewer',
      component: () => import('@/views/EditorView.vue'),
    },
    // v2.0 / W1 系统页：都挂在「管理」域下，不新增一级菜单
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { capability: 'settings:read' },
    },
    {
      // v4.11.0 / FR-v4.11-30：项目域「用户管理（仅当前用户和关联项目）」只读页。
      // 与 `/admin`（系统级账号 CRUD，`user:write`）不是一回事，故单独一条路由。
      // 放在 `/settings` 之后：`/settings` 是精确匹配，不会被这条抢走。
      path: '/settings/users',
      name: 'settings-users',
      component: () => import('@/views/SettingsUsersView.vue'),
      meta: { capability: 'public' },
    },
    {
      // v2.5 / FR-AI-10..13：AI 助手逐个配置端点 / 密钥 / 模型。仅管理员（后端 403 兜底）。
      path: '/settings/ai',
      name: 'settings-ai',
      component: () => import('@/views/AiAssistantsView.vue'),
      meta: { capability: 'ai:config' },
    },
    {
      // 执行节点（NODX-01，v2.1）：平台级资源，入口在设置页工具栏。
      path: '/nodes',
      name: 'nodes',
      component: () => import('@/views/NodesView.vue'),
    },
    { path: '/about', name: 'about', component: () => import('@/views/AboutView.vue') },
    {
      path: '/changelog',
      name: 'changelog',
      component: () => import('@/views/ChangelogView.vue'),
    },
    {
      // 无权限直达的落点（FR-v2.0-08）：不是 404，也不泄露目标页内容
      path: '/403',
      name: 'forbidden',
      component: () => import('@/views/ForbiddenView.vue'),
    },
    {
      // v2.0 / W4 导入预检：不新增一级菜单，由命令面板与 CasesView 入口进入
      path: '/imports',
      name: 'imports',
      component: () => import('@/views/ImportPreflightView.vue'),
    },
    {
      // v2.0 / W5 组授权与一层场景编排
      path: '/groups',
      name: 'groups',
      component: () => import('@/views/GroupsView.vue'),
    },
    {
      // v2.0 / W3 双内核能力矩阵与选择来源
      path: '/kernels',
      name: 'kernels',
      component: () => import('@/views/KernelsView.vue'),
    },
    // ===== v2.2 / M2 只读页与看板（MENU-02 / MENU-03 / MENU-04 / MENU-05）=====
    {
      // MENU-03：测试总表看板，挂在「用例」组下。
      path: '/cases/board',
      name: 'case-board',
      component: () => import('@/views/CaseBoardView.vue'),
    },
    {
      path: '/environment/overview',
      name: 'environment-overview',
      component: () => import('@/views/environment/EnvironmentOverviewView.vue'),
    },
    {
      path: '/environment/collect',
      name: 'environment-collect',
      component: () => import('@/views/environment/EnvironmentCollectView.vue'),
    },
    {
      path: '/environment/topology',
      name: 'environment-topology',
      component: () => import('@/views/environment/TopologySnapshotView.vue'),
    },
    {
      path: '/environment/prepare',
      name: 'environment-prepare',
      component: () => import('@/views/environment/EnvironmentPrepareView.vue'),
    },
    {
      path: '/environment/analysis',
      name: 'environment-analysis',
      component: () => import('@/views/environment/CollectionAnalysisView.vue'),
    },
    {
      // v4.3.0 / FR-v4.3-07：连接与拓扑（只读，从设备/端口/链路派生）。
      // 能力码只挡「有没有入口」，项目内可见范围与刷新权限仍由后端 403 兜底。
      path: '/environment/connections',
      name: 'environment-connections',
      component: () => import('@/views/environment/ConnectionsView.vue'),
      meta: { capability: 'asset:read' },
    },
    {
      path: '/maintenance/principles',
      name: 'maintenance-principles',
      component: () => import('@/views/maintenance/ExecutionPrinciplesView.vue'),
    },
    {
      path: '/maintenance/records',
      name: 'maintenance-records',
      component: () => import('@/views/maintenance/RecoveryRecordsView.vue'),
    },
    {
      path: '/maintenance/recover',
      name: 'maintenance-recover',
      component: () => import('@/views/maintenance/ReadOnlyRecoverView.vue'),
    },
    {
      // MENU-05：性能基线 3 个只读页，独立路由（不新增一级菜单）。
      path: '/baselines/iperf',
      name: 'baseline-iperf',
      component: () => import('@/views/baselines/IperfBaselineView.vue'),
    },
    {
      path: '/baselines/perftest',
      name: 'baseline-perftest',
      component: () => import('@/views/baselines/PerftestBaselineView.vue'),
    },
    {
      path: '/baselines/ccl',
      name: 'baseline-ccl',
      component: () => import('@/views/baselines/CclBaselineView.vue'),
    },
    // v4.5.0 全局模式（FR-v4.5-03）：独立命名空间，避免与项目内页面权限混淆。
    { path: '/g', redirect: '/g/projects' },
    {
      path: '/g/projects',
      name: 'global-projects',
      meta: { global: true },
      component: () => import('@/views/global/GlobalProjectsView.vue'),
    },
    {
      path: '/g/cases',
      name: 'global-cases',
      meta: { global: true },
      component: () => import('@/views/global/GlobalCasesView.vue'),
    },
    {
      path: '/g/assets',
      name: 'global-assets',
      meta: { global: true },
      component: () => import('@/views/global/GlobalAssetsView.vue'),
    },
    {
      path: '/g/templates',
      name: 'global-templates',
      meta: { global: true },
      component: () => import('@/views/global/GlobalTemplatesView.vue'),
    },
    {
      path: '/g/settings',
      name: 'global-settings',
      meta: { global: true },
      component: () => import('@/views/global/GlobalSettingsView.vue'),
    },
    // v4.9.2 / OPT-01（FR-49-01~04）：全局系统设置的 6 个目标路由，复用现有设置视图。
    // 全部带 meta.global = true —— 点击后 ui_mode 保持 global，不会回 /workbench。
    {
      path: '/g/settings/ai',
      name: 'global-settings-ai',
      meta: { global: true },
      component: () => import('@/views/AiAssistantsView.vue'),
    },
    {
      path: '/g/admin',
      name: 'global-admin',
      meta: { global: true },
      component: () => import('@/views/AdminView.vue'),
    },
    {
      path: '/g/admin/audit',
      name: 'global-admin-audit',
      meta: { global: true },
      component: () => import('@/views/AuditView.vue'),
    },
    {
      path: '/g/about',
      name: 'global-about',
      meta: { global: true },
      component: () => import('@/views/AboutView.vue'),
    },
    {
      path: '/g/changelog',
      name: 'global-changelog',
      meta: { global: true },
      component: () => import('@/views/ChangelogView.vue'),
    },
    // 同批次其他 Agent 创建的视图：一律用 import.meta.glob 懒加载动态 import。
    // 文件暂不存在时 loader 为 undefined（渲染空内容），落地后自动生效。
    {
      path: '/g/cases/:id',                 // @/views/global/GlobalCaseDetailView.vue
      name: 'global-case-detail',
      meta: { global: true },
      component: optionalView('../views/global/GlobalCaseDetailView.vue'),
    },
    {
      path: '/g/assets/:id',                // @/views/global/GlobalAssetDetailView.vue
      name: 'global-asset-detail',
      meta: { global: true },
      component: optionalView('../views/global/GlobalAssetDetailView.vue'),
    },
    {
      path: '/assets/:id',                  // @/views/AssetDetailView.vue
      name: 'asset-detail',
      component: optionalView('../views/AssetDetailView.vue'),
    },
    {
      path: '/g/templates/:id',             // @/views/global/TemplateWorkbenchView.vue
      name: 'global-template-workbench',
      meta: { global: true },
      component: optionalView('../views/global/TemplateWorkbenchView.vue'),
    },
    {
      path: '/scenarios',                   // @/views/ScenariosView.vue
      name: 'scenarios',
      component: optionalView('../views/ScenariosView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
})

// 未登录跳 /login；已登录访问 /login 直接回落地页（token 只存 localStorage，服务端仍会校验）
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.authenticated && to.path === '/login') return '/workbench'
    return true
  }
  if (!auth.authenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // NAV-01 / FR-v2.0-08：能力守卫只管「给不给入口」，真正的资源级越权仍由后端 403 兜底。
  // 未知角色按最小权限处理；未加载 user 时先放行，让后端判（避免刷新瞬间误踢到 403）。
  const capability = to.meta.capability as string | undefined
  if (capability && auth.user) {
    const owned = capabilitiesForRole(roleFromCodes(auth.user.roles))
    if (!owned.includes(capability)) return { path: '/403' }
  }
  // v4.9.2 / OPT-01：模式保持与角色准入是两条独立判据（见文件头部纯函数）。
  const uiMode = useUiModeStore()
  if (shouldPreserveGlobalMode(to)) {
    // 角色准入只判角色；未加载 user 时先放行，后端会 403（FR-v4.5-06）。
    if (auth.user) {
      const roles = auth.user.roles ?? []
      if (!roles.some((role) => canEnterGlobalMode(role))) return { path: '/403' }
    }
    if (uiMode.mode !== 'global') void uiMode.setMode('global')
    // 全局路由一律早返回：不调用 rememberProjectPath、不切回 project 模式。
    return true
  }
  if (!to.meta.public) {
    uiMode.rememberProjectPath(to.fullPath)
    if (uiMode.mode === 'global' && !to.path.startsWith('/g') && uiMode.ready) {
      void uiMode.setMode('project')
    }
  }
  return true
})

// UX-04 步骤 7：设计系统页只在 DEV 挂载（生产不挂载）。import.meta.env.DEV 在生产构建
// 被静态折叠；`check_bundle_budget.py` 另在产物里 grep __STYLEGUIDE_DEV_ONLY__ 兜底，
// 防止某次构建配置变化把整页打进生产包。
if (import.meta.env.DEV) {
  router.addRoute({
    path: '/dev/styleguide',
    name: 'styleguide',
    component: () => import('@/views/StyleguideView.vue'),
    meta: { public: true },
  })
  router.addRoute({
    path: '/dev/grid-bench',
    name: 'gridbench',
    component: () => import('@/views/GridBenchView.vue'),
    meta: { public: true },
  })
}

/**
 * 首屏稳定后空闲预取高频路由（FR-3.2-06「骨架与预取」）。
 *
 * <p>只调用路由记录里已经声明的动态 import 函数，不改变路由行为；ESM 缓存保证
 * 真正导航时不会二次下载。默认只预取工作台 / 用例 / 运行三页：这三页是登录后
 * 最常去的入口，其余页面继续按需加载，避免抢首屏带宽。
 *
 * <p>测试环境不触发：单测挂载 App 时不应真的去加载整棵视图树。
 */
const DEFAULT_PREFETCH = ['/workbench', '/cases', '/runs'] as const

export function prefetchRouteViews(paths: readonly string[] = DEFAULT_PREFETCH): void {
  if (import.meta.env.MODE === 'test') return
  for (const path of paths) {
    const resolved = router.resolve(path)
    const record = resolved.matched[resolved.matched.length - 1]
    const loader = record?.components?.default as unknown
    if (typeof loader === 'function') {
      // 预取失败不影响导航；导航时仍会重新 import 并由路由错误边界处理
      ;(loader as () => Promise<unknown>)().catch(() => undefined)
    }
  }
}

export default router
