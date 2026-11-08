import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { capabilitiesForRole, roleFromCodes } from '@/config/navigation'

// 6 个一级菜单（总览 / 资产 / 用例 / 执行 / 报告 / 管理）
// history 模式要求服务端回退到 index.html，否则刷新 404（TC-v0.1-041）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
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
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
})

// 未登录跳 /login；已登录访问 /login 直接回总览（token 只存 localStorage，服务端仍会校验）
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.authenticated && to.path === '/login') return '/overview'
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
}

export default router
