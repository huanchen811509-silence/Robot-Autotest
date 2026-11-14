/**
 * W57-32/43 · 全站列表 CRUD 骨架单测。
 *
 * 骨架自己不实现表格，只组合 DataTable —— 所以把 `./DataTable.vue` 换成桩，
 * 断言的是「六槽位编排 / 批量与筛选互斥 / 空态三态 / 属性透传 / 就地刷新」，
 * 而不是 NDataTable 的渲染细节（那由 DataTable.spec.ts 覆盖）。桩把收到的
 * 关键 props 写成 data-* 属性，方便验证 resolvedTableProps 的合并结果。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import ListScaffold from './ListScaffold.vue'

vi.mock('./DataTable.vue', async () => {
  const { defineComponent, h: hh } = await import('vue')
  return {
    default: defineComponent({
      name: 'DataTableStub',
      setup(_props, { attrs, slots }) {
        return () =>
          hh(
            'div',
            {
              class: 'data-table-stub',
              'data-loading': String(attrs.loading ?? false),
              'data-error': String(attrs.error ?? false),
              'data-empty': String(attrs.emptyIllustration ?? ''),
              'data-empty-kebab': String(attrs['empty-illustration'] ?? ''),
              'data-empty-title': String(attrs.emptyTitle ?? ''),
            },
            slots.empty?.(),
          )
      },
    }),
  }
})

type SlotFn = (slotProps: Record<string, unknown>) => ReturnType<typeof h>

let app: App | null = null
let host: HTMLElement | null = null

async function mountScaffold(
  props: Record<string, unknown> = {},
  slots: Record<string, SlotFn> = {},
): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () =>
      h(ListScaffold as never, { columns: [], data: [], ...props } as never, slots as never),
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('ListScaffold 六槽位编排', () => {
  it('标题 / 筛选 / 表格 / 分页四个槽位都渲染', async () => {
    const root = await mountScaffold({}, {
      title: () => h('h2', { class: 'slot-title' }, '标题'),
      filters: () => h('div', { class: 'slot-filters' }, '筛选'),
      table: () => h('div', { class: 'slot-table' }, '表格'),
      pagination: () => h('div', { class: 'slot-pagination' }, '分页'),
    })
    expect(root.querySelector('.slot-title')).not.toBeNull()
    expect(root.querySelector('.slot-filters')).not.toBeNull()
    expect(root.querySelector('.slot-table')).not.toBeNull()
    expect(root.querySelector('.slot-pagination')).not.toBeNull()
  })

  it('未提供 #table 时用默认 DataTable，且 #empty 插槽被透传', async () => {
    const root = await mountScaffold({}, {
      empty: () => h('div', { class: 'slot-empty' }, '自定义空态'),
    })
    expect(root.querySelector('.data-table-stub')).not.toBeNull()
    expect(root.querySelector('.slot-empty')).not.toBeNull()
  })

  it('没给 #pagination / #filters / #batch 时对应容器不渲染', async () => {
    const root = await mountScaffold()
    expect(root.querySelector('.list-scaffold__pagination')).toBeNull()
    expect(root.querySelector('[data-testid="list-filters"]')).toBeNull()
    expect(root.querySelector('[data-testid="list-batch"]')).toBeNull()
  })

  it('title prop 作为默认标题（无需 #title 槽）', async () => {
    const root = await mountScaffold({ title: '默认标题' })
    expect(root.querySelector('.list-scaffold__title')?.textContent).toBe('默认标题')
  })
})

describe('ListScaffold 批量条与筛选区互斥', () => {
  it('batchActive=true 且给了 #batch：渲染批量条、隐藏筛选区', async () => {
    const root = await mountScaffold({ batchActive: true }, {
      filters: () => h('div', { class: 'slot-filters' }, '筛选'),
      batch: () => h('div', { class: 'slot-batch' }, '批量'),
    })
    expect(root.querySelector('[data-testid="list-batch"]')).not.toBeNull()
    expect(root.querySelector('.slot-batch')).not.toBeNull()
    expect(root.querySelector('[data-testid="list-filters"]')).toBeNull()
  })

  it('batchActive=false：渲染筛选区、不渲染批量条', async () => {
    const root = await mountScaffold({ batchActive: false }, {
      filters: () => h('div', { class: 'slot-filters' }, '筛选'),
      batch: () => h('div', { class: 'slot-batch' }, '批量'),
    })
    expect(root.querySelector('[data-testid="list-filters"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="list-batch"]')).toBeNull()
  })

  it('只给 #batch 但 batchActive=false 且没给 #filters：两块都不渲染', async () => {
    const root = await mountScaffold({ batchActive: false }, {
      batch: () => h('div', { class: 'slot-batch' }, '批量'),
    })
    expect(root.querySelector('[data-testid="list-batch"]')).toBeNull()
    expect(root.querySelector('[data-testid="list-filters"]')).toBeNull()
    expect(root.querySelector('.slot-batch')).toBeNull()
  })
})

describe('ListScaffold 空态三态与属性透传', () => {
  it('filtered=true：默认 DataTable 收到 no-result 空态', async () => {
    const root = await mountScaffold({ filtered: true })
    expect(root.querySelector('.data-table-stub')?.getAttribute('data-empty')).toBe('no-result')
  })

  it('filtered=false：默认 DataTable 收到 no-data 空态', async () => {
    const root = await mountScaffold()
    expect(root.querySelector('.data-table-stub')?.getAttribute('data-empty')).toBe('no-data')
  })

  it('宿主自定义 emptyIllustration / emptyTitle 不被覆盖', async () => {
    const root = await mountScaffold({ emptyIllustration: 'inbox', emptyTitle: '还没有报告' })
    const stub = root.querySelector('.data-table-stub')!
    expect(stub.getAttribute('data-empty')).toBe('inbox')
    expect(stub.getAttribute('data-empty-title')).toBe('还没有报告')
  })

  it('loading 透传给 DataTable（加载保留表头骨架）', async () => {
    const root = await mountScaffold({ loading: true })
    expect(root.querySelector('.data-table-stub')?.getAttribute('data-loading')).toBe('true')
  })

  it('error=true 透传，且不再覆盖空态插图', async () => {
    const root = await mountScaffold({ error: true, filtered: true })
    const stub = root.querySelector('.data-table-stub')!
    expect(stub.getAttribute('data-error')).toBe('true')
    expect(stub.getAttribute('data-empty')).toBe('')
  })

  it('#table slot prop emptyProps 暴露 kebab-case empty-illustration 给宿主', async () => {
    let seen: Record<string, unknown> = {}
    await mountScaffold({ filtered: true }, {
      table: (slotProps) => {
        seen = slotProps.emptyProps as Record<string, unknown>
        return h('div', { class: 'slot-table' }, '表格')
      },
    })
    expect(seen['empty-illustration']).toBe('no-result')
  })
})

describe('ListScaffold 就地刷新', () => {
  it('showRefresh 渲染刷新按钮并发出 refresh（不整页重载）', async () => {
    const refresh = vi.fn()
    const root = await mountScaffold({ showRefresh: true, onRefresh: refresh })
    const button = root.querySelector<HTMLElement>('[data-testid="list-refresh"]')!
    expect(button).not.toBeNull()
    button.click()
    await nextTick()
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('默认不渲染刷新按钮', async () => {
    const root = await mountScaffold()
    expect(root.querySelector('[data-testid="list-refresh"]')).toBeNull()
  })
})
