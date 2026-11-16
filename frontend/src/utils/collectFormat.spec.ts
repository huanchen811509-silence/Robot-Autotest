import { describe, expect, it } from 'vitest'
import { collectRawText, collectValueText, hasRawValue } from '@/utils/collectFormat'

/** 假 t：直接回 "key(参数)"，断言结构而不是文案（文案改动不该弄红单测）。 */
const t = (key: string, params?: Record<string, unknown>): string =>
  params ? `${key}(${Object.entries(params).map(([k, v]) => `${k}=${v}`).join(',')})` : key

describe('采集结果结构化（W70-06）', () => {
  it('CPU 拆成核数与型号，不是把 JSON 贴出来', () => {
    const text = collectValueText(
      { category: 'CPU', value: { cores: 16, model: 'i5-13500H' } },
      t,
    )
    expect(text).toContain('cores=16')
    expect(text).toContain('model=i5-13500H')
    expect(text).not.toContain('{')
  })

  it('内存给出总量 / 已用 / 可用三项，单位是 GB', () => {
    const text = collectValueText(
      { category: 'MEMORY', value: { totalMb: 15860, usedMb: 1698, availableMb: 14161 } },
      t,
    )
    expect(text).toContain('total=15.5 GB')
    expect(text).toContain('used=1.7 GB')
    expect(text).toContain('free=13.8 GB')
  })

  it('磁盘给出根分区占比与磁盘块数', () => {
    const text = collectValueText(
      {
        category: 'DISK',
        value: { root: { usePercent: '5%', size: '1055762868' }, disks: [{}, {}, {}] },
      },
      t,
    )
    expect(text).toContain('percent=5%')
    expect(text).toContain('count=3')
  })

  it('网卡统计 up 数量，而不是列一串 mac', () => {
    const text = collectValueText(
      {
        category: 'NIC',
        value: [{ state: 'UP' }, { state: 'UP' }, { state: 'DOWN' }],
      },
      t,
    )
    expect(text).toContain('total=3')
    expect(text).toContain('up=2')
  })

  it('路由取默认网关地址，不是整行路由表', () => {
    const text = collectValueText(
      {
        category: 'ROUTE',
        value: { defaultRoute: 'default via 10.202.16.1 dev eth9 proto kernel', routes: ['a', 'b'] },
      },
      t,
    )
    expect(text).toContain('gateway=10.202.16.1')
    expect(text).toContain('count=2')
  })

  it('null 值：有原因就显示原因，没原因显示 —，绝不显示 null', () => {
    expect(collectValueText({ category: 'GPU', value: null, reason: '容器内无 /dev/nvidia0' }, t))
      .toBe('env.common.notCollected · 容器内无 /dev/nvidia0')
    expect(collectValueText({ category: 'GPU', value: null }, t)).toBe('—')
  })

  it('未知类别退回 JSON 预览（截断），不吞掉内容', () => {
    const text = collectValueText({ category: 'NEW_THING', value: { a: 1, b: 2 } }, t)
    expect(text).toContain('"a":1')
  })

  it('字符串类别原样返回（OS / KERNEL 不该被包装）', () => {
    expect(collectValueText({ category: 'OS', value: 'Ubuntu 24.04.5 LTS' }, t)).toBe('Ubuntu 24.04.5 LTS')
  })

  it('只有结构化值才给「原始值」折叠，字符串不给（那是噪音）', () => {
    expect(hasRawValue({ category: 'CPU', value: { cores: 1 } })).toBe(true)
    expect(hasRawValue({ category: 'OS', value: 'Ubuntu' })).toBe(false)
    expect(collectRawText({ a: 1 })).toContain('"a": 1')
  })
})
