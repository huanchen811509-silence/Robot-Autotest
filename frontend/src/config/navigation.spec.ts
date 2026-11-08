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
  isLeafActive,
  isNodeActive,
  leafTarget,
  navKeysForRole,
  roleFromCodes,
  visibleNav,
  visibleNavByCapabilities,
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
      'settings-platform', 'settings-users', 'settings-about', 'settings-changelog',
    ])
  })

  it('管理员能看到系统设置分组下的全部子项（含审计）', () => {
    const settings = visibleNav(true).find((node) => node.key === 'settings') as NavGroup
    expect(settings.children.map((child) => child.key)).toEqual([
      'settings-platform', 'settings-users', 'settings-audit', 'settings-about', 'settings-changelog',
    ])
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
  it('v2.2 / MENU-01：一级恰好 8 组，层级不超过 2', () => {
    expect(NAVIGATION).toHaveLength(8)
    expect(NAVIGATION.map((node) => node.key)).toEqual([
      'overview', 'assets', 'cases', 'runs', 'reports', 'settings',
      'environment', 'maintenance',
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

    expect(viewer).not.toContain('runs')            // execution:read
    expect(viewer).not.toContain('settings-audit')    // audit:read
    expect(viewer).not.toContain('settings-platform') // settings:read
    expect(engineer).toContain('runs')
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
    expect(keys).toContain('overview')
    expect(keys).not.toContain('settings-audit')
    expect(keys).not.toContain('settings-platform')
    expect(keys).not.toContain('runs')
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

  it('报告详情仍点亮「报告」', () => {
    const reports = NAVIGATION.find((node) => node.key === 'reports') as NavLeaf
    expect(isLeafActive(reports, { path: '/reports/RUN-001/print' })).toBe(true)
    expect(isLeafActive(reports, { path: '/reports/RUN-001' })).toBe(true)
  })

  it('前缀匹配落在路径分段边界：/reportsX 不误点亮', () => {
    const reports = NAVIGATION.find((node) => node.key === 'reports') as NavLeaf
    expect(isLeafActive(reports, { path: '/reportsX' })).toBe(false)
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
    expect(isGroupActive(groups().find((g) => g.key === 'runs')!, route)).toBe(true)
    expect(activeGroupKey(route)).toBe('runs')
    expect(isNodeActive(NAVIGATION[0], route)).toBe(false)
  })
})

/* --------------------------------------------------------------------------
 * v2.2 / MENU-01（FR-v2.2-01、06）：8 组 IA + 业务域不建一级菜单
 * -------------------------------------------------------------------------- */
describe('v2.2 / MENU-01 八组菜单与业务域隔离', () => {
  it('一级分组数 = 8', () => {
    expect(NAVIGATION).toHaveLength(8)
  })

  it('8 个一级项各至少 1 个二级入口（叶子自身即一个可导航入口）', () => {
    expect(NAVIGATION).toHaveLength(8)
    for (const node of NAVIGATION) {
      expect(secondLevelEntries(node).length, `一级项 ${node.key} 无二级入口`)
        .toBeGreaterThanOrEqual(1)
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
