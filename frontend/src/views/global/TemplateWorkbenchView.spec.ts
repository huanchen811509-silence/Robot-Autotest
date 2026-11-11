/**
 * `TemplateWorkbenchView` 视图级回归（v4.11.0 / W-E-05；上游 v4.9.2 / OPT-05）。
 *
 * <p>这一页有一句必须成立的话：**编辑的是模板内容清单，不是公共用例本体**。
 * 落到代码上就是「只调 `templateProjectsApi`，不碰任何写回用例的接口」。本 spec 盯：
 * ① 明细按用例 / 用例集分组渲染；
 * ② 内置模板只读——不渲染新增区、行内输入框 disabled、没有删除按钮；
 * ③ 新增项必须同时有名称与引用 ID（后端会跳过 refId 为空的明细，只填名字等于白存）；
 * ④ 每次增删改名都立刻 `saveItems` 持久化，而不是攒到某个看不见的「保存」按钮。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import TemplateWorkbenchView from '@/views/global/TemplateWorkbenchView.vue'
import { templateProjectsApi } from '@/api/templateProjects'

vi.mock('@/api/templateProjects', () => ({
  templateProjectsApi: {
    detail: vi.fn(),
    saveItems: vi.fn(),
    list: vi.fn(),
    create: vi.fn(),
    remove: vi.fn(),
  },
}))

/** 模板 id 来自路由参数（`/g/templates/:id`），这里固定成 5。 */
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '5' }, query: {}, path: '/g/templates/5' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

const detailApi = vi.mocked(templateProjectsApi.detail)
const saveItems = vi.mocked(templateProjectsApi.saveItems)

function makeDetail(builtin: boolean) {
  return {
    template: {
      id: 5,
      name: '基础链路模板',
      code: 'TPL-5',
      kernel: 'robot',
      visibility: 'public',
      description: '',
      builtin,
    },
    items: [
      { itemType: 'CASE', refId: 11, mode: 'reference', name: '链路连通性' },
      { itemType: 'CASE', refId: 12, mode: 'reference', name: '时延抖动' },
      { itemType: 'CASE_SET', refId: 71, mode: 'reference', name: '冒烟集' },
      { itemType: 'SCENARIO', refId: 81, mode: 'reference', name: '场景：上电' },
    ],
    cases: [],
    caseSets: [],
    scenarios: [],
    assets: [],
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(TemplateWorkbenchView)
  app.use(i18n)
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function el(root: HTMLElement, testid: string): HTMLElement | null {
  return root.querySelector<HTMLElement>(`[data-testid="${testid}"]`)
}

function items(root: HTMLElement, key: 'cases' | 'caseSets'): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(`[data-testid="workbench-${key}-item"]`)]
}

beforeEach(() => {
  detailApi.mockReset()
  detailApi.mockResolvedValue(makeDetail(false) as never)
  saveItems.mockReset()
  saveItems.mockResolvedValue(undefined as never)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('TemplateWorkbenchView 明细渲染', () => {
  it('按 CASE / CASE_SET 分组，SCENARIO 不混进可编辑组', async () => {
    const root = await mountView()

    expect(detailApi).toHaveBeenCalledWith('5')
    expect(items(root, 'cases')).toHaveLength(2)
    expect(items(root, 'caseSets')).toHaveLength(1)
    expect(el(root, 'workbench-notice')?.textContent).toContain('清单')
  })

  it('取数失败显示后端原因，不渲染空分组冒充「没有内容」', async () => {
    detailApi.mockRejectedValue(new Error('模板不存在'))
    const root = await mountView()

    expect(el(root, 'workbench-error')?.textContent).toContain('模板不存在')
    expect(el(root, 'workbench-cases')).toBeNull()
  })
})

describe('TemplateWorkbenchView 内置模板只读', () => {
  beforeEach(() => {
    detailApi.mockResolvedValue(makeDetail(true) as never)
  })

  it('显示只读提示，且不渲染新增区与删除按钮', async () => {
    const root = await mountView()

    expect(el(root, 'workbench-readonly')).not.toBeNull()
    expect(el(root, 'workbench-cases-add')).toBeNull()
    expect(el(root, 'workbench-cases-remove')).toBeNull()
    // 行内输入框仍在（能看到名称），但必须是 disabled
    expect((el(root, 'workbench-cases-edit') as HTMLInputElement).disabled).toBe(true)
  })
})

describe('TemplateWorkbenchView 增删改名立即持久化', () => {
  it('只填名称不填引用 ID 时报错且不保存', async () => {
    const root = await mountView()

    const name = el(root, 'workbench-cases-name') as HTMLInputElement
    name.value = '新用例'
    name.dispatchEvent(new Event('input'))
    await nextTick()

    el(root, 'workbench-cases-add')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(saveItems).not.toHaveBeenCalled()
    expect(el(root, 'workbench-message')).not.toBeNull()
  })

  it('名称 + 引用 ID 齐全时保存，且清单里带上原有其它类项', async () => {
    const root = await mountView()

    const name = el(root, 'workbench-cases-name') as HTMLInputElement
    const ref = el(root, 'workbench-cases-ref') as HTMLInputElement
    name.value = '新用例'
    ref.value = '99'
    name.dispatchEvent(new Event('input'))
    ref.dispatchEvent(new Event('input'))
    await nextTick()

    el(root, 'workbench-cases-add')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(saveItems).toHaveBeenCalledTimes(1)
    const [templateId, list] = saveItems.mock.calls[0]
    expect(templateId).toBe(5)
    expect(list).toHaveLength(5)
    // 顺序是 cases → caseSets → others，新项落在用例组末尾而不是整个清单末尾
    expect(list.find((item) => item.refId === 99)).toMatchObject({
      itemType: 'CASE',
      refId: 99,
      name: '新用例',
    })
    // SCENARIO 属于 others，保存时必须原样带回去，不能因为「不可编辑」就丢掉
    expect(list.some((item) => item.itemType === 'SCENARIO')).toBe(true)
  })

  it('删除一条后立刻保存，清单少一条且不误删别组', async () => {
    const root = await mountView()

    el(root, 'workbench-cases-remove')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(saveItems).toHaveBeenCalledTimes(1)
    const [, list] = saveItems.mock.calls[0]
    expect(list).toHaveLength(3)
    expect(list.filter((item) => item.itemType === 'CASE')).toHaveLength(1)
    expect(list.filter((item) => item.itemType === 'CASE_SET')).toHaveLength(1)
  })

  it('改名后保存，新名称进入清单', async () => {
    const root = await mountView()

    const edit = el(root, 'workbench-cases-edit') as HTMLInputElement
    edit.value = '链路连通性（改名）'
    edit.dispatchEvent(new Event('change'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(saveItems).toHaveBeenCalledTimes(1)
    const [, list] = saveItems.mock.calls[0]
    expect(list[0].name).toBe('链路连通性（改名）')
  })
})
