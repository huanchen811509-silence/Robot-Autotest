import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import CaseSetWorkspace from '@/components/CaseSetWorkspace.vue'

let app: App | null = null
let host: HTMLElement | null = null

async function mount(active: boolean): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  // createApp 只有 (rootComponent, rootProps) 两个参数；slots 必须在 h() 里给
  app = createApp({
    render: () => h(CaseSetWorkspace, { active }, {
      nav: () => h('div', { 'data-testid': 'nav-slot' }, '集合列表'),
      workspace: () => h('div', { 'data-testid': 'workspace-slot' }, '成员工作区'),
    }),
  })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('CaseSetWorkspace 三栏布局壳（W62-02）', () => {
  it('两栏容器都在，中栏 slot 恒定渲染', async () => {
    const root = await mount(false)
    expect(root.querySelector('[data-testid="case-set-workspace"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="set-nav"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="set-main"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="nav-slot"]')?.textContent).toContain('集合列表')
  })

  it('未选中集合：右栏是自解释空态，不默认选第一个、不挂载成员区', async () => {
    const root = await mount(false)
    const empty = root.querySelector<HTMLElement>('[data-testid="case-set-empty"]')
    expect(empty).not.toBeNull()
    expect(empty?.textContent).toContain('从左侧选择一个用例集')
    expect(root.querySelector('[data-testid="workspace-slot"]')).toBeNull()
  })

  it('选中集合：空态消失，成员工作区挂载', async () => {
    const root = await mount(true)
    expect(root.querySelector('[data-testid="case-set-empty"]')).toBeNull()
    expect(root.querySelector('[data-testid="workspace-slot"]')?.textContent).toContain('成员工作区')
  })

  it('布局是中栏固定 + 右栏自适应（不是纵向堆叠）', async () => {
    const root = await mount(true)
    const grid = root.querySelector<HTMLElement>('[data-testid="case-set-workspace"]')!
    expect(grid.className).toContain('case-set-workspace')
    // 样式在 scoped CSS 里，靠类名 + 结构断言；真实宽度由浏览器截图核对
    expect(grid.firstElementChild?.tagName.toLowerCase()).toBe('aside')
    expect(grid.lastElementChild?.tagName.toLowerCase()).toBe('section')
  })
})

/**
 * W69-05：窄屏（≤1400px）右栏改为抽屉。
 *
 * 判据来源：v5.19 DEV-PLAN W69-05 出口条件①「1366 下右栏为抽屉且可开合」。
 * jsdom 没有 matchMedia，这里按「窄屏命中」stub，只验证形态与关闭路径；
 * 真实像素宽度由 `row-hit.mjs --pages sets --probe drawer` 在真实 Chrome 里量。
 */
describe('CaseSetWorkspace 窄屏抽屉（W69-05）', () => {
  function stubMatchMedia(matches: boolean): void {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }))
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('窄屏 + 已选中：右栏是抽屉（有遮罩与收起入口），宽屏不出现', async () => {
    stubMatchMedia(true)
    const narrowRoot = await mount(true)
    await nextTick()
    const shell = narrowRoot.querySelector<HTMLElement>('.case-set-shell')!
    expect(shell.getAttribute('data-narrow')).toBe('true')
    expect(shell.getAttribute('data-drawer')).toBe('open')
    expect(narrowRoot.querySelector('[data-testid="case-set-drawer-mask"]')).not.toBeNull()
    expect(narrowRoot.querySelector('[data-testid="case-set-drawer-close"]')).not.toBeNull()

    app?.unmount()
    host?.remove()

    stubMatchMedia(false)
    const wideRoot = await mount(true)
    await nextTick()
    const wideShell = wideRoot.querySelector<HTMLElement>('.case-set-shell')!
    expect(wideShell.getAttribute('data-narrow')).toBe('false')
    expect(wideShell.getAttribute('data-drawer')).toBe('closed')
    expect(wideRoot.querySelector('[data-testid="case-set-drawer-mask"]')).toBeNull()
  })

  it('窄屏未选中集合：不弹抽屉，右栏是空态', async () => {
    stubMatchMedia(true)
    const root = await mount(false)
    await nextTick()
    expect(root.querySelector<HTMLElement>('.case-set-shell')!.getAttribute('data-drawer')).toBe('closed')
    expect(root.querySelector('[data-testid="case-set-empty"]')).not.toBeNull()
  })

  it('点遮罩与点收起按钮都触发 close（单一关闭语义）', async () => {
    stubMatchMedia(true)
    const onClose = vi.fn()
    const root = document.createElement('div')
    document.body.appendChild(root)
    host = root
    app = createApp({
      render: () => h(CaseSetWorkspace, { active: true, onClose }, {
        nav: () => h('div', '集合列表'),
        workspace: () => h('div', { 'data-testid': 'workspace-slot' }, '成员工作区'),
      }),
    })
    app.use(i18n)
    app.mount(root)
    await nextTick()

    root.querySelector<HTMLElement>('[data-testid="case-set-drawer-mask"]')!.click()
    await nextTick()
    expect(onClose).toHaveBeenCalledTimes(1)

    root.querySelector<HTMLElement>('[data-testid="case-set-drawer-close"]')!.click()
    await nextTick()
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
