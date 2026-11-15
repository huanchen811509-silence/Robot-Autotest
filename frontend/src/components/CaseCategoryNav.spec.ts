/**
 * `CaseCategoryNav` 两级筛选（v5.11.0 / W61-03 / FR-v5.11-02 判据 2/3/4）。
 *
 * 关键不变量：**选中节点的计数 = 右表 total**（判据 4 的「两处数字相等」）。
 * 这里用 activeCount 注入右表读数，断言选中节点显示的就是它，而不是接口的分组计数。
 */
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import CaseCategoryNav, { type CategorySelection } from './CaseCategoryNav.vue'
import type { GroupedCase } from '@/api/platformCaseSets'

const GROUPS: GroupedCase[] = [
  {
    l1Id: 1,
    l1Name: '网络',
    total: 120,
    uncategorized: 20,
    l2s: [
      { l2Id: 11, l2Name: 'RoCE', count: 70 },
      { l2Id: 12, l2Name: 'TCP', count: 30 },
    ],
  },
  {
    l1Id: 2,
    l1Name: '存储',
    total: 40,
    uncategorized: 5,
    l2s: [{ l2Id: 21, l2Name: 'NVMe', count: 35 }],
  },
]

let app: App | null = null

interface Mounted {
  root: HTMLElement
  emitted: CategorySelection[]
}

async function mount(
  modelValue: CategorySelection,
  extra: Record<string, unknown> = {},
): Promise<Mounted> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  const emitted: CategorySelection[] = []
  i18n.global.locale.value = 'zh-CN'
  app = createApp({
    render: () =>
      h(CaseCategoryNav as never, {
        modelValue,
        groups: GROUPS,
        allCount: 160,
        activeCount: 7,
        'onUpdate:modelValue': (value: CategorySelection) => emitted.push(value),
        ...extra,
      } as never),
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return { root, emitted }
}

const EMPTY: CategorySelection = { l1: '', l2: '', uncategorized: false }

afterEach(() => {
  app?.unmount()
  app = null
  document.body.innerHTML = ''
})

describe('CaseCategoryNav', () => {
  it('渲染「全部 / 未分类」+ 两级分类，L2 默认折叠', async () => {
    const { root } = await mount(EMPTY)
    expect(root.querySelector('[data-testid="category-all"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="category-uncategorized"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="category-l1-1"]')).not.toBeNull()
    // 未展开时不该出现二级
    expect(root.querySelector('[data-testid="category-l2-11"]')).toBeNull()
  })

  it('点一级分类：按整域筛选（l2 清空）', async () => {
    const { root, emitted } = await mount(EMPTY)
    ;(root.querySelector('[data-testid="category-l1-1"]') as HTMLButtonElement).click()
    await nextTick()
    expect(emitted.at(-1)).toEqual({ l1: '网络', l2: '', uncategorized: false })
  })

  it('选中一级后二级可见，点二级按叶子筛选', async () => {
    // 选中一级分类时组件已自动展开，二级节点直接可点
    const { root, emitted } = await mount({ l1: '网络', l2: '', uncategorized: false })
    ;(root.querySelector('[data-testid="category-l2-11"]') as HTMLButtonElement).click()
    await nextTick()
    expect(emitted.at(-1)).toEqual({ l1: '网络', l2: 'RoCE', uncategorized: false })
  })

  it('折叠箭头把二级收起来（再点一次展开回来）', async () => {
    const { root } = await mount({ l1: '网络', l2: '', uncategorized: false })
    const caret = root.querySelector('[data-testid="category-l1-toggle-1"]') as HTMLButtonElement
    expect(root.querySelector('[data-testid="category-l2-11"]')).not.toBeNull()
    caret.click()
    await nextTick()
    expect(root.querySelector('[data-testid="category-l2-11"]')).toBeNull()
    caret.click()
    await nextTick()
    expect(root.querySelector('[data-testid="category-l2-11"]')).not.toBeNull()
  })

  it('「全部」回到全集，「未分类」只筛未分类', async () => {
    const { root, emitted } = await mount({ l1: '网络', l2: 'RoCE', uncategorized: false })
    ;(root.querySelector('[data-testid="category-all"]') as HTMLButtonElement).click()
    await nextTick()
    ;(root.querySelector('[data-testid="category-uncategorized"]') as HTMLButtonElement).click()
    await nextTick()
    expect(emitted).toEqual([
      { l1: '', l2: '', uncategorized: false },
      { l1: '', l2: '', uncategorized: true },
    ])
  })

  it('未分类计数 = 各一级分类 uncategorized 之和', async () => {
    const { root } = await mount(EMPTY)
    expect(root.querySelector('[data-testid="category-count-uncategorized"]')?.textContent?.trim())
      .toBe('25')
  })

  it('判据 4：选中节点显示右表 total（不是接口分组计数）', async () => {
    const { root } = await mount({ l1: '网络', l2: 'RoCE', uncategorized: false })
    const active = root.querySelector('[data-testid="category-l2-11"]') as HTMLElement
    expect(active.getAttribute('aria-current')).toBe('true')
    expect(active.textContent).toContain('7')
    // 未选中的一级分类仍显示接口计数
    expect(root.querySelector('[data-testid="category-l1-2"]')?.textContent).toContain('40')
  })

  it('命中当前一级分类时自动展开（URL 恢复后能看到二级）', async () => {
    const { root } = await mount({ l1: '网络', l2: '', uncategorized: false })
    expect(root.querySelector('[data-testid="category-l2-11"]')).not.toBeNull()
  })
})
