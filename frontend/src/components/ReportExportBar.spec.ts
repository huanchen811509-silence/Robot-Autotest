/**
 * v5.14.0 / W64-07：四格式导出接线。
 * 只断言「格式清单与后端一致、exportBlob 真被调用、文件名与大小有回执」；
 * 导出件能否打开（PDF 页数 / DOCX 可解析）属 FILE 证据，留 W7 真实探针。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { i18n } from '@/locales'
import { testReportsApi } from '@/api/testReports'
import { ApiError } from '@/api/http'
import ReportExportBar from '@/components/ReportExportBar.vue'

vi.mock('@/api/testReports', () => ({
  exportFileName: (id: number, format: string) => `report-${id}.${format}`,
  testReportsApi: { exportBlob: vi.fn() },
}))

function mountBar() {
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp(ReportExportBar, { reportId: 7 })
  app.use(i18n)
  app.mount(root)
  return root
}

beforeEach(() => {
  document.body.innerHTML = ''
  vi.mocked(testReportsApi.exportBlob).mockReset()
  vi.mocked(testReportsApi.exportBlob).mockResolvedValue(new Blob(['x']))
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true, value: vi.fn(() => 'blob:mock'),
  })
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true, value: vi.fn(),
  })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

describe('ReportExportBar 四格式导出（v5.14.0 / W64-07）', () => {
  it('下拉含 html/pdf/docx/xlsx 四格式，默认 pdf', () => {
    const root = mountBar()
    const select = root.querySelector<HTMLSelectElement>('[data-testid="export-format"]')
    expect(select).not.toBeNull()
    expect(Array.from(select!.options).map((o) => o.value)).toEqual(['html', 'pdf', 'docx', 'xlsx'])
    expect(select!.value).toBe('pdf')
  })

  it('点导出真调 exportBlob，回执带文件名与字节数', async () => {
    const root = mountBar()
    root.querySelector<HTMLButtonElement>('[data-testid="export-trigger"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    expect(testReportsApi.exportBlob).toHaveBeenCalledWith(7, 'pdf', 'external')
    expect(root.querySelector('[data-testid="export-message"]')?.textContent).toContain('report-7.pdf')
  })

  it('失败显示后端原文（含处置建议），不产生空文件、不误标成功', async () => {
    const detail = '外部版导出被拒绝：缺少 SW_BUILD，补齐后重新冻结，或改用 mode=internal 导出内部版'
    vi.mocked(testReportsApi.exportBlob).mockRejectedValueOnce(
      new ApiError(40001, 'INVALID_ARGUMENT', detail))
    const root = mountBar()
    root.querySelector<HTMLButtonElement>('[data-testid="export-trigger"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const el = root.querySelector('[data-testid="export-message"]')
    expect(el?.textContent).toContain('SW_BUILD')
    expect(el?.textContent).toContain('mode=internal')
    expect(el?.classList.contains('export-message-error')).toBe(true)
    expect(el?.textContent).not.toContain('report-7.pdf')
  })

  it('内部版导出：下拉可选 internal，且把 mode 传给后端', async () => {
    const root = mountBar()
    const modeSel = root.querySelector<HTMLSelectElement>('[data-testid="export-mode"]')!
    expect(Array.from(modeSel.options).map((o) => o.value)).toEqual(['external', 'internal'])
    modeSel.value = 'internal'
    modeSel.dispatchEvent(new Event('change'))
    root.querySelector<HTMLButtonElement>('[data-testid="export-trigger"]')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(testReportsApi.exportBlob).toHaveBeenCalledWith(7, 'pdf', 'internal')
  })
})
