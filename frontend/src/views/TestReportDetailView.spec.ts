/**
 * v5.14.0 / W64-05：测试报告详情页的只读预览与安全边界。
 * 只断言「快照通道被接上、iframe sandbox 收紧、空态可解释」，不冒充真视口截图。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { i18n } from '@/locales'
import { testReportsApi } from '@/api/testReports'
import TestReportDetailView from '@/views/TestReportDetailView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '7' }, query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/testReports', () => ({
  testReportsApi: {
    snapshot: vi.fn(),
    freeze: vi.fn(async () => ({})),
    template: vi.fn(),
    sections: vi.fn(),
  },
}))

const frozen = {
  html: '<h1>Test Report</h1><nav id="toc"><a href="#conclusion">一、结论层</a></nav>'
    + '<section id="conclusion">结论</section><section id="evidence">证据</section>'
    + '<section id="appendix">附录</section><dl><dt>runId</dt><dd>RUN-1</dd></dl>',
  hash: 'abc123',
  state: 'FROZEN',
  title: '报告 7',
  frozen: true,
  hasSnapshot: true,
}

function mountView() {
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp(TestReportDetailView)
  app.use(i18n)
  app.mount(root)
  return root
}

beforeEach(() => {
  vi.mocked(testReportsApi.snapshot).mockReset()
  vi.mocked(testReportsApi.snapshot).mockResolvedValue(frozen)
  vi.mocked(testReportsApi.template).mockReset()
  vi.mocked(testReportsApi.template).mockResolvedValue({
    id: 8, version: 1, name: 'EHT-PYTEST', fieldKeys: [],
    kernel: 'pytest', templateSource: 'PLATFORM_KERNEL_MATCH',
  })
  vi.mocked(testReportsApi.sections).mockReset()
  vi.mocked(testReportsApi.sections).mockResolvedValue({
    kernel: 'pytest',
    counts: { passed: 6, failed: 1, missingEvidence: 1, undetermined: 2, total: 10 },
    undetermined: [],
    evidence: [{
      nodeRef: 'tests/net/test_bw.py::test_10g',
      criteriaText: '吞吐 >= 100G', measuredValue: '98.2G',
      evidencePath: 'artifacts/RUN-7/cases/test_bw/raw/1.stdout.txt',
    }],
    metadata: {}, metadataRequired: [], commitSha: null,
    diff: { hasBaseline: true, baselineRunId: 'RUN-PREV', newFail: 1, newPass: 0, bothFail: 0, notRun: 0 },
  })
  document.body.innerHTML = ''

})

describe('TestReportDetailView 快照预览（v5.14.0 / W64-05）', () => {
  it('渲染快照 iframe，sandbox 不含 allow-same-origin / allow-scripts', async () => {
    const root = mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const frame = root.querySelector<HTMLIFrameElement>('[data-testid="snapshot-frame"]')
    expect(frame).not.toBeNull()
    expect(frame!.getAttribute('sandbox')).toBe('')
    expect(frame!.getAttribute('srcdoc')).toContain('Test Report')
    expect(testReportsApi.snapshot).toHaveBeenCalledWith(7)
    expect(root.querySelector('[data-testid="frozen-hint"]')).not.toBeNull()
    // 命中内核模板时不该误报「通用模板」；模板名与内核要看得见。
    expect(root.querySelector('[data-testid="generic-template-hint"]')).toBeNull()
    expect(root.textContent).toContain('EHT-PYTEST')
    expect(root.textContent).toContain('pytest')
    // FR-09 判据 1：快照自带三段锚点，iframe sandbox 内可跳（不依赖外层 JS）。
    expect(frame!.getAttribute('srcdoc')).toContain('id="conclusion"')
    expect(frame!.getAttribute('srcdoc')).toContain('id="evidence"')
    expect(frame!.getAttribute('srcdoc')).toContain('id="appendix"')
    expect(frame!.getAttribute('srcdoc')).toContain('href="#conclusion"')
    // 证据层：真实相对路径经 CaseScriptLink 展示，四态计数可见。
    expect(root.querySelector('[data-testid="evidence-row"]')).not.toBeNull()
    expect(root.textContent).toContain('artifacts/RUN-7/cases/test_bw/raw/1.stdout.txt')
    expect(root.querySelector('[data-testid="evidence-counts"]')!.textContent).toContain('10')
    expect(testReportsApi.sections).toHaveBeenCalledWith(7)
  })

  it('无基线时明确「无上次执行」，RF 内核显示不适用', async () => {
    vi.mocked(testReportsApi.sections).mockResolvedValueOnce({
      kernel: 'robot',
      counts: { passed: 0, failed: 0, missingEvidence: 0, undetermined: 10, total: 10 },
      undetermined: [], evidence: [], metadata: {}, metadataRequired: [],
      commitSha: null,
      diff: { hasBaseline: false, baselineRunId: null, newFail: 0, newPass: 0, bothFail: 0, notRun: 0 },
    })
    const root = mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(root.querySelector('[data-testid="no-baseline"]')).not.toBeNull()
    expect(root.querySelector('[data-testid="evidence-empty"]')).not.toBeNull()
    // 语言包默认 locale 不固定，只断言空态节点与四态计数，不写死中文文案。
    expect(root.querySelector('[data-testid="evidence-counts"]')!.textContent).toContain('10')
  })

  it('回退到通用模板时界面明确提示（FR-v5.14-08 判据 3）', async () => {
    vi.mocked(testReportsApi.template).mockResolvedValueOnce({
      id: 1, version: 1, name: 'DEFAULT', fieldKeys: [],
      kernel: 'robot', templateSource: 'PLATFORM_FALLBACK',
    })
    const root = mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(root.querySelector('[data-testid="generic-template-hint"]')).not.toBeNull()
    expect(root.textContent).toContain('DEFAULT')
    expect(testReportsApi.template).toHaveBeenCalledWith(7)
  })

  it('DRAFT 显示冻结动作；无快照显示可解释空态而不是假按钮', async () => {
    vi.mocked(testReportsApi.snapshot).mockResolvedValueOnce({
      ...frozen, state: 'DRAFT', frozen: false,
    })
    const draftRoot = mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(draftRoot.querySelector('[data-testid="freeze-report"]')).not.toBeNull()

    document.body.innerHTML = ''
    vi.mocked(testReportsApi.snapshot).mockResolvedValueOnce({
      ...frozen, hasSnapshot: false, html: '',
    })
    const emptyRoot = mountView()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(emptyRoot.querySelector('[data-testid="snapshot-missing"]')).not.toBeNull()
    expect(emptyRoot.querySelector('[data-testid="snapshot-frame"]')).toBeNull()
  })
})
