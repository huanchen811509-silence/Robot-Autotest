/**
 * CaseDetailPanel 统一契约回归（v4.9.2 / OPT-02 / FR-49-07~09）。
 *
 * 判据：六组字段、≥12 项可见、源表无此列显式占位（空白单元格 0）、
 * 副本徽章与掩码凭据、按 source 分派 API、详情模块只读（无写接口）。
 *
 * API 用 vi.mock 隔离，绝不发真实请求。测试用 i18n 以 flatJson 注入
 * docs/evidence/opt-20261111/i18n/frontend-views.json 中的键，使「源表无此列」
 * 等占位文案可被真实断言（学徒尚未把新键合并进 zh-CN.ts / en-US.ts）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import type { CaseDetailView } from '@/api/caseDetail'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'

const get = vi.hoisted(() => vi.fn())
vi.mock('@/api/caseDetail', () => ({ caseDetailApi: { get } }))

/** 与证据 JSON 的 zh-CN 取值一致；flatJson:true 让点路径键可直接命中。 */
const ZH: Record<string, string> = {
  'caseDetail.missingColumn': '源表无此列',
  'caseDetail.yes': '是',
  'caseDetail.no': '否',
  'caseDetail.loading': '正在加载用例详情…',
  'caseDetail.loadFailed': '用例详情加载失败',
  'caseDetail.group.identity': '标识',
  'caseDetail.group.category': '分类',
  'caseDetail.group.content': '内容',
  'caseDetail.group.execution': '执行',
  'caseDetail.group.trace': '溯源',
  'caseDetail.group.version': '版本',
  'caseDetail.field.caseCode': '用例编号',
  'caseDetail.field.title': '用例名称',
  'caseDetail.field.externalId': '外部编号',
  'caseDetail.field.sourceBadge': '来源徽章',
  'caseDetail.field.categoryL1': '一级分类',
  'caseDetail.field.categoryL2': '二级分类',
  'caseDetail.field.caseKind': '用例分类',
  'caseDetail.field.safetyLevel': '安全级',
  'caseDetail.field.precondition': '预置条件',
  'caseDetail.field.steps': '测试步骤',
  'caseDetail.field.expected': '预期结果',
  'caseDetail.field.testResource': '测试资源',
  'caseDetail.field.aSideCommand': 'A端命令',
  'caseDetail.field.bSideCommand': 'B端命令',
  'caseDetail.field.switchConfig': '交换机配置',
  'caseDetail.field.testResult': '测试结果',
  'caseDetail.field.sourceSheet': '来源工作表',
  'caseDetail.field.contentSha': '内容摘要',
  'caseDetail.field.syncJobId': '同步作业号',
  'caseDetail.field.localOverride': '本地覆盖',
  'caseDetail.field.keyMaskedCredential': '凭据（掩码）',
  'caseDetail.field.versionNo': '版本号',
  'caseDetail.field.lifecycleStatus': '生命周期',
  'caseDetail.field.priority': '优先级',
  'caseDetail.copy.externalId': '来源 external_id',
  'caseDetail.copy.syncJobId': 'sync_job.id',
  'caseDetail.copy.localOverride': 'local_override',
  'caseDetail.copy.sourceBadge': 'sourceBadge',
}

const testI18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  flatJson: true,
  messages: { 'zh-CN': { ...ZH }, 'en-US': { ...ZH } },
})

const detail: CaseDetailView = {
  id: 42,
  caseCode: 'TC-001',
  externalId: 'EXT-9',
  title: '网口 up/down 回归',
  categoryL1: '网络',
  categoryL2: 'RoCE',
  priority: 'P1',
  safetyLevel: 'L1',
  lifecycleStatus: 'active',
  versionNo: 3,
  sourceSheet: 'Sheet1',
  contentSha: 'sha-abc',
  aSideCommand: 'ip link set eth0 up',
  bSideCommand: null,
  switchConfig: 'vlan 100',
  testResult: 'PASS',
  testResource: 'host-a',
  syncJobId: 7,
  localOverride: false,
  sourceBadge: 'global',
  keyMaskedCredential: '****',
}

let app: App | null = null
let host: HTMLElement | null = null

async function flush(): Promise<void> {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

async function mountPanel(props: {
  caseId: string | number
  source?: 'global' | 'project' | 'copy'
  projectId?: string | number | null
}): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(CaseDetailPanel, props)
  app.use(testI18n)
  app.mount(root)
  await flush()
  return root
}

function pick(root: HTMLElement, testId: string): HTMLElement {
  return root.querySelector<HTMLElement>(`[data-testid="${testId}"]`)!
}

function valueOf(root: HTMLElement, field: string): string {
  return pick(root, `case-field-${field}`).querySelector('.case-detail-value')!.textContent!.trim()
}

beforeEach(() => {
  get.mockReset()
  get.mockResolvedValue(detail)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('CaseDetailPanel 六组字段与可见基线（FR-49-07）', () => {
  it('mounted 即按 caseId 拉取（URL 直达，不依赖列表页状态）', async () => {
    await mountPanel({ caseId: '42', source: 'global' })

    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith({ source: 'global', caseId: '42', projectId: null })
  })

  it('渲染 6 组、24 项字段（≥12 项可见），且空白单元格数为 0', async () => {
    const root = await mountPanel({ caseId: 42, source: 'global' })

    const groups = root.querySelectorAll('[data-testid^="case-group-"]')
    const fields = root.querySelectorAll('[data-testid^="case-field-"]')
    expect(groups.length).toBe(6)
    expect(fields.length).toBeGreaterThanOrEqual(12)
    expect(fields.length).toBe(24)

    const values = [...root.querySelectorAll('.case-detail-value')]
    expect(values.length).toBe(24)
    const blanks = values.filter((cell) => cell.textContent!.trim() === '')
    expect(blanks).toHaveLength(0)

    expect(valueOf(root, 'caseCode')).toBe('TC-001')
    expect(valueOf(root, 'title')).toBe('网口 up/down 回归')
    expect(valueOf(root, 'aSideCommand')).toBe('ip link set eth0 up')
    expect(valueOf(root, 'versionNo')).toBe('3')
  })

  it('六组标题与关键分组都能定位', async () => {
    const root = await mountPanel({ caseId: 42, source: 'project', projectId: 3 })

    for (const key of ['identity', 'category', 'content', 'execution', 'trace', 'version']) {
      expect(pick(root, `case-group-${key}`).querySelector('h3')!.textContent!.trim()).not.toBe('')
    }
    expect(get).toHaveBeenCalledWith({ source: 'project', caseId: 42, projectId: 3 })
  })
})

describe('CaseDetailPanel 源表无此列显式占位（FR-49-07）', () => {
  it('bSideCommand 为 null 与库无此列的 4 项都显示「源表无此列」，不留空白', async () => {
    const root = await mountPanel({ caseId: 42, source: 'global' })

    const missing = ['bSideCommand', 'caseKind', 'precondition', 'steps', 'expected']
    for (const key of missing) {
      const cell = pick(root, `case-field-${key}`).querySelector('.case-detail-value')!
      expect(cell.textContent!.trim()).toBe('源表无此列')
      expect(cell.classList.contains('is-missing')).toBe(true)
    }
    expect(root.querySelectorAll('.case-detail-value.is-missing').length).toBeGreaterThanOrEqual(5)
  })

  it('localOverride 布尔显示「否」，不退化成空字符串', async () => {
    const root = await mountPanel({ caseId: 42, source: 'global' })
    expect(valueOf(root, 'localOverride')).toBe('否')
  })
})

describe('CaseDetailPanel 副本徽章与掩码凭据（FR-49-08/09）', () => {
  it('source=copy 展示 external_id / syncJobId / localOverride / sourceBadge 四个徽章', async () => {
    const root = await mountPanel({ caseId: 42, source: 'copy', projectId: 9 })

    expect(get).toHaveBeenCalledWith({ source: 'copy', caseId: 42, projectId: 9 })
    const badges = pick(root, 'copy-badges')
    expect(badges.querySelectorAll('.badge')).toHaveLength(4)
    expect(pick(root, 'copy-external-id').textContent).toContain('EXT-9')
    expect(pick(root, 'copy-sync-job').textContent).toContain('7')
    expect(pick(root, 'copy-local-override').textContent).toContain('否')
    expect(pick(root, 'copy-source-badge').textContent).toContain('global')
  })

  it('凭据字段只显示掩码，且带 data-key-masked 标记', async () => {
    const root = await mountPanel({ caseId: 42, source: 'copy', projectId: 9 })

    const cell = pick(root, 'case-field-keyMaskedCredential').querySelector('.case-detail-value')!
    expect(cell.getAttribute('data-key-masked')).toBe('keyMaskedCredential')
    expect(cell.textContent!.trim()).toBe('****')
    expect(root.textContent).not.toContain('secret123')
  })

  it('source=global / project 不渲染副本徽章区', async () => {
    const root = await mountPanel({ caseId: 42, source: 'global' })
    expect(root.querySelector('[data-testid="copy-badges"]')).toBeNull()
  })
})

describe('CaseDetailPanel 详情只读契约（FR-49-09）', () => {
  it('真实 caseDetailApi 只暴露 get 系列，不提供任何 PUT / POST 写方法', async () => {
    const actual = await vi.importActual<{ caseDetailApi: Record<string, unknown> }>(
      '@/api/caseDetail',
    )
    const keys = Object.keys(actual.caseDetailApi)

    expect(keys).toContain('get')
    for (const write of ['update', 'create', 'save', 'remove', 'putCategories']) {
      expect(keys).not.toContain(write)
    }
  })
})
