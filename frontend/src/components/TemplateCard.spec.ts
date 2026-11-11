/**
 * TemplateCard 回归（v4.9.2 / OPT-05 / FR-49-17~18、FR-49-22）。
 *
 * 判据：6 项显示（用例数 / 用例集数 / 场景数 / 资产数 / 内核 / 最近更新）、
 * 四类计数取自 GET /api/global/templates/{id} 明细而非 itemCount 聚合值、
 * 「以此创建项目」默认 mode:'copy' 且展示确认与结果。
 *
 * API 用 vi.mock 隔离；测试用 i18n 以 flatJson 注入证据 JSON 的 zh-CN 取值。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import type { TemplateRow } from '@/api/globalTemplates'
import type { TemplateDetailView } from '@/api/templateProjects'
import TemplateCard from '@/components/TemplateCard.vue'

const detailFn = vi.hoisted(() => vi.fn())
const createFromTemplate = vi.hoisted(() => vi.fn())
const httpRequest = vi.hoisted(() => vi.fn())
vi.mock('@/api/templateProjects', () => ({
  templateProjectsApi: { detail: detailFn, createFromTemplate },
}))
// 仅用于 vi.importActual 校验真实 templateProjectsApi 的端点；组件本身仍走上面的 mock。
vi.mock('@/api/http', () => ({ request: httpRequest }))

const ZH: Record<string, string> = {
  'templateCard.cases': '用例数',
  'templateCard.caseSets': '用例集数',
  'templateCard.scenarios': '场景数',
  'templateCard.assets': '资产数',
  'templateCard.kernel': '内核',
  'templateCard.lastUpdated': '最近更新',
  'templateCard.noData': '—',
  'templateCard.loading': '正在读取模板明细…',
  'templateCard.builtin': '内置',
  'templateCard.createProject': '以此创建项目',
  'templateCard.confirmCreate': '确认以此模板创建项目？默认 copy 模式。',
  'templateCard.confirm': '确认创建',
  'templateCard.cancel': '取消',
  'templateCard.created': '项目已创建（#{id}）',
  'templateCard.createFailed': '创建项目失败',
}

const testI18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  flatJson: true,
  messages: { 'zh-CN': { ...ZH }, 'en-US': { ...ZH } },
})

const template: TemplateRow = {
  id: 5,
  name: '回归基线',
  code: 'TPL-REG',
  kernel: 'robot',
  visibility: 'team',
  builtin: false,
  status: 'active',
  ownerId: 1,
  description: '标准回归',
  // 故意与明细不一致：证明四类计数取明细，不信 itemCount 聚合值。
  itemCount: 0,
  createdAt: '2026-10-01T00:00:00',
}

function item(name: string): { itemType: string; refId: number | null; mode: string; name: string } {
  return { itemType: 'CASE', refId: null, mode: 'reference', name }
}

const detail: TemplateDetailView = {
  template: { ...template, itemCount: 10, updatedAt: '2026-11-10T12:00:00' },
  cases: [item('c1'), item('c2')],
  caseSets: [item('s1')],
  scenarios: [item('sc1'), item('sc2'), item('sc3')],
  assets: [item('a1'), item('a2'), item('a3'), item('a4')],
  items: [],
}

let app: App | null = null
let host: HTMLElement | null = null

async function flush(): Promise<void> {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

function mountCard(props: { template: TemplateRow; busy?: boolean }): HTMLElement {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(TemplateCard, props)
  app.use(testI18n)
  app.mount(root)
  return root
}

async function mountCardAsync(props: { template: TemplateRow; busy?: boolean }): Promise<HTMLElement> {
  const root = mountCard(props)
  await flush()
  return root
}

function pick(root: HTMLElement, testId: string): HTMLElement {
  return root.querySelector<HTMLElement>(`[data-testid="${testId}"]`)!
}

beforeEach(() => {
  detailFn.mockReset()
  createFromTemplate.mockReset()
  httpRequest.mockReset()
  detailFn.mockResolvedValue(detail)
  createFromTemplate.mockResolvedValue({ id: 99 })
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('TemplateCard 6 项显示与四类计数（FR-49-17/18）', () => {
  it('取 GET /api/global/templates/{id} 明细渲染 6 项，不信 itemCount', async () => {
    const root = await mountCardAsync({ template })

    expect(detailFn).toHaveBeenCalledWith(5)
    expect(pick(root, 'card-cases').textContent!.trim()).toBe('2')
    expect(pick(root, 'card-case-sets').textContent!.trim()).toBe('1')
    expect(pick(root, 'card-scenarios').textContent!.trim()).toBe('3')
    expect(pick(root, 'card-assets').textContent!.trim()).toBe('4')
    expect(pick(root, 'card-kernel').textContent!.trim()).toBe('robot')
    expect(pick(root, 'card-last-updated').textContent!.trim()).toBe('2026-11-10T12:00:00')
    // itemCount=0（列表聚合值）不得让四类计数退化成 0
    for (const id of ['card-cases', 'card-case-sets', 'card-scenarios', 'card-assets']) {
      expect(pick(root, id).textContent!.trim()).not.toBe('0')
    }
  })

  it('明细加载失败时四类计数显示「—」，不留空白', async () => {
    detailFn.mockRejectedValueOnce(new Error('network'))
    const root = await mountCardAsync({ template })

    for (const id of ['card-cases', 'card-case-sets', 'card-scenarios', 'card-assets']) {
      expect(pick(root, id).textContent!.trim()).toBe('—')
    }
    expect(pick(root, 'card-last-updated').textContent!.trim()).toBe('2026-10-01T00:00:00')
  })

  it('内置模板显示徽章，停用模板带 is-disabled 样式类', async () => {
    const root = await mountCardAsync({ template: { ...template, builtin: true, status: 'disabled' } })

    expect(root.textContent).toContain('内置')
    expect(pick(root, 'template-card').classList.contains('is-disabled')).toBe(true)
  })
})

describe('templateProjectsApi 实装端点（FR-49-19/21）', () => {
  it('saveItems 打到 PUT /global/templates/{id}/content 且带模板元信息', async () => {
    const actual = await vi.importActual<typeof import('@/api/templateProjects')>(
      '@/api/templateProjects',
    )
    httpRequest.mockResolvedValue({ id: 5 })

    await actual.templateProjectsApi.saveItems(5, [item('c1')], { name: '回归基线' })

    expect(httpRequest).toHaveBeenCalledTimes(1)
    const [url, options] = httpRequest.mock.calls[0] as [string, { method: string; body: string }]
    expect(url).toBe('/global/templates/5/content')
    expect(options.method).toBe('PUT')
    expect(JSON.parse(options.body)).toMatchObject({ name: '回归基线', items: [{ itemType: 'CASE' }] })
  })
})

describe('TemplateCard 以模板创建项目（FR-49-22）', () => {
  it('确认弹层默认 copy 模式，结果回显新建项目 ID', async () => {
    const root = await mountCardAsync({ template })

    pick(root, 'card-create-project').click()
    await nextTick()
    expect(pick(root, 'card-confirm')).toBeTruthy()
    expect((pick(root, 'card-project-name') as HTMLInputElement).value).toBe('回归基线-project')

    pick(root, 'card-confirm-create').click()
    await flush()
    expect(createFromTemplate).toHaveBeenCalledTimes(1)
    expect(createFromTemplate).toHaveBeenCalledWith({
      templateId: 5,
      name: '回归基线-project',
      mode: 'copy',
    })
    expect(pick(root, 'card-result').textContent).toContain('99')
  })

  it('创建失败展示错误文案，不抛未捕获异常', async () => {
    createFromTemplate.mockRejectedValueOnce(new Error('quota exceeded'))
    const root = await mountCardAsync({ template })

    pick(root, 'card-create-project').click()
    await nextTick()
    pick(root, 'card-confirm-create').click()
    await flush()
    expect(pick(root, 'card-result').textContent).toContain('quota exceeded')
  })
})
