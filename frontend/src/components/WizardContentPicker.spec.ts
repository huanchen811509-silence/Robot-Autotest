/**
 * `WizardContentPicker` 组件级回归（v5.17.0 / W67-06 首次建立）。
 *
 * 这个组件是两个向导（建项目 / 建模板）共用的内容选择器。W67-06 之前它只有四类来源，
 * 场景选不了——不是因为界面没做，而是**场景与用例集不在一张表**（`test_scenario` vs
 * `test_set`），id 空间独立，混进 `setIds` 会撞号并静默错关联。
 *
 * 本组只钉三件事，都属于「换个 id 维度就悄悄坏」的类型：
 * ① 场景分区必须存在且能被勾选；
 * ② 勾选后 `scenarioIds` 与 `setIds` **互不污染**（各进各的）；
 * ③ 没有场景候选时要给提示，不能留一片空白让人以为坏了。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { i18n } from '@/locales'
import WizardContentPicker from '@/components/WizardContentPicker.vue'

vi.mock('@/api/platformCaseSets', () => ({
  platformCaseSetApi: { list: vi.fn(async () => []) },
}))
vi.mock('@/api/globalAssets', () => ({
  globalAssetApi: { list: vi.fn(async () => []) },
}))
vi.mock('@/components/CaseCategoryTree.vue', () => ({
  default: { name: 'CaseCategoryTree', props: ['modelValue'], template: '<div class="tree-stub" />' },
}))

interface Selection {
  templateKeys: string[]
  setIds: number[]
  sets: { id: number; name: string }[]
  caseIds: number[]
  assetIds: number[]
  scenarioIds: number[]
}

function emptySelection(): Selection {
  return { templateKeys: [], setIds: [], sets: [], caseIds: [], assetIds: [], scenarioIds: [] }
}

let app: App | null = null
let host: HTMLElement | null = null
let emitted: Selection[] = []

async function mountPicker(props: Record<string, unknown> = {}): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  emitted = []
  const Wrapper = defineComponent({
    setup() {
      const model = ref<Selection>(emptySelection())
      return () =>
        h(WizardContentPicker, {
          modelValue: model.value,
          'onUpdate:modelValue': (value: Selection) => {
            model.value = value
            emitted.push(value)
          },
          ...props,
        })
    },
  })
  app = createApp(Wrapper)
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

beforeEach(() => {
  emitted = []
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('场景分区（W67-06）', () => {
  it('分区列表含「场景」，且没有候选时给提示而不是空白', async () => {
    const root = await mountPicker()
    expect(root.querySelector('[data-testid="section-scenario"]')).toBeTruthy()
    root.querySelector<HTMLElement>('[data-testid="section-toggle-scenario"]')!.click()
    await nextTick()
    expect(root.querySelector('[data-testid="no-scenario"]')).toBeTruthy()
  })

  it('勾选场景后 scenarioIds 增加，且不污染 setIds', async () => {
    const root = await mountPicker({
      scenarioOptions: [
        { id: 31, name: '开机自检场景' },
        { id: 32, name: '压测场景' },
      ],
      setOptions: [{ id: 11, name: '用例集 A' }],
    })
    root.querySelector<HTMLElement>('[data-testid="section-toggle-scenario"]')!.click()
    await nextTick()
    root.querySelector<HTMLInputElement>('[data-testid="scenario-31"]')!.click()
    await nextTick()
    const last = emitted.at(-1)!
    expect(last.scenarioIds).toEqual([31])
    expect(last.setIds).toEqual([])
    expect(last.sets).toEqual([])
  })

  it('勾选用例集不污染 scenarioIds（两个维度互不相干）', async () => {
    const root = await mountPicker({
      scenarioOptions: [{ id: 31, name: '开机自检场景' }],
      setOptions: [{ id: 11, name: '用例集 A' }],
    })
    root.querySelector<HTMLElement>('[data-testid="section-toggle-set"]')!.click()
    await nextTick()
    root.querySelector<HTMLInputElement>('[data-testid="set-11"]')!.click()
    await nextTick()
    const last = emitted.at(-1)!
    expect(last.setIds).toEqual([11])
    expect(last.scenarioIds).toEqual([])
  })

  it('摘要里场景计数独立可读', async () => {
    const root = await mountPicker({
      scenarioOptions: [{ id: 31, name: 'S1' }, { id: 32, name: 'S2' }],
    })
    root.querySelector<HTMLElement>('[data-testid="section-toggle-scenario"]')!.click()
    await nextTick()
    root.querySelector<HTMLInputElement>('[data-testid="scenario-31"]')!.click()
    await nextTick()
    root.querySelector<HTMLInputElement>('[data-testid="scenario-32"]')!.click()
    await nextTick()
    const summary = root.querySelector('[data-testid="summary-scenario"]')?.textContent ?? ''
    expect(summary).toContain('2')
  })
})
