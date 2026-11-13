/**
 * `NodesView` 视图级回归（NODX-01）。
 *
 * 三处容易在静态检查下蒙混过关的接线：
 * ① 状态徽标必须原样显示后端四态——「看起来像离线」的 NEW 节点若被前端推断成 OFFLINE，
 *    「注册了还没部署 agent」和「agent 挂了」就无法区分（本组用例的立意）；
 * ② 注册响应里的明文令牌只出现在一次性对话框里，**不得**写进 localStorage / store；
 * ③ 停用/启用走的是同一行按钮的正确分支。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '@/locales'
import { nodeApi, type ExecutionNodeView } from '@/api/nodes'
import NodesView from '@/views/NodesView.vue'

const nodes: ExecutionNodeView[] = [
  {
    id: 1,
    name: 'lab-node-01',
    status: 'ONLINE',
    rfAvailable: true,
    pytestAvailable: true,
    maxConcurrency: 2,
    lastHeartbeatAt: '2026-11-06T14:00:00Z',
    runningRuns: 0,
    agentBaseUrl: 'http://10.0.0.8:8082',
    lastRun: { runId: 'RUN-20261107-010000-1', state: 'FINISHED', finishedAt: '2026-11-06T14:00:00Z' },
  },
  {
    id: 2,
    name: 'lab-node-02',
    status: 'NEW',
    rfAvailable: false,
    pytestAvailable: false,
    maxConcurrency: 1,
    lastHeartbeatAt: null,
    runningRuns: 0,
    agentBaseUrl: null,
    lastRun: null,
  },
]

vi.mock('@/api/nodes', () => ({
  nodeApi: {
    list: vi.fn(async () => nodes),
    register: vi.fn(async () => ({ nodeId: 3, name: 'lab-node-03', token: 'tok-' + 'a'.repeat(64) })),
    disable: vi.fn(async () => ({ ...nodes[0], status: 'DISABLED' })),
    enable: vi.fn(async () => ({ ...nodes[0], status: 'NEW' })),
  },
}))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(NodesView)
  app.use(pinia!)
  app.use(i18n)
  // 空态的「下一步」是 RouterLink（v5.6.0 / FR-v5.6-03），没有 router 会在挂载时崩。
  // 这里只关心链接渲染成什么，不关心跳转行为，所以给一个只声明目标路径的内存路由。
  app.use(createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/assets', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  }))
  app.mount(root)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  return root
}

function statusCells(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLElement>('[data-testid="node-status"]')].map(
    (cell) => cell.textContent?.trim() ?? '',
  )
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  vi.mocked(nodeApi.list).mockClear()
  vi.mocked(nodeApi.register).mockClear()
  vi.mocked(nodeApi.disable).mockClear()
  vi.mocked(nodeApi.enable).mockClear()
  localStorage.clear()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('NodesView 列表', () => {
  it('四态原样显示：NEW 节点不得被前端推断成 OFFLINE', async () => {
    const root = await mountView()
    const statuses = statusCells(root)
    expect(statuses).toHaveLength(2)
    expect(statuses[0]).toBe(i18n.global.t('nodesView.status.ONLINE'))
    expect(statuses[1]).toBe(i18n.global.t('nodesView.status.NEW'))
  })

  it('能力与并发上限可见：RF + pytest 组合与未上报能力', async () => {
    const root = await mountView()
    const text = root.textContent ?? ''
    expect(text).toContain('lab-node-01')
    expect(text).toContain('RF')
    expect(text).toContain('pytest')
    expect(text).toContain(i18n.global.t('nodesView.noCapability'))
  })

  it('停用调用 disable；列表已是 DISABLED 时走 enable 分支', async () => {
    const root = await mountView()
    const toggles = [...root.querySelectorAll<HTMLButtonElement>('[data-testid="node-toggle"]')]
    expect(toggles).toHaveLength(2)
    toggles[0]!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(nodeApi.disable).toHaveBeenCalledWith(1)
    expect(nodeApi.enable).not.toHaveBeenCalled()

    // 重挂：节点已 DISABLED，按钮分支应切到 enable
    vi.mocked(nodeApi.list).mockResolvedValue([{ ...nodes[0], status: 'DISABLED' }, nodes[1]])
    const root2 = await mountView()
    const toggles2 = [...root2.querySelectorAll<HTMLButtonElement>('[data-testid="node-toggle"]')]
    toggles2[0]!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(nodeApi.enable).toHaveBeenCalledWith(1)
  })
})

describe('NodesView 注册', () => {
  it('注册后明文令牌只出现在对话框里，且不写 localStorage', async () => {
    const root = await mountView()
    const register = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === i18n.global.t('nodesView.register'),
    )
    expect(register).toBeTruthy()
    register!.click()
    await nextTick()

    const nameInput = root.querySelector<HTMLInputElement>('.form-card input')
    expect(nameInput).toBeTruthy()
    nameInput!.value = 'lab-node-03'
    nameInput!.dispatchEvent(new Event('input'))
    await nextTick()

    const submit = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === i18n.global.t('common.save'),
    )
    submit!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(nodeApi.register).toHaveBeenCalledWith({
      name: 'lab-node-03',
      rfAvailable: true,
      pytestAvailable: false,
      maxConcurrency: 1,
      agentBaseUrl: undefined,
    })
    // NModal teleport 到 body，不在组件根里
    const tokenBox = document.querySelector<HTMLElement>('[data-testid="node-token"]')
    expect(tokenBox!.textContent).toContain('tok-')
    // 明文令牌是「只显示一次」的承诺：页面任何持久面都不得留副本
    expect(localStorage.getItem('nodeToken')).toBeNull()
    expect(localStorage.length).toBe(0)
  })

  it('注册表单可填 agent 接入地址并随请求提交（NODX-04）', async () => {
    const root = await mountView()
    const register = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === i18n.global.t('nodesView.register'),
    )
    register!.click()
    await nextTick()

    const inputs = [...root.querySelectorAll<HTMLInputElement>('.form-card input')]
    const nameInput = inputs[0]!
    const agentPlaceholder = i18n.global.t('nodesView.agentBaseUrlPlaceholder')
    const agentInput = inputs.find((i) => i.placeholder === agentPlaceholder)!
    expect(agentInput).toBeTruthy()
    nameInput.value = 'lab-node-03'
    nameInput.dispatchEvent(new Event('input'))
    agentInput.value = 'http://10.0.0.9:8082'
    agentInput.dispatchEvent(new Event('input'))
    await nextTick()

    const submit = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === i18n.global.t('common.save'),
    )
    submit!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(nodeApi.register).toHaveBeenCalledWith(
      expect.objectContaining({ agentBaseUrl: 'http://10.0.0.9:8082' }),
    )
  })
})

describe('NodesView 离线时长与最近任务（NODX-04）', () => {
  it('心跳列显示相对时长且不在前端推断状态；无心跳显示空', async () => {
    // 固定 5 分钟前的心跳
    vi.mocked(nodeApi.list).mockResolvedValue([
      { ...nodes[0]!, lastHeartbeatAt: new Date(Date.now() - 5 * 60000).toISOString() },
      nodes[1]!,
    ])
    const root = await mountView()
    const cells = [...root.querySelectorAll<HTMLElement>('[data-testid="node-heartbeat"]')]
    expect(cells).toHaveLength(2)
    expect(cells[0]!.textContent).toContain(
      i18n.global.t('nodesView.minutesAgo', { n: 5 }),
    )
    // 无心跳的 NEW 节点只显示占位符（EMPTY='—'），不伪造「离线」字样
    expect(cells[1]!.textContent?.trim()).toBe('—')
  })

  it('最近任务列显示 runId 与本地化状态；无 run 显示空', async () => {
    const root = await mountView()
    const cells = [...root.querySelectorAll<HTMLElement>('[data-testid="node-last-run"]')]
    expect(cells).toHaveLength(2)
    expect(cells[0]!.textContent).toContain('RUN-20261107-010000-1')
    expect(cells[0]!.textContent).toContain(i18n.global.t('enum.runStatus.finished'))
    expect(cells[1]!.textContent?.trim()).toBe('—')
  })
})
