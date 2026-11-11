/**
 * 能力判据单一真源（v4.12.0 / FR-v4.12-21）。
 *
 * 背景是 v4.11.0 的一处回归：`/settings/users` 的 meta.capability 是 `'public'`，
 * 但守卫拿 `capabilitiesForRole(role).includes('public')` 判 —— 那张表里没有 public，
 * 于是**任何角色进这一页都被踢到 /403**，而导航过滤那边有 public 特判，菜单照样显示。
 *
 * 所以这里既测行为（四角色 × 三类 capability），也测**同源**（两处调用点都不许再有
 * 自己的 public 分支）—— 只测行为的话，将来谁在守卫里重新写一份 includes，行为仍然正确，
 * 缺陷会以另一种形式回来。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  capabilitiesForRole,
  capabilitySatisfied,
  filterNavByCapabilityPredicate,
  type NavRole,
} from '@/config/navigation'

const ROLES: NavRole[] = ['admin', 'pm', 'engineer', 'viewer']

/** 与路由守卫同构的谓词：按角色兜底能力集判断。 */
function byRole(role: NavRole): (capability: string) => boolean {
  const owned = new Set(capabilitiesForRole(role))
  return (capability) => owned.has(capability)
}

function readSource(relPath: string): string {
  return readFileSync(resolve(process.cwd(), relPath), 'utf8')
}

describe('capabilitySatisfied（public 对所有角色放行）', () => {
  it('public：四种角色全部放行', () => {
    for (const role of ROLES) {
      expect(capabilitySatisfied('public', byRole(role))).toBe(true)
    }
  })

  it('无 capability（undefined / null / 空串）：一律放行', () => {
    for (const role of ROLES) {
      expect(capabilitySatisfied(undefined, byRole(role))).toBe(true)
      expect(capabilitySatisfied(null, byRole(role))).toBe(true)
      expect(capabilitySatisfied('', byRole(role))).toBe(true)
    }
  })

  it('具体能力：按传入的谓词判，不由本函数扩张权限', () => {
    expect(capabilitySatisfied('user:write', byRole('admin'))).toBe(true)
    expect(capabilitySatisfied('user:write', byRole('pm'))).toBe(false)
    expect(capabilitySatisfied('user:write', byRole('engineer'))).toBe(false)
    expect(capabilitySatisfied('audit:read', byRole('viewer'))).toBe(false)
    // 谓词可以完全独立于角色（服务端能力集路径）
    expect(capabilitySatisfied('anything', () => true)).toBe(true)
    expect(capabilitySatisfied('anything', () => false)).toBe(false)
  })

  it('旧口径会误判：capabilitiesForRole 本身不含 public（回归的根因留存为断言）', () => {
    for (const role of ROLES) {
      expect(capabilitiesForRole(role)).not.toContain('public')
    }
  })
})

describe('导航过滤走同一判据', () => {
  it('public 节点在空能力集下仍保留，其余被过滤', () => {
    const nodes = [
      { key: 'pub', labelKey: 'x', capability: 'public' },
      { key: 'secret', labelKey: 'y', capability: 'user:write' },
    ]
    const kept = filterNavByCapabilityPredicate(
      nodes as Parameters<typeof filterNavByCapabilityPredicate>[0],
      () => false,
    )
    expect(kept.map((node) => node.key)).toEqual(['pub'])
  })
})

describe('同源断言（源码里只允许一处 public 特判）', () => {
  it('导航配置里 public 特判只在 capabilitySatisfied 内', () => {
    const source = readSource('src/config/navigation.ts')
    // 文件里有两个 `const keep =`（另一个是 adminOnly 过滤），必须锚定到本函数内部那一处
    const fnStart = source.indexOf('export function filterNavByCapabilityPredicate')
    const keepStart = source.indexOf('const keep = (node: NavNode)', fnStart)
    const keepEnd = source.indexOf('const result: NavNode[] = []', keepStart)
    expect(fnStart).toBeGreaterThan(-1)
    expect(keepStart).toBeGreaterThan(fnStart)
    expect(keepEnd).toBeGreaterThan(keepStart)
    const keepBody = source.slice(keepStart, keepEnd)
    expect(keepBody).not.toContain("=== 'public'")
    expect(keepBody).toContain('capabilitySatisfied(')
  })

  it('路由守卫不再自己拼 includes(capability)', () => {
    const source = readSource('src/router/index.ts')
    expect(source).not.toContain('includes(capability)')
    expect(source).toContain('capabilitySatisfied(')
  })
})
