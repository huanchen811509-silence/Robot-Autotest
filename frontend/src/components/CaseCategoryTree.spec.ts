/**
 * CaseCategoryTree（W57-10/11）组件级单测：懒加载、三态、置灰、搜索不扰动已选、上限、前 200。
 * 数据全部通过 props / caseLoader 注入，不依赖真实网络。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import CaseCategoryTree from '@/components/CaseCategoryTree.vue'
import { globalGroupedApi } from '@/api/platformCaseSets'
import { globalCaseApi } from '@/api/globalCases'

vi.mock('@/api/platformCaseSets', () => ({
  globalGroupedApi: { grouped: vi.fn() },
}))
vi.mock('@/api/globalCases', () => ({
  globalCaseApi: { page: vi.fn() },
}))

const groups = [
  { l1Id: 1, l1Name: '以太网', l2s: [{ l2Id: 11, l2Name: '链路', count: 3 }], total: 3, uncategorized: 0 },
  { l1Id: 2, l1Name: 'RDMA', l2s: [{ l2Id: 21, l2Name: '性能', count: 2 }], total: 2, uncategorized: 1 },
]
const treeCases = [
  { id: 101, title: '链路连通性', caseCode: 'CLI-001' },
  { id: 102, title: '时延抖动', caseCode: 'CLI-002' },
  { id: 103, title: '吞吐', caseCode: 'CLI-003' },
]
const caseLoader = vi.fn(async () => ({ items: treeCases, total: 3 }))
const grouped = vi.mocked(globalGroupedApi.grouped)

let app: App | null = null
let host: HTMLElement | null = null

async function mountTree(props: Record<string, unknown> = {}): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(CaseCategoryTree, { initialGroups: groups, caseLoader, ...props })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  return root
}

async function settle(): Promise<void> {
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

async function expand(root: HTMLElement, testid: string): Promise<void> {
  root.querySelector<HTMLButtonElement>(`[data-testid="${testid}"]`)!.click()
  await settle()
}

beforeEach(() => {
  caseLoader.mockReset()
  caseLoader.mockResolvedValue({ items: treeCases, total: 3 })
  grouped.mockReset()
  grouped.mockResolvedValue(groups as never)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('CaseCategoryTree 结构与懒加载', () => {
  it('渲染一级 / 二级，二级默认折叠（归组视图默认折叠到二级）', async () => {
    const root = await mountTree()
    expect(root.querySelector('[data-testid="category-tree"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="l1-toggle-1"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="l2-toggle-1-11"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="l2-cases-1-11"]')).toBeNull()
  })

  it('传入 initialGroups 时不再请求 grouped 端点', async () => {
    await mountTree()
    expect(grouped).not.toHaveBeenCalled()
  })

  it('展开二级才懒加载用例明细', async () => {
    const root = await mountTree()
    expect(root.querySelector('[data-testid="tree-case-101"]')).toBeNull()
    await expand(root, 'l2-toggle-1-11')
    expect(caseLoader).toHaveBeenCalledTimes(1)
    expect(caseLoader.mock.calls[0]).toEqual(['以太网', '链路'])
    expect(root.querySelector('[data-testid="l2-cases-1-11"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="tree-case-101"]')).not.toBeNull()
  })

  it('重复展开命中缓存，不再打接口', async () => {
    const root = await mountTree()
    await expand(root, 'l2-toggle-1-11')
    await expand(root, 'l2-toggle-1-11') // 收起
    await expand(root, 'l2-toggle-1-11') // 再展开
    expect(caseLoader).toHaveBeenCalledTimes(1)
  })

  it('全部通过 caseLoader 注入时，不打 globalCaseApi.page', async () => {
    const root = await mountTree()
    await expand(root, 'l2-toggle-1-11')
    expect(globalCaseApi.page).not.toHaveBeenCalled()
  })
})

describe('CaseCategoryTree 三态与选择', () => {
  it('勾一级：先加载其下用例，再整批选中并 emit change', async () => {
    const onChange = vi.fn()
    const root = await mountTree({ onChange })
    const box = root.querySelector<HTMLInputElement>('[data-testid="l1-check-1"]')!
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await settle()

    expect(onChange).toHaveBeenCalled()
    expect(onChange.mock.calls.at(-1)![0]).toEqual([101, 102, 103])
    expect(root.querySelector('[data-testid="l1-check-1"]')?.getAttribute('data-state')).toBe('all')
  })

  it('勾一条用例：二级变 some（indeterminate），emit 单条', async () => {
    const onChange = vi.fn()
    const root = await mountTree({ onChange })
    await expand(root, 'l2-toggle-1-11')
    const box = root.querySelector<HTMLInputElement>('[data-testid="tree-case-101"]')!
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await settle()

    expect(onChange.mock.calls.at(-1)![0]).toEqual([101])
    expect(root.querySelector('[data-testid="l2-check-1-11"]')?.getAttribute('data-state')).toBe('some')
  })

  it('已在本集的用例置灰且不可勾选', async () => {
    const root = await mountTree({ existingCaseIds: [101] })
    await expand(root, 'l2-toggle-1-11')
    const box = root.querySelector<HTMLInputElement>('[data-testid="tree-case-101"]')!
    expect(box.disabled).toBe(true)
    expect(root.textContent).toContain('已在本集')
  })

  it('modelValue 初始化：重建后恢复已选（向导返回上一步不丢选择）', async () => {
    const root = await mountTree({ modelValue: [101, 103] })
    expect(root.querySelector('[data-testid="tree-selected-count"]')?.textContent).toContain('2')
  })
})

describe('CaseCategoryTree 搜索 / 上限 / 规模', () => {
  it('搜索只过滤可见项，已选数量不变', async () => {
    const onChange = vi.fn()
    const root = await mountTree({ onChange })
    await expand(root, 'l2-toggle-1-11')
    const box = root.querySelector<HTMLInputElement>('[data-testid="tree-case-101"]')!
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await settle()

    const search = root.querySelector<HTMLInputElement>('[data-testid="tree-search"]')!
    search.value = 'RDMA'
    search.dispatchEvent(new Event('input'))
    await nextTick()

    expect(root.querySelector('[data-testid="l1-toggle-1"]')).toBeNull()
    expect(root.querySelector('[data-testid="l1-toggle-2"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="tree-selected-count"]')?.textContent).toContain('1')
  })

  it('到达上限显示提示', async () => {
    const root = await mountTree({ maxSelect: 1 })
    await expand(root, 'l2-toggle-1-11')
    const box = root.querySelector<HTMLInputElement>('[data-testid="tree-case-101"]')!
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await settle()
    expect(root.querySelector('[data-testid="tree-limit"]')).not.toBeNull()
  })

  it('单分类 > 200 条显示「前 200 / 共 N」', async () => {
    const big = [
      { l1Id: 3, l1Name: '大分类', l2s: [{ l2Id: 31, l2Name: '大二级', count: 350 }], total: 350, uncategorized: 0 },
    ]
    const root = await mountTree({ initialGroups: big })
    expect(root.querySelector('[data-testid="l2-cap-3-31"]')?.textContent).toContain('前 200 / 共 350')
  })

  it('空分组给出自解释空态', async () => {
    const root = await mountTree({ initialGroups: [] })
    expect(root.querySelector('[data-testid="tree-empty"]')).not.toBeNull()
    expect(root.textContent).toContain('按一级分类重建样例集')
  })

  it('展开/折叠用原生 button，勾选用原生 checkbox（键盘可用）', async () => {
    const root = await mountTree()
    expect(root.querySelector('[data-testid="l1-toggle-1"]')?.tagName).toBe('BUTTON')
    expect(root.querySelector('[data-testid="l1-check-1"]')?.tagName).toBe('INPUT')
  })
})
