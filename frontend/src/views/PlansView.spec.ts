/**
 * `PlansView` 视图级回归（UX-04b 逐页迁移）。
 *
 * 本页迁移里被重新接线的三处，全是「静态检查看不出来」的：
 * ① 表格 6 列改 `columns` 渲染后，「名称 + 套件路径」「Cron + 时区」这类双行单元格
 *    容易丢一半（渲染函数只返回第一行也不报错）；
 * ② 「暂停 / 恢复」同一个按钮两个文案，接错就一直显示暂停、点下去反而恢复；
 * ③ 编辑对话框的预填——`openEdit` → `form` → `FormField` 这条链断了会打开一个空表单，
 *    用户按保存就把原计划覆盖成空值。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App, type Slots } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import PlansView from '@/views/PlansView.vue'
import { planApi } from '@/api/plans'
import { auditApi } from '@/api/audit'
import { ApiError } from '@/api/http'

/**
 * 二次确认桩。
 *
 * 「立即触发」「删除」都先 `await confirmAction(...)`：把它留成真实实现，
 * 单测里会挂在没有人点的弹窗上——请求当然一条都不会发，测试还以为自己过了。
 * 用 vi.hoisted 是因为 vi.mock 的工厂会被提升到 import 之前，普通变量拿不到。
 */
const { confirmActionMock } = vi.hoisted(() => ({ confirmActionMock: vi.fn(async () => true) }))
vi.mock('@/composables/useConfirm', () => ({ confirmAction: confirmActionMock }))

vi.mock('@/api/plans', () => ({
  planApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 1,
          projectId: 1,
          name: '每夜全量',
          testSetId: null,
          environmentId: null,
          suitePaths: ['suites/roce', 'suites/rdma'],
          includeTag: null,
          concurrency: 2,
          timeoutSeconds: 3600,
          cron: '0 0 2 * * *',
          timezone: 'Asia/Shanghai',
          status: 'enabled',
          overlapPolicy: 'skip',
          lastFireAt: null,
          nextFireAt: '2026-11-06T02:00:00+08:00',
          nextFireTimes: ['2026-11-06T02:00:00+08:00', '2026-11-07T02:00:00+08:00'],
          dependencyIds: [],
          lastRunId: 'RUN-001',
          lastRunState: 'FINISHED',
          lastRunFinishedAt: '2026-11-05T02:08:00+08:00',
          createdBy: null,
          createdAt: null,
        },
        {
          id: 2,
          projectId: 1,
          name: '手工回归',
          testSetId: 7,
          environmentId: 3,
          suitePaths: ['suites/manual'],
          includeTag: 'smoke',
          concurrency: 1,
          timeoutSeconds: 600,
          cron: '0 30 9 * * MON',
          timezone: 'Asia/Shanghai',
          status: 'paused',
          overlapPolicy: 'skip',
          lastFireAt: null,
          nextFireAt: null,
          nextFireTimes: [],
          dependencyIds: [1],
          lastRunId: null,
          lastRunState: null,
          lastRunFinishedAt: null,
          createdBy: null,
          createdAt: null,
        },
      ],
      total: 2,
      page: 1,
      size: 100,
    })),
    create: vi.fn(),
    update: vi.fn(),
    preview: vi.fn(async () => []),
    pause: vi.fn(),
    resume: vi.fn(),
    triggerNow: vi.fn(async () => ({ runId: 'RUN-NEW' })),
    remove: vi.fn(),
    conflicts: vi.fn(async () => []),
    batch: vi.fn(async () => []),
  },
}))

vi.mock('@/api/cases', () => ({
  caseSetApi: {
    page: vi.fn(async () => ({
      items: [{ id: 7, projectId: 1, setCode: 'SMOKE', name: '冒烟集', description: null }],
      total: 1,
      page: 1,
      size: 100,
    })),
    create: vi.fn(),
  },
}))

vi.mock('@/api/audit', () => ({
  auditApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 11,
          projectId: 1,
          actorId: 0,
          operator: 'system',
          action: 'PLAN_DOWNSTREAM_SKIPPED',
          resourceType: 'execution_plan',
          resourceId: '1',
          detail:
            'mark=skipped_dependency, downstream=2, reason=upstream_failed, upstream_state=failed, upstream_run=RUN-FAIL-001',
          summary: null,
          ip: null,
          occurredAt: '2026-11-06T02:08:00+08:00',
        },
        {
          id: 12,
          projectId: 1,
          actorId: 0,
          operator: 'system',
          action: 'PLAN_DOWNSTREAM_SKIPPED',
          resourceType: 'execution_plan',
          resourceId: '1',
          detail:
            'mark=skipped_dependency, downstream=999, reason=downstream_paused, upstream_run=RUN-FAIL-002',
          summary: null,
          ip: null,
          occurredAt: '2026-11-06T02:09:00+08:00',
        },
      ],
      total: 2,
      page: 1,
      size: 20,
    })),
    exportCsv: vi.fn(),
  },
}))

vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

/** 组件用 `<RouterLink v-slot="{ navigate }" custom>` 包了按钮，给同契约的桩即可。 */
const RouterLinkStub = {
  name: 'RouterLink',
  props: { to: { type: [String, Object], default: '' } },
  setup(_props: unknown, { slots }: { slots: Slots }) {
    return () => h('a', slots.default?.({ navigate: () => {} }))
  },
}

/**
 * 真实 router（memory history）。
 *
 * 为什么不再用空 RouterLink 桩：v3.10 起「计划组 / 依赖图 / 返回运行列表」是菜单项，
 * 走 `router.push` 而不是 <RouterLink>。没有 router 时 `useRouter()` 会告警，
 * 而且「点了菜单到底去没去那个页面」根本断言不了。
 */
function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/plans', component: { template: '<div />' } },
      { path: '/plans/groups', component: { template: '<div />' } },
      { path: '/plans/graph', component: { template: '<div />' } },
      { path: '/runs', component: { template: '<div />' } },
    ],
  })
}

let router: Router | null = null
let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  router = createTestRouter()
  app = createApp(PlansView)
  app.use(pinia!)
  app.use(router)
  app.use(i18n)
  app.component('RouterLink', RouterLinkStub)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function rows(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>('.n-data-table-tbody .n-data-table-tr')]
}

/**
 * 打开某一行的「更多」菜单（FR-v3.10-01 判据②）。
 *
 * 菜单内容是 naive-ui teleport 到 body 的，不在 root 里——所以先点行内触发点，
 * 再去 `document.body` 里按文案取菜单项。用真实点击而不是直接调函数，
 * 是为了顺带证明「菜单真的能点开」。
 */
async function openRowMenu(root: HTMLElement, rowIndex: number): Promise<void> {
  const trigger = rows(root)[rowIndex].querySelector<HTMLElement>('.more-menu')
  if (!trigger) throw new Error(`row ${rowIndex} has no more-menu trigger`)
  trigger.click()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

/**
 * 当前打开的菜单容器。
 *
 * naive-ui 把下拉 teleport 到 body，而 body 会**跨用例累积**（上一个用例的菜单节点
 * 不会随组件卸载消失）。所以不能全局按文案找，必须只认最后打开的那一个，
 * 否则会读到上一条用例的菜单——「暂停/恢复文案」用例就是被这个坑咬过。
 */
function activeMenu(): HTMLElement {
  const menus = [...document.body.querySelectorAll<HTMLElement>('.n-dropdown')]
  const last = menus[menus.length - 1]
  if (!last) throw new Error('no dropdown is open')
  return last
}

/** 当前菜单里的文案列表。 */
function menuLabels(): string[] {
  return [...activeMenu().querySelectorAll<HTMLElement>('.n-dropdown-option')].map((el) =>
    (el.textContent ?? '').trim(),
  )
}

/**
 * 点菜单项。
 *
 * 直接 `li.click()` 在 happy-dom 下不触发 naive-ui 的 select：它把 onClick 挂在
 * 选项内部的 body 节点上，`li` 本身只负责布局。所以派发一个冒泡的 click 到 body 层，
 * 让事件真的走一遍冒泡链——顺便验证「点菜单项」这条路径本身是通的。
 */
function clickMenuItem(text: string): void {
  const item = menuItem(text)
  const target =
    item.querySelector<HTMLElement>('.n-dropdown-option-body__label') ?? item
  // 真实点击是 mousedown → mouseup → click 三步：naive-ui 的选项在 mousedown 时记激活态，
  // 只发 click 会在某些选项上「点不动」（早期版本就是这样漏掉了「立即触发」）
  for (const type of ['mousedown', 'mouseup', 'click']) {
    target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true }))
  }
}

/** 从当前打开的菜单里按文案取菜单项（选项节点是 li.n-dropdown-option）。 */
function menuItem(text: string): HTMLElement {
  const item = [...activeMenu().querySelectorAll<HTMLElement>('.n-dropdown-option')].find(
    (el) => (el.textContent ?? '').trim() === text,
  )
  if (!item) throw new Error(`menu item not found: ${text}`)
  return item
}

/** 顶栏按钮（新建 / 刷新 / 返回运行列表），不在表格行里。 */
function toolbarButton(root: HTMLElement, text: string): HTMLButtonElement {
  const button = [...root.querySelectorAll<HTMLButtonElement>('.toolbar button')].find(
    (item) => item.textContent?.trim() === text,
  )
  if (!button) throw new Error(`toolbar button not found: ${text}`)
  return button
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  // 下拉是 teleport 到 body 的，组件卸载不会带走它：手动清，防止下一条用例读到上一条的菜单
  document.body.querySelectorAll('.n-dropdown, .v-binder-follower-container').forEach((node) => node.remove())
  app = null
  host = null
  pinia = null
  router = null
})

describe('PlansView 列表', () => {
  it('双行单元格两行都在：名称 + 套件路径、Cron + 时区', async () => {
    const root = await mountView()
    expect(root.textContent).toContain('每夜全量')
    // 套件路径拼成一行，不能只渲染名称就把路径丢掉
    expect(root.textContent).toContain('suites/roce, suites/rdma')
    expect(root.textContent).toContain('0 0 2 * * *')
    expect(root.textContent).toContain('Asia/Shanghai')
  })

  it('未来触发时间保留第 2、3 次，不只显示第一个', async () => {
    const root = await mountView()
    // 两次未来触发都来自同一条计划；只渲染首个的话第二次的时间不会出现
    const second = i18n.global.t('plans.nextFire')
    expect(second).toBeTruthy()
    const body = rows(root)[0].textContent ?? ''
    const matches = body.match(/\d{2}:\d{2}/g) ?? []
    expect(matches.length).toBeGreaterThanOrEqual(2)
  })

  it('状态徽标图标 + 文字双编码', async () => {
    const root = await mountView()
    const tags = [...root.querySelectorAll<HTMLElement>('.status-tag')]
    expect(tags).toHaveLength(2)
    expect(tags.every((tag) => tag.querySelector('svg') !== null)).toBe(true)
  })

  it('暂停 / 恢复按行状态取文案，不是全页一个文案', async () => {
    const root = await mountView()
    const pauseText = i18n.global.t('plans.pause')
    const resumeText = i18n.global.t('plans.resume')

    // 行内只剩一个「更多」，文案差异要在菜单里看：启用中的行给「暂停」，已暂停的行给「恢复」
    await openRowMenu(root, 0)
    expect(rows(root)[0].querySelectorAll('button')).toHaveLength(1)
    expect(menuLabels()).toContain(pauseText)
    expect(menuLabels()).not.toContain(resumeText)

    await openRowMenu(root, 1)
    expect(menuLabels()).toContain(resumeText)
    expect(menuLabels()).not.toContain(pauseText)
  })
})

describe('PlansView 编辑对话框', () => {
  it('点「编辑」预填原值，而不是打开一张空表单', async () => {
    const root = await mountView()
    expect(root.querySelector('.form-card')).toBeNull()

    await openRowMenu(root, 1)
    clickMenuItem(i18n.global.t('plans.edit'))
    await nextTick()

    const card = root.querySelector('.form-card')
    expect(card).not.toBeNull()
    const inputs = [...card!.querySelectorAll<HTMLInputElement>('input')]
    const values = inputs.map((input) => input.value)
    expect(values).toContain('手工回归')
    expect(values).toContain('0 30 9 * * MON')
    expect(values).toContain('smoke')
  })

  it('新建打开空表单，且不显示「编辑」标题', async () => {
    const root = await mountView()
    toolbarButton(root, i18n.global.t('plans.create')).click()
    await nextTick()

    const card = root.querySelector('.form-card')
    expect(card).not.toBeNull()
    expect(card!.textContent).toContain(i18n.global.t('plans.createTitle'))
    expect(card!.textContent).not.toContain(i18n.global.t('plans.editTitle'))
  })

  it('依赖列表排除自己，勾选后写回 dependencyIds', async () => {
    const root = await mountView()
    await openRowMenu(root, 0)
    clickMenuItem(i18n.global.t('plans.edit'))
    await nextTick()

    const deps = [...root.querySelectorAll<HTMLElement>('.dep-list .n-checkbox')]
    // 编辑「每夜全量」时，可选依赖只有它自己以外的那条
    expect(deps).toHaveLength(1)
    expect(deps[0].textContent).toContain('手工回归')

    deps[0].click()
    await nextTick()
    expect(deps[0].className).toContain('n-checkbox--checked')
  })
})

describe('PlansView 编排冲突（W5）', () => {
  /** 冲突区默认收起（FR-v3.10-01 判据③），要看内容先展开。 */
  async function openConflicts(root: HTMLElement): Promise<void> {
    const toggle = root.querySelector<HTMLElement>('[data-testid="conflict-toggle"]')
    if (!toggle) throw new Error('conflict toggle not found')
    toggle.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }

  it('冲突行用计划名展示涉及的计划，并标记严重级', async () => {
    vi.mocked(planApi.conflicts).mockResolvedValueOnce([
      { type: 'DUPLICATE_TARGET', severity: 'WARN', planIds: [1, 2], detail: '目标完全相同' },
    ])
    const root = await mountView()
    // 收起态：只有入口按钮，不许把冲突行渲染出来
    expect(root.querySelector('[data-testid="conflict-row"]')).toBeNull()
    expect(root.querySelector('[data-testid="conflict-collapsed-hint"]')).not.toBeNull()

    await openConflicts(root)
    const row = root.querySelector<HTMLElement>('[data-testid="conflict-row"]')
    expect(row).not.toBeNull()
    expect(row!.textContent).toContain(i18n.global.t('plans.conflictTypes.DUPLICATE_TARGET'))
    expect(row!.textContent).toContain('每夜全量')
    expect(row!.textContent).toContain('手工回归')
    expect(row!.getAttribute('data-severity')).toBe('WARN')
  })

  it('无冲突时显示空态，不显示批量按钮', async () => {
    const root = await mountView()
    // 收起时连「未发现冲突」都不该出现——那会让人以为已经查过了
    expect(root.querySelector('[data-testid="conflict-empty"]')).toBeNull()

    await openConflicts(root)
    expect(root.querySelector('[data-testid="conflict-empty"]')?.textContent).toContain(
      i18n.global.t('plans.conflictEmpty'),
    )
    expect(root.querySelector('[data-testid="conflict-row"]')).toBeNull()
  })

  it('全选后批量暂停：多条冲突里的计划去重后一次性提交', async () => {
    vi.mocked(planApi.conflicts).mockResolvedValue([
      { type: 'DUPLICATE_TARGET', severity: 'WARN', planIds: [1, 2], detail: 'd1' },
      { type: 'SAME_SLOT', severity: 'WARN', planIds: [2, 3], detail: 'd2' },
    ])
    vi.mocked(planApi.batch).mockResolvedValue([
      { planId: 1, changed: true, status: 'PAUSED', message: '已更新' },
      { planId: 2, changed: true, status: 'PAUSED', message: '已更新' },
      { planId: 3, changed: false, status: 'PAUSED', message: '已处于目标状态' },
    ])
    const root = await mountView()
    await openConflicts(root)

    root.querySelector<HTMLElement>('.conflict-all')!.click()
    await nextTick()
    const pause = [...root.querySelectorAll<HTMLButtonElement>('[data-testid="plan-conflicts"] button')]
      .find((button) => button.textContent?.includes(i18n.global.t('plans.conflictPause')))
    expect(pause).toBeDefined()
    pause!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(planApi.batch).toHaveBeenCalledWith(1, [1, 2, 3], 'PAUSED')
    expect(root.querySelector('[data-testid="conflict-batch-result"]')?.textContent).toContain(
      i18n.global.t('plans.conflictApplied', { ok: 2, failed: 1 }),
    )
  })
})

describe('PlansView 依赖跳过事件（PLN-02）', () => {
  function toggle(root: HTMLElement): HTMLButtonElement {
    const button = [...root.querySelectorAll<HTMLButtonElement>('.skipped-head button')][0]
    if (!button) throw new Error('skipped toggle not found')
    return button
  }

  it('展开后按审计 detail 还原下游计划名、原因文案与上游运行号', async () => {
    const root = await mountView()
    expect(root.querySelector('.skipped-table')).toBeNull()
    expect(auditApi.page).not.toHaveBeenCalled()

    toggle(root).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(auditApi.page).toHaveBeenCalledWith({
      projectId: 1,
      action: 'PLAN_DOWNSTREAM_SKIPPED',
      from: undefined,
      to: undefined,
      page: 1,
      size: 20,
    })
    const body = [...root.querySelectorAll('.skipped-table .n-data-table-tbody .n-data-table-tr')].map(
      (tr) => tr.textContent ?? '',
    )
    expect(body).toHaveLength(2)
    // downstream=2 命中已有计划名；原因码 upstream_failed 走本地化，不显示原始码
    expect(body[0]).toContain('手工回归')
    expect(body[0]).toContain(i18n.global.t('plans.skippedReasons.upstream_failed'))
    expect(body[0]).toContain('RUN-FAIL-001')
    // downstream=999 在计划列表里查不到，退回 #id，不能显示成空
    /* color-check: ignore-start 这里是计划 id 前缀，不是硬编码色值 */
    expect(body[1]).toContain('#999')
    /* color-check: ignore-end */
    expect(body[1]).toContain(i18n.global.t('plans.skippedReasons.downstream_paused'))
  })

  it('无权限时不给空表格，明确提示仅管理员或项目所有者可看', async () => {
    vi.mocked(auditApi.page).mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN', 'forbidden'))
    const root = await mountView()

    toggle(root).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(root.querySelector('.skipped-table')).toBeNull()
    expect(root.textContent).toContain(i18n.global.t('plans.skippedForbidden'))
  })

  it('超过一页时给翻页器，翻页带页码重新拉（PLN-02 残余）', async () => {
    vi.mocked(auditApi.page).mockResolvedValueOnce({
      items: [
        {
          id: 11,
          projectId: 1,
          actorId: 0,
          operator: 'system',
          action: 'PLAN_DOWNSTREAM_SKIPPED',
          resourceType: 'execution_plan',
          resourceId: '1',
          detail: 'mark=skipped_dependency, downstream=2, reason=upstream_failed, upstream_run=RUN-1',
          summary: null,
          ip: null,
          occurredAt: '2026-11-06T02:08:00+08:00',
        },
      ],
      total: 45,
      page: 1,
      size: 20,
    })
    const root = await mountView()

    toggle(root).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    // 45 条 / 每页 20 → 3 页，翻页器必须出现（原来写死 20 条时根本没有这个东西）
    const pager = root.querySelector('.skipped-pager')
    expect(pager).not.toBeNull()
    expect(root.textContent).toContain(i18n.global.t('plans.skippedTotal', { n: 45 }))

    const page2 = [...pager!.querySelectorAll<HTMLElement>('.n-pagination-item')].find(
      (el) => (el.textContent ?? '').trim() === '2',
    )
    if (!page2) throw new Error('page 2 button not found')
    page2.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(vi.mocked(auditApi.page).mock.calls.at(-1)?.[0]).toMatchObject({ page: 2, size: 20 })
  })

  it('填时间范围后回到第一页并按 from 重新拉（PLN-02 残余）', async () => {
    const root = await mountView()

    toggle(root).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const input = root.querySelector<HTMLInputElement>('.skipped-from input')
    if (!input) throw new Error('from date input not found')
    input.value = '2026-01-01'
    input.dispatchEvent(new Event('input'))
    input.dispatchEvent(new Event('change'))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(vi.mocked(auditApi.page).mock.calls.at(-1)?.[0]).toMatchObject({
      from: '2026-01-01',
      page: 1,
    })
  })
})

/**
 * FR-v3.9-04 判据④：计划页要真接分页，且单页 ≤ 50 行。
 *
 * 为什么单独立一组：真实探针在演示项目上只看到 0 行（该项目没有计划），
 * 而分页条是 `v-if="plans.length > planRowsPerPage"` ——「没计划所以没分页」和
 * 「没实现分页」在 DOM 上长得一模一样。这一组用 60 条计划把两条判据都钉住。
 */
describe('PlansView 分页（FR-v3.9-04 判据④）', () => {
  function manyPlans(count: number) {
    return Array.from({ length: count }, (_, index) => ({
      id: index + 1,
      projectId: 1,
      name: `计划-${String(index + 1).padStart(2, '0')}`,
      testSetId: null,
      environmentId: null,
      suitePaths: ['suites/roce'],
      includeTag: null,
      concurrency: 1,
      timeoutSeconds: 600,
      cron: '0 0 2 * * *',
      timezone: 'Asia/Shanghai',
      status: 'enabled',
      overlapPolicy: 'skip',
      lastFireAt: null,
      nextFireAt: null,
      nextFireTimes: [],
      dependencyIds: [],
      lastRunId: null,
      lastRunState: null,
      lastRunFinishedAt: null,
      createdBy: null,
      createdAt: null,
    }))
  }

  it('计划超过一页时分页条出现，且单页不超过 50 行', async () => {
    // 故意记住最大档 100：判据要求单页 ≤50，实现里必须再取一次 min(.,50)
    window.localStorage.setItem('robot.list.pageSize', '100')
    vi.mocked(planApi.page).mockResolvedValueOnce({
      items: manyPlans(60),
      total: 60,
      page: 1,
      size: 100,
    } as never)

    const root = await mountView()
    window.localStorage.clear()

    expect(rows(root).length).toBe(50)
    expect(root.querySelector('.pager')).not.toBeNull()
    // 50 条/页 + 60 条 → 2 页
    expect(root.textContent).toContain('计划-50')
    expect(root.textContent).not.toContain('计划-51')
  })

  it('翻到第 2 页时数据跟着换（不是只换页码）', async () => {
    window.localStorage.setItem('robot.list.pageSize', '100')
    vi.mocked(planApi.page).mockResolvedValueOnce({
      items: manyPlans(60),
      total: 60,
      page: 1,
      size: 100,
    } as never)

    const root = await mountView()
    window.localStorage.clear()

    const pager = root.querySelector('.pager')
    if (!pager) throw new Error('pager not found')
    const page2 = [...pager.querySelectorAll<HTMLElement>('.n-pagination-item')].find(
      (el) => (el.textContent ?? '').trim() === '2',
    )
    if (!page2) throw new Error('page 2 button not found')
    page2.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const first = rows(root)[0].textContent ?? ''
    expect(first).toContain('计划-51')
    expect(rows(root).length).toBe(10)
  })
})

/**
 * FR-v3.10-01 信息架构：一屏 244 个控件 → 分区 + 收敛操作。
 *
 * 判据①「首屏主操作 ≤ 8」只能数 DOM，不能靠肉眼；判据②「行内操作收进菜单」同理。
 * 这里把两条都钉成断言，并补「菜单里的动作真的会发对应请求」——
 * 否则「收进菜单」很可能收着收着就收丢了（这正是本版最怕的回归）。
 */
describe('PlansView 信息架构（FR-v3.10-01）', () => {
  /**
   * 首屏主操作：页面级可点控件。
   * 排除项各有理由：表格行内（判据②单独约束「行内 1 个」）、分页器（翻页不是主操作）、
   * 全局导航（在侧栏与顶栏，不属于本页）。
   */
  function primaryControls(root: HTMLElement): HTMLElement[] {
    return [...root.querySelectorAll<HTMLElement>('button, [role="button"]')].filter((el) => {
      if (el.closest('.n-data-table-tbody')) return false
      if (el.closest('.n-pagination')) return false
      if (el.closest('.app-side') || el.closest('.app-header')) return false
      return true
    })
  }

  it('首屏主操作不超过 8 个', async () => {
    const root = await mountView()
    const controls = primaryControls(root)
    expect(controls.length).toBeGreaterThan(0)
    expect(controls.length).toBeLessThanOrEqual(8)
  })

  it('行内只剩一个「更多」触发点，四个动作都在菜单里', async () => {
    const root = await mountView()
    expect(rows(root)[0].querySelectorAll('button')).toHaveLength(1)

    await openRowMenu(root, 0)
    expect(menuLabels()).toEqual([
      i18n.global.t('plans.edit'),
      i18n.global.t('plans.pause'),
      i18n.global.t('plans.triggerNow'),
      i18n.global.t('common.delete'),
    ])
  })

  it('工具条「更多」保留三个离开本页的入口', async () => {
    const root = await mountView()
    const trigger = root.querySelector<HTMLElement>('[data-testid="plan-toolbar-more"]')
    expect(trigger).not.toBeNull()
    trigger!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(menuLabels()).toEqual([
      i18n.global.t('plans.groupsEntry'),
      i18n.global.t('plans.graphEntry'),
      i18n.global.t('plans.backToRuns'),
    ])

    // 点了要真的去：菜单化最容易出的问题就是「项还在、跳转没了」
    clickMenuItem(i18n.global.t('plans.graphEntry'))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(router!.currentRoute.value.path).toBe('/plans/graph')
  })

  it('菜单里的「暂停」真的发请求（收进菜单不等于收丢）', async () => {
    vi.mocked(planApi.pause).mockResolvedValue(undefined as never)
    const root = await mountView()

    await openRowMenu(root, 0)
    clickMenuItem(i18n.global.t('plans.pause'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(planApi.pause).toHaveBeenCalledWith(1, 1)
  })

  it('菜单里的「立即触发」真的发请求', async () => {
    vi.mocked(planApi.triggerNow).mockResolvedValue({ runId: 'RUN-NEW' } as never)
    const root = await mountView()

    await openRowMenu(root, 0)
    clickMenuItem(i18n.global.t('plans.triggerNow'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    // 「立即触发」会先要二次确认：确认弹过才算真发请求
    expect(confirmActionMock).toHaveBeenCalled()
    expect(planApi.triggerNow).toHaveBeenCalledWith(1, 1)
  })
})
