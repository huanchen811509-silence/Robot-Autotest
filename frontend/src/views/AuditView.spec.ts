/**
 * `AuditView` 视图级回归（UX-06 走查发现的真实缺陷）。
 *
 * 缺陷：两个 `NDatePicker` 绑的是 `v-model:formatted-value`，初始值给的是空串 `''`。
 * naive-ui 在 setup 阶段就把 formatted-value 交给 date-fns 解析，`format(new Date(''))`
 * 抛 `RangeError: Invalid time value`，整页 setup 失败——走查截图里 `/admin/audit` 是一片空白，
 * 而 `vue-tsc`、单测、构建全都发现不了（没有页面级测试覆盖这个视图）。
 *
 * 本文件锁住两件事：
 * ① 挂载不抛错且标题正常渲染（日期控件初始值必须是 null）；
 * ② 未选日期时查询参数里不出现 from / to。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import AuditView from '@/views/AuditView.vue'
import { auditApi } from '@/api/audit'

vi.mock('@/api/audit', () => ({
  auditApi: {
    page: vi.fn(async () => ({
      items: [
        {
          id: 1,
          projectId: 1,
          actorId: 2,
          operator: 'qa-ui',
          action: 'project:create',
          resourceType: 'project',
          resourceId: '1',
          detail: null,
          summary: 'created project QA',
          ip: '127.0.0.1',
          occurredAt: '2026-11-05T02:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    })),
    exportCsv: vi.fn(async () => new Blob(['csv'])),
  },
}))

let app: App | null = null
let host: HTMLElement | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(AuditView)
  app.use(createPinia())
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

beforeEach(() => {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.projectId = 1
  auth.projects = [
    {
      id: 1,
      code: 'QA',
      name: 'QA 项目',
      description: null,
      enabled: true,
      ownerId: null,
      visibility: null,
      testKernel: 'robot',
    },
  ]
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('AuditView', () => {
  it('日期控件初始值为空时页面正常渲染（回归：空串会让 NDatePicker 抛 RangeError）', async () => {
    const root = await mountView()
    expect(root.querySelector('h1')?.textContent?.trim()).toBe(i18n.global.t('audit.title'))
    // 日期控件挂出来了（说明 setup 没有被异常打断）
    expect(root.querySelectorAll('.n-date-picker').length).toBe(2)
  })

  it('未选日期时查询参数不带 from / to，默认第一页 20 条', async () => {
    await mountView()
    expect(auditApi.page).toHaveBeenCalledTimes(1)
    expect(auditApi.page).toHaveBeenCalledWith({
      projectId: null,
      action: '',
      from: undefined,
      to: undefined,
      page: 1,
      size: 20,
    })
  })
})
