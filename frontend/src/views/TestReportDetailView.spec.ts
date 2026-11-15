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
  },
}))

const frozen = {
  html: '<h1>Test Report</h1><dl><dt>runId</dt><dd>RUN-1</dd></dl>',
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
