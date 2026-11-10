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
  BUSINESS_DOMAIN_LABELS,
  hasBusinessDomainAsTopLevel,
  secondLevelEntries,
  activeGroupKey,
  filterNav,
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
  it('非管理员看不到审计子项，但「系统设置」分组仍在（设置是高频入口）', () => {
    const settings = visibleNav(false).find((node) => node.key === 'settings') as NavGroup
    expect(settings).toBeTruthy()
    expect(settings.children.map((child) => child.key)).toEqual([
      'settings-platform', 'settings-ai', 'settings-users', 'settings-about', 'settings-changelog',
    ])
  })

  it('管理员能看到系统设置分组下的全部子项（含审计）', () => {
    const settings = visibleNav(true).find((node) => node.key === 'settings') as NavGroup
    expect(settings.children.map((child) => child.key)).toEqual([
      'settings-platform', 'settings-ai', 'settings-users', 'settings-audit', 'settings-about',
      'settings-changelog',
    ])
  })

  it('AI 助手配置是管理员专属能力（ai:config），PM 与工程师都看不到', () => {
    const keysOf = (role: Parameters<typeof capabilitiesForRole>[0]) =>
      visibleNavByCapabilities(capabilitiesForRole(role)).flatMap((node) =>
        isGroup(node) ? node.children.map((child) => child.key) : [node.key],
      )
    expect(keysOf('admin')).toContain('settings-ai')
    expect(keysOf('pm')).not.toContain('settings-ai')
    expect(keysOf('engineer')).not.toContain('settings-ai')
    expect(keysOf('viewer')).not.toContain('settings-ai')
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

  it('W0.8：系统设置独立成一级菜单，平台设置页在它下面', () => {
    const settings = NAVIGATION.find((node) => node.key === 'settings') as NavGroup
    expect(isGroup(settings)).toBe(true)
    expect(settings.children.some((child) => child.path === '/settings')).toBe(true)
    expect(NAVIGATION.some((node) => node.key === 'admin')).toBe(false)
  })

  it('四角色菜单快照：权限只减不增，未知角色按最小权限', () => {
    const viewer = navKeysForRole('viewer')
    const pm = navKeysForRole('pm')
    const engineer = navKeysForRole('engineer')
    const admin = navKeysForRole('admin')

    expect(viewer).not.toContain('execution-runs')  // execution:read
    expect(viewer).not.toContain('settings-audit')    // audit:read
    expect(viewer).not.toContain('settings-platform') // settings:read
    expect(engineer).toContain('execution-runs')
    expect(engineer).not.toContain('settings-platform')
    expect(pm).toContain('settings-platform')
    expect(pm).not.toContain('settings-audit')
    expect(admin).toContain('settings-audit')
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

  it('执行与报告合并：组内 6 项顺序固定，且一级不再有 reports', () => {
    const execution = byKey('execution') as NavGroup
    expect(execution.children.map((child) => child.path)).toEqual([
      '/runs', '/plans', '/nodes', '/reports', '/reports/templates', '/test-reports',
    ])
    expect(NAVIGATION.some((node) => node.key === 'reports')).toBe(false)
  })

  it('合并组不带组级 capability，能力只设在 3 个运行态子项上（否则 viewer 会丢报告入口）', () => {
    const execution = byKey('execution') as NavGroup
    expect(execution.capability).toBeUndefined()
    const guarded = execution.children.filter((child) => child.capability === 'execution:read')
    expect(guarded.map((child) => child.key)).toEqual([
      'execution-runs', 'execution-plans', 'execution-nodes',
    ])
    const reportItems = execution.children.filter((child) => child.section === 'report')
    expect(reportItems.every((child) => child.capability === undefined)).toBe(true)
  })

  it('角色矩阵：viewer 仍见 3 个报告项、不见 3 个运行项；engineer 6 项全见（V-UI-11）', () => {
    const keysOf = (role: Parameters<typeof navKeysForRole>[0]): string[] => navKeysForRole(role)
    const viewer = keysOf('viewer')
    expect(viewer).toEqual(expect.arrayContaining([
      'execution-reports', 'execution-report-templates', 'execution-external-reports',
    ]))
    expect(viewer).not.toContain('execution-runs')
    expect(viewer).not.toContain('execution-plans')
    expect(viewer).not.toContain('execution-nodes')

    const engineer = keysOf('engineer')
    expect(engineer).toEqual(expect.arrayContaining([
      'execution-runs', 'execution-plans', 'execution-nodes',
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

  it('合并组内恰好 1 条分区线：前三项 run、后三项 report（FR-UI-29）', () => {
    const execution = byKey('execution') as NavGroup
    expect(execution.children.map((child) => child.section)).toEqual([
      'run', 'run', 'run', 'report', 'report', 'report',
    ])
    const breaks = execution.children
      .map((_, index) => isSectionBreak(execution.children, index))
      .filter(Boolean)
    expect(breaks).toHaveLength(1)
    expect(isSectionBreak(execution.children, 3)).toBe(true)
    expect(isSectionBreak(execution.children, 0)).toBe(false)
  })

  it('四处改名落地：用户管理 / 维护原则 / 环境概览 / 独立测试计划词条', () => {
    const settings = byKey('settings') as NavGroup
    expect(settings.children.find((c) => c.key === 'settings-users')?.labelKey).toBe('menu.adminUsers')
    const maintenance = byKey('maintenance') as NavGroup
    expect(maintenance.children.find((c) => c.key === 'maintenance-principles')?.labelKey)
      .toBe('menu.mntPrinciples')
    const environment = byKey('environment') as NavGroup
    expect(environment.children.find((c) => c.key === 'environment-overview')?.labelKey)
      .toBe('menu.envOverview')
    const execution = byKey('execution') as NavGroup
    const plans = execution.children.find((c) => c.key === 'execution-plans') as NavLeaf
    expect(plans.labelKey).toBe('menu.runsPlans')
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
