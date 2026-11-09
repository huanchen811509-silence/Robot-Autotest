/**
 * `TestReportsView` 视图级回归（FR-v3.10-02 判据③④）。
 *
 * 本页这次改的是「怎么选中一条 run」，而它正好是**错了也不报错**的那类改动：
 * ① 手输框要是还留在主路径上，用户照样能过——只是继续手打 22 位 runId；
 * ② 标题没带出来时提交的是空字符串，后端接受空标题，报告建出来叫「无标题」；
 * ③ 提交的 runId 取错 ref（取了手输框、而不是下拉选中值），建的是一份指向
 *    不存在的 run 的草稿，要等到渲染才炸。
 *
 * `RunPicker` 在这里换成桩：真实组件的下拉是 teleport + 虚拟列表，happy-dom 下
 * 不渲染选项，点不到。组件本身的过滤逻辑已由 `RunPicker.spec` / `utils/runPicker.spec`
 * 覆盖，这里只关心「选中之后页面做了什么」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { testReportsApi, type TestReportItem } from '@/api/testReports'
import TestReportsView from '@/views/TestReportsView.vue'

/**
 * `RunPicker` 桩：一个按钮，点下去同时派发 `update:modelValue` 与 `select`。
 *
 * 数据写在工厂内部而不是引用外部常量——工厂在 import 阶段执行，那时模块顶层的
 * 常量还在 TDZ 里，引用会直接抛 `Cannot access before initialization`。
 */
vi.mock('@/components/RunPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'RunPickerStub',
      props: {
        projectId: { type: Number, default: 0 },
        modelValue: { type: String, default: null },
      },
      emits: ['update:modelValue', 'select'],
      setup(_props, { emit }) {
        return () =>
          h(
            'button',
            {
              type: 'button',
              'data-testid': 'stub-pick',
              onClick: () => {
                const picked = {
                  runId: 'RUN-20261107100000-aaaa',
                  state: 'FINISHED',
                  totalTests: 12,
                  finishedAt: '2026-11-07T10:00:00+08:00',
                  enqueuedAt: '2026-11-07T09:50:00+08:00',
                  startedAt: '2026-11-07T09:55:00+08:00',
                }
                emit('update:modelValue', picked.runId)
                emit('select', picked)
              },
            },
            'pick',
          )
      },
    }),
  }
})

vi.mock('@/api/testReports', () => ({
  testReportsApi: { list: vi.fn(), create: vi.fn(), createFromCaseSet: vi.fn() },
}))

/**
 * `CaseSetPicker` 桩：一个按钮，点下去派发 `update:modelValue` 与 `select`。
 * 与 `RunPicker` 同理——真实下拉在 happy-dom 下不渲染选项。
 */
vi.mock('@/components/CaseSetPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'CaseSetPickerStub',
      props: {
        projectId: { type: Number, default: 0 },
        modelValue: { type: Number, default: null },
      },
      emits: ['update:modelValue', 'select'],
      setup(_props, { emit }) {
        return () =>
          h(
            'button',
            {
              type: 'button',
              'data-testid': 'stub-pick-case-set',
              onClick: () => {
                const picked = { id: 9, setCode: 'TS-RDMA', name: 'RDMA 冒烟' }
                emit('update:modelValue', picked.id)
                emit('select', picked)
              },
            },
            'pick case set',
          )
      },
    }),
  }
})

const EXISTING: TestReportItem[] = [
  {
    id: 7,
    projectId: 1,
    runId: 'RUN-20261101100000-bbbb',
    title: '回归周报',
    state: 'draft',
    version: 1,
    snapshotHash: null,
    createdAt: '2026-11-08T09:00:00+08:00',
    frozenAt: null,
  },
]

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(TestReportsView)
  app.use(pinia)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

async function click(root: HTMLElement, testid: string): Promise<void> {
  root.querySelector<HTMLElement>(`[data-testid="${testid}"]`)!.click()
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

async function submit(root: HTMLElement): Promise<void> {
  root.querySelector('form.create-bar')!.dispatchEvent(new Event('submit'))
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
  i18n.global.locale.value = 'zh-CN'
  vi.mocked(testReportsApi.list).mockResolvedValue(EXISTING)
  vi.mocked(testReportsApi.create).mockResolvedValue(EXISTING[0])
  vi.mocked(testReportsApi.createFromCaseSet).mockResolvedValue(EXISTING[0])
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('TestReportsView 建草稿主路径', () => {
  it('默认走下拉选择，手打入口不出现在页面上（判据④）', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="stub-pick"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="manual-run-id"]')).toBeNull()
    expect(root.querySelector('[data-testid="toggle-advanced"]')?.textContent).toContain('高级')
  })

  it('「高级：手输 RUN_ID」可切出输入框，再切回下拉', async () => {
    const root = await mountView()
    await click(root, 'toggle-advanced')
    expect(root.querySelector('[data-testid="manual-run-id"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="stub-pick"]')).toBeNull()

    await click(root, 'toggle-advanced')
    expect(root.querySelector('[data-testid="manual-run-id"]')).toBeNull()
    expect(root.querySelector('[data-testid="stub-pick"]')).not.toBeNull()
  })

  it('选中执行记录后标题自动带出 runId（判据③）', async () => {
    const root = await mountView()
    await click(root, 'stub-pick')
    const title = root.querySelector<HTMLInputElement>('[data-testid="draft-title"] input')!
    expect(title.value).toBe('测试报告 RUN-20261107100000-aaaa')
  })

  it('提交时带上项目、选中的 runId 与自动标题', async () => {
    const root = await mountView()
    await click(root, 'stub-pick')
    await submit(root)
    expect(testReportsApi.create).toHaveBeenCalledWith(
      1,
      'RUN-20261107100000-aaaa',
      '测试报告 RUN-20261107100000-aaaa',
    )
  })

  it('没选中任何 run 时提交按钮不可用，且不发请求', async () => {
    const root = await mountView()
    const button = root.querySelector<HTMLButtonElement>('form.create-bar button[type="submit"]')!
    expect(button.disabled).toBe(true)
    await submit(root)
    expect(testReportsApi.create).not.toHaveBeenCalled()
  })

  it('标题可以改，改完按改的提交', async () => {
    const root = await mountView()
    await click(root, 'stub-pick')
    const title = root.querySelector<HTMLInputElement>('[data-testid="draft-title"] input')!
    title.value = 'RoCE 双网卡 11 月回归'
    title.dispatchEvent(new Event('input'))
    await nextTick()
    await submit(root)
    expect(testReportsApi.create).toHaveBeenCalledWith(1, 'RUN-20261107100000-aaaa', 'RoCE 双网卡 11 月回归')
  })

  it('高级路径按手输的 runId 提交，不受下拉选中值影响', async () => {
    const root = await mountView()
    await click(root, 'stub-pick')
    await click(root, 'toggle-advanced')
    const manual = root.querySelector<HTMLInputElement>('[data-testid="manual-run-id"] input')!
    manual.value = 'RUN-ARCHIVED-0001'
    manual.dispatchEvent(new Event('input'))
    await nextTick()
    await submit(root)
    expect(testReportsApi.create).toHaveBeenCalledWith(1, 'RUN-ARCHIVED-0001', '测试报告 RUN-20261107100000-aaaa')
  })
})

describe('TestReportsView 按用例集来源（FR-v3.10-03）', () => {
  it('默认来源是「按执行记录」，用例集选择器不在页面上', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="source-run"]')?.className).toContain('is-active')
    expect(root.querySelector('[data-testid="stub-pick"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="stub-pick-case-set"]')).toBeNull()
  })

  it('切到「按用例集」后换成选择器，且不再渲染 run 下拉', async () => {
    const root = await mountView()
    await click(root, 'source-case-set')
    expect(root.querySelector('[data-testid="stub-pick-case-set"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="stub-pick"]')).toBeNull()
    // 手打 runId 的「高级」入口只属于「按执行记录」，切来源后不应残留
    expect(root.querySelector('[data-testid="toggle-advanced"]')).toBeNull()
  })

  it('切回来会清掉用例集选择，避免两种来源的选择混在一起', async () => {
    const root = await mountView()
    await click(root, 'source-case-set')
    await click(root, 'stub-pick-case-set')
    const titleAfterPick = root.querySelector<HTMLInputElement>('[data-testid="draft-title"] input')!
    expect(titleAfterPick.value).toBe('用例集报告 RDMA 冒烟')

    await click(root, 'source-run')
    const titleBack = root.querySelector<HTMLInputElement>('[data-testid="draft-title"] input')!
    expect(titleBack.value).toBe('')
  })

  it('没用例集时不能提交（空 run + 空集不冒充可提交）', async () => {
    const root = await mountView()
    await click(root, 'source-case-set')
    const button = root.querySelector<HTMLButtonElement>('form.create-bar button[type="submit"]')!
    expect(button.disabled).toBe(true)
    await submit(root)
    expect(testReportsApi.createFromCaseSet).not.toHaveBeenCalled()
  })

  it('按用例集提交：留空「指定 RUN_ID」= 用该集最近一次执行', async () => {
    const root = await mountView()
    await click(root, 'source-case-set')
    await click(root, 'stub-pick-case-set')
    await submit(root)
    expect(testReportsApi.createFromCaseSet).toHaveBeenCalledWith(1, 9, '', '用例集报告 RDMA 冒烟')
    // 旧路径一次都不能被调用，否则等于两条路径混着走
    expect(testReportsApi.create).not.toHaveBeenCalled()
  })

  it('按用例集提交：填了指定 RUN_ID 时原样带上（钉死某一次执行）', async () => {
    const root = await mountView()
    await click(root, 'source-case-set')
    await click(root, 'stub-pick-case-set')
    const pinned = root.querySelector<HTMLInputElement>('[data-testid="case-set-run-id"] input')!
    pinned.value = 'RUN-20261107100000-aaaa'
    pinned.dispatchEvent(new Event('input'))
    await nextTick()
    await submit(root)
    expect(testReportsApi.createFromCaseSet).toHaveBeenCalledWith(
      1,
      9,
      'RUN-20261107100000-aaaa',
      '用例集报告 RDMA 冒烟',
    )
  })
})

describe('TestReportsView 列表', () => {
  it('列表带「创建时间」列并格式化输出', async () => {
    const root = await mountView()
    const text = root.textContent ?? ''
    expect(text).toContain('创建时间')
    // 2026-11-08T09:00:00+08:00 → 年月日时分里的「11/08」片段
    expect(text).toMatch(/2026/)
    expect(text).toContain('回归周报')
  })

  it('来源列区分「按执行记录」与「用例集 #id」（判据④ 列表可辨来源）', async () => {
    vi.mocked(testReportsApi.list).mockResolvedValue([
      { ...EXISTING[0], id: 7, sourceType: 'RUN', caseSetId: null },
      { ...EXISTING[0], id: 8, title: '用例集报告', sourceType: 'CASE_SET', caseSetId: 9 },
    ])
    const root = await mountView()
    const text = root.textContent ?? ''
    expect(text).toContain('按执行记录')
    expect(text).toContain('用例集 #9')
  })

  it('建草稿成功后清空选择并给提示', async () => {
    const root = await mountView()
    await click(root, 'stub-pick')
    await submit(root)
    expect(root.textContent).toContain('草稿已创建')
    const title = root.querySelector<HTMLInputElement>('[data-testid="draft-title"] input')!
    expect(title.value).toBe('')
  })
})
