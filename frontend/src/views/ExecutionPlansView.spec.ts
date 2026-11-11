/**
 * `ExecutionPlansView` 视图级回归（v4.11.0 / FR-v4.11-13..16）。
 *
 * <p>这一页是「即时发起台」，四条判据都能被一句错误的实现悄悄绕过：
 * ① 勾选用例集必须**展开成 caseIds** 再提交——直接把 set id 当 case id 传，
 *    后端会因为主键撞上别的用例而跑错一批；
 * ② 五个参数「不填」必须是**不带该键**，不是传 0 / 空串（那会覆盖后端默认值）；
 * ③ `runRequestId` 必须带 `EP-` 前缀，否则「发起记录」无法与用例页发起的运行区分；
 * ④ 安全预检为 blocked 时，发起按钮要真的禁用（只提示不禁用等于没拦）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import ExecutionPlansView from '@/views/ExecutionPlansView.vue'
import { caseApi, caseSetApi } from '@/api/cases'
import { executionApi } from '@/api/execution'

vi.mock('@/api/cases', () => ({
  caseApi: { page: vi.fn() },
  caseSetApi: { page: vi.fn(), items: vi.fn() },
}))

vi.mock('@/api/execution', () => ({
  executionApi: {
    page: vi.fn(),
    safetyPrecheck: vi.fn(),
    runCases: vi.fn(),
  },
}))

vi.mock('@/api/iam', () => ({
  projectApi: { list: vi.fn(async () => []), remove: vi.fn(), update: vi.fn() },
  authApi: { renew: vi.fn(), logout: vi.fn() },
  userApi: { page: vi.fn(async () => ({ items: [], total: 0 })) },
}))

const routerState = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerState.push }),
  useRoute: () => ({ query: {}, path: '/execution-plans' }),
}))

const cases = [
  { id: 11, caseCode: 'CLI-001', title: '链路连通性', priority: 'P1', safetyLevel: 'SAFE' },
  { id: 12, caseCode: 'CLI-002', title: '时延抖动', priority: 'P2', safetyLevel: 'SAFE' },
  { id: 13, caseCode: 'CLI-003', title: '破坏性复位', priority: 'P0', safetyLevel: 'DESTRUCTIVE' },
]

const sets = [
  { id: 71, name: '冒烟集', setCode: 'SMOKE' },
  { id: 72, name: '回归集', setCode: 'REGRESS' },
]

/** 集合 71 = 用例 11+12；集合 72 = 用例 13。 */
const setItems: Record<number, Array<{ id: number }>> = {
  71: [{ id: 11 }, { id: 12 }],
  72: [{ id: 13 }],
}

const runs = [
  { id: 1, runId: 'RUN-EP-1', state: 'FINISHED', totalTests: 2, runRequestId: 'EP-abc' },
  { id: 2, runId: 'RUN-UI-1', state: 'FINISHED', totalTests: 5, runRequestId: 'UI-xyz' },
  { id: 3, runId: 'RUN-EP-2', state: 'RUNNING', totalTests: 3, runRequestId: 'EP-def' },
  { id: 4, runId: 'RUN-NULL', state: 'FINISHED', totalTests: 1, runRequestId: null },
]

const casePage = vi.mocked(caseApi.page)
const setPage = vi.mocked(caseSetApi.page)
const setItemsApi = vi.mocked(caseSetApi.items)
const precheck = vi.mocked(executionApi.safetyPrecheck)
const runCases = vi.mocked(executionApi.runCases)
const runsPage = vi.mocked(executionApi.page)

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ExecutionPlansView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

/** 勾选框用 `h()` 渲染：直接置 checked 再派发 change，等价于用户点击。 */
async function check(root: HTMLElement, testid: string): Promise<void> {
  const box = root.querySelector<HTMLInputElement>(`[data-testid="${testid}"]`)!
  box.checked = true
  box.dispatchEvent(new Event('change'))
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function countText(root: HTMLElement): string {
  return root.querySelector('[data-testid="selected-count"]')?.textContent?.trim() ?? ''
}

beforeEach(() => {
  casePage.mockReset()
  casePage.mockResolvedValue({ items: cases, total: 3 } as never)
  setPage.mockReset()
  setPage.mockResolvedValue({ items: sets, total: 2 } as never)
  setItemsApi.mockReset()
  setItemsApi.mockImplementation(async (_projectId: number, setId: number) => setItems[setId] as never)

  precheck.mockReset()
  precheck.mockResolvedValue({ allowed: true, blocked: [] } as never)
  runCases.mockReset()
  runCases.mockResolvedValue({ id: 9, runId: 'RUN-EP-9', state: 'QUEUED' } as never)
  runsPage.mockReset()
  runsPage.mockResolvedValue({ items: runs, total: 4 } as never)

  routerState.push.mockReset()

  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projectId = 93
  auth.user = {
    id: 1,
    username: 'qa-e2e-admin',
    displayName: '质量管理员',
    roles: ['admin'],
  } as never
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('ExecutionPlansView 选择区', () => {
  it('勾单个用例：计数 +1，并触发一次安全预检', async () => {
    const root = await mountView()
    expect(countText(root)).toContain('0')

    await check(root, 'pick-11')

    expect(countText(root)).toContain('1')
    expect(precheck).toHaveBeenCalledTimes(1)
    expect(precheck.mock.calls[0][1]).toEqual([11])
  })

  it('勾用例集展开成 caseIds，而不是把 set id 当用例 id 提交', async () => {
    const root = await mountView()

    await check(root, 'pick-set-71')

    expect(setItemsApi).toHaveBeenCalledWith(93, 71)
    expect(countText(root)).toContain('2')
    expect(precheck.mock.calls.at(-1)![1]).toEqual([11, 12])
  })

  it('再勾第二个集合时并入，不覆盖已选', async () => {
    const root = await mountView()
    await check(root, 'pick-set-71')
    await check(root, 'pick-set-72')

    expect(countText(root)).toContain('3')
    expect(precheck.mock.calls.at(-1)![1]).toEqual([11, 12, 13])
  })
})

describe('ExecutionPlansView 发起', () => {
  async function selectOne(root: HTMLElement): Promise<void> {
    await check(root, 'pick-11')
  }

  it('未填参数时请求体不带这些键（不是传 0 / 空串）', async () => {
    const root = await mountView()
    await selectOne(root)

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(runCases).toHaveBeenCalledTimes(1)
    const payload = runCases.mock.calls[0][1]
    expect(payload.caseIds).toEqual([11])
    for (const key of [
      'environmentId',
      'concurrency',
      'timeoutSeconds',
      'testTimeoutSeconds',
      'includeTag',
    ]) {
      expect(Object.prototype.hasOwnProperty.call(payload, key)).toBe(false)
    }
  })

  it('填了参数才带该键，且值原样透传', async () => {
    const root = await mountView()
    await selectOne(root)

    const numbers = root.querySelectorAll<HTMLInputElement>('.params input[type="number"]')
    // 顺序：环境 / 并发 / 超时 / 单用例超时
    numbers[1].value = '4'
    numbers[1].dispatchEvent(new Event('input'))
    await nextTick()

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const payload = runCases.mock.calls[0][1]
    expect(payload.concurrency).toBe(4)
    expect(Object.prototype.hasOwnProperty.call(payload, 'environmentId')).toBe(false)
  })

  it('runRequestId 以 EP- 开头（本页发起的唯一标记），并发起后跳运行详情', async () => {
    const root = await mountView()
    await selectOne(root)

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const payload = runCases.mock.calls[0][1]
    expect(payload.runRequestId?.startsWith('EP-')).toBe(true)
    // EP- + UUID = 39 字符，必须落在 run.run_request_id 的 VARCHAR(64) 内
    expect(payload.runRequestId!.length).toBeLessThanOrEqual(64)
    expect(routerState.push).toHaveBeenCalledWith('/runs/RUN-EP-9')
  })

  it('一条都没选时发起按钮禁用，点也不发请求', async () => {
    const root = await mountView()
    const start = root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!

    expect(start.disabled).toBe(true)
    start.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(runCases).not.toHaveBeenCalled()
  })

  it('安全预检 blocked 时：列出用例码，且按钮禁用', async () => {
    precheck.mockResolvedValue({
      allowed: false,
      blocked: [{ caseId: 11, caseCode: 'CLI-001', safetyLevel: 'DESTRUCTIVE', reason: '需人工确认' }],
    } as never)
    const root = await mountView()

    await check(root, 'pick-11')

    expect(root.querySelector('[data-testid="blocked-hint"]')?.textContent).toContain('CLI-001')
    expect(root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.disabled).toBe(true)
  })

  it('预检接口失败不阻塞发起（拦截交给后端 403 兜底）', async () => {
    precheck.mockRejectedValue(new Error('boom'))
    const root = await mountView()
    await check(root, 'pick-11')

    expect(root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.disabled).toBe(false)
  })
})

describe('ExecutionPlansView 发起记录', () => {
  it('只保留 EP- 前缀的运行，其他来源不混进来', async () => {
    const root = await mountView()

    expect(runsPage).toHaveBeenCalledWith(93, { page: 1, size: 200 })
    expect(root.textContent).toContain('RUN-EP-1')
    expect(root.textContent).toContain('RUN-EP-2')
    expect(root.textContent).not.toContain('RUN-UI-1')
    expect(root.textContent).not.toContain('RUN-NULL')
  })
})
