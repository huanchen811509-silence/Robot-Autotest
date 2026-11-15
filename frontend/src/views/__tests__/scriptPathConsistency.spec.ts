/**
 * 脚本路径「三处同源」回归（v5.11.0 / FR-v5.11-05 / W61-13）。
 *
 * 同一条用例的脚本文件名，在**列表单元格**与**详情区**必须是**逐字相同**的字符串。
 * 这条不变量靠的是「文件名由后端算、前端只显示」——只要有人在前端补一句
 * `path.split('/').pop()`，三处就会开始漂移：列表显示 `test_bw.py`、详情显示
 * `/home/ci/repo/tests/net/test_bw.py`，而两边都不会报错。
 *
 * 因此这里不测「组件自己能渲染」，而是同时挂列表用的紧凑版与详情用的完整版，
 * 把同一个后端载荷喂进去，抓 DOM 文本逐字比对。
 *
 * 判据同时锁住「路径必须是相对路径」：绝对路径一旦漏到界面，用户复制去执行就是错的。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import zhCN from '@/locales/zh-CN'
import CaseScriptLink from '@/components/CaseScriptLink.vue'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'
import type { CaseDetailView } from '@/api/caseDetail'

const get = vi.hoisted(() => vi.fn())
vi.mock('@/api/caseDetail', () => ({ caseDetailApi: { get } }))

/** 后端下发的脚本三件套（与 `CaseScriptFields` 同名同值）。 */
const SCRIPT = {
  scriptPath: 'tests/net/test_bw.py',
  scriptName: 'test_bw.py',
  scriptNodeId: 'tests/net/test_bw.py::test_bw',
  bindingState: 'BOUND_VERIFIED',
}

const DETAIL: CaseDetailView = {
  id: 42,
  caseCode: 'TC-001',
  externalId: 'EXT-9',
  title: '网口带宽回归',
  categoryL1: '网络',
  categoryL2: 'RoCE',
  priority: 'P1',
  safetyLevel: 'L1',
  lifecycleStatus: 'active',
  versionNo: 3,
  sourceSheet: 'Sheet1',
  contentSha: 'sha-abc',
  aSideCommand: 'iperf3 -c 10.0.0.1',
  bSideCommand: null,
  switchConfig: 'vlan 100',
  testResult: 'PASS',
  testResource: 'host-a',
  syncJobId: null,
  localOverride: false,
  sourceBadge: 'LOCAL',
  keyMaskedCredential: '****',
  ...SCRIPT,
}

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zhCN, 'en-US': zhCN },
})

let apps: App[] = []
let hosts: HTMLElement[] = []

function mount(component: unknown, props: Record<string, unknown>): HTMLElement {
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp(component as never, props)
  app.use(i18n)
  app.mount(root)
  apps.push(app)
  hosts.push(root)
  return root
}

async function flush(): Promise<void> {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

afterEach(() => {
  for (const app of apps) app.unmount()
  for (const host of hosts) host.remove()
  apps = []
  hosts = []
  get.mockReset()
})

describe('脚本路径三处同源（FR-v5.11-05）', () => {
  it('列表单元格与详情区的文件名逐字相同，且都不含路径分隔符', async () => {
    // 列表：`GlobalCasesView` 的脚本列就是这么喂 props 的
    const listRoot = mount(CaseScriptLink, {
      scriptPath: SCRIPT.scriptPath,
      scriptName: SCRIPT.scriptName,
      bindingState: SCRIPT.bindingState,
    })
    const listName = listRoot
      .querySelector('[data-testid="case-script-name"]')!
      .textContent!.trim()

    // 详情：同一个载荷走 `caseDetailApi.get`
    get.mockResolvedValue(DETAIL)
    const detailRoot = mount(CaseDetailPanel, { caseId: 42, source: 'project', projectId: 3 })
    await flush()
    const scriptBlock = detailRoot.querySelector('[data-testid="case-detail-script"]')
    expect(scriptBlock, '详情区没有脚本行，脚本路径只活在列表里').toBeTruthy()
    const detailName = scriptBlock!
      .querySelector('[data-testid="case-script-name"]')!
      .textContent!.trim()

    expect(detailName).toBe(listName)
    expect(listName).toBe(SCRIPT.scriptName)
    expect(listName).not.toContain('/')
    expect(listName).not.toContain('\\')
  })

  it('详情的完整路径是仓库内相对路径（绝对路径漏到界面就是错的）', async () => {
    get.mockResolvedValue(DETAIL)
    const root = mount(CaseDetailPanel, { caseId: 42, source: 'project', projectId: 3 })
    await flush()

    const path = root.querySelector('[data-testid="case-script-path"]')!.textContent!.trim()
    expect(path).toBe(SCRIPT.scriptPath)
    // 不以 / 或盘符开头、不含 file:// —— 复制去执行时必须能直接用
    expect(path).toMatch(/^(?!\/)(?![A-Za-z]:[\\/])(?!file:).+$/)
  })

  it('详情同时给出 nodeid，便于直接定位到函数', async () => {
    get.mockResolvedValue(DETAIL)
    const root = mount(CaseDetailPanel, { caseId: 42, source: 'project', projectId: 3 })
    await flush()

    const node = root.querySelector('[data-testid="case-detail-node-id"]')!
    expect(node.textContent!.trim()).toBe(SCRIPT.scriptNodeId)
  })

  it('未绑定脚本时列表与详情给出同一句「未绑定」，不留空白单元格', async () => {
    const unbound = { scriptPath: null, scriptName: null, bindingState: 'UNBOUND' }
    const listRoot = mount(CaseScriptLink, unbound)
    const listText = listRoot.textContent!.trim()

    get.mockResolvedValue({ ...DETAIL, ...unbound, scriptNodeId: null })
    const detailRoot = mount(CaseDetailPanel, { caseId: 42, source: 'project', projectId: 3 })
    await flush()
    const block = detailRoot.querySelector('[data-testid="case-detail-script"]')!

    expect(block.textContent).toContain(listText)
    expect(listText.length).toBeGreaterThan(0)
  })
})
