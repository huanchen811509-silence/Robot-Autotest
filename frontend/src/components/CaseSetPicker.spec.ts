/**
 * `CaseSetPicker` 组件级回归（FR-v3.10-03 判据①②）。
 *
 * 只测「取数与空态」这三件容易悄悄错的事：
 * ① 必须按**当前项目**取（漏传 projectId 会列出别的项目的用例集，选完建出跨项目报告）；
 * ② 接口失败要显示失败标记，**不能**显示「本项目还没有用例集」——两者是相反结论；
 * ③ 未选项目时不发请求，直接空态。
 *
 * 「选中后 emit 什么」由 `TestReportsView.spec` 用桩组件覆盖（真实下拉在 happy-dom 下
 * 不渲染选项，点不到）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { caseSetApi } from '@/api/cases'
import CaseSetPicker from '@/components/CaseSetPicker.vue'

vi.mock('@/api/cases', () => ({ caseSetApi: { page: vi.fn() } }))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia

async function mountPicker(projectId = 1): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () => h(CaseSetPicker, { projectId, modelValue: null, 'onUpdate:modelValue': () => {} }),
  })
  app.use(pinia)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

beforeEach(() => {
  vi.clearAllMocks()
  pinia = createPinia()
  setActivePinia(pinia)
  i18n.global.locale.value = 'zh-CN'
  vi.mocked(caseSetApi.page).mockResolvedValue({
    items: [
      { id: 9, projectId: 1, setCode: 'TS-RDMA', name: 'RDMA 冒烟', description: null },
      { id: 10, projectId: 1, setCode: 'TS-ROCE', name: 'RoCE 回归', description: null },
    ],
    total: 2,
    page: 1,
    size: 100,
  } as never)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('CaseSetPicker', () => {
  it('按当前项目取用例集', async () => {
    await mountPicker(1)
    expect(caseSetApi.page).toHaveBeenCalledWith(1, { page: 1, size: 100 })
  })

  it('有用例集时不显示空态也不显示失败', async () => {
    const root = await mountPicker()
    expect(root.querySelector('[data-testid="case-set-empty"]')).toBeNull()
    expect(root.querySelector('[data-testid="case-set-failed"]')).toBeNull()
  })

  it('接口失败 → 显示失败，而不是「本项目还没有用例集」', async () => {
    vi.mocked(caseSetApi.page).mockRejectedValue(new Error('boom'))
    const root = await mountPicker()
    expect(root.querySelector('[data-testid="case-set-failed"]')?.textContent).toContain('加载失败')
    expect(root.querySelector('[data-testid="case-set-empty"]')).toBeNull()
  })

  it('未选项目时不发请求，直接空态', async () => {
    const root = await mountPicker(0)
    expect(caseSetApi.page).not.toHaveBeenCalled()
    expect(root.querySelector('[data-testid="case-set-empty"]')?.textContent).toContain('还没有用例集')
  })
})
