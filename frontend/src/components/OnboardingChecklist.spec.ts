/**
 * 新手引导（FR-3.1-08）。
 *
 * 两件事必须锁住：完成态只认真实概览数字（0 不能算完成）；折叠 / 不再显示按
 * 用户 + 项目持久化，刷新后不能又弹回来。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import OnboardingChecklist from '@/components/OnboardingChecklist.vue'
import type { ProjectOverview } from '@/api/overview'

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null
let router: Router | null = null

function overview(overrides: {
  cases?: number
  devices?: number
  runs?: number
  finished?: number
}): ProjectOverview {
  return {
    projectId: 1,
    generatedAt: '2026-11-08T10:00:00+08:00',
    windowDays: 30,
    cases: { total: overrides.cases ?? 0, byPriority: [], byReviewStatus: [], topDomains: [] },
    assets: {
      devices: overrides.devices ?? 0,
      ports: 0,
      links: 0,
      credentials: 0,
      environments: 0,
      testSets: 0,
    },
    runs: {
      total: overrides.runs ?? 0,
      inWindow: 0,
      running: 0,
      finished: overrides.finished ?? 0,
      failed: 0,
      canceled: 0,
      casePass: 0,
      caseFail: 0,
      caseSkip: 0,
      passRateBp: null,
      avgDurationMs: null,
      lastRunAt: null,
    },
    recentRuns: [],
    dailyTrend: [],
  }
}

async function mount(data: ProjectOverview, withProject = true): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 1, username: 'qa', displayName: 'QA', roles: [] } as unknown as typeof auth.user
  auth.projects = withProject
    ? ([
        { id: 1, code: 'P1', name: '项目一', enabled: true, visibility: 'private', ownerId: 1 },
      ] as unknown as typeof auth.projects)
    : []
  auth.projectId = withProject ? 1 : null

  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { render: () => null } }],
  })

  app = createApp({ render: () => h(OnboardingChecklist, { data }) })
  app.use(pinia)
  app.use(router)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return host
}

beforeEach(() => {
  i18n.global.locale.value = 'zh-CN'
  localStorage.clear()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
  router = null
  localStorage.clear()
})

describe('新手引导完成态', () => {
  it('概览数字为 0 时五步全部未完成，并给出对应跳转', async () => {
    const root = await mount(overview({}), false)

    expect(root.querySelectorAll('[data-step]')).toHaveLength(5)
    expect(root.querySelectorAll('.onboarding__step--done')).toHaveLength(0)
    expect(root.textContent).toContain('已完成 0/5')
    expect(root.querySelectorAll('.onboarding__link')).toHaveLength(5)
  })

  it('按用例 / 节点 / 运行数字判断完成态', async () => {
    const root = await mount(overview({ cases: 3, devices: 2, runs: 1, finished: 0 }))

    // 创建项目（auth 已有项目）+ 用例 + 节点 + 运行 = 4；查看报告需 finished > 0
    expect(root.querySelectorAll('.onboarding__step--done')).toHaveLength(4)
    expect(root.querySelector('[data-step="importCases"]')?.classList).toContain(
      'onboarding__step--done',
    )
    expect(root.querySelector('[data-step="viewReport"]')?.classList).not.toContain(
      'onboarding__step--done',
    )
  })

  it('五步都完成时自动收起为一句完成文案', async () => {
    const root = await mount(overview({ cases: 1, devices: 1, runs: 1, finished: 1 }))

    expect(root.querySelector('#onboarding-steps')).toBeNull()
    expect(root.querySelector('.onboarding__completed')?.textContent).toContain('引导已完成')
  })
})

describe('折叠与不再显示持久化', () => {
  it('折叠后刷新仍保持收起（按用户 + 项目）', async () => {
    const root = await mount(overview({}))
    const collapse = [...root.querySelectorAll<HTMLButtonElement>('.onboarding__toggle')][0]
    collapse.click()
    await nextTick()

    expect(root.querySelector('#onboarding-steps')).toBeNull()
    const raw = localStorage.getItem('robot.onboarding.1.1')
    expect(raw).toContain('"collapsed":true')

    app?.unmount()
    host?.remove()
    host = null
    app = null

    const remounted = await mount(overview({}))
    expect(remounted.querySelector('#onboarding-steps')).toBeNull()
  })

  it('不再显示后整卡消失，且持久化', async () => {
    const root = await mount(overview({}))
    const dismiss = root.querySelector<HTMLButtonElement>('.onboarding__dismiss')
    dismiss?.click()
    await nextTick()

    expect(root.querySelector('[data-test="onboarding"]')).toBeNull()
    expect(localStorage.getItem('robot.onboarding.1.1')).toContain('"dismissed":true')
  })
})
