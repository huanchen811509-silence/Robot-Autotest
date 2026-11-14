/**
 * W57-17 · 列筛选弹层单测。
 *
 * NPopover 的弹层是 teleport + 懒渲染，happy-dom 下不会把「应用 / 清除 / 选项」
 * 挂进 DOM，点不到。这里把 naive-ui 的四个组件换成同契约的轻量桩：NPopover 把
 * trigger 与弹层内容 inline 渲染出来，NButton / NInput / NCheckbox 保留事件语义。
 * 测的是「列筛选自己的状态机与 emit 纪律」，不是 popover 的定位算法。
 *
 * 重点覆盖修复过的 mode 互斥 emit：multi 只发 update:selected、text 只发
 * update:text。若两个都发，DataTable 的两个 handler 会串行把对方刚提交的值清空。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { i18n } from '@/locales'
import ColumnFilter from './ColumnFilter.vue'

vi.mock('naive-ui', async () => {
  const { defineComponent, h: hh } = await import('vue')
  return {
    NPopover: defineComponent({
      name: 'NPopoverStub',
      setup(_props, { slots }) {
        return () => hh('div', { class: 'popover-stub' }, [slots.trigger?.(), slots.default?.()])
      },
    }),
    NButton: defineComponent({
      name: 'NButtonStub',
      setup(_props, { attrs, slots }) {
        return () => hh('button', { ...attrs }, slots.default?.())
      },
    }),
    NInput: defineComponent({
      name: 'NInputStub',
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup(props, { emit, attrs }) {
        return () =>
          hh('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          })
      },
    }),
    NCheckbox: defineComponent({
      name: 'NCheckboxStub',
      props: { checked: { type: Boolean, default: false } },
      emits: ['update:checked'],
      setup(props, { emit, attrs }) {
        return () =>
          hh('input', {
            type: 'checkbox',
            ...attrs,
            checked: props.checked,
            onChange: (event: Event) =>
              emit('update:checked', (event.target as HTMLInputElement).checked),
          })
      },
    }),
  }
})

vi.mock('./AppIcon.vue', () => ({
  default: { name: 'AppIconStub', render: () => null },
}))

const OPTIONS = [
  { label: '通过', value: 'PASS' },
  { label: '失败', value: 'FAIL' },
]

function makeSpies() {
  return {
    selected: vi.fn(),
    text: vi.fn(),
    apply: vi.fn(),
    clear: vi.fn(),
  }
}
type Spies = ReturnType<typeof makeSpies>

function handlers(s: Spies): Record<string, unknown> {
  return {
    'onUpdate:selected': s.selected,
    'onUpdate:text': s.text,
    onApply: s.apply,
    onClear: s.clear,
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function mountFilter(
  props: Record<string, unknown> = {},
  s: Spies = makeSpies(),
): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () =>
      h(ColumnFilter as never, {
        columnKey: 'state',
        title: '状态',
        mode: 'multi',
        options: OPTIONS,
        ...props,
        ...handlers(s),
      } as never),
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

describe('ColumnFilter 触发按钮与草稿态', () => {
  it('渲染列标题 / aria-label / data-testid', async () => {
    const root = await mountFilter()
    const trigger = root.querySelector<HTMLElement>('[data-testid="column-filter-trigger-state"]')
    expect(trigger).not.toBeNull()
    expect(trigger?.textContent).toContain('状态')
    expect(trigger?.getAttribute('aria-label')).toBe('筛选：状态')
  })

  it('有选中值时触发按钮进入 is-active', async () => {
    const root = await mountFilter({ selected: ['PASS'] })
    expect(root.querySelector('.column-filter__trigger.is-active')).not.toBeNull()
  })

  it('multi 模式渲染选项列表与搜索框', async () => {
    const root = await mountFilter({ mode: 'multi', options: OPTIONS })
    expect(root.querySelectorAll('[data-testid="column-filter-options"] label')).toHaveLength(2)
    expect(root.querySelector('[data-testid="column-filter-search"]')).not.toBeNull()
  })

  it('text 模式渲染文本输入框，不渲染选项列表', async () => {
    const root = await mountFilter({ mode: 'text' })
    expect(root.querySelector('[data-testid="column-filter-text"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="column-filter-options"]')).toBeNull()
  })
})

describe('ColumnFilter apply / clear 的 mode 互斥 emit', () => {
  it('multi 勾选后应用只发 update:selected 与 apply，不发 update:text', async () => {
    const s = makeSpies()
    const root = await mountFilter({ mode: 'multi', options: OPTIONS }, s)
    const box = root.querySelectorAll<HTMLInputElement>(
      '[data-testid="column-filter-options"] input[type="checkbox"]',
    )[0]
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await nextTick()
    root.querySelector<HTMLElement>('[data-testid="column-filter-apply"]')!.click()
    await nextTick()
    expect(s.selected).toHaveBeenCalledWith(['PASS'])
    expect(s.apply).toHaveBeenCalledTimes(1)
    expect(s.text).not.toHaveBeenCalled()
  })

  it('text 输入后应用只发 update:text，不发 update:selected', async () => {
    const s = makeSpies()
    const root = await mountFilter({ mode: 'text' }, s)
    const input = root.querySelector<HTMLInputElement>('[data-testid="column-filter-text"]')!
    input.value = 'smoke'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector<HTMLElement>('[data-testid="column-filter-apply"]')!.click()
    await nextTick()
    expect(s.text).toHaveBeenCalledWith('smoke')
    expect(s.apply).toHaveBeenCalledTimes(1)
    expect(s.selected).not.toHaveBeenCalled()
  })

  it('文本草稿来自 props.text，应用时 trim', async () => {
    const s = makeSpies()
    const root = await mountFilter({ mode: 'text', text: '  rdma  ' }, s)
    root.querySelector<HTMLElement>('[data-testid="column-filter-apply"]')!.click()
    await nextTick()
    expect(s.text).toHaveBeenCalledWith('rdma')
  })

  it('multi 清除发空数组与 clear，且不影响 text 通道', async () => {
    const s = makeSpies()
    const root = await mountFilter({ mode: 'multi', selected: ['PASS'], options: OPTIONS }, s)
    root.querySelector<HTMLElement>('[data-testid="column-filter-clear"]')!.click()
    await nextTick()
    expect(s.selected).toHaveBeenCalledWith([])
    expect(s.clear).toHaveBeenCalledTimes(1)
    expect(s.text).not.toHaveBeenCalled()
  })

  it('text 清除发空字符串，不发 update:selected', async () => {
    const s = makeSpies()
    const root = await mountFilter({ mode: 'text', text: 'smoke' }, s)
    root.querySelector<HTMLElement>('[data-testid="column-filter-clear"]')!.click()
    await nextTick()
    expect(s.text).toHaveBeenCalledWith('')
    expect(s.selected).not.toHaveBeenCalled()
  })
})

describe('ColumnFilter 搜索与外部回填', () => {
  it('搜索关键字即时过滤选项（本地反馈）', async () => {
    const root = await mountFilter({ mode: 'multi', options: OPTIONS })
    const search = root.querySelector<HTMLInputElement>('[data-testid="column-filter-search"]')!
    search.value = '失败'
    search.dispatchEvent(new Event('input'))
    await nextTick()
    expect(root.querySelectorAll('[data-testid="column-filter-options"] label')).toHaveLength(1)
    expect(root.textContent).toContain('失败')
    expect(root.textContent).not.toContain('通过')
  })

  it('外部（URL 回填）改了 selected 时草稿同步，再应用发的是新值', async () => {
    const s = makeSpies()
    const external = reactive({ selected: [] as string[] })
    const root = document.createElement('div')
    document.body.appendChild(root)
    host = root
    app = createApp({
      render: () =>
        h(ColumnFilter as never, {
          columnKey: 'state',
          title: '状态',
          mode: 'multi',
          options: [OPTIONS[0]],
          selected: external.selected,
          ...handlers(s),
        } as never),
    })
    app.use(i18n)
    app.mount(root)
    await nextTick()
    external.selected = ['PASS']
    await nextTick()
    root.querySelector<HTMLElement>('[data-testid="column-filter-apply"]')!.click()
    await nextTick()
    expect(s.selected).toHaveBeenCalledWith(['PASS'])
  })
})
