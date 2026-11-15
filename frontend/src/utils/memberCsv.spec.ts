import { describe, expect, it } from 'vitest'
import { buildMemberCsv } from '@/utils/memberCsv'

const header = ['No.', 'Code', 'Title', 'Category', 'Script path']

describe('成员清单 CSV（W62-05 判据 4）', () => {
  it('表头五列齐全且带 BOM', () => {
    const csv = buildMemberCsv([], header)
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv.slice(1)).toBe('No.,Code,Title,Category,Script path')
  })

  it('序号从 1 连续，五列逐行对齐', () => {
    const csv = buildMemberCsv([
      { caseCode: 'TC-001', title: '登录成功', category: '冒烟', scriptPath: 'tests/test_login.py' },
      { caseCode: 'TC-002', title: '错误口令', category: null, scriptPath: null },
    ], header)
    const lines = csv.slice(1).split('\r\n')
    expect(lines).toHaveLength(3)
    expect(lines[1]).toBe('1,TC-001,登录成功,冒烟,tests/test_login.py')
    expect(lines[2]).toBe('2,TC-002,错误口令,,')
  })

  it('逗号 / 引号 / 换行都转义，不串列', () => {
    const csv = buildMemberCsv([
      { caseCode: 'TC,1', title: '含"引号"', category: '多行\n分类', scriptPath: null },
    ], header)
    const lines = csv.slice(1).split('\r\n')
    // 换行被包在引号里，所以整体仍是两行（表头 + 数据）
    expect(lines).toHaveLength(2)
    expect(lines[1]).toContain('"TC,1"')
    expect(lines[1]).toContain('"含""引号"""')
  })

  it('表头列数不是 5 时拒绝生成', () => {
    expect(() => buildMemberCsv([], ['a', 'b'])).toThrow('exactly 5')
  })
})
