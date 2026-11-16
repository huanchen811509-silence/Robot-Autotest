import type { Component } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiModeStore } from '@/stores/uiMode'
import { useMenuCapabilitiesStore } from '@/stores/menuCapabilities'
import { capabilitySatisfied } from '@/config/navigation'

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
      meta: { public: true },
      component: () => import('@/views/WorkbenchView.vue'),
    },
    {
      // 项目工作区（FR-v4.1-05）：导出 / 预检 / 导入 / 任务中心；写权限由后端校验。
      path: '/workspace',
      name: 'workspace',
      meta: { capability: 'project:read' },
      component: () => import('@/views/WorkspaceView.vue'),
    },
    {
      // 存量同步（v4.9.0 / FR-v4.9-01..07）：预览 → 冲突处理 → 执行 → 回滚。
      // 项目范围由顶部项目选择器决定，写权限由后端 403 兜底。
      path: '/sync',
      name: 'project-sync',
      meta: { capability: 'settings:read' },
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
      meta: { capability: 'project:read' },
      component: () => import('@/views/OverviewView.vue'),
    },
    {
      path: '/assets',
      name: 'assets',
      meta: { capability: 'asset:read' },
      component: () => import('@/views/AssetsView.vue'),
    },
    {
      path: '/cases',
      name: 'cases',
      meta: { capability: 'case:read' },
      component: () => import('@/views/CasesView.vue'),
    },
    {
      // 用例集管理（CAS-02 前端部分）：仍属「用例」一级菜单的子页面，
      // 不新增一级菜单，入口在用例库工具栏与执行计划的用例集下拉旁。
      path: '/cases/sets',
      name: 'case-sets',
      meta: { capability: 'case:read' },
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
      meta: { capability: 'execution:read' },
      component: () => import('@/views/RunsView.vue'),
    },
    {
      // 执行计划（PLN-01 / PLN-02）：属于「执行」一级菜单下的子页面，
      // 不新增一级菜单，从运行列表与左侧「执行」进入。
      path: '/plans',
      name: 'plans',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/PlansView.vue'),
    },
    {
      // 计划组（ORCH-01，v2.1）：计划页的子页面，入口在计划页工具栏。
      // 独立页面而不是计划页里的弹层——组成员顺序是整页操作的场景，弹层放不下。
      path: '/plans/groups',
      name: 'plan-groups',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/PlanGroupsView.vue'),
    },
    {
      // 计划依赖图（ORCH-02，v2.1）：只读 SVG，不提供拖拽编辑——
      // 拖出来的边会绕过保存时的环检测。
      path: '/plans/graph',
      name: 'plan-graph',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/PlanGraphPage.vue'),
    },
    {
      // 失败聚类（ORCH-03，v2.1）：运行详情的下钻页，入口在运行列表的失败数。
      // 用 query 里的 runId 预填，深链可分享——贴一条链接就能让同事看到同一批失败。
      path: '/runs/failure-clusters',
      name: 'failure-clusters',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/FailureClustersView.vue'),
    },
    {
      path: '/reports',
      name: 'reports',
      meta: { capability: 'report:read' },
      component: () => import('@/views/ReportsView.vue'),
    },
    {
      // 报告模板（TPLX-01，v2.1）：报告页的子页面，入口在报告页工具栏。
      // 可选字段只来自后端白名单接口，页面不内联字段名单（见视图注释）。
      path: '/reports/templates',
      name: 'report-templates',
      meta: { capability: 'report:read' },
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
      meta: { capability: 'report:read' },
      component: () => import('@/views/ReportDetailView.vue'),
    },
    {
      path: '/test-reports',
      name: 'test-reports',
      meta: { capability: 'report:read' },
      component: () => import('@/views/TestReportsView.vue'),
    },
    {
      // v5.14.0 / W64-05：测试报告详情页（只读快照预览）。放在列表之后，
      // 精确路径 /test-reports 不会被 :id 抢走。
      path: '/test-reports/:id',
      name: 'test-report-detail',
      meta: { capability: 'report:read' },
      component: () => import('@/views/TestReportDetailView.vue'),
    },
    {
      path: '/admin',
      name: 'admin',
      meta: { capability: 'user:write' },
      component: () => import('@/views/AdminView.vue'),
    },
    {
      // 审计查询（HRD-02）：挂在「管理」菜单下的子页面，不新增第 7 个一级菜单；
      // 入口仅对管理员显示，非管理员即便手输 URL 也会被后端 403 拦截。
      path: '/admin/audit',
      name: 'audit',
      meta: { capability: 'audit:read' },
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
      meta: { capability: 'case:read' },
      component: () => import('@/views/EditorView.vue'),
    },
    {
      // 只读查看器：独立路由（FR-v0.3-18），不依赖编辑权限即可分享给只读用户
      path: '/viewer/:projectId',
      name: 'viewer',
      meta: { capability: 'case:read' },
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
      meta: { capability: 'execution:read' },
      component: () => import('@/views/NodesView.vue'),
    },
    { path: '/about', name: 'about', component: () => import('@/views/AboutView.vue') },
    {
      path: '/changelog',
      name: 'changelog',
      meta: { public: true },
      component: () => import('@/views/ChangelogView.vue'),
    },
    {
      // 无权限直达的落点（FR-v2.0-08）：不是 404，也不泄露目标页内容
      path: '/403',
      name: 'forbidden',
      meta: { public: true },
      component: () => import('@/views/ForbiddenView.vue'),
    },
    {
      // v2.0 / W4 导入预检：不新增一级菜单，由命令面板与 CasesView 入口进入
      path: '/imports',
      name: 'imports',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/ImportPreflightView.vue'),
    },
    {
      // v2.0 / W5 组授权与一层场景编排
      path: '/groups',
      name: 'groups',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/GroupsView.vue'),
    },
    {
      // v2.0 / W3 双内核能力矩阵与选择来源
      path: '/kernels',
      name: 'kernels',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/KernelsView.vue'),
    },
    {
      // v5.1.0 / FR-v5.1-12：pytest 工程工作台（健康 / 覆盖率 / 绑定 / 配置）。
      // 菜单入口在 5.2.0 随「工程视图」一起挂（W52-08），本期先由路由直达。
      path: '/pytest-workbench',
      name: 'pytest-workbench',
      meta: { capability: 'execution:read' },
      component: () => import('@/views/PytestWorkbenchView.vue'),
    },

    // EHT-10~12：工程视图页内入口（不新增一级/二级菜单叶子，4 组 11 叶基线不变）。
    {
      path: '/eht/cases',
      meta: { capability: 'case:read' },
      name: 'eht-case-library',
      component: () => import('@/views/eht/EhtCaseLibraryView.vue'),
    },
    {
      path: '/eht/quick-actions',
      meta: { capability: 'case:read' },
      name: 'eht-quick-actions',
      component: () => import('@/views/eht/EhtQuickActionsView.vue'),
    },
    {
      path: '/eht/duration-baseline',
      meta: { capability: 'case:read' },
      name: 'eht-duration-baseline',
      component: () => import('@/views/eht/EhtDurationBaselineView.vue'),
    },
    {
      path: '/eht/disposition',
      meta: { capability: 'case:read' },
      name: 'eht-disposition',
      component: () => import('@/views/eht/EhtDispositionView.vue'),
    },
    {
      path: '/eht/environment-radar',
      meta: { capability: 'env:read' },
      name: 'eht-environment-radar',
      component: () => import('@/views/eht/EhtEnvironmentRadarView.vue'),
    },
    {
      path: '/eht/kernel-unified',
      meta: { capability: 'case:read' },
      name: 'eht-kernel-unified',
      component: () => import('@/views/eht/EhtKernelUnifiedView.vue'),
    },
    {
      path: '/eht/manual-execution',
      meta: { capability: 'case:read' },
      name: 'eht-manual-execution',
      component: () => import('@/views/eht/EhtManualExecutionView.vue'),
    },
    {
      path: '/eht/result-split',
      meta: { capability: 'case:read' },
      name: 'eht-result-split',
      component: () => import('@/views/eht/EhtResultSplitView.vue'),
    },
    // ===== v2.2 / M2 只读页（MENU-02 / MENU-04 / MENU-05）=====
    // v5.11.0 / FR-v5.11-08：原 MENU-03「测试总表看板」(`/cases/board`) 已下架——
    // 该页 `rows` 恒为 `[]`（全文无赋值），是没有数据来源的空壳页；留着等于让用户
    // 点进一个永远「0 条」的页面，比没有入口更糟。
    {
      path: '/environment/overview',
      name: 'environment-overview',
      meta: { capability: 'env:read' },
      component: () => import('@/views/environment/EnvironmentOverviewView.vue'),
    },
    {
      path: '/environment/collect',
      name: 'environment-collect',
      meta: { capability: 'env:read' },
      component: () => import('@/views/environment/EnvironmentCollectView.vue'),
    },
    {
      path: '/environment/topology',
      name: 'environment-topology',
      meta: { capability: 'env:read' },
      component: () => import('@/views/environment/TopologySnapshotView.vue'),
    },
    {
      // v5.15.0 / W65-14：旧深链不删，收敛到 4 个主页面。
      path: '/environment/prepare',
      name: 'environment-prepare',
      meta: { public: true }, // 纯重定向：不需要能力判定
      redirect: '/environment/overview',
    },
    {
      path: '/environment/analysis',
      name: 'environment-analysis',
      meta: { public: true }, // 纯重定向：不需要能力判定
      redirect: '/environment/topology',
    },
    {
      // v4.3.0 / FR-v4.3-07：连接与拓扑（只读，从设备/端口/链路派生）。
      // 能力码只挡「有没有入口」，项目内可见范围与刷新权限仍由后端 403 兜底。
      path: '/environment/connections',
      name: 'environment-connections',
      component: () => import('@/views/environment/ConnectionsView.vue'),
      meta: { capability: 'asset:read' },
    },
    // v5.16.0 / FR-v5.16-08：维护三只读页并入运维四页，旧深链一律重定向，不留 404。
    // 内容没有删：原则并入「升级与演练」，恢复记录并入「归档与清理」，
    // 只读找回并入「备份与恢复」的「找历史数据」区块。
    { path: '/maintenance/principles', redirect: '/ops/upgrade' },
    { path: '/maintenance/records', redirect: '/ops/archive' },
    { path: '/maintenance/recover', redirect: '/ops/backup' },
    {
      path: '/ops/health',
      name: 'ops-health',
      meta: { capability: 'settings:read' },
      component: () => import('@/views/ops/OpsHealthView.vue'),
    },
    {
      path: '/ops/backup',
      name: 'ops-backup',
      meta: { capability: 'settings:read' },
      component: () => import('@/views/ops/OpsBackupView.vue'),
    },
    {
      path: '/ops/archive',
      name: 'ops-archive',
      meta: { capability: 'settings:read' },
      component: () => import('@/views/ops/OpsArchiveView.vue'),
    },
    {
      path: '/ops/upgrade',
      name: 'ops-upgrade',
      meta: { capability: 'settings:read' },
      component: () => import('@/views/ops/OpsUpgradeView.vue'),
    },
    {
      // MENU-05：性能基线 3 个只读页，独立路由（不新增一级菜单）。
      path: '/baselines/iperf',
      name: 'baseline-iperf',
      meta: { capability: 'report:read' },
      component: () => import('@/views/baselines/IperfBaselineView.vue'),
    },
    {
      path: '/baselines/perftest',
      name: 'baseline-perftest',
      meta: { capability: 'report:read' },
      component: () => import('@/views/baselines/PerftestBaselineView.vue'),
    },
    {
      path: '/baselines/ccl',
      name: 'baseline-ccl',
      meta: { capability: 'report:read' },
      component: () => import('@/views/baselines/CclBaselineView.vue'),
    },
    // v4.5.0 全局模式（FR-v4.5-03）：独立命名空间，避免与项目内页面权限混淆。
    { path: '/g', redirect: '/g/projects' },
    {
      path: '/g/projects',
      name: 'global-projects',
      meta: { global: true, capability: 'project:read' },
      component: () => import('@/views/global/GlobalProjectsView.vue'),
    },
    {
      // W57-09 / W57-12：全局「新建项目」独立路径，复用同一个 6 步向导；
      // 与项目树的 `/projects/new` 分开，避免两棵树同时点亮，并由 from=global 决定返回落点。
      path: '/g/projects/new',
      name: 'global-project-new',
      meta: { global: true, capability: 'project:create' },
      component: () => import('@/views/ProjectWizardView.vue'),
    },
    {
      path: '/g/cases',
      name: 'global-cases',
      meta: { global: true, capability: 'case:read' },
      component: () => import('@/views/global/GlobalCasesView.vue'),
    },
    {
      // W57-09：全局用例集列表。
      // v5.11.0 / FR-v5.11-08：成员详情改在页内 `DetailDock` 展开（`?detail=<id>`），
      // 原 `/g/case-sets/:id` 孤儿路由（全仓无 push / RouterLink 指向）已删除。
      path: '/g/case-sets',
      name: 'global-case-sets',
      meta: { global: true, capability: 'case:read' },
      component: () => import('@/views/global/GlobalCaseSetsView.vue'),
    },
    {
      path: '/g/assets',
      name: 'global-assets',
      meta: { global: true, capability: 'asset:read' },
      component: () => import('@/views/global/GlobalAssetsView.vue'),
    },
    {
      path: '/g/templates',
      name: 'global-templates',
      meta: { global: true, capability: 'asset:read' },
      component: () => import('@/views/global/GlobalTemplatesView.vue'),
    },
    {
      // v5.10.0 / FR-v5.10-05：新建模板向导。放在 `/g/templates/:id` 之前没有匹配优先级问题
      // （Vue Router 按静态段优先），但放在列表之后更贴近阅读顺序。
      path: '/g/templates/new',
      name: 'global-template-new',
      meta: { global: true, capability: 'asset:read' },
      component: () => import('@/views/global/TemplateWizardView.vue'),
    },
    {
      path: '/g/settings',
      name: 'global-settings',
      meta: { global: true, capability: 'settings:read' },
      // v4.12.0 / FR-v4.12-18~20：复用项目侧同一份设置视图，由 meta.global 切全局语义。
      // 原 GlobalSettingsView 只是把侧栏的 6 项再列一遍，属于「点进来还要再点一次」的中间页。
      component: () => import('@/views/SettingsView.vue'),
    },
    // v4.9.2 / OPT-01（FR-49-01~04）：全局系统设置的 6 个目标路由，复用现有设置视图。
    // 全部带 meta.global = true —— 点击后 ui_mode 保持 global，不会回 /workbench。
    {
      path: '/g/settings/ai',
      name: 'global-settings-ai',
      meta: { global: true, capability: 'ai:config' },
      component: () => import('@/views/AiAssistantsView.vue'),
    },
    {
      // v5.9.0 / FR-v5.9-04：用户管理迁入「用户与权限」一级菜单，旧入口只做重定向。
      // 不保留第二套写入口——两个页面都能改同一个用户，权限与审计迟早对不上。
      path: '/g/admin',
      redirect: '/g/iam/users',
    },
    {
      path: '/g/iam/users',
      name: 'global-iam-users',
      meta: { global: true, capability: 'user:write' },
      component: () => import('@/views/iam/IamUsersView.vue'),
    },
    {
      path: '/g/iam/groups',
      name: 'global-iam-groups',
      meta: { global: true, capability: 'user:write' },
      component: () => import('@/views/iam/IamGroupsView.vue'),
    },
    {
      path: '/g/iam/projects',
      name: 'global-iam-projects',
      meta: { global: true, capability: 'user:write' },
      component: () => import('@/views/iam/IamProjectLinksView.vue'),
    },
    {
      path: '/g/iam/roles',
      name: 'global-iam-roles',
      meta: { global: true, capability: 'user:write' },
      component: () => import('@/views/iam/IamRolesView.vue'),
    },
    {
      path: '/g/admin/audit',
      name: 'global-admin-audit',
      meta: { global: true, capability: 'audit:read' },
      component: () => import('@/views/AuditView.vue'),
    },
    {
      path: '/g/about',
      name: 'global-about',
      // v5.21.0 / W71-06：公开页与全局模式标记合并成一个 meta（两个 meta 是语法错误）
      meta: { global: true, public: true },
      component: () => import('@/views/AboutView.vue'),
    },
    {
      path: '/g/changelog',
      name: 'global-changelog',
      meta: { global: true, public: true },
      component: () => import('@/views/ChangelogView.vue'),
    },
    // 同批次其他 Agent 创建的视图：一律用 import.meta.glob 懒加载动态 import。
    // 文件暂不存在时 loader 为 undefined（渲染空内容），落地后自动生效。
    {
      path: '/g/assets/:id',                // @/views/global/GlobalAssetDetailView.vue
      name: 'global-asset-detail',
      meta: { global: true, capability: 'asset:read' },
      component: optionalView('../views/global/GlobalAssetDetailView.vue'),
    },
    {
      path: '/assets/:id',                  // @/views/AssetDetailView.vue
      name: 'asset-detail',
      meta: { capability: 'asset:read' },
      component: optionalView('../views/AssetDetailView.vue'),
    },
    {
      path: '/g/templates/:id',             // @/views/global/TemplateWorkbenchView.vue
      name: 'global-template-workbench',
      meta: { global: true, capability: 'asset:read' },
      component: optionalView('../views/global/TemplateWorkbenchView.vue'),
    },
    {
      path: '/scenarios',                   // @/views/ScenariosView.vue
      name: 'scenarios',
      meta: { capability: 'case:read' },
      component: optionalView('../views/ScenariosView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      meta: { public: true },
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
})

// 未登录跳 /login；已登录访问 /login 直接回落地页（token 只存 localStorage，服务端仍会校验）
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.authenticated && to.path === '/login') return '/workbench'
    return true
  }
  if (!auth.authenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // NAV-01 / FR-v2.0-08：能力守卫只管「给不给入口」，真正的资源级越权仍由后端 403 兜底。
  //
  // v5.21.0 / FR-v5.21-02 / W71-06：判据换成**服务端能力集**（stores/menuCapabilities）。
  // 旧实现用的是前端那张 `capabilitiesForRole(role)` 表——两套口径迟早分叉：
  // 后端加了 `asset:read` 的白名单，前端表里没有就永远 403；反之前端多给一个码，
  // 按钮看得见、接口全 403。现在两边同源：服务端下发什么，前端就按什么判。
  // 刷新瞬间能力集未加载 → 先 await 一次（不是"没加载就先放行"）。
  const capability = to.meta.capability as string | undefined
  if (capability) {
    // 刷新 / 直接输 URL 时 `auth.user` 还没回来。旧写法 `if (capability && auth.user)`
    // 等于"角色未知就放行"——viewer 手输 /execution-plans 能进去（v5.21 实测复现）。
    // 这里先补一次 loadMe()，再判；401 由 http 层统一踢回登录页。
    if (!auth.user) {
      try {
        await auth.loadMe()
      } catch {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
    const caps = useMenuCapabilitiesStore()
    await caps.ensureLoaded()
    if (!capabilitySatisfied(capability, (name) => caps.has(name))) {
      // v5.21.0：带上被拦路径，403 页能说出「你被拦在哪」
      return { path: '/403', query: { from: to.fullPath } }
    }
  }
  // v4.9.2 / OPT-01：模式保持与角色准入是两条独立判据（见文件头部纯函数）。
  const uiMode = useUiModeStore()
  if (shouldPreserveGlobalMode(to)) {
    // 角色准入只判角色；未加载 user 时先放行，后端会 403（FR-v4.5-06）。
    if (auth.user) {
      const roles = auth.user.roles ?? []
      if (!roles.some((role) => canEnterGlobalMode(role))) return { path: '/403', query: { from: to.fullPath } }
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
