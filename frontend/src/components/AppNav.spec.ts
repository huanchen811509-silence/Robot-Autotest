/**
 * 侧栏导航渲染（v2.3 / FR-UI-07、29 / V-UI-02）。
 *
 * 这三条原来只能靠人眼：
 * ① 叶子与分组形态可区分——叶子**没有**展开箭头、**没有** `aria-expanded`；
 * ② 默认只展开当前激活组，没有激活组时展开第一组；
 * ③ 用户手型优先：手动折叠过的组，重新挂载后仍保持折叠。
 *
 * ④ 合并组内「运行态 / 报告态」之间只画 1 条细分隔线。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App, type PropType } from 'vue'
import { i18n } from '@/locales'
import AppNav from '@/components/AppNav.vue'
import type { NavNode } from '@/config/navigation'

const routeRef = vi.hoisted(() => ({ path: '/plans', query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({ useRoute: () => routeRef }))

const nodes: NavNode[] = [
  {
    key: 'workbench',
    labelKey: 'menu.workspace',
    icon: 'dashboard',
    children: [
      { key: 'wb-overview', labelKey: 'menu.workbenchOverview', icon: 'boxes', path: '/workbench' },
      { key: 'wb-board', labelKey: 'menu.overview', icon: 'reports', path: '/overview' },
    ],
  },
  {
    key: 'execution',
    labelKey: 'menu.execution',
    icon: 'runs',
    children: [
      { key: 'x-runs', labelKey: 'menu.runsList', icon: 'runs', path: '/runs', section: 'run' },
      { key: 'x-plans', labelKey: 'menu.runsPlans', icon: 'cases', path: '/plans', section: 'run' },
      { key: 'x-reports', labelKey: 'menu.reports', icon: 'reports', path: '/reports', section: 'report' },
      {
        key: 'x-templates',
        labelKey: 'menu.reportTemplates',
        icon: 'copy',
        path: '/reports/templates',
        section: 'report',
      },
    ],
  },
]

let app: App | null = null
let host: HTMLElement | null = null

const RouterLinkStub = defineComponent({
  props: {
    to: { type: [String, Object] as PropType<string | { path: string }>, required: true },
  },
  setup(props, { slots }) {
    return () =>
      h('a', { href: typeof props.to === 'string' ? props.to : props.to.path }, slots.default?.())
  },
})

async function mountNav(): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({ render: () => h(AppNav, { nodes }) })
  app.use(i18n)
  app.component('RouterLink', RouterLinkStub)
  app.mount(host)
  await nextTick()
  return host
}

function groupHead(root: HTMLElement, index: number): HTMLButtonElement {
  return root.querySelectorAll<HTMLButtonElement>('.app-nav-group-head')[index]
}

function childrenBox(root: HTMLElement, key: string): HTMLElement {
  return root.querySelector<HTMLElement>(`[data-group="${key}"]`)!
}

beforeEach(() => {
  localStorage.removeItem('robot.nav.expanded')
  routeRef.path = '/plans'
  routeRef.query = {}
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('叶子与分组形态（FR-UI-07）', () => {
  it('分组头有 aria-expanded 与展开箭头，叶子子项两者都没有', async () => {
    const root = await mountNav()
    const head = groupHead(root, 0)
    expect(head.getAttribute('aria-expanded')).toBe('false')
    expect(head.querySelector('.app-nav-chevron')).not.toBeNull()

    const subs = [...root.querySelectorAll<HTMLAnchorElement>('.app-nav-sub')]
    expect(subs.length).toBeGreaterThan(0)
    for (const sub of subs) {
      expect(sub.getAttribute('aria-expanded')).toBeNull()
      expect(sub.querySelector('.app-nav-chevron')).toBeNull()
    }
  })

  it('点击分组头翻转 aria-expanded', async () => {
    const root = await mountNav()
    const head = groupHead(root, 0)
    const before = head.getAttribute('aria-expanded')
    head.click()
    await nextTick()
    expect(head.getAttribute('aria-expanded')).not.toBe(before)
  })
})

describe('默认展开策略（FR-UI-07）', () => {
  it('只展开当前激活组，其余组收起', async () => {
    const root = await mountNav()
    expect(childrenBox(root, 'execution').style.display).not.toBe('none')
    expect(childrenBox(root, 'workbench').style.display).toBe('none')
    expect(groupHead(root, 1).getAttribute('aria-expanded')).toBe('true')
    expect(groupHead(root, 0).getAttribute('aria-expanded')).toBe('false')
  })

  it('没有激活组时只展开第一组（不当成「全展开」）', async () => {
    routeRef.path = '/nowhere'
    const root = await mountNav()
    expect(childrenBox(root, 'workbench').style.display).not.toBe('none')
    expect(childrenBox(root, 'execution').style.display).toBe('none')
  })

  it('用户手型优先：手动折叠当前组后，重新挂载仍是折叠态', async () => {
    const root = await mountNav()
    expect(childrenBox(root, 'execution').style.display).not.toBe('none')

    groupHead(root, 1).click()
    await nextTick()
    expect(childrenBox(root, 'execution').style.display).toBe('none')
    expect(JSON.parse(localStorage.getItem('robot.nav.expanded') ?? '{}')).toEqual({
      execution: false,
    })

    app?.unmount()
    host?.remove()
    const again = await mountNav()
    expect(childrenBox(again, 'execution').style.display).toBe('none')
  })
})

describe('菜单文字（v2.3.1 修复）', () => {
  it('每个导航项都渲染非空文字，且 title 与文字一致（窄轨下靠 title 才认得出）', async () => {
    const root = await mountNav()
    const items = [...root.querySelectorAll<HTMLElement>('.app-nav-item')]
    expect(items.length).toBeGreaterThan(0)
    for (const item of items) {
      const span = item.querySelector('span')
      expect(span).not.toBeNull()
      const text = span!.textContent?.trim() ?? ''
      expect(text.length).toBeGreaterThan(0)
      expect(item.getAttribute('title')).toBe(text)
    }
  })

  it('一级菜单用完整名称：测试用例 / 测试资产（不是「用例」「资产」）', () => {
    expect(i18n.global.t('menu.cases')).toBe('测试用例')
    expect(i18n.global.t('menu.assets')).toBe('测试资产')
  })
})

describe('合并组的分区线（FR-UI-29）', () => {
  it('4 项两个 section 之间恰好 1 条分隔线，且是纯装饰（aria-hidden）', async () => {
    routeRef.path = '/plans'
    const root = await mountNav()
    const box = childrenBox(root, 'execution')
    const seps = [...box.querySelectorAll('.app-nav-sep')]
    expect(seps).toHaveLength(1)
    expect(seps[0].getAttribute('aria-hidden')).toBe('true')
    // 分隔线只出现在「运行态 → 报告态」切换处：位置在 4 个子项之间的第 3 个位置
    const order = [...box.children].map((el) => el.className.split(' ')[0])
    expect(order).toEqual(['app-nav-item', 'app-nav-item', 'app-nav-sep', 'app-nav-item', 'app-nav-item'])
  })
})
