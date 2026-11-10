/**
 * `ProjectCard` 组件回归（v2.3 / UI-03，FR-UI-11 / FR-UI-13）。
 *
 * <p>三件最容易「看起来对、其实错」的事：
 * ① 空值不能退化成 0 —— `null` 显示「—」，0 是真实数据；
 * ② 「进入」与「设为当前」语义分离：前者切项目 + 跳转，后者只切项目；
 * ③ 当前项目卡要能被辅助技术识别（`aria-current`），并把「设为当前」置灰。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'
import type { ProjectCard as ProjectCardData } from '@/api/projectCards'
import ProjectCard from '@/components/ProjectCard.vue'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const base: ProjectCardData = {
  id: 7,
  code: 'ED202-COMMON',
  name: 'ED202 公共用例库',
  description: '公共回归用例',
  enabled: true,
  visibility: 'private',
  ownerId: 1,
  isCurrent: false,
  caseCount: 813,
  runCount: 3,
  passRateBp: 9700,
  lastRunAt: '2026-11-08T08:48:58',
  deviceCount: 0,
  memberCount: 2,
  myRole: 'admin',
}

let app: App | null = null
let host: HTMLElement | null = null
let pinia: Pinia | null = null

async function mountCard(card: ProjectCardData, current = false): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ProjectCard, { card, current })
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

/** 带管理态挂载（v4.3.0 FR-v4.3-09：停用 / 彻底删除入口）。 */
async function mountManaged(
  card: ProjectCardData,
  handlers: { onToggleEnabled?: () => void; onRequestDelete?: () => void } = {},
): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ProjectCard, {
    card,
    current: false,
    canManage: true,
    canDelete: true,
    ...handlers,
  })
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

function pick(root: HTMLElement, testid: string): HTMLElement {
  return root.querySelector<HTMLElement>(`[data-testid="${testid}"]`)!
}

beforeEach(() => {
  push.mockReset()
  pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().projectId = null
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('ProjectCard 三层字段', () => {
  it('有数据时按三层渲染概要层与细节层', async () => {
    const root = await mountCard(base)

    expect(pick(root, 'card-cases').textContent).toBe('813')
    expect(pick(root, 'card-runs').textContent).toBe('3')
    expect(pick(root, 'card-pass-rate').textContent).toBe('97.0%')
    expect(pick(root, 'card-last-run').textContent).not.toBe('')
    expect(pick(root, 'card-devices').textContent).toBe('0')
    expect(pick(root, 'card-members').textContent).toBe('2')
    expect(pick(root, 'card-visibility').textContent).toBe('私有')
    expect(pick(root, 'card-role').textContent).toBe('管理员')
  })

  it('通过率 / 最近运行 / 我的角色为 null 时显示「—」而不是 0', async () => {
    const root = await mountCard({
      ...base,
      passRateBp: null,
      lastRunAt: null,
      myRole: null,
    })

    expect(pick(root, 'card-pass-rate').textContent).toBe('—')
    expect(pick(root, 'card-last-run').textContent).toBe('—')
    expect(pick(root, 'card-role').textContent).toBe('—')
    // 通过率不能退化成「0.0%」——那会被读成「跑过且全挂」
    expect(pick(root, 'card-pass-rate').textContent).not.toContain('0.0%')
  })
})

describe('ProjectCard 动作语义', () => {
  it('「进入」= 设为当前项目 + 跳转 /overview', async () => {
    const root = await mountCard(base)
    pick(root, 'card-enter').click()
    await nextTick()

    expect(useAuthStore().projectId).toBe(7)
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith('/overview')
  })

  it('「设为当前」= 只切项目，不跳转', async () => {
    const root = await mountCard(base)
    pick(root, 'card-set-current').click()
    await nextTick()

    expect(useAuthStore().projectId).toBe(7)
    expect(push).not.toHaveBeenCalled()
  })

  it('当前项目卡带 aria-current，且「设为当前」禁用', async () => {
    const root = await mountCard({ ...base, isCurrent: true }, true)
    const card = pick(root, 'project-card')

    expect(card.getAttribute('aria-current')).toBe('true')
    expect(root.textContent).toContain('当前项目')
    expect((pick(root, 'card-set-current') as HTMLButtonElement).disabled).toBe(true)
  })

  it('停用项目显示「已停用」，且不是「启用」', async () => {
    const root = await mountCard({ ...base, enabled: false })

    expect(root.textContent).toContain('已停用')
    expect(root.textContent).not.toContain('启用')
  })
})

describe('ProjectCard 停用与彻底删除（FR-v4.3-09）', () => {
  it('无管理能力时不渲染停用 / 删除入口', async () => {
    const root = await mountCard(base)

    expect(root.querySelector('[data-testid="card-toggle"]')).toBeNull()
    expect(root.querySelector('[data-testid="card-delete"]')).toBeNull()
  })

  it('停用按钮按当前状态换文案，点击只发事件不直接改数据', async () => {
    const onToggleEnabled = vi.fn()
    const root = await mountManaged({ ...base, enabled: false }, { onToggleEnabled })
    const toggle = pick(root, 'card-toggle') as HTMLButtonElement

    expect(toggle.textContent?.trim()).toBe('启用')
    toggle.click()
    await nextTick()
    expect(onToggleEnabled).toHaveBeenCalledTimes(1)
  })

  it('有用例 / 运行 / 设备的项目：彻底删除置灰并给出原因', async () => {
    const root = await mountManaged(base)
    const remove = pick(root, 'card-delete') as HTMLButtonElement

    expect(remove.disabled).toBe(true)
    expect(remove.getAttribute('title')).toContain('只能停用')
  })

  it('空项目：彻底删除可点，点击只发请求事件', async () => {
    const onRequestDelete = vi.fn()
    const root = await mountManaged(
      { ...base, caseCount: 0, runCount: 0, deviceCount: 0 },
      { onRequestDelete },
    )
    const remove = pick(root, 'card-delete') as HTMLButtonElement

    expect(remove.disabled).toBe(false)
    remove.click()
    await nextTick()
    expect(onRequestDelete).toHaveBeenCalledTimes(1)
  })
})
