import { describe, expect, it } from 'vitest'
import { engineCode, nextProjectCode, suggestProjectCode, yearMonth } from '@/config/projectCode'

const NOV = new Date(2026, 10, 10) // 2026-11-10

describe('项目编码自动生成', () => {
  it('格式为 前缀-引擎-年月-序号，空库从 001 开始', () => {
    expect(suggestProjectCode('API', 'robot', [], NOV)).toBe('API-RF-202611-001')
    expect(suggestProjectCode('PY', 'pytest', [], NOV)).toBe('PY-PYT-202611-001')
  })

  it('序号只在同前缀+同引擎+同月份内递增，跨月/跨引擎互不影响', () => {
    const existing = ['API-RF-202611-001', 'API-RF-202611-007', 'API-PYT-202611-009', 'API-RF-202610-012']
    expect(suggestProjectCode('API', 'robot', existing, NOV)).toBe('API-RF-202611-008')
    expect(suggestProjectCode('API', 'pytest', existing, NOV)).toBe('API-PYT-202611-010')
  })

  it('序号补三位，超过 999 自然进四位', () => {
    expect(suggestProjectCode('PRJ', 'robot', ['PRJ-RF-202611-999'], NOV)).toBe('PRJ-RF-202611-1000')
  })

  it('前缀非法字符剔除，空前缀退回 PRJ', () => {
    expect(suggestProjectCode('a-p i', 'robot', [], NOV)).toBe('API-RF-202611-001')
    expect(suggestProjectCode('', 'robot', ['PRJ-RF-202611-002'], NOV)).toBe('PRJ-RF-202611-003')
  })

  it('引擎缩写：Robot Framework→RF，Pytest→PYT，未知按 RF', () => {
    expect(engineCode('robot')).toBe('RF')
    expect(engineCode('PYTEST')).toBe('PYT')
    expect(engineCode(null)).toBe('RF')
  })

  it('年月取本机自然月，两位数月份补零', () => {
    expect(yearMonth(new Date(2026, 0, 5))).toBe('202601')
    expect(yearMonth(NOV)).toBe('202611')
  })

  it('重新生成把当前值当已用，避免连点两次拿到同一个值', () => {
    expect(nextProjectCode('API', 'robot', ['API-RF-202611-001'], 'API-RF-202611-002', NOV)).toBe(
      'API-RF-202611-003',
    )
    expect(nextProjectCode('API', 'robot', ['API-RF-202611-001'], '', NOV)).toBe('API-RF-202611-002')
  })
})
