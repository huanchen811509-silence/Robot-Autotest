/**
 * `SuiteTree` 组件级回归（UX-04b 第六批）。
 *
 * 这棵树是报告详情页里唯一**递归渲染 + 自带折叠状态**的组件，两类缺陷只有挂载才能发现：
 * ① 折叠/展开状态错位 —— 点套件标题收起后，子级仍留在 DOM 里（或反过来点不开）；
 * ② 状态只有颜色 —— 换成 `StatusTag` 之前它是 `badge verdict-*`，六预设里 crimson
 *    与 fail 同色相，色觉障碍下套件树等于没有状态信息。
 *
 * 另外这批把整行原生 `<button class="tree-toggle">` 换成了 `NButton text`：
 * 原生那份**没有 hover / focus-visible**（scoped style 不在 `check_states.py` 扫描范围，
 * 所以守门一直绿），这里至少断言渲染出的是库按钮，避免以后被改回裸 `<button>`。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import type { ReportSuiteNode } from '@/api/reports'
import SuiteTree from '@/components/report/SuiteTree.vue'

const TREE: ReportSuiteNode = {
  name: 'Roce',
  status: 'FAIL',
  suites: [
    {
      name: 'Smoke',
      status: 'PASS',
      suites: [],
      cases: [
        {
          caseKey: 'TC-009',
          name: '子套件用例',
          verdict: 'PASS',
          durationMs: 300,
          failureMsg: null,
          commandText: 'NONE',
          commandSource: 'NONE',
          commandExitCode: null,
          keywords: [],
        },
      ],
    },
  ],
  cases: [
    {
      caseKey: 'TC-001',
      name: '基本收发',
      verdict: 'PASS',
      durationMs: 1200,
      failureMsg: null,
      commandText: 'ipmitool raw 0x30 0x93 0xdb 0x07 0x00 0x00',
      commandSource: 'RF_KEYWORD',
      commandExitCode: 0,
      keywords: [
        {
          name: 'Setup',
          status: 'PASS',
          children: [{ name: 'Open Connection', status: 'PASS', children: [] }],
        },
      ],
    },
    {
      caseKey: 'TC-002',
      name: '大包吞吐',
      verdict: 'FAIL',
      durationMs: 8000,
      failureMsg: 'expected 190 got 120',
      // 超长命令：验证折叠 + 展开全文
      commandText: `iperf3 -c 192.0.2.10 -t 30 -P 8 --json --logfile /tmp/iperf-${'x'.repeat(60)}.json`,
      commandSource: 'STDOUT',
      commandExitCode: 1,
      keywords: [],
    },
    {
      caseKey: 'TC-003',
      name: '历史用例（未采集）',
      verdict: 'SKIP',
      durationMs: 0,
      failureMsg: null,
      commandText: null,
      commandSource: null,
      commandExitCode: null,
      keywords: [],
    },
  ],
}

let app: App | null = null
let host: HTMLElement | null = null
let logs: string[] = []

async function mountTree(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  logs = []
  // 用 h() 建包装组件而不是字符串模板：不依赖运行时编译器，emit 监听也直接写在 props 里
  const Wrapper = defineComponent({
    setup() {
      return () =>
        h(SuiteTree, {
          node: TREE,
          depth: 0,
          onOpenLogs: (caseKey: string) => logs.push(caseKey),
        })
    },
  })
  app = createApp(Wrapper)
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

beforeEach(() => {
  logs = []
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('SuiteTree 折叠', () => {
  it('根套件默认展开、子套件默认折叠', async () => {
    const root = await mountTree()
    // 根套件的两条用例可见
    expect(root.textContent).toContain('基本收发')
    expect(root.textContent).toContain('大包吞吐')
    // 子套件标题在，但它的用例默认不可见
    expect(root.textContent).toContain('Smoke')
    expect(root.textContent).not.toContain('子套件用例')
  })

  it('点子套件标题展开后出现它的用例', async () => {
    const root = await mountTree()
    root.querySelector<HTMLElement>('[data-suite-name="Smoke"]')!.click()
    await nextTick()
    expect(root.textContent).toContain('子套件用例')
  })

  it('再点根套件标题收起，用例行从 DOM 消失', async () => {
    const root = await mountTree()
    root.querySelector<HTMLElement>('[data-suite-name="Roce"]')!.click()
    await nextTick()
    expect(root.textContent).not.toContain('基本收发')
    // 收起只是隐藏，套件名要留着（否则连不回来）
    expect(root.textContent).toContain('Roce')
  })
})

describe('SuiteTree 用例交互', () => {
  it('点「查看日志」把 caseKey 交给父级', async () => {
    const root = await mountTree()
    const viewLog = [
      ...root.querySelectorAll<HTMLElement>('[data-case-key="TC-002"] ~ .link-btn'),
    ]
    expect(viewLog.length).toBe(1)
    viewLog[0].click()
    await nextTick()
    expect(logs).toEqual(['TC-002'])
  })

  it('点用例标题展开步骤树，递归两层都渲染', async () => {
    const root = await mountTree()
    root.querySelector<HTMLElement>('[data-case-key="TC-001"]')!.click()
    await nextTick()
    expect(root.textContent).toContain('Setup')
    expect(root.textContent).toContain('Open Connection')
  })

  it('没有关键字步骤时给提示，不留空白区块', async () => {
    const root = await mountTree()
    root.querySelector<HTMLElement>('[data-case-key="TC-002"]')!.click()
    await nextTick()
    expect(root.textContent).toContain(i18n.global.t('reports.noSteps'))
  })
})

describe('SuiteTree 状态与控件', () => {
  it('套件与用例的状态都是图标 + 文字双编码', async () => {
    const root = await mountTree()
    const tags = [...root.querySelectorAll<HTMLElement>('.status-tag')]
    // 根套件 1 + 三条用例 3 + 子套件 1 = 5（子套件收起，但它自己的标题徽标在）
    expect(tags.length).toBe(5)
    expect(tags.every((tag) => tag.querySelector('svg') !== null)).toBe(true)
    // FAIL 与 PASS 落在不同色档上，不能都是一种颜色
    const tones = new Set(tags.map((tag) => tag.dataset.tone))
    expect(tones.has('fail')).toBe(true)
    expect(tones.has('ok')).toBe(true)
  })

  it('整行是键盘可达的按钮（三态由 check_states 守门，这里只钉语义）', async () => {
    const root = await mountTree()
    const toggles = [...root.querySelectorAll<HTMLElement>('.tree-toggle')]
    expect(toggles.length).toBe(5)
    // type="button" 防止在表单里被当成 submit；标签名必须是 button 而不是可点的 div
    expect(toggles.every((el) => el.tagName === 'BUTTON')).toBe(true)
    expect(toggles.every((el) => el.getAttribute('type') === 'button')).toBe(true)
  })
})

/**
 * FR-v3.9-03 ①④：命令行的三种状态必须**显示成不同文案**。
 *
 * 这里最容易退化的地方是把「还没采集」和「本来就没命令」都渲染成空白——
 * 两者对读报告的人是完全不同的结论（一个是数据缺失、一个是确实没下发），
 * 所以拆成三条用例逐态钉死，而不是只查一句 contains。
 */
describe('SuiteTree 下发命令（FR-v3.9-03）', () => {
  function cmdRow(root: HTMLElement, caseKey: string): HTMLElement | null {
    const block = root.querySelector<HTMLElement>(`[data-case-key="${caseKey}"]`)?.closest('.case-block')
    return block?.querySelector<HTMLElement>('.case-cmd') ?? null
  }

  it('有命令：显示原文、来源与退出码，并标 present', async () => {
    const root = await mountTree()
    const row = cmdRow(root, 'TC-001')!
    expect(row.dataset.command).toBe('present')
    expect(row.textContent).toContain('ipmitool raw 0x30 0x93')
    expect(row.textContent).toContain(i18n.global.t('reports.command.sourceRf'))
    // 退出码文案由 i18n 拼（中文是「退出码 0」，不是字面 exit）
    expect(row.textContent).toContain(i18n.global.t('reports.command.exitCode', { code: 0 }))
    expect(row.querySelector('.cmd-text')).not.toBeNull()
  })

  it('历史行（commandText=null）：显示「未采集」，且不给复制 / 定位按钮', async () => {
    const root = await mountTree()
    const row = cmdRow(root, 'TC-003')!
    expect(row.dataset.command).toBe('absent')
    expect(row.textContent).toContain(i18n.global.t('reports.command.notCollected'))
    expect(row.querySelector('[data-action="copy"]')).toBeNull()
    expect(row.querySelector('[data-action="locate-log"]')).toBeNull()
  })

  it('历史行 commandText=null 与 NONE 文案必须不同', async () => {
    const root = await mountTree()
    // 子套件默认折叠，先展开
    root.querySelector<HTMLElement>('[data-suite-name="Smoke"]')!.click()
    await nextTick()
    const none = cmdRow(root, 'TC-009')!
    const legacy = cmdRow(root, 'TC-003')!
    expect(none.textContent).toContain(i18n.global.t('reports.command.none'))
    expect(legacy.textContent).toContain(i18n.global.t('reports.command.notCollected'))
    expect(i18n.global.t('reports.command.none')).not.toBe(
      i18n.global.t('reports.command.notCollected'),
    )
  })

  it('超长命令默认折叠，展开后加 is-expanded 并可收回', async () => {
    const root = await mountTree()
    const row = cmdRow(root, 'TC-002')!
    const text = row.querySelector<HTMLElement>('.cmd-text')!
    expect(text.classList.contains('is-expanded')).toBe(false)
    row.querySelector<HTMLElement>('[data-action="expand"]')!.click()
    await nextTick()
    expect(text.classList.contains('is-expanded')).toBe(true)
    row.querySelector<HTMLElement>('[data-action="collapse"]')!.click()
    await nextTick()
    expect(text.classList.contains('is-expanded')).toBe(false)
  })

  it('短命令不给折叠按钮（避免无意义控件）', async () => {
    const root = await mountTree()
    const row = cmdRow(root, 'TC-001')!
    expect(row.querySelector('[data-action="expand"]')).toBeNull()
  })

  it('点「定位日志」把 caseKey 交给父级', async () => {
    const root = await mountTree()
    const row = cmdRow(root, 'TC-001')!
    row.querySelector<HTMLElement>('[data-action="locate-log"]')!.click()
    await nextTick()
    expect(logs).toEqual(['TC-001'])
  })

  it('复制成功后按钮文案切到「已复制」', async () => {
    // jsdom 没有 Clipboard API，也没有 execCommand：显式桩一条成功的通路，
    // 否则测的其实是「兜底也失败了」这条分支。
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    const root = await mountTree()
    const row = cmdRow(root, 'TC-001')!
    row.querySelector<HTMLElement>('[data-action="copy"]')!.click()
    // copyText 走 Promise，等一轮宏任务让 then 落定
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(writeText).toHaveBeenCalledWith('ipmitool raw 0x30 0x93 0xdb 0x07 0x00 0x00')
    expect(row.textContent).toContain(i18n.global.t('reports.command.copied'))
  })

  it('复制失败时给出可见提示，不静默吞掉', async () => {
    // 现场 http 内网地址 + 权限被拒：两条通路都失败，必须有提示
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    const root = await mountTree()
    const row = cmdRow(root, 'TC-001')!
    row.querySelector<HTMLElement>('[data-action="copy"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(root.textContent).toContain(i18n.global.t('reports.command.copyFailed'))
  })
})
