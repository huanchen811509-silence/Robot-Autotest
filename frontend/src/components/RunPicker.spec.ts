/**
 * `RunPicker` 组件级回归（FR-v3.10-02 判据①②）。
 *
 * 纯逻辑（排序 / 时间档 / 关键词）已经在 `utils/runPicker.spec.ts` 测死，这里只测
 * 「组件有没有把它接上」——这类接线错误静态检查一个都抓不到：
 * ① `executionApi.page` 必须带上**当前项目**和一次够用的 pageSize，漏传 projectId
 *    会把别的项目的 run 列进下拉，用户选完建出一份指向他人 run 的报告；
 * ② 关键词输入没接进 `filtered` 时，界面照常显示、只是过滤不生效；
 * ③ 加载失败被 `catch` 吞掉只留空下拉，用户会以为「本项目没跑过」。
 *
 * 不测「点选项后 emit select」：naive-ui 的 NSelect 下拉是 teleport + 虚拟列表，
 * happy-dom 量不出宽度、选项不渲染。这条改由 `TestReportsView.spec` 用桩组件覆盖。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { executionApi, type ExecutionRun } from '@/api/execution'
import RunPicker from '@/components/RunPicker.vue'

vi.mock('@/api/execution', () => ({ executionApi: { page: vi.fn() } }))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia

function run(partial: Partial<ExecutionRun> & { runId: string }): ExecutionRun {
  return {
    id: 1,
    projectId: 29,
    testSetId: null,
    state: 'FINISHED',
    priority: 2,
    concurrency: 1,
    deviceScope: null,
    attemptNo: 1,
    totalTests: 0,
    passedTests: 0,
    failedTests: 0,
    failureClass: null,
    failureReason: null,
    failureMessage: null,
    artifactsDir: null,
    enqueuedAt: null,
    startedAt: null,
    finishedAt: null,
    ...partial,
  }
}

const RUNS = [
  run({ runId: 'RUN-20261107100000-aaaa', finishedAt: '2026-11-07T10:00:00+08:00', totalTests: 12 }),
  run({ runId: 'RUN-20261101100000-bbbb', finishedAt: '2026-11-01T10:00:00+08:00', totalTests: 8 }),
]

async function mountPicker(projectId = 29, modelValue: string | null = null): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () => h(RunPicker, { projectId, modelValue, 'onUpdate:modelValue': () => {} }),
  })
  app.use(pinia)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

async function typeKeyword(root: HTMLElement, value: string): Promise<void> {
  const input = root.querySelector<HTMLInputElement>('[data-testid="run-picker-keyword"] input')!
  input.value = value
  input.dispatchEvent(new Event('input'))
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

beforeEach(() => {
  // 必须先清调用记录：不清的话「未选项目不发请求」会读到上一条用例的 4 次调用，
  // 而 `mockResolvedValue` 是清实现才丢，所以清完再重新装一次返回值。
  vi.clearAllMocks()
  pinia = createPinia()
  setActivePinia(pinia)
  i18n.global.locale.value = 'zh-CN'
  vi.mocked(executionApi.page).mockResolvedValue({
    items: RUNS,
    total: RUNS.length,
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

describe('RunPicker', () => {
  it('按当前项目拉最近 100 条执行记录', async () => {
    await mountPicker(29)
    expect(executionApi.page).toHaveBeenCalledWith(29, { page: 1, size: 100 })
  })

  it('有记录时不显示空态提示', async () => {
    const root = await mountPicker()
    expect(root.querySelector('[data-testid="run-picker-empty"]')).toBeNull()
    expect(root.querySelector('[data-testid="run-picker-failed"]')).toBeNull()
  })

  it('关键词过滤后没有命中 → 显示空态；清空关键词 → 空态消失', async () => {
    const root = await mountPicker()
    await typeKeyword(root, 'zzz')
    const empty = root.querySelector('[data-testid="run-picker-empty"]')
    expect(empty?.textContent).toContain('没有执行记录')

    await typeKeyword(root, '')
    expect(root.querySelector('[data-testid="run-picker-empty"]')).toBeNull()
  })

  it('接口失败 → 显示失败标记，且不伪装成「没有记录」', async () => {
    vi.mocked(executionApi.page).mockRejectedValue(new Error('boom'))
    const root = await mountPicker()
    expect(root.querySelector('[data-testid="run-picker-failed"]')?.textContent).toContain('加载失败')
    // 失败时不能同时给空态：两者对用户是相反结论
    expect(root.querySelector('[data-testid="run-picker-empty"]')).toBeNull()
  })

  it('未选项目时不发请求，直接空态', async () => {
    const root = await mountPicker(0)
    expect(executionApi.page).not.toHaveBeenCalled()
    expect(root.querySelector('[data-testid="run-picker-empty"]')).not.toBeNull()
  })

  it('已选中一条时显示该条的用例数摘要', async () => {
    const root = await mountPicker(29, 'RUN-20261107100000-aaaa')
    expect(root.querySelector('[data-testid="run-picker-summary"]')?.textContent).toContain('12 条用例')
  })
})
