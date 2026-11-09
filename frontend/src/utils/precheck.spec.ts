import { describe, expect, it } from 'vitest'
import {
  groupPrechecks,
  isPrecheckBlocked,
  needsPrecheck,
} from '@/utils/precheck'

const checks = [
  { key: 'nodes', level: 'BLOCK' as const, title: '无可用节点', detail: '0 台在线', hint: '先上线节点' },
  { key: 'quota', level: 'WARN' as const, title: '并发偏高', detail: '12 > 8', hint: null },
  { key: 'cases', level: 'OK' as const, title: '用例可用', detail: '共 30 条', hint: null },
]

describe('groupPrechecks', () => {
  it('按 BLOCK → WARN → OK 固定顺序分组，空组不渲染', () => {
    const groups = groupPrechecks(checks)
    expect(groups.map((group) => group.level)).toEqual(['BLOCK', 'WARN', 'OK'])
    expect(groups[0].items).toHaveLength(1)
  })

  it('没有检查项时返回空数组', () => {
    expect(groupPrechecks([])).toEqual([])
  })
})

describe('isPrecheckBlocked', () => {
  it('只有 verdict=BLOCK 才阻断；WARN 不阻止继续执行', () => {
    expect(isPrecheckBlocked({ verdict: 'BLOCK', checks, estimatedTotal: 1, estimatedSeconds: 1, sampleSize: 0 })).toBe(true)
    expect(isPrecheckBlocked({ verdict: 'WARN', checks, estimatedTotal: 1, estimatedSeconds: 1, sampleSize: 0 })).toBe(false)
    expect(isPrecheckBlocked(null)).toBe(false)
  })
})

describe('needsPrecheck', () => {
  it('全通过且无检查项/估算时直接执行', () => {
    expect(
      needsPrecheck({ verdict: 'OK', checks: [], estimatedTotal: 0, estimatedSeconds: 0, sampleSize: 0 }),
    ).toBe(false)
  })

  it('有检查项或有估算就弹窗，哪怕是 OK', () => {
    const base = { verdict: 'OK' as const, checks: [], estimatedTotal: 0, estimatedSeconds: 0, sampleSize: 0 }
    expect(needsPrecheck({ ...base, checks })).toBe(true)
    expect(needsPrecheck({ ...base, estimatedTotal: 12 })).toBe(true)
    expect(needsPrecheck({ ...base, verdict: 'WARN' })).toBe(true)
  })

  it('预检缺失（旧后端）不弹窗', () => {
    expect(needsPrecheck(null)).toBe(false)
    expect(needsPrecheck(undefined)).toBe(false)
  })
})
