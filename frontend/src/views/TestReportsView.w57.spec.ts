/**
 * TestReportsView W57 接入回归（ListScaffold 六槽位样板页）。
 *
 * 复用 `TestReportsView.spec.ts` 的 RunPicker / CaseSetPicker 桩与
 * `@/api/testReports` mock，只补 W57 新增的 `@/api/listCrud`。断言落在骨架编排、
 * 只读提示、筛选/批量互斥、就地刷新、不渲染「新增」等静态结构上。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import { testReportsApi, type TestReportItem } from '@/api/testReports'
import TestReportsView from '@/views/TestReportsView.vue'

vi.mock('@/components/RunPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'RunPickerStub',
      props: { projectId: { type: Number, default: 0 }, modelValue: { type: String, default: null } },
      emits: ['update:modelValue', 'select'],
      setup(_props, { emit }) {
        return () =>
          h('button', {
            type: 'button',
            'data-testid': 'stub-pick',
            onClick: () => {
              emit('update:modelValue', 'RUN-20261107100000-aaaa')
              emit('select', { runId: 'RUN-20261107100000-aaaa', state: 'FINISHED', totalTests: 12 })
            },
          }, 'pick')
      },
    }),
  }
})

vi.mock('@/components/CaseSetPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'CaseSetPickerStub',
      props: { projectId: { type: Number, default: 0 }, modelValue: { type: Number, default: null } },
      emits: ['update:modelValue', 'select'],
      setup(_props, { emit }) {
        return () =>
          h('button', {
            type: 'button',
            'data-testid': 'stub-pick-case-set',
            onClick: () => {
              emit('update:modelValue', 9)
              emit('select', { id: 9, setCode: 'TS-RDMA', name: 'RDMA 冒烟' })
            },
          }, 'pick case set')
      },
    }),
  }
})

vi.mock('@/api/testReports', () => ({
  testReportsApi: { list: vi.fn(), create: vi.fn(), createFromCaseSet: vi.fn() },
}))

vi.mock('@/api/listCrud', () => ({
  listCrudApi: {
    updateTestReport: vi.fn(async () => ({})),
    deleteTestReport: vi.fn(async () => ({})),
    restoreTestReport: vi.fn(async () => ({})),
  },
}))

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

describe('TestReportsView W57 ListScaffold 骨架', () => {
  it('渲染带语义标记的列表骨架', async () => {
    const root = await mountView()
    expect(root.querySelector('.list-scaffold[data-check-semantic]')).not.toBeNull()
  })

  it('#title 与 #filters 都渲染，筛选区带只读说明', async () => {
    const root = await mountView()
    expect(root.textContent).toContain('测试报告列表')
    expect(root.textContent).toContain('报告快照只读，仅元数据可编辑')
    expect(root.querySelector('[data-testid="readonly-notice"]')).not.toBeNull()
  })

  it('默认无选择：渲染筛选区、隐藏批量条（互斥）', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="list-filters"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="list-batch"]')).toBeNull()
  })

  it('渲染列筛选触发按钮', async () => {
    const root = await mountView()
    expect(root.querySelectorAll('[data-testid^="column-filter-trigger-"]').length).toBeGreaterThan(0)
  })
})

describe('TestReportsView W57 B 类动作纪律', () => {
  it('不渲染「新增」按钮，但保留 A 类建草稿入口', async () => {
    const root = await mountView()
    const labels = [...root.querySelectorAll('button')].map((b) => b.textContent?.trim() ?? '')
    expect(labels).not.toContain('新增')
    expect(root.querySelector('form.create-bar')).not.toBeNull()
  })

  it('危险操作确认默认不渲染', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="confirm-ok"]')).toBeNull()
  })

  it('showRefresh 的刷新按钮就地重载列表，不整页刷新', async () => {
    const root = await mountView()
    const button = root.querySelector<HTMLElement>('[data-testid="list-refresh"]')!
    expect(button).not.toBeNull()
    const before = vi.mocked(testReportsApi.list).mock.calls.length
    button.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(vi.mocked(testReportsApi.list).mock.calls.length).toBeGreaterThan(before)
  })
})
