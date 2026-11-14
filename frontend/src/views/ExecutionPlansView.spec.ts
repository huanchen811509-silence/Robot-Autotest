/**
 * `ExecutionPlansView` 视图级回归（v4.11.0 / FR-v4.11-13..16）。
 *
 * <p>这一页是「即时发起台」，四条判据都能被一句错误的实现悄悄绕过：
 * ① 勾选用例集必须**展开成 caseIds** 再提交——直接把 set id 当 case id 传，
 *    后端会因为主键撞上别的用例而跑错一批；
 * ② 五个参数「不填」必须是**不带该键**，不是传 0 / 空串（那会覆盖后端默认值）；
 * ③ `runRequestId` 必须带 `EP-` 前缀，否则「发起记录」无法与用例页发起的运行区分；
 * ④ 安全预检为 blocked 时，发起按钮要真的禁用（只提示不禁用等于没拦）。
 * ⑤ 发起只有「预览 → 确认」这一条路，失败**不许**回退 `runCases`（复核 P0-1）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import ExecutionPlansView from '@/views/ExecutionPlansView.vue'
import { caseApi, caseSetApi } from '@/api/cases'
import { executionApi } from '@/api/execution'
import { runLaunchApi } from '@/api/runLaunch'
import { ApiError } from '@/api/http'

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

// W57-30：默认模拟「后端尚未部署两步发起端点」，既有断言借此走旧 caseIds 回退路径全绿。
vi.mock('@/api/runLaunch', () => ({
  runLaunchApi: {
    preview: vi.fn(),
    confirmAndStart: vi.fn(),
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

/** 预览返回体（与后端 `SelectionPreviewResponse` 同名）。 */
function previewOf(snapshotId: number, caseIds: number[]) {
  return {
    snapshotId,
    selectionSha256: 'f'.repeat(64),
    caseCount: caseIds.length,
    items: caseIds.map((caseId) => ({ caseId, caseCode: `CLI-${caseId}` })),
  }
}

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

  const launchPreview = vi.mocked(runLaunchApi.preview)
  const launchConfirm = vi.mocked(runLaunchApi.confirmAndStart)
  launchPreview.mockReset()
  launchPreview.mockRejectedValue(new Error('preview endpoint unavailable'))
  launchConfirm.mockReset()
  launchConfirm.mockResolvedValue({ runId: 'RUN-NEW-9' } as never)

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

  it('未填参数时确认体不带这些键（不是传 0 / 空串）', async () => {
    const launchPreview = vi.mocked(runLaunchApi.preview)
    const launchConfirm = vi.mocked(runLaunchApi.confirmAndStart)
    launchPreview.mockResolvedValue(previewOf(88, [11]))

    const root = await mountView()
    await selectOne(root)

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    // 选择条件只走预览，执行参数只走确认——两步各管一段，不混在一个请求里。
    expect(launchPreview).toHaveBeenCalledTimes(1)
    expect(launchPreview.mock.calls[0][1].caseIds).toEqual([11])

    expect(launchConfirm).toHaveBeenCalledTimes(1)
    const payload = launchConfirm.mock.calls[0][1]
    expect(payload.snapshotId).toBe(88)
    for (const key of [
      'environmentId',
      'concurrency',
      'timeoutSeconds',
      'testTimeoutSeconds',
      'includeTag',
    ]) {
      expect(Object.prototype.hasOwnProperty.call(payload, key)).toBe(false)
    }
    // 旧链路一旦被调用就是静默回退，判红。
    expect(runCases).not.toHaveBeenCalled()
  })

  it('填了参数才带该键，且值原样透传', async () => {
    vi.mocked(runLaunchApi.preview).mockResolvedValue(previewOf(88, [11]))
    const root = await mountView()
    await selectOne(root)

    const numbers = root.querySelectorAll<HTMLInputElement>('.params input[type="number"]')
    // 顺序：环境 / 并发 / 超时 / 单用例超时
    numbers[1].value = '4'
    numbers[1].dispatchEvent(new Event('input'))
    await nextTick()

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const payload = vi.mocked(runLaunchApi.confirmAndStart).mock.calls[0][1]
    expect(payload.concurrency).toBe(4)
    expect(Object.prototype.hasOwnProperty.call(payload, 'environmentId')).toBe(false)
  })

  it('runRequestId 以 EP- 开头（本页发起的唯一标记），并发起后跳运行详情', async () => {
    vi.mocked(runLaunchApi.preview).mockResolvedValue(previewOf(88, [11]))
    vi.mocked(runLaunchApi.confirmAndStart).mockResolvedValue({ runId: 'RUN-EP-9' } as never)
    const root = await mountView()
    await selectOne(root)

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const payload = vi.mocked(runLaunchApi.confirmAndStart).mock.calls[0][1]
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

/* --------------------------------------------------------------------------
 * W57-29/30 补充断言（以上老断言一条未删，全部保持通过）
 * -------------------------------------------------------------------------- */
/**
 * W57-47 整改（复核 P0-1 / W57-56）：这里的断言**必须与后端 record 同名**。
 *
 * 首版在此伪造了 `token` 与 `new ApiError(409, ...)`，断言的是「我发了请求」而不是
 * 「后端认这个请求」，于是确认阶段恒传 0、前端静默回退旧链路，测试全绿而功能是断的。
 * 现在断言三件事：① 预览返回被读成 `snapshotId`；② 确认体只带后端认的字段；
 * ③ 失败时**不**回退旧链路（回退会绕过摘要比对）。
 */
describe('W57-47 两步发起：字段契约 + 禁止静默回退', () => {
  const launchPreview = vi.mocked(runLaunchApi.preview)
  const launchConfirm = vi.mocked(runLaunchApi.confirmAndStart)

  it('预览返回 snapshotId 时，确认体带 snapshotId 且不含 token', async () => {
    launchPreview.mockResolvedValue({
      snapshotId: 4321,
      selectionSha256: 'a'.repeat(64),
      caseCount: 1,
      items: [{ caseId: 11, caseCode: 'TC-11' }],
    } as never)
    launchConfirm.mockResolvedValue({ runId: 'RUN-NEW-1' } as never)
    const root = await mountView()
    await check(root, 'pick-11')

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(launchConfirm).toHaveBeenCalledTimes(1)
    const body = launchConfirm.mock.calls[0][1] as unknown as Record<string, unknown>
    expect(body.snapshotId).toBe(4321)
    expect(body).not.toHaveProperty('token')
    expect(runCases).not.toHaveBeenCalled()
    expect(routerState.push).toHaveBeenCalledWith('/runs/RUN-NEW-1')
  })

  it('预览体只交后端认的字段（mode / setIds / caseIds）', async () => {
    launchPreview.mockResolvedValue({
      snapshotId: 1, selectionSha256: 'b'.repeat(64), caseCount: 1, items: [],
    } as never)
    launchConfirm.mockResolvedValue({ runId: 'RUN-NEW-2' } as never)
    const root = await mountView()
    await check(root, 'pick-11')

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const previewBody = launchPreview.mock.calls[0][1] as unknown as Record<string, unknown>
    expect(Object.keys(previewBody).sort()).toEqual(['caseIds', 'mode', 'setIds'])
  })

  it('预览失败时如实报错且**不**回退旧链路（不启动任何执行）', async () => {
    launchPreview.mockRejectedValue(new ApiError(40900, 'DIGEST_MISMATCH', '选择摘要不一致'))
    const root = await mountView()
    await check(root, 'pick-11')

    root.querySelector<HTMLButtonElement>('[data-testid="start-run"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    const hint = root.querySelector('[data-testid="start-error"]')?.textContent ?? ''
    expect(hint).toContain('选择摘要不一致')
    // 关键：回退会绕过选择快照与 409 拦截，等于把「执行可信度」整条主题架空。
    expect(runCases).not.toHaveBeenCalled()
    expect(routerState.push).not.toHaveBeenCalledWith(expect.stringContaining('/runs/'))
  })
})

describe('W57-29 toggleSet 缺陷硬对照回归（视图集成）', () => {
  it('集 71(11+12) + 手工勾 13 → 取消 71 → 只剩 1 条（13），旧实现会剩 0', async () => {
    const root = await mountView()
    await check(root, 'pick-set-71') // 集合带来 11、12
    await check(root, 'pick-13') // 手工单独勾 13
    expect(countText(root)).toContain('3')

    const box = root.querySelector<HTMLInputElement>('[data-testid="pick-set-71"]')!
    box.checked = false
    box.dispatchEvent(new Event('change'))
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(countText(root)).toContain('1')
    expect(precheck.mock.calls.at(-1)![1]).toEqual([13])
  })
})
