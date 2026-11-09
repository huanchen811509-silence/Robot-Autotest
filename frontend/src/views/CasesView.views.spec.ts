/**
 * 保存视图接入用例页的接线测试：应用视图后筛选/页大小/列回填，页码回 1。
 *
 * <p>纯函数 `applyViewState` 已在 useSavedViews.spec 覆盖；这里只验证 CasesView
 * 真的把结果写回筛选状态、并据此重新请求列表，防止「视图逻辑写对了但页面没接上」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import CasesView from '@/views/CasesView.vue'
import { caseApi } from '@/api/cases'
import type { CaseViewPayload, SavedView } from '@/api/views'

const { viewItems } = vi.hoisted(() => ({ viewItems: [] as SavedView[] }))

vi.mock('@/api/views', () => ({
  viewsApi: {
    list: vi.fn(async () => viewItems),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}))

vi.mock('@/api/cases', () => ({
  caseApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 1,
          caseCode: 'TC-001',
          title: 'first',
          priority: 'P1',
          safetyLevel: 'L1',
          reviewStatus: 'draft',
          domainTag: 'roce',
          capability: 'throughput',
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    })),
    capabilities: vi.fn(async () => []),
    create: vi.fn(),
    submit: vi.fn(),
    review: vi.fn(),
    remove: vi.fn(),
    batch: vi.fn(),
    previewImport: vi.fn(),
    commitImport: vi.fn(),
  },
}))

vi.mock('@/components/ProjectPicker.vue', () => ({
  default: { name: 'ProjectPicker', template: '<div />' },
}))

function makeView(id: number, name: string, payload: Partial<CaseViewPayload> = {}, isDefault = false): SavedView {
  return {
    id,
    projectId: 1,
    userId: 7,
    name,
    scope: 'case',
    payload: {
      keyword: '',
      domain: '',
      priority: '',
      reviewStatus: '',
      safetyLevel: '',
      size: 10,
      columns: ['caseCode', 'title', 'priority', 'safetyLevel', 'reviewStatus', 'domainTag', 'capability'],
      sort: null,
      ...payload,
    },
    isDefault,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(CasesView)
  app.use(pinia!)
  app.use(i18n)
  app.component('RouterLink', {
    name: 'RouterLink',
    props: { to: { type: [String, Object], default: '' } },
    setup() {
      return () => null
    },
  })
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  return root
}

function lastPageCall(): Record<string, unknown> {
  return vi.mocked(caseApi.page).mock.calls.at(-1)?.[1] as Record<string, unknown>
}

beforeEach(() => {
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 1
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
  viewItems.splice(0, viewItems.length)
  vi.clearAllMocks()
})

describe('CasesView 应用保存视图', () => {
  it('默认视图加载后自动回填筛选、页大小与列，页码回 1', async () => {
    viewItems.push(
      makeView(1, '默认视图', {
        keyword: 'roce',
        domain: 'network',
        priority: 'P1',
        reviewStatus: 'pending',
        safetyLevel: 'L1',
        size: 20,
        columns: ['caseCode', 'title'],
      }, true),
    )

    const root = await mountView()

    // 视图回填后页面自己发起的最后一次请求必须带上视图条件
    expect(lastPageCall()).toMatchObject({
      keyword: 'roce',
      domain: 'network',
      priority: 'P1',
      reviewStatus: 'pending',
      safetyLevel: 'L1',
      size: 20,
      page: 1,
    })

    // 隐藏列不再出现在表头，必显的编号列仍在
    expect(root.textContent).toContain(i18n.global.t('cases.caseCode'))
    expect(root.textContent).not.toContain(i18n.global.t('cases.priority'))
  })

  it('从视图菜单应用非默认视图，筛选状态与页大小同步回填', async () => {
    viewItems.push(
      makeView(1, '默认视图', { keyword: 'roce' }, true),
      makeView(2, '高优先级', {
        keyword: 'latency',
        priority: 'P0',
        size: 50,
        columns: ['caseCode', 'title'],
      }),
    )

    const root = await mountView()

    const trigger = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes(i18n.global.t('views.label')),
    )
    expect(trigger).toBeTruthy()
    trigger!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    // NPopover 内容 Teleport 到 body，按视图名找到应用按钮
    const applyButton = [...document.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('高优先级'),
    )
    expect(applyButton).toBeTruthy()
    applyButton!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(lastPageCall()).toMatchObject({
      keyword: 'latency',
      priority: 'P0',
      size: 50,
      page: 1,
    })
  })
})
