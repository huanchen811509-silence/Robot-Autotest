import { describe, expect, it } from 'vitest'
import { formatCountdown, formatEta } from '@/utils/eta'

const ZH = { second: '秒', minute: '分', hour: '小时' }

describe('formatEta', () => {
  it('不足 1 分钟只显示秒', () => {
    expect(formatEta(0, ZH)).toBe('0秒')
    expect(formatEta(59, ZH)).toBe('59秒')
  })

  it('分钟级显示「分秒」，小时级显示「小时分」', () => {
    expect(formatEta(60, ZH)).toBe('1分0秒')
    expect(formatEta(125, ZH)).toBe('2分5秒')
    expect(formatEta(3600, ZH)).toBe('1小时0分')
    expect(formatEta(7325, ZH)).toBe('2小时2分')
  })

  it('缺失/非法/负值统一输出占位符，不伪造倒计时', () => {
    expect(formatEta(undefined, ZH)).toBe('—')
    expect(formatEta(null, ZH)).toBe('—')
    expect(formatEta(-1, ZH)).toBe('—')
    expect(formatEta(Number.NaN, ZH)).toBe('—')
    expect(formatEta(Number.POSITIVE_INFINITY, ZH)).toBe('—')
  })

  it('单位文案由调用方注入，不内置任何语言', () => {
    expect(formatEta(90, { second: 's', minute: 'm', hour: 'h' })).toBe('1m30s')
  })
})

describe('formatCountdown', () => {
  it('按 mm:ss 输出并补零', () => {
    expect(formatCountdown(0)).toBe('00:00')
    expect(formatCountdown(5)).toBe('00:05')
    expect(formatCountdown(65)).toBe('01:05')
    expect(formatCountdown(300)).toBe('05:00')
  })

  it('非法或非正数按 00:00，不出现负值倒计时', () => {
    expect(formatCountdown(-10)).toBe('00:00')
    expect(formatCountdown(Number.NaN)).toBe('00:00')
  })
})
