/**
 * `ProjectCard` 组件回归（v2.3 / UI-03，FR-UI-11 / FR-UI-13）。
 *
 * <p>三件最容易「看起来对、其实错」的事：
 * ① 空值不能退化成 0 —— `null` 显示「—」；0 在**用例数 / 设备数**上是真实数据照常显示，
 *    但 **运行数** 例外（v5.22.0 起 0 也显示「—」，见本文件末尾「零运行」一组）；
 * ② 「进入」与「设为当前」语义分离：前者切项目 + 跳转，后者只切项目；
 * ③ 当前项目卡要能被辅助技术识别（`aria-current`），并把「设为当前」置灰。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
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
  origin: 'USER',
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
  handlers: {
    onToggleEnabled?: () => void
    onRequestDelete?: () => void
    onRequestChangeOrigin?: () => void
  } = {},
): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(ProjectCard, {
    card,
    current: false,
    canManage: true,
    canDelete: true,
    canChangeOrigin: true,
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

/**
 * v5.22.0 / FR-v5.22-06 判据 06-3。
 *
 * 这一组推翻了本文件开头那句「0 是真实数据」——**对 `runCount` 不成立**：
 * 它是「近 7 天运行数」，0 与「从没跑过」在界面上长得一模一样，而这两种情况
 * 该给的下一步完全不同。其余计数（用例数 / 设备数）保持 0 就显示 0。
 */
describe('ProjectCard 零运行（FR-v5.22-06 判据 06-3）', () => {
  it('近 7 天 0 次运行显示「—」而不是 0，并给「发起第一次运行」入口', async () => {
    const root = await mountCard({ ...base, runCount: 0, lastRunAt: null })

    expect(pick(root, 'card-runs').textContent).toBe('—')
    const cta = pick(root, 'card-first-run') as HTMLButtonElement
    expect(cta).toBeTruthy()
    expect(cta.textContent?.trim()).toBe(i18n.global.t('workbench.firstRun'))
    // 短文案后完整语义靠 title 保留（v5.22 缺陷修复）
    expect(cta.getAttribute('title')).toBe(i18n.global.t('workbench.firstRunHint'))
    // 用例数为 0 时仍显示 0：只有「运行数」这一个指标有「从没跑过」的语义
    expect(pick(root, 'card-devices').textContent).toBe('0')
  })

  it('「发起运行」是描边按钮：透明底 + 主色字 + 主色边，防止字色=底色的撞色回归', () => {
    // jsdom 不解析 scoped CSS 的 computed style，改为读 SFC 源断言样式三要素。
    // 回归背景：全局 .btn 是实心 --accent，原实现只改 color/border 没改 background，
    // 实测 color=background=rgb(251,146,60)，字完全看不见。
    // vitest 的 import.meta.url 是 http scheme，不能 fileURLToPath；用 cwd 相对路径读源码。
    const src = readFileSync('src/components/ProjectCard.vue', 'utf-8')
    const block = src.match(/\.project-card__actions \.btn-first-run \{([\s\S]*?)\}/)?.[1] ?? ''
    expect(block).toMatch(/background:\s*transparent/)
    expect(block).toMatch(/color:\s*var\(--accent\)/)
    expect(block).toMatch(/border:\s*1px solid var\(--accent\)/)
  })

  it('点「发起第一次运行」先切到该卡片项目，再进发起页', async () => {
    const root = await mountCard({ ...base, runCount: 0 })
    pick(root, 'card-first-run').click()
    await nextTick()

    // 不切项目就跳，发起页会拿着上一个项目的用例集去渲染
    expect(useAuthStore().projectId).toBe(7)
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith('/execution-plans')
  })

  it('有运行时不给「发起第一次运行」，数字照常显示', async () => {
    const root = await mountCard(base)

    expect(root.querySelector('[data-testid="card-first-run"]')).toBeNull()
    expect(pick(root, 'card-runs').textContent).toBe('3')
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

  it('用户自建 + 有数据：彻底删除仍可点，提示级联删除（大师反馈回归）', async () => {
    const root = await mountManaged(base) // base: origin=USER, caseCount=813
    const remove = pick(root, 'card-delete') as HTMLButtonElement

    expect(remove.disabled).toBe(false)
    expect(remove.getAttribute('title')).toContain('级联')
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

  it('程序自带项目：删除置灰并给专门提示，且渲染来源徽标', async () => {
    const root = await mountManaged({ ...base, origin: 'BUILTIN', caseCount: 0, runCount: 0, deviceCount: 0 })
    const remove = pick(root, 'card-delete') as HTMLButtonElement
    const badge = root.querySelector('[data-testid="card-origin"]')

    expect(remove.disabled).toBe(true)
    expect(remove.getAttribute('title')).toContain('程序自带')
    expect(badge?.textContent).toContain('程序自带')
  })

  it('切换来源按钮：有管理态时渲染，点击只发事件', async () => {
    const onRequestChangeOrigin = vi.fn()
    const root = await mountManaged(base, { onRequestChangeOrigin })
    const btn = pick(root, 'card-change-origin') as HTMLButtonElement

    expect(btn).not.toBeNull()
    btn.click()
    await nextTick()
    expect(onRequestChangeOrigin).toHaveBeenCalledTimes(1)
  })
})
