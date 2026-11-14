/**
 * RunSelectionPanel（W57-29/30）组件级单测：四模式切换、搜索分页事件、规模提示、来源抽屉、勾选事件。
 * 组件是纯 props 进 / 事件出，不依赖 pinia / i18n / 网络。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import RunSelectionPanel from '@/components/RunSelectionPanel.vue'

const caseSets = [
  { id: 71, projectId: 1, setCode: 'SMOKE', name: '冒烟集', description: null },
  { id: 72, projectId: 1, setCode: 'REG', name: '回归集', description: null },
] as never
const caseRows = [
  { id: 11, caseCode: 'CLI-001', title: '链路' },
  { id: 12, caseCode: 'CLI-002', title: '时延' },
] as never

function events() {
  return {
    onToggleSet: vi.fn(),
    onToggleCase: vi.fn(),
    onSetMode: vi.fn(),
    onSetAll: vi.fn(),
    onClear: vi.fn(),
    onSearchSets: vi.fn(),
    onSetPage: vi.fn(),
    onSearchCases: vi.fn(),
    onCasePage: vi.fn(),
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function mountPanel(overrides: Record<string, unknown> = {}) {
  const spy = events()
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(RunSelectionPanel, {
    caseSets,
    caseRows,
    caseTotal: 2,
    casePage: 1,
    casePageSize: 20,
    selectedCaseIds: [11],
    selectedCount: 1,
    selectedSetIds: [71],
    mode: 'MIXED',
    sources: [{ caseId: 11, sources: ['set:71'] }],
    setTotal: 2,
    setPage: 1,
    setPageSize: 20,
    allSelected: false,
    ...spy,
    ...overrides,
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return { root, spy }
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('RunSelectionPanel 默认（MIXED）', () => {
  it('集合区与用例区都渲染，保留既有勾选 testid', async () => {
    const { root } = await mountPanel()
    expect(root.querySelector('[data-testid="set-section"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="case-section"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="pick-set-71"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="pick-11"]')).not.toBeNull()
  })

  it('selected-count 常显并带数字', async () => {
    const { root } = await mountPanel({ selectedCount: 3 })
    expect(root.querySelector('[data-testid="selected-count"]')?.textContent).toContain('3')
  })

  it('集合勾选态由 selectedSetIds 决定', async () => {
    const { root } = await mountPanel()
    const box = root.querySelector<HTMLInputElement>('[data-testid="pick-set-71"]')!
    const other = root.querySelector<HTMLInputElement>('[data-testid="pick-set-72"]')!
    expect(box.checked).toBe(true)
    expect(other.checked).toBe(false)
  })

  it('用例勾选态由 selectedCaseIds 决定', async () => {
    const { root } = await mountPanel()
    expect(root.querySelector<HTMLInputElement>('[data-testid="pick-11"]')!.checked).toBe(true)
    expect(root.querySelector<HTMLInputElement>('[data-testid="pick-12"]')!.checked).toBe(false)
  })
})

describe('RunSelectionPanel 四模式', () => {
  it('点 mode-SET 触发 set-mode(SET)', async () => {
    const { root, spy } = await mountPanel()
    root.querySelector<HTMLButtonElement>('[data-testid="mode-SET"]')!.click()
    await nextTick()
    expect(spy.onSetMode).toHaveBeenCalledWith('SET')
  })

  it('mode=SET 时隐藏逐条用例区，只留集合区', async () => {
    const { root } = await mountPanel({ mode: 'SET' })
    expect(root.querySelector('[data-testid="set-section"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="case-section"]')).toBeNull()
  })

  it('mode=CASE 时隐藏用例集区，只留用例区', async () => {
    const { root } = await mountPanel({ mode: 'CASE' })
    expect(root.querySelector('[data-testid="set-section"]')).toBeNull()
    expect(root.querySelector('[data-testid="case-section"]')).not.toBeNull()
  })

  it('mode=ALL 渲染全量面板，点确认 emit set-all(true)', async () => {
    const { root, spy } = await mountPanel({ mode: 'ALL' })
    expect(root.querySelector('[data-testid="all-panel"]')).not.toBeNull()
    root.querySelector<HTMLButtonElement>('[data-testid="mode-all-confirm"]')!.click()
    await nextTick()
    expect(spy.onSetAll).toHaveBeenCalledWith(true)
  })
})

describe('RunSelectionPanel 搜索 / 分页 / 规模', () => {
  it('用例集搜索 emit search-sets(keyword)', async () => {
    const { root, spy } = await mountPanel()
    const input = root.querySelector<HTMLInputElement>('[data-testid="set-search"]')!
    input.value = '冒烟'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="set-search-btn"]')!.click()
    await nextTick()
    expect(spy.onSearchSets).toHaveBeenCalledWith('冒烟')
  })

  it('用例搜索 emit search-cases(keyword)', async () => {
    const { root, spy } = await mountPanel()
    const input = root.querySelector<HTMLInputElement>('[data-testid="case-search"]')!
    input.value = 'CLI'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLButtonElement>('[data-testid="case-search-btn"]')!.click()
    await nextTick()
    expect(spy.onSearchCases).toHaveBeenCalledWith('CLI')
  })

  it('集合翻页 emit set-page；用例翻页 emit case-page', async () => {
    const { root, spy } = await mountPanel({ setTotal: 60, caseTotal: 60 })
    root.querySelectorAll<HTMLButtonElement>('[data-testid="set-pager"] .btn')[1].click()
    root.querySelectorAll<HTMLButtonElement>('[data-testid="case-pager"] .btn')[1].click()
    await nextTick()
    expect(spy.onSetPage).toHaveBeenCalledWith(2)
    expect(spy.onCasePage).toHaveBeenCalledWith(2)
  })

  it('清单 > 200 条显示「前 200」提示', async () => {
    const { root } = await mountPanel({ caseTotal: 350 })
    expect(root.querySelector('[data-testid="case-list-cap"]')?.textContent).toContain('前 200')
  })

  it('> 10000 条需二次确认，确认前禁用全量按钮', async () => {
    const { root } = await mountPanel({ mode: 'ALL', caseTotal: 10001 })
    expect(root.querySelector('[data-testid="large-confirm-bar"]')).not.toBeNull()
    expect(root.querySelector<HTMLButtonElement>('[data-testid="mode-all-confirm"]')!.disabled).toBe(true)
    root.querySelector<HTMLButtonElement>('[data-testid="large-confirm"]')!.click()
    await nextTick()
    expect(root.querySelector<HTMLButtonElement>('[data-testid="mode-all-confirm"]')!.disabled).toBe(false)
  })
})

describe('RunSelectionPanel 来源下钻与勾选事件', () => {
  it('点「查看来源」打开抽屉，关闭后收起', async () => {
    const { root } = await mountPanel()
    root.querySelector<HTMLButtonElement>('[data-testid="open-sources"]')!.click()
    await nextTick()
    expect(root.querySelector('[data-testid="sources-drawer"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="source-row-11"]')).not.toBeNull()
    root.querySelector<HTMLButtonElement>('[data-testid="drawer-close"]')!.click()
    await nextTick()
    expect(root.querySelector('[data-testid="sources-drawer"]')).toBeNull()
  })

  it('抽屉逐来源下钻，显示「用例集：名称」', async () => {
    const { root } = await mountPanel({
      setNames: { 71: '冒烟集' },
      sources: [{ caseId: 11, sources: ['set:71', 'case:11'] }],
    })
    root.querySelector<HTMLButtonElement>('[data-testid="open-sources"]')!.click()
    await nextTick()
    const text = root.querySelector('[data-testid="source-row-11"]')?.textContent ?? ''
    expect(text).toContain('用例集：冒烟集')
    expect(text).toContain('手工勾选')
  })

  it('集合勾选 emit toggle-set(整行对象)；用例勾选 emit toggle-case(id)', async () => {
    const { root, spy } = await mountPanel()
    root.querySelector<HTMLInputElement>('[data-testid="pick-set-72"]')!.dispatchEvent(new Event('change'))
    root.querySelector<HTMLInputElement>('[data-testid="pick-12"]')!.dispatchEvent(new Event('change'))
    await nextTick()
    expect(spy.onToggleSet).toHaveBeenCalledWith(expect.objectContaining({ id: 72 }))
    expect(spy.onToggleCase).toHaveBeenCalledWith(12)
  })

  it('清空按钮 emit clear', async () => {
    const { root, spy } = await mountPanel()
    root.querySelector<HTMLButtonElement>('[data-testid="clear-selection"]')!.click()
    await nextTick()
    expect(spy.onClear).toHaveBeenCalledTimes(1)
  })
})
