/**
 * ScenariosView 回归（v4.9.2 / OPT-07 / FR-49-26~30）。
 *
 * 判据：路由页可达并显式标注「场景仍限一层（FR-49-29 降级，授权人：大师）」；
 * 创建 / 编组 / 删除三类 CRUD 走 scenarioApi；同页挂载的 CategoryTree 覆盖
 * 两级分类树（二级必属所选一级）与「筛选条数 = 加入条数」。
 *
 * API 用 vi.mock 隔离（含子组件 CategoryTree 的依赖），绝不发真实请求；
 * 测试用 i18n 以 flatJson 注入证据 JSON 的 zh-CN 取值。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import ScenariosView from '@/views/ScenariosView.vue'

const scenarioList = vi.hoisted(() => vi.fn())
const scenarioCreate = vi.hoisted(() => vi.fn())
const scenarioRegroup = vi.hoisted(() => vi.fn())
const scenarioRemove = vi.hoisted(() => vi.fn())
const caseSetPage = vi.hoisted(() => vi.fn())
const addByCategoryFilter = vi.hoisted(() => vi.fn())
const categoriesInScope = vi.hoisted(() => vi.fn())
const globalPage = vi.hoisted(() => vi.fn())
const exportCategoryCsv = vi.hoisted(() => vi.fn())

vi.mock('@/api/cases', () => ({
  scenarioApi: {
    list: scenarioList,
    create: scenarioCreate,
    regroup: scenarioRegroup,
    remove: scenarioRemove,
  },
  caseSetApi: { page: caseSetPage, addByCategoryFilter },
}))
vi.mock('@/api/globalCases', () => ({
  globalCaseApi: { categoriesInScope, page: globalPage, exportCategoryCsv },
}))

const ZH: Record<string, string> = {
  'scenarios.title': '场景管理',
  'scenarios.subtitle': '创建 / 编组 / 删除场景',
  'scenarios.oneLevelHint': '场景仍限一层（FR-49-29 降级，授权人：大师）；case_set_ids 逗号串不改中间表。',
  'scenarios.violations': '检测到 {count} 处一层违规数据',
  'scenarios.noProject': '请先在顶栏选择一个项目',
  'scenarios.loading': '正在加载场景…',
  'scenarios.loadFailed': '场景加载失败',
  'scenarios.createTitle': '新建场景',
  'scenarios.name': '场景名称',
  'scenarios.code': '场景编号',
  'scenarios.nameRequired': '请填写场景名称与编号',
  'scenarios.caseSets': '用例集',
  'scenarios.create': '创建场景',
  'scenarios.created': '场景已创建',
  'scenarios.createFailed': '场景创建失败',
  'scenarios.regroup': '编组',
  'scenarios.regrouped': '场景已重新编组',
  'scenarios.regroupFailed': '编组失败',
  'scenarios.saveRegroup': '保存编组',
  'scenarios.delete': '删除',
  'scenarios.deleted': '场景已删除',
  'scenarios.deleteFailed': '场景删除失败',
  'scenarios.confirmDelete': '确认删除该场景？',
  'scenarios.confirm': '确认',
  'scenarios.caseSetCount': '{count} 个用例集',
  'scenarios.empty': '暂无场景',
  'scenarios.categorySection': '按分类筛选并批量加入用例集',
  'categoryTree.title': '两级分类树与按分类组集',
  'categoryTree.all': '全部分类',
  'categoryTree.mode.l1': '全选某一级',
  'categoryTree.mode.l2': '全选某二级',
  'categoryTree.mode.intersect': '跨表交集',
  'categoryTree.selected': '已选：{l1} / {l2}',
  'categoryTree.invalidPair': '二级必属所选一级：当前组合非法，请重新选择。',
  'categoryTree.intersectSet': '交集用例集',
  'categoryTree.targetSet': '加入目标用例集',
  'categoryTree.filter': '筛选',
  'categoryTree.addToSet': '批量加入用例集',
  'categoryTree.exportCsv': '导出 CSV',
  'categoryTree.externalIdHint': 'CSV 每行含 external_id，行数 = 筛选结果条数。',
  'categoryTree.matched': '筛选条数 {count}',
  'categoryTree.equalSign': '=',
  'categoryTree.added': '加入条数 {count}',
  'categoryTree.countEqual': '筛选条数 = 加入条数（一致）',
  'categoryTree.countMismatch': '筛选条数与加入条数不一致',
  'categoryTree.itemSource': 'item_source = {source}',
  'categoryTree.exported': 'CSV 已开始下载',
  'categoryTree.filterFailed': '筛选失败',
  'categoryTree.addFailed': '批量加入失败',
  'categoryTree.exportFailed': 'CSV 导出失败',
  'categoryTree.needTargetSet': '请选择要加入的目标用例集',
}

const testI18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  flatJson: true,
  messages: { 'zh-CN': { ...ZH }, 'en-US': { ...ZH } },
})

const sets = [
  { id: 1, projectId: 3, setCode: 'SET-1', name: '基线集', description: null },
  { id: 2, projectId: 3, setCode: 'SET-2', name: 'RoCE 集', description: null },
]

const categories = [
  { id: 1, level: 1, parentId: null, name: '网络', sortNo: 1, status: 'active', caseCount: 5 },
  { id: 2, level: 2, parentId: 1, name: 'RoCE', sortNo: 1, status: 'active', caseCount: 3 },
  { id: 3, level: 1, parentId: null, name: '存储', sortNo: 2, status: 'active', caseCount: 2 },
]

const scenarioRow = {
  id: 10,
  name: '网络回归场景',
  case_set_ids: '1',
  step_order: 1,
  parent_scenario_id: null,
}

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function flush(): Promise<void> {
  for (let i = 0; i < 3; i += 1) {
    await Promise.resolve()
    await nextTick()
  }
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ScenariosView)
  app.use(pinia!)
  app.use(testI18n)
  app.mount(root)
  await flush()
  return root
}

function pick(root: HTMLElement, testId: string): HTMLElement {
  return root.querySelector<HTMLElement>(`[data-testid="${testId}"]`)!
}

function setInput(root: HTMLElement, testId: string, value: string): void {
  const el = pick(root, testId) as HTMLInputElement
  el.value = value
  el.dispatchEvent(new Event('input'))
}

function setSelect(root: HTMLElement, testId: string, value: string): void {
  const el = pick(root, testId) as HTMLSelectElement
  el.value = value
  el.dispatchEvent(new Event('change'))
}

beforeEach(() => {
  scenarioList.mockReset()
  scenarioCreate.mockReset()
  scenarioRegroup.mockReset()
  scenarioRemove.mockReset()
  caseSetPage.mockReset()
  addByCategoryFilter.mockReset()
  categoriesInScope.mockReset()
  globalPage.mockReset()
  exportCategoryCsv.mockReset()

  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = 3

  scenarioList.mockResolvedValue({ items: [], oneLevelViolations: [] })
  caseSetPage.mockResolvedValue({ items: sets, total: 2, page: 1, size: 100 })
  scenarioCreate.mockResolvedValue(10)
  scenarioRegroup.mockResolvedValue({ regrouped: true })
  scenarioRemove.mockResolvedValue({ deleted: true })
  categoriesInScope.mockResolvedValue(categories)
  globalPage.mockResolvedValue({ items: [], total: 5, page: 1, size: 200 })
  addByCategoryFilter.mockResolvedValue({ matched: 5, added: 5, itemSource: 'CASE' })
  exportCategoryCsv.mockResolvedValue(new Blob(['external_id']))
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('ScenariosView 一层降级说明与分类树（FR-49-26/29）', () => {
  it('页面显式标注「场景仍限一层（FR-49-29 降级，授权人：大师）」', async () => {
    const root = await mountView()

    expect(pick(root, 'scenario-one-level-hint').textContent).toContain('FR-49-29')
    expect(pick(root, 'scenario-one-level-hint').textContent).toContain('大师')
    expect(pick(root, 'scenario-create')).toBeTruthy()
  })

  it('三种筛选模式与两级分类树渲染，二级必属所选一级', async () => {
    const root = await mountView()

    for (const mode of ['l1', 'l2', 'intersect']) {
      expect(root.querySelector(`[data-testid="mode-${mode}"]`)).toBeTruthy()
    }
    expect(pick(root, 'category-tree')).toBeTruthy()
    expect(pick(root, 'tree-l1-1')).toBeTruthy()
    expect(pick(root, 'tree-l1-3')).toBeTruthy()

    pick(root, 'tree-l1-1').click()
    await nextTick()
    expect(root.querySelector('[data-testid="tree-l2-2"]')).toBeTruthy()

    // 切换一级后清空二级：非法组合不会出现
    pick(root, 'tree-l1-3').click()
    await nextTick()
    expect(root.querySelector('[data-testid="tree-l2-2"]')).toBeNull()
  })

  it('批量加入后展示「筛选条数 = 加入条数」', async () => {
    const root = await mountView()

    pick(root, 'tree-l1-1').click()
    await nextTick()
    setSelect(root, 'target-set', '1')
    await nextTick()

    pick(root, 'run-filter').click()
    await flush()
    expect(pick(root, 'filter-counts').textContent).toContain('5')

    pick(root, 'add-to-set').click()
    await flush()
    expect(addByCategoryFilter).toHaveBeenCalledTimes(1)
    expect(addByCategoryFilter.mock.calls[0][0]).toBe(3)
    expect(addByCategoryFilter.mock.calls[0][1]).toBe(1)
    expect(addByCategoryFilter.mock.calls[0][2].categoryL1).toBe('网络')
    expect(pick(root, 'count-check').textContent).toContain('筛选条数 = 加入条数')
  })
})

describe('ScenariosView 场景 CRUD（FR-49-29）', () => {
  it('创建场景带名称 / 编号 / 一层用例集 id 列表', async () => {
    const root = await mountView()

    setInput(root, 'scenario-name', '网络回归')
    setInput(root, 'scenario-code', 'SC-NET')
    await nextTick()
    pick(root, 'create-set-1').click()
    await nextTick()
    pick(root, 'scenario-create-submit').click()
    await flush()

    expect(scenarioCreate).toHaveBeenCalledTimes(1)
    expect(scenarioCreate).toHaveBeenCalledWith(3, {
      setCode: 'SC-NET',
      name: '网络回归',
      caseSetIds: [1],
    })
    expect(pick(root, 'scenario-message').textContent).toContain('场景已创建')
  })

  it('编组只改一层用例集 id 串（不改中间表）', async () => {
    scenarioList.mockResolvedValue({ items: [scenarioRow], oneLevelViolations: [] })
    const root = await mountView()

    expect(pick(root, 'scenario-10')).toBeTruthy()
    pick(root, 'scenario-edit-10').click()
    await nextTick()
    pick(root, 'edit-set-2').click()
    await nextTick()
    pick(root, 'scenario-regroup-submit').click()
    await flush()

    expect(scenarioRegroup).toHaveBeenCalledTimes(1)
    expect(scenarioRegroup).toHaveBeenCalledWith(3, 10, [1, 2])
    expect(pick(root, 'scenario-message').textContent).toContain('场景已重新编组')
  })

  it('删除需二次确认，确认后调 scenarioApi.remove', async () => {
    scenarioList.mockResolvedValue({ items: [scenarioRow], oneLevelViolations: [] })
    const root = await mountView()

    expect(root.querySelector('[data-testid="scenario-delete-confirm"]')).toBeNull()
    pick(root, 'scenario-delete-10').click()
    await nextTick()
    expect(pick(root, 'scenario-confirm-10')).toBeTruthy()

    pick(root, 'scenario-delete-confirm').click()
    await flush()
    expect(scenarioRemove).toHaveBeenCalledTimes(1)
    expect(scenarioRemove).toHaveBeenCalledWith(3, 10)
    expect(pick(root, 'scenario-message').textContent).toContain('场景已删除')
  })

  it('未选项目时显示提示且不发列表请求', async () => {
    useAuthStore().projectId = null
    const root = await mountView()

    expect(pick(root, 'scenario-error').textContent).toContain('请先在顶栏选择一个项目')
    expect(scenarioList).not.toHaveBeenCalled()
  })
})
