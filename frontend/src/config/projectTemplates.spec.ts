import { describe, expect, it } from 'vitest'
import zhCN from '@/locales/zh-CN'
import enUS from '@/locales/en-US'
import { PROJECT_TEMPLATES, TEMPLATE_PROBLEMS, validateTemplate } from './projectTemplates'

function resolve(source: Record<string, unknown>, key: string): unknown {
  return key.split('.').reduce<unknown>((acc, part) => {
    if (acc && typeof acc === 'object') return (acc as Record<string, unknown>)[part]
    return undefined
  }, source)
}

describe('project templates', () => {
  it('四份模板文件全部通过 schema 校验', () => {
    expect(PROJECT_TEMPLATES).toHaveLength(4)
    expect(TEMPLATE_PROBLEMS).toEqual([])
  })

  it('模板键唯一，且 nameKey/descKey 在双语 locale 中存在', () => {
    const keys = new Set(PROJECT_TEMPLATES.map((tpl) => tpl.key))
    expect(keys.size).toBe(PROJECT_TEMPLATES.length)
    for (const tpl of PROJECT_TEMPLATES) {
      expect(resolve(zhCN as unknown as Record<string, unknown>, tpl.nameKey)).toBeTruthy()
      expect(resolve(enUS as unknown as Record<string, unknown>, tpl.nameKey)).toBeTruthy()
      expect(resolve(zhCN as unknown as Record<string, unknown>, tpl.descKey)).toBeTruthy()
      expect(resolve(enUS as unknown as Record<string, unknown>, tpl.descKey)).toBeTruthy()
    }
  })

  it('脏样本必须判红（负向自证）', () => {
    expect(validateTemplate(null)).not.toEqual([])
    expect(validateTemplate({ ...PROJECT_TEMPLATES[0], testKernel: 'junit' })).toContain(
      'testKernel-unsupported',
    )
    expect(validateTemplate({ ...PROJECT_TEMPLATES[0], visibility: 'team' })).toContain(
      'visibility-unsupported',
    )
    expect(validateTemplate({ ...PROJECT_TEMPLATES[0], key: 'Bad Key' })).toContain('key-invalid')
    expect(validateTemplate({ ...PROJECT_TEMPLATES[0], tags: [] })).toContain('tags-empty')
    expect(validateTemplate({ ...PROJECT_TEMPLATES[0], nameKey: 'other.scope' })).toContain(
      'nameKey-scope',
    )
  })
})
