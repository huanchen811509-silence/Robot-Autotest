/**
 * `CategoryTree` 组件级回归（v4.11.0 / W-E-05；上游 v4.9.2 / OPT-07）。
 *
 * <p>这棵树把「按分类组集」这件事做成了三步：选节点 → 看筛选条数 → 批量加入。
 * 三步里任何一步错了，页面都还是「能点、能出数」，只是数不对，所以逐条钉住：
 * ① 二级必属所选一级（切一级即清空二级，不能留着上一级的子节点）；
 * ② 选二级自动切 `l2` 模式，筛选请求才带 `categoryL2`；
 * ③ 没选目标用例集时**不许发请求**，只在界面上报错；
 * ④ 加入后必须同时给出「筛选条数」和「加入条数」并做相等判定——只报一个数，
 *    用户无法发现「筛了 30 条只加了 12 条」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import CategoryTree from '@/components/CategoryTree.vue'
import { globalCaseApi } from '@/api/globalCases'
import { caseSetApi } from '@/api/cases'

vi.mock('@/api/globalCases', () => ({
  globalCaseApi: {
    categoriesInScope: vi.fn(),
    page: vi.fn(),
    exportCategoryCsv: vi.fn(),
  },
}))

vi.mock('@/api/cases', () => ({
  caseSetApi: { page: vi.fn(), addByCategoryFilter: vi.fn() },
}))

const categories = [
  { id: 1, name: '以太网', level: 1, parentId: null, caseCount: 20 },
  { id: 11, name: '链路', level: 2, parentId: 1, caseCount: 12 },
  { id: 12, name: '性能', level: 2, parentId: 1, caseCount: 8 },
  { id: 2, name: 'RDMA', level: 1, parentId: null, caseCount: 5 },
  { id: 21, name: '拥塞', level: 2, parentId: 2, caseCount: 5 },
]

const sets = [
  { id: 71, name: '冒烟集', setCode: 'SMOKE' },
  { id: 72, name: '回归集', setCode: 'REGRESS' },
]

const categoriesInScope = vi.mocked(globalCaseApi.categoriesInScope)
const pageApi = vi.mocked(globalCaseApi.page)
const exportCsv = vi.mocked(globalCaseApi.exportCategoryCsv)
const setsPage = vi.mocked(caseSetApi.page)
const addByCategoryFilter = vi.mocked(caseSetApi.addByCategoryFilter)

let app: App | null = null
let host: HTMLElement | null = null

async function mountTree(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(CategoryTree, { projectId: 93 })
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function el(root: HTMLElement, testid: string): HTMLElement | null {
  return root.querySelector<HTMLElement>(`[data-testid="${testid}"]`)
}

beforeEach(() => {
  categoriesInScope.mockReset()
  categoriesInScope.mockResolvedValue(categories as never)
  pageApi.mockReset()
  pageApi.mockResolvedValue({ items: [], total: 20 } as never)
  exportCsv.mockReset()
  exportCsv.mockResolvedValue(new Blob(['a,b']) as never)
  setsPage.mockReset()
  setsPage.mockResolvedValue({ items: sets, total: 2 } as never)
  addByCategoryFilter.mockReset()
  addByCategoryFilter.mockResolvedValue({ matched: 12, added: 12, itemSource: 'CASE' } as never)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('CategoryTree 树形与联动', () => {
  it('进入即取分类与用例集，并渲染一级节点与其计数', async () => {
    const root = await mountTree()

    expect(categoriesInScope).toHaveBeenCalledWith(93)
    expect(setsPage).toHaveBeenCalledWith(93, { page: 1, size: 100 })
    expect(el(root, 'tree-l1-1')?.textContent).toContain('以太网')
    expect(el(root, 'tree-l1-1')?.textContent).toContain('20')
    // 未展开时二级节点不渲染
    expect(el(root, 'tree-l2-11')).toBeNull()
  })

  it('点一级展开其二级；切到另一个一级时二级清空（二级必属所选一级）', async () => {
    const root = await mountTree()

    el(root, 'tree-l1-1')!.click()
    await nextTick()
    expect(el(root, 'tree-l2-11')).not.toBeNull()
    expect(el(root, 'tree-l2-21')).toBeNull()

    el(root, 'tree-l1-2')!.click()
    await nextTick()
    expect(el(root, 'tree-l2-21')).not.toBeNull()
    // 上一级的子节点必须消失，不能挂在新一级下面
    expect(el(root, 'tree-l2-11')).toBeNull()
    expect(el(root, 'pair-invalid')).toBeNull()
  })

  it('选二级自动切到 l2 模式，筛选请求带 categoryL2', async () => {
    const root = await mountTree()
    el(root, 'tree-l1-1')!.click()
    await nextTick()

    el(root, 'tree-l2-11')!.click()
    await nextTick()
    expect((root.querySelector('[data-testid="mode-l2"]') as HTMLInputElement).checked).toBe(true)

    el(root, 'run-filter')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(pageApi).toHaveBeenCalledTimes(1)
    expect(pageApi.mock.calls[0][0]).toMatchObject({ categoryL1: '以太网', categoryL2: '链路' })
  })

  it('l1 模式下只带一级，不带二级（切二级后切回一级要能撤回）', async () => {
    const root = await mountTree()
    el(root, 'tree-l1-1')!.click()
    await nextTick()
    el(root, 'tree-l2-11')!.click()
    await nextTick()

    const l1Radio = root.querySelector<HTMLInputElement>('[data-testid="mode-l1"]')!
    l1Radio.checked = true
    l1Radio.dispatchEvent(new Event('change'))
    await nextTick()

    el(root, 'run-filter')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const params = pageApi.mock.calls.at(-1)![0]
    expect(params.categoryL1).toBe('以太网')
    expect(params.categoryL2).toBeUndefined()
  })

  it('intersect 模式才出现「交集用例集」下拉', async () => {
    const root = await mountTree()
    expect(el(root, 'intersect-set')).toBeNull()

    const radio = root.querySelector<HTMLInputElement>('[data-testid="mode-intersect"]')!
    radio.checked = true
    radio.dispatchEvent(new Event('change'))
    await nextTick()

    expect(el(root, 'intersect-set')).not.toBeNull()
  })
})

describe('CategoryTree 组集判据', () => {
  it('没选用例集时点「加入」只报错，不发请求', async () => {
    const root = await mountTree()
    el(root, 'tree-l1-1')!.click()
    await nextTick()

    el(root, 'add-to-set')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(addByCategoryFilter).not.toHaveBeenCalled()
    expect(el(root, 'category-tree-error')).not.toBeNull()
  })

  it('加入后同时给出「筛选条数 = 加入条数」并判为相等', async () => {
    const root = await mountTree()
    el(root, 'tree-l1-1')!.click()
    await nextTick()

    const select = el(root, 'target-set') as HTMLSelectElement
    select.value = '71'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    el(root, 'add-to-set')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(addByCategoryFilter).toHaveBeenCalledWith(93, 71, expect.objectContaining({ categoryL1: '以太网' }))
    expect(el(root, 'filter-counts')?.textContent).toContain('12')
    expect(el(root, 'count-check')?.className).toContain('ok')
  })

  it('加入条数少于筛选条数时判为不一致（不静默通过）', async () => {
    addByCategoryFilter.mockResolvedValue({ matched: 12, added: 7, itemSource: 'CASE' } as never)
    const root = await mountTree()
    el(root, 'tree-l1-1')!.click()
    await nextTick()

    const select = el(root, 'target-set') as HTMLSelectElement
    select.value = '71'
    select.dispatchEvent(new Event('change'))
    await nextTick()

    el(root, 'add-to-set')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const check = el(root, 'count-check')!
    expect(check.className).toContain('category-tree__error')
    expect(check.className).not.toContain('ok')
  })

  it('筛选失败时把后端原因原样展示，不吞成通用文案', async () => {
    pageApi.mockRejectedValue(new Error('分类不存在'))
    const root = await mountTree()

    el(root, 'run-filter')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(el(root, 'category-tree-error')?.textContent).toContain('分类不存在')
  })
})
