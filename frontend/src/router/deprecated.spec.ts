/**
 * 下架路由的死链守门（v5.11.0 / FR-v5.11-08 / W61-06、W61-07）。
 *
 * 三条路由的共同点是「点了才白屏」：
 * - `/g/cases/:id`、`/g/case-sets/:id`：全仓没有任何 `router.push` / `<RouterLink>`
 *   指向它们，是历史遗留的孤儿路由；详情已改在列表页内 `DetailDock` 展开。
 * - `/cases/board`：`CaseBoardView` 的 `rows` 恒为 `[]`（全文无赋值），空壳页。
 *
 * 删掉之后必须留断言，否则下一次有人「顺手加回来」时没有任何东西拦得住——
 * 空壳页加回来不会红、孤儿路由加回来也不会红，直到用户点进去看到白屏。
 */
import { describe, expect, it } from 'vitest'
import router from '@/router'
import { GLOBAL_NAVIGATION, NAVIGATION, isGroup } from '@/config/navigation'
import type { NavGroup, NavLeaf, NavNode } from '@/config/navigation'

const REMOVED_ROUTE_NAMES = ['case-board', 'global-case-detail', 'global-case-sets-detail']
const REMOVED_LEAF_PATHS = ['/cases/board']
const REMOVED_ALSO_MATCH = ['/g/cases/', '/g/case-sets/']

function leavesOf(nodes: NavNode[]): NavLeaf[] {
  const out: NavLeaf[] = []
  for (const node of nodes) {
    if (isGroup(node)) out.push(...(node as NavGroup).children)
  }
  return out
}

describe('v5.11.0 下架路由', () => {
  it('路由表已无 case-board / global-case-detail / global-case-sets-detail', () => {
    const names = router.getRoutes().map((route) => String(route.name ?? ''))
    for (const name of REMOVED_ROUTE_NAMES) {
      expect(names, `路由 ${name} 仍在：空壳页或孤儿路由没删干净`).not.toContain(name)
    }
  })

  it('三条下架路径都落到 not-found，没有被别的页面顺手接住', () => {
    for (const path of ['/cases/board', '/g/cases/12', '/g/case-sets/12']) {
      expect(router.resolve(path).name, `${path} 仍被真实页面接住`).toBe('not-found')
    }
  })

  it('两棵树都没有叶子指向被删路径（含 alsoMatch 前缀）', () => {
    const leaves = [...leavesOf(NAVIGATION), ...leavesOf(GLOBAL_NAVIGATION)]
    // 防「解析失效导致空数组恒过」：叶子数应远大于 10。
    expect(leaves.length).toBeGreaterThan(10)
    for (const leaf of leaves) {
      expect(REMOVED_LEAF_PATHS, `${leaf.key} 指向已下架页面`).not.toContain(leaf.path)
      for (const prefix of REMOVED_ALSO_MATCH) {
        expect(leaf.path.startsWith(prefix), `${leaf.key} 的 path 落在已删前缀 ${prefix} 下`)
          .toBe(false)
        for (const match of leaf.alsoMatch ?? []) {
          expect(match.startsWith(prefix), `${leaf.key} 的 alsoMatch ${match} 指向已删前缀`)
            .toBe(false)
        }
      }
    }
  })
})
