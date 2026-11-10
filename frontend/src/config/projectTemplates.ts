/**
 * 项目模板（FR-v4.1-04）：`config/project-templates/*.json` 是唯一真源。
 *
 * 模板只提供「默认值」，不预置用例 / 成员 / 环境数据 —— 创建后仍可逐项修改。
 * 校验器与单测共用同一份 `validateTemplate`，避免「文件能加载就算通过」。
 */

import blank from '../../../config/project-templates/blank.json'
import apiRegression from '../../../config/project-templates/api-regression.json'
import uiWeb from '../../../config/project-templates/ui-web.json'
import pytestService from '../../../config/project-templates/pytest-service.json'

export type TemplateKernel = 'robot' | 'pytest'
export type TemplateVisibility = 'private' | 'project' | 'public'

export interface ProjectTemplate {
  schemaVersion: string
  key: string
  /** 模板内部编号：`前缀-引擎-年月-序号`，与创建项目时的编码同一格式。 */
  code: string
  nameKey: string
  descKey: string
  testKernel: TemplateKernel
  visibility: TemplateVisibility
  codePrefix: string
  defaultRoleCode: string
  tags: string[]
}

export const PROJECT_TEMPLATES: ProjectTemplate[] = [
  blank,
  apiRegression,
  uiWeb,
  pytestService,
] as ProjectTemplate[]

const KERNELS: TemplateKernel[] = ['robot', 'pytest']
const VISIBILITIES: TemplateVisibility[] = ['private', 'project', 'public']
const KEY_PATTERN = /^[a-z][a-z0-9-]{2,30}$/
const PREFIX_PATTERN = /^[A-Z][A-Z0-9]{1,7}$/
const ROLE_PATTERN = /^[a-z][a-z0-9_]{2,31}$/
/** 内部编号：`前缀-引擎-年月-序号`，引擎只能是 RF（Robot Framework）或 PYT（Pytest）。 */
const CODE_PATTERN = /^([A-Z][A-Z0-9]{1,7})-(RF|PYT)-(\d{6})-(\d{3,})$/

/** 返回问题清单；空数组 = 合法。索引用于定位第几个模板。 */
export function validateTemplate(raw: unknown): string[] {
  const problems: string[] = []
  if (!raw || typeof raw !== 'object') return ['not-an-object']
  const t = raw as Record<string, unknown>
  if (t.schemaVersion !== '1.0') problems.push('schemaVersion!=1.0')
  if (typeof t.key !== 'string' || !KEY_PATTERN.test(t.key)) problems.push('key-invalid')
  for (const field of ['nameKey', 'descKey', 'codePrefix', 'defaultRoleCode', 'testKernel', 'visibility']) {
    if (typeof t[field] !== 'string' || !(t[field] as string).trim()) problems.push(`${field}-missing`)
  }
  const nameKey = typeof t.nameKey === 'string' ? t.nameKey : ''
  const descKey = typeof t.descKey === 'string' ? t.descKey : ''
  if (nameKey && !nameKey.startsWith('projectWizard.templates.')) problems.push('nameKey-scope')
  if (descKey && !descKey.startsWith('projectWizard.templates.')) problems.push('descKey-scope')
  if (!KERNELS.includes(t.testKernel as TemplateKernel)) problems.push('testKernel-unsupported')
  if (!VISIBILITIES.includes(t.visibility as TemplateVisibility)) problems.push('visibility-unsupported')
  if (typeof t.codePrefix !== 'string' || !PREFIX_PATTERN.test(t.codePrefix)) problems.push('codePrefix-invalid')
  // 编号要与前缀、引擎对得上：前缀取自 codePrefix，引擎由 testKernel 决定
  const code = typeof t.code === 'string' ? CODE_PATTERN.exec(t.code) : null
  if (!code) {
    problems.push('code-invalid')
  } else {
    if (code[1] !== t.codePrefix) problems.push('code-prefix-mismatch')
    if (code[2] !== (t.testKernel === 'pytest' ? 'PYT' : 'RF')) problems.push('code-engine-mismatch')
  }
  if (typeof t.defaultRoleCode !== 'string' || !ROLE_PATTERN.test(t.defaultRoleCode)) {
    problems.push('roleCode-invalid')
  }
  if (!Array.isArray(t.tags) || t.tags.length === 0 || !t.tags.every((tag) => typeof tag === 'string' && tag.trim())) {
    problems.push('tags-empty')
  }
  return problems
}

/** 四个模板的静态问题；构建期与单测共用，非空即判红。 */
export const TEMPLATE_PROBLEMS: string[] = (() => {
  const list: string[] = []
  const keys = new Set<string>()
  const codes = new Set<string>()
  PROJECT_TEMPLATES.forEach((template, index) => {
    if (keys.has(template.key)) list.push(`#${index}:duplicate-key`)
    keys.add(template.key)
    if (codes.has(template.code)) list.push(`#${index}:duplicate-code`)
    codes.add(template.code)
    validateTemplate(template).forEach((problem) => list.push(`#${index}:${problem}`))
  })
  if (PROJECT_TEMPLATES.length !== 4) list.push(`count=${PROJECT_TEMPLATES.length}`)
  return list
})()
