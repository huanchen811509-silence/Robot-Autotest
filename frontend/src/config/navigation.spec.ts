/**
 * 两级菜单的结构与选中判定（#81 UX-04c）。
 *
 * 这些断言保护的是「菜单与路由不脱钩」这件事：菜单是纯配置，一旦某个叶子路径写错
 * 或者被路由删掉，界面上点进去就是 404，构建期不会报错。所以这里既测判定逻辑，
 * 也把每个叶子拿去路由表里解析一遍。
 */
import { describe, expect, it } from 'vitest'
import router from '@/router'
import {
  NAVIGATION,
  GLOBAL_NAVIGATION,
  BUSINESS_DOMAIN_LABELS,
  hasBusinessDomainAsTopLevel,
  secondLevelEntries,
  activeGroupKey,
  filterNav,
  filterNavByCapabilities,
  isGroup,
  isGroupActive,
  isSectionBreak,
  isLeafActive,
  isNodeActive,
  leafTarget,
  navKeysForRole,
  roleFromCodes,
  visibleNav,
  visibleNavByCapabilities,
  capabilitiesForRole,
  navTreeFor,
  PYTEST_SUITE_NAVIGATION,
  type NavGroup,
  type NavLeaf,
  type NavNode,
} from '@/config/navigation'

function leaves(): NavLeaf[] {
  return NAVIGATION.flatMap((node) => (isGroup(node) ? node.children : [node]))
}

function groups(): NavGroup[] {
  return NAVIGATION.filter(isGroup) as NavGroup[]
}

describe('navigation 结构', () => {
  it('每个叶子的 path 都能被路由表解析到真实页面（菜单不指向 404）', () => {
    for (const leaf of leaves()) {
      const resolved = router.resolve(leafTarget(leaf))
      const matchedName = resolved.matched.at(-1)?.name
      expect(matchedName, `叶子 ${leaf.key} 的 ${leaf.path} 没有命中路由`).toBeTruthy()
      expect(matchedName).not.toBe('not-found')
    }
  })

  it('分组至少两个子项——不允许出现只有一个子项的折叠组', () => {
    for (const group of groups()) {
      expect(group.children.length, `分组 ${group.key} 子项过少`).toBeGreaterThanOrEqual(2)
    }
  })

  it('叶子 key 全局唯一', () => {
    const keys = leaves().map((leaf) => leaf.key)
    expect(new Set(keys).size).toBe(keys.length)
  })
})

describe('权限过滤', () => {
  // v4.11.0 / FR-v4.11-08：项目侧「系统设置」收敛为 2 项（用户管理 + 关于），
  // 平台级 6 项迁到全局侧栏；两模式不再 diff = 0（R-50-06）。
  it('项目侧「系统设置」恰 2 项，且都是 public（不随角色增减）', () => {
    const asEngineer = visibleNav(false).find((node) => node.key === 'settings') as NavGroup
    const asAdmin = visibleNav(true).find((node) => node.key === 'settings') as NavGroup
    expect(asEngineer.children.map((child) => child.key)).toEqual(['settings-me', 'settings-about'])
    expect(asAdmin.children.map((child) => child.key)).toEqual(['settings-me', 'settings-about'])
    expect(asEngineer.children.every((child) => child.capability === 'public')).toBe(true)
  })

  it('AI 助手配置是管理员专属能力（ai:config），只存在于全局「系统设置」组', () => {
    const globalKeysFor = (role: Parameters<typeof capabilitiesForRole>[0]) =>
      filterNavByCapabilities(GLOBAL_NAVIGATION, capabilitiesForRole(role)).flatMap((node) =>
        isGroup(node) ? node.children.map((child) => child.key) : [node.key],
      )
    expect(globalKeysFor('admin')).toContain('settings-ai')
    expect(globalKeysFor('pm')).not.toContain('settings-ai')
    expect(globalKeysFor('engineer')).not.toContain('settings-ai')
    // 项目树里不再有该入口（v4.11.0 迁移）
    const projectKeys = NAVIGATION.flatMap((node) =>
      isGroup(node) ? node.children.map((child) => child.key) : [node.key],
    )
    expect(projectKeys).not.toContain('settings-ai')
  })

  it('过滤后子项归零的分组整组去掉，不留空折叠组', () => {
    const onlyAudit: NavGroup = {
      key: 'tmp',
      labelKey: 'tmp',
      icon: 'admin',
      children: [
        {
          key: 'tmp-audit',
          labelKey: 'audit.title',
          icon: 'audit',
          path: '/admin/audit',
          adminOnly: true,
        },
      ],
    }
    expect(filterNav([onlyAudit], false)).toHaveLength(0)
    expect(filterNav([onlyAudit], true)).toHaveLength(1)
  })
})

describe('v2.0 六域 IA 与能力驱动过滤（NAV-01 / FR-v2.0-07、08）', () => {
  it('v2.3 / FR-UI-01：一级恰好 7 组，顺序按「日常闭环 → 支撑域」，层级不超过 2', () => {
    expect(NAVIGATION).toHaveLength(7)
    expect(NAVIGATION.map((node) => node.key)).toEqual([
      'workbench', 'execution', 'cases', 'assets', 'environment', 'maintenance', 'settings',
    ])
    for (const node of NAVIGATION) {
      if (isGroup(node)) {
        for (const child of node.children) expect('children' in child).toBe(false)
      }
    }
  })

  it('v4.11.0：项目「系统设置」只留用户管理 + 关于，平台设置迁到全局', () => {
    const settings = NAVIGATION.find((node) => node.key === 'settings') as NavGroup
    expect(isGroup(settings)).toBe(true)
    expect(settings.children.map((child) => child.path)).toEqual(['/settings/users', '/about'])
    // 平台设置页仍在（路由不删，只不在项目侧栏），但不能出现在项目菜单里
    expect(settings.children.some((child) => child.path === '/settings')).toBe(false)
    expect(NAVIGATION.some((node) => node.key === 'admin')).toBe(false)
  })

  it('四角色菜单快照：权限只减不增，未知角色按最小权限', () => {
    const viewer = navKeysForRole('viewer')
    const pm = navKeysForRole('pm')
    const engineer = navKeysForRole('engineer')
    const admin = navKeysForRole('admin')

    expect(viewer).not.toContain('execution-runs')  // execution:read
    expect(viewer).not.toContain('settings-audit')    // audit:read
    expect(engineer).toContain('execution-runs')
    // v4.11.0：项目侧只留 public 的两项，工程/观察者也能进
    expect(viewer).toContain('settings-me')
    expect(engineer).toContain('settings-me')
    expect(pm).toContain('settings-me')
    // 平台设置 / 审计 / AI 已不在项目树，任何角色都不该出现
    for (const role of [viewer, pm, engineer, admin]) {
      expect(role).not.toContain('settings-platform')
      expect(role).not.toContain('settings-audit')
    }
    expect(admin).toContain('settings-me')
    expect(admin.length).toBeGreaterThanOrEqual(pm.length)
    expect(roleFromCodes(['something-new'])).toBe('viewer')
    expect(roleFromCodes(null)).toBe('viewer')
  })

  it('能力为空时只留无门槛入口，审计与设置都不出现', () => {
    const keys = visibleNavByCapabilities([]).flatMap((node) =>
      isGroup(node) ? [node.key, ...node.children.map((child) => child.key)] : [node.key],
    )
    expect(keys).toContain('workbench-board')
    expect(keys).not.toContain('settings-audit')
    expect(keys).not.toContain('settings-platform')
    expect(keys).not.toContain('execution-runs')
  })
})

describe('选中判定', () => {
  const assets = groups().find((group) => group.key === 'assets') as NavGroup

  it('资产三子项按 ?tab= 精确区分，且恰好一个选中', () => {
    for (const tab of ['devices', 'links', 'credentials']) {
      const route = { path: '/assets', query: { tab } }
      const active = assets.children.filter((child) => isLeafActive(child, route))
      expect(active).toHaveLength(1)
      expect(active[0].key).toBe(`assets-${tab}`)
    }
  })

  it('裸 /assets（无 tab）不点亮任何子项，由视图补默认页签后恢复', () => {
    const route = { path: '/assets', query: {} }
    expect(assets.children.some((child) => isLeafActive(child, route))).toBe(false)
  })

  const execution = groups().find((group) => group.key === 'execution') as NavGroup
  const leafOf = (key: string): NavLeaf =>
    execution.children.find((child) => child.key === key) as NavLeaf

  it('报告详情仍点亮「报告列表」（列表 / 详情 / 打印三处同属一个入口）', () => {
    expect(isLeafActive(leafOf('execution-reports'), { path: '/reports/RUN-001/print' })).toBe(true)
    expect(isLeafActive(leafOf('execution-reports'), { path: '/reports/RUN-001' })).toBe(true)
    expect(isLeafActive(leafOf('execution-reports'), { path: '/reports' })).toBe(true)
  })

  it('前缀匹配落在路径分段边界：/reportsX 不误点亮', () => {
    expect(isLeafActive(leafOf('execution-reports'), { path: '/reportsX' })).toBe(false)
  })

  it('报告模板独立成项：点模板时「报告列表」不再跟着亮（exclude 生效）', () => {
    const list = leafOf('execution-reports')
    const templates = leafOf('execution-report-templates')
    expect(isLeafActive(templates, { path: '/reports/templates' })).toBe(true)
    expect(isLeafActive(list, { path: '/reports/templates' })).toBe(false)
    // 排除只作用于模板前缀，详情页仍归列表
    expect(isLeafActive(list, { path: '/reports/RUN-9' })).toBe(true)
  })

  it('编辑器与只读查看器点亮「用例库」并展开「用例」分组', () => {
    const casesGroup = groups().find((group) => group.key === 'cases') as NavGroup
    const casesList = casesGroup.children.find((child) => child.key === 'cases-list') as NavLeaf
    expect(isLeafActive(casesList, { path: '/editor/12' })).toBe(true)
    expect(isLeafActive(casesList, { path: '/viewer/12' })).toBe(true)
    expect(isGroupActive(casesGroup, { path: '/editor/12' })).toBe(true)
  })

  it('分组在有子项选中时算选中，并成为自动展开目标', () => {
    const route = { path: '/plans' }
    expect(isGroupActive(groups().find((g) => g.key === 'execution')!, route)).toBe(true)
    expect(activeGroupKey(route)).toBe('execution')
    expect(isNodeActive(NAVIGATION[0], route)).toBe(false)
  })
})

/* --------------------------------------------------------------------------
 * v2.3 / FR-UI-01..08、26..30：7 组 IA、执行与报告合并、命名唯一性
 * -------------------------------------------------------------------------- */
describe('v2.3 菜单 IA 与执行/报告合并', () => {
  const byKey = (key: string): NavNode => NAVIGATION.find((node) => node.key === key) as NavNode

  it('工作台是分组，且「项目总览 / 工作区 / 项目看板 / 新建项目」路径都命中真实页面', () => {
    const workbench = byKey('workbench')
    expect(isGroup(workbench)).toBe(true)
    const group = workbench as NavGroup
    expect(group.children.map((child) => child.path)).toEqual([
      '/workbench',
      '/workspace',
      '/overview',
      '/projects/new',
    ])
    for (const child of group.children) {
      const name = router.resolve(leafTarget(child)).matched.at(-1)?.name
      expect(name, `${child.path} 没有命中路由`).toBeTruthy()
      expect(name).not.toBe('not-found')
    }
  })

  it('v4.3.0 / FR-v4.3-01：新建项目是独立叶子，按 project:create 授予 ADMIN + PM', () => {
    const workbench = byKey('workbench') as NavGroup
    const create = workbench.children.find((child) => child.key === 'workbench-create') as NavLeaf
    expect(create).toBeTruthy()
    expect(create.path).toBe('/projects/new')
    expect(create.capability).toBe('project:create')
    // 原三个子项的路径与顺序不被新叶子打乱（边界要求）
    expect(workbench.children.slice(0, 3).map((child) => child.path)).toEqual([
      '/workbench', '/workspace', '/overview',
    ])
    const keysOf = (role: Parameters<typeof navKeysForRole>[0]) => navKeysForRole(role)
    expect(keysOf('admin')).toContain('workbench-create')
    expect(keysOf('pm')).toContain('workbench-create')
    expect(keysOf('engineer')).not.toContain('workbench-create')
    expect(keysOf('viewer')).not.toContain('workbench-create')
  })

  it('v4.3.0 / FR-v4.3-07：环境组新增「连接与拓扑」，四角色均可见', () => {
    const environment = byKey('environment') as NavGroup
    const leaf = environment.children.find((child) => child.key === 'environment-connections')
    expect(leaf?.path).toBe('/environment/connections')
    expect(leaf?.labelKey).toBe('menu.envConnections')
    for (const role of ['admin', 'pm', 'engineer', 'viewer'] as const) {
      expect(navKeysForRole(role)).toContain('environment-connections')
    }
  })

  it('「工作台」与「项目看板」是两件事：词条键不同，且各自只被该用的地方引用', () => {
    const workbench = byKey('workbench') as NavGroup
    const board = workbench.children.find((child) => child.key === 'workbench-board') as NavLeaf
    expect(workbench.labelKey).toBe('menu.workspace')
    expect(board.labelKey).toBe('menu.overview')
    // 侧栏一级叫「工作台」，/overview 页头叫「项目看板」——两键不能相等，
    // 否则又回到 v1.0 那个「同一个页面两处叫两个名字」的缺陷。
    expect(workbench.labelKey).not.toBe(board.labelKey)
  })

  it('执行与报告合并：组内 8 项顺序固定，且一级不再有 reports', () => {
    const execution = byKey('execution') as NavGroup
    // v4.11.0 / FR-v4.11-12：首位插入「执行计划」（即时发起台），cron 计划顺延到第 3。
    // v5.2.0 / W52-08：`/kernels` 补入口，落在「执行节点」之后、报告之前（运行态末尾）。
    expect(execution.children.map((child) => child.path)).toEqual([
      '/execution-plans', '/runs', '/plans', '/nodes', '/kernels',
      '/reports', '/reports/templates', '/test-reports',
    ])
    expect(NAVIGATION.some((node) => node.key === 'reports')).toBe(false)
  })

  it('合并组不带组级 capability，能力只设在 5 个运行态子项上（否则 viewer 会丢报告入口）', () => {
    const execution = byKey('execution') as NavGroup
    expect(execution.capability).toBeUndefined()
    const guarded = execution.children.filter((child) => child.capability === 'execution:read')
    expect(guarded.map((child) => child.key)).toEqual([
      'execution-launch', 'execution-runs', 'execution-plans', 'execution-nodes',
      'execution-kernels',
    ])
    const reportItems = execution.children.filter((child) => child.section === 'report')
    expect(reportItems.every((child) => child.capability === undefined)).toBe(true)
  })

  it('角色矩阵：viewer 仍见 3 个报告项、不见 4 个运行项；engineer 7 项全见（V-UI-11）', () => {
    const keysOf = (role: Parameters<typeof navKeysForRole>[0]): string[] => navKeysForRole(role)
    const viewer = keysOf('viewer')
    expect(viewer).toEqual(expect.arrayContaining([
      'execution-reports', 'execution-report-templates', 'execution-external-reports',
    ]))
    expect(viewer).not.toContain('execution-launch')
    expect(viewer).not.toContain('execution-runs')
    expect(viewer).not.toContain('execution-plans')
    expect(viewer).not.toContain('execution-nodes')

    const engineer = keysOf('engineer')
    expect(engineer).toEqual(expect.arrayContaining([
      'execution-launch', 'execution-runs', 'execution-plans', 'execution-nodes',
      'execution-reports', 'execution-report-templates', 'execution-external-reports',
    ]))
  })

  it('/test-reports 有且只有一个菜单入口（消除孤儿页）', () => {
    const hits = leaves().filter((leaf) => leaf.path === '/test-reports')
    expect(hits).toHaveLength(1)
  })

  it('/nodes 有且只有一个菜单入口（设置页里那个是跳转链接，不是第二个渲染）', () => {
    const hits = leaves().filter((leaf) => leaf.path === '/nodes')
    expect(hits).toHaveLength(1)
  })

  it('7 个一级项图标两两不同', () => {
    const icons = NAVIGATION.map((node) => node.icon)
    expect(new Set(icons).size).toBe(icons.length)
    expect(icons).toHaveLength(7)
  })

  it('section 只影响渲染分组，不影响选中判定（同一 leaf 带/不带 section 结果相同）', () => {
    const execution = byKey('execution') as NavGroup
    const withSection = execution.children.find((c) => c.key === 'execution-reports') as NavLeaf
    const without: NavLeaf = { ...withSection }
    delete without.section
    for (const path of ['/reports', '/reports/RUN-1', '/reports/templates', '/x']) {
      expect(isLeafActive(withSection, { path })).toBe(isLeafActive(without, { path }))
    }
  })

  it('合并组内恰好 1 条分区线：前五项 run、后三项 report（FR-UI-29）', () => {
    const execution = byKey('execution') as NavGroup
    expect(execution.children.map((child) => child.section)).toEqual([
      'run', 'run', 'run', 'run', 'run', 'report', 'report', 'report',
    ])
    const breaks = execution.children
      .map((_, index) => isSectionBreak(execution.children, index))
      .filter(Boolean)
    expect(breaks).toHaveLength(1)
    expect(isSectionBreak(execution.children, 5)).toBe(true)
    expect(isSectionBreak(execution.children, 0)).toBe(false)
  })

  it('四处改名落地：用户管理 / 维护原则 / 环境概览 / 独立测试计划词条', () => {
    const settings = byKey('settings') as NavGroup
    // v4.11.0：项目侧「用户管理」是新的只读页（仅当前用户和关联项目），词条也换了
    expect(settings.children.find((c) => c.key === 'settings-me')?.labelKey)
      .toBe('menu.settingsUsersMe')
    const maintenance = byKey('maintenance') as NavGroup
    expect(maintenance.children.find((c) => c.key === 'maintenance-principles')?.labelKey)
      .toBe('menu.mntPrinciples')
    const environment = byKey('environment') as NavGroup
    expect(environment.children.find((c) => c.key === 'environment-overview')?.labelKey)
      .toBe('menu.envOverview')
    const execution = byKey('execution') as NavGroup
    const plans = execution.children.find((c) => c.key === 'execution-plans') as NavLeaf
    expect(plans.labelKey).toBe('menu.runsPlans')
    // 新增的「执行计划」用独立词条，不借用 cron 计划的名
    const launch = execution.children.find((c) => c.key === 'execution-launch') as NavLeaf
    expect(launch.labelKey).toBe('menu.executionPlans')
    expect(launch.path).toBe('/execution-plans')
  })
})

/* --------------------------------------------------------------------------
 * v2.2 / MENU-01（FR-v2.2-01、06）：业务域不建一级菜单（v2.3 后一级为 7 组）
 * -------------------------------------------------------------------------- */
describe('v2.2 / MENU-01 八组菜单与业务域隔离', () => {
  it('一级分组数 = 7', () => {
    expect(NAVIGATION).toHaveLength(7)
  })

  it('7 个一级项全部是分组，且各至少 2 个二级入口（不再有叶子冒充分组）', () => {
    expect(NAVIGATION).toHaveLength(7)
    for (const node of NAVIGATION) {
      expect(isGroup(node), `一级项 ${node.key} 不是分组`).toBe(true)
      expect(secondLevelEntries(node).length, `一级项 ${node.key} 子项过少`)
        .toBeGreaterThanOrEqual(2)
    }
  })

  it('6 个业务能力域名不出现在任何一级项（key / labelKey 字面量）', () => {
    expect(hasBusinessDomainAsTopLevel(NAVIGATION)).toBe(false)
    for (const domain of BUSINESS_DOMAIN_LABELS) {
      const hit = NAVIGATION.find((node) =>
        `${node.key} ${node.labelKey}`.toLowerCase().includes(domain.toLowerCase()),
      )
      expect(hit, `业务域 ${domain} 不应作为一级项`).toBeUndefined()
    }
  })

  it('反例：含「RDMA」一级项的假配置，机检应判失败', () => {
    const fakeLeaf: NavLeaf = {
      key: 'fake-rdma-domain',
      labelKey: 'RDMA',
      icon: 'boxes',
      path: '/rdma',
    }
    const fake: NavNode[] = [...NAVIGATION, fakeLeaf]
    expect(fake).toHaveLength(NAVIGATION.length + 1)
    expect(hasBusinessDomainAsTopLevel(fake)).toBe(true)
    // 同一谓词对真实配置判通过，证明它确实在区分两种配置，而非恒真 / 恒假
    expect(hasBusinessDomainAsTopLevel(NAVIGATION)).toBe(false)
  })
})

/* --------------------------------------------------------------------------
 * v4.11.0 / FR-v4.11-01、02、05、25：全局两级菜单
 * -------------------------------------------------------------------------- */
describe('v4.11.0 全局菜单两级化', () => {
  const globalGroups = () => GLOBAL_NAVIGATION.filter(isGroup) as NavGroup[]
  const globalLeaves = (): NavLeaf[] =>
    GLOBAL_NAVIGATION.flatMap((node) => (isGroup(node) ? node.children : [node]))

  it('5 个一级组、14 个叶子，每组至少 2 项（PRD §4.2）', () => {
    expect(GLOBAL_NAVIGATION).toHaveLength(5)
    expect(globalGroups().map((group) => group.key)).toEqual([
      'global-projects', 'global-cases', 'global-assets', 'global-templates', 'global-settings',
    ])
    // 2 + 2 + 2 + 2 + 6
    expect(globalGroups().map((group) => group.children.length)).toEqual([2, 2, 2, 2, 6])
    expect(globalLeaves()).toHaveLength(14)
    for (const group of globalGroups()) {
      expect(group.children.length, `全局组 ${group.key} 退化成单叶`).toBeGreaterThanOrEqual(2)
    }
  })

  it('全局一级项不混入业务能力域名（与项目树同一约束）', () => {
    expect(hasBusinessDomainAsTopLevel(GLOBAL_NAVIGATION)).toBe(false)
  })

  it('同 path 的叶子其 query 两两不同，且每组只有一个「无 query 的默认项」或一个默认值', () => {
    const byPath = new Map<string, NavLeaf[]>()
    for (const leaf of globalLeaves()) {
      const bucket = byPath.get(leaf.path) ?? []
      bucket.push(leaf)
      byPath.set(leaf.path, bucket)
    }
    for (const [path, bucket] of byPath) {
      if (bucket.length < 2) continue
      const shape = bucket.map((leaf) => JSON.stringify(leaf.query ?? {}))
      expect(new Set(shape).size, `${path} 下两个叶子 query 相同`).toBe(shape.length)
      // 每组必须有且只有一个「默认态」：无 query 的第一项，或带 query 但只有一个候选
      const withoutQuery = bucket.filter((leaf) => !leaf.query)
      expect(withoutQuery.length, `${path} 无法确定默认叶子`).toBeLessThanOrEqual(1)
    }
  })

  it('全局「系统设置」组 6 项顺序与 SETTINGS_NAV 逐项一致', () => {
    const settings = globalGroups().find((group) => group.key === 'global-settings') as NavGroup
    expect(settings.children.map((child) => child.key)).toEqual([
      'settings-platform', 'settings-ai', 'settings-users', 'settings-audit',
      'settings-about', 'settings-changelog',
    ])
    expect(settings.children.map((child) => child.path)).toEqual([
      '/g/settings', '/g/settings/ai', '/g/admin', '/g/admin/audit', '/g/about', '/g/changelog',
    ])
  })

  it('activeGroupKey 按传入的树判定：全局树认 global-settings，项目树认 execution', () => {
    expect(activeGroupKey({ path: '/g/settings/ai' }, GLOBAL_NAVIGATION)).toBe('global-settings')
    // 同一路由在项目树里不属于任何组 —— 证明入参真的生效，而不是仍读 NAVIGATION
    expect(activeGroupKey({ path: '/g/settings/ai' }, NAVIGATION)).toBeNull()
    expect(activeGroupKey({ path: '/plans/graph' }, NAVIGATION)).toBe('execution')
    // 默认参数保持旧行为
    expect(activeGroupKey({ path: '/plans/graph' })).toBe('execution')
  })

  it('全局「我参与的」与「全部项目」是同一 path 的两个 query，互不点亮', () => {
    const projects = globalGroups().find((group) => group.key === 'global-projects') as NavGroup
    const all = projects.children[0] as NavLeaf
    const mine = projects.children[1] as NavLeaf
    expect(isLeafActive(all, { path: '/g/projects' })).toBe(true)
    expect(isLeafActive(mine, { path: '/g/projects' })).toBe(false)
    expect(isLeafActive(mine, { path: '/g/projects', query: { scope: 'mine' } })).toBe(true)
    expect(isLeafActive(all, { path: '/g/projects', query: { scope: 'mine' } })).toBe(true)
  })

  it('低权限账号过滤后：全局「系统设置」只剩 public 两项，组仍保留', () => {
    const onlyPublic = filterNavByCapabilities(GLOBAL_NAVIGATION, ['public'])
    const settings = onlyPublic.find((node) => node.key === 'global-settings') as NavGroup
    expect(settings).toBeTruthy()
    expect(settings.children.map((child) => child.key)).toEqual(['settings-about', 'settings-changelog'])
    // 4 个内容组不含 capability，public 账号仍可见
    expect(onlyPublic.map((node) => node.key)).toEqual([
      'global-projects', 'global-cases', 'global-assets', 'global-templates', 'global-settings',
    ])
  })

  it('能力过滤后为空的组整组隐藏（不留空折叠组）', () => {
    const noCaps = filterNavByCapabilities(GLOBAL_NAVIGATION, [])
    const settings = noCaps.find((node) => node.key === 'global-settings') as NavGroup
    // about / changelog 是 public，永远保留；其余 4 项被过滤
    expect(settings.children).toHaveLength(2)
  })
})

describe('navTreeFor：按工程类型选树（v5.2.0 / W52-08）', () => {
  function pytestLeaves(): NavLeaf[] {
    return PYTEST_SUITE_NAVIGATION.flatMap((node) => (isGroup(node) ? node.children : [node]))
  }

  it('全局模式一律用全局树，与工程类型无关', () => {
    expect(navTreeFor('global', 'pytest-suite')).toBe(GLOBAL_NAVIGATION)
    expect(navTreeFor('global', 'standard')).toBe(GLOBAL_NAVIGATION)
    expect(navTreeFor('global', undefined)).toBe(GLOBAL_NAVIGATION)
  })

  it('项目模式下 pytest-suite 用专用树，其余用标准树', () => {
    expect(navTreeFor('project', 'pytest-suite')).toBe(PYTEST_SUITE_NAVIGATION)
    expect(navTreeFor('project', 'standard')).toBe(NAVIGATION)
    // 未知/缺省按标准工程：宁可多显示入口，不要少显示
    expect(navTreeFor('project', null)).toBe(NAVIGATION)
    expect(navTreeFor('project', 'whatever')).toBe(NAVIGATION)
  })

  it('pytest 树不出现资产/环境/维护/节点/计划入口', () => {
    const paths = pytestLeaves().map((leaf) => leaf.path)
    const forbidden = ['/assets', '/environment', '/maintenance', '/nodes', '/plans', '/sync']
    for (const prefix of forbidden) {
      const hit = paths.some((path) => path === prefix || path.startsWith(prefix + '/'))
      expect(hit, '不该出现 ' + prefix).toBe(false)
    }
  })

  it('pytest 树的每个叶子都能被路由表解析到真实页面', () => {
    for (const leaf of pytestLeaves()) {
      const resolved = router.resolve(leafTarget(leaf))
      const name = resolved.matched.at(-1)?.name
      expect(name, 'pytest 叶子 ' + leaf.key + ' 的 ' + leaf.path + ' 没有命中路由').toBeTruthy()
      expect(name).not.toBe('not-found')
    }
  })

  it('pytest 树每个分组至少两个子项', () => {
    for (const node of PYTEST_SUITE_NAVIGATION) {
      if (isGroup(node)) {
        expect(node.children.length, '分组 ' + node.key + ' 子项过少').toBeGreaterThanOrEqual(2)
      }
    }
  })

  it('两棵树的叶子集合不相同——差异化不是改个顺序', () => {
    const projectPaths = new Set(leaves().map((leaf) => leaf.path))
    const pytestPaths = new Set(pytestLeaves().map((leaf) => leaf.path))
    expect(pytestPaths.size).toBeGreaterThan(0)
    expect([...pytestPaths].sort()).not.toEqual([...projectPaths].sort())
    // 除 pytest 专用页外，其余路径必须来自标准树（不另造页面）
    const pytestOnly = new Set(['/pytest-workbench'])
    for (const path of pytestPaths) {
      if (pytestOnly.has(path)) continue
      expect(projectPaths.has(path), path + ' 不在标准树里，也未登记为 pytest 专用页').toBe(true)
    }
  })

  it('pytest 树里工作台落地页就是工程健康页', () => {
    const workbench = PYTEST_SUITE_NAVIGATION.find((node) => node.key === 'pytest-workbench')
    expect(workbench && isGroup(workbench)).toBe(true)
    const first = (workbench as NavGroup).children[0]
    expect(first.path).toBe('/pytest-workbench')
  })
})
