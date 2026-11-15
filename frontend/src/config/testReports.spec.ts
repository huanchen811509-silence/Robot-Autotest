import { describe, expect, it } from 'vitest'
import { exportFileName } from '@/api/testReports'

describe('exportFileName', () => {
  it('keeps known formats lowercased', () => {
    expect(exportFileName(12, 'PDF')).toBe('report-12.pdf')
    expect(exportFileName(3, 'docx')).toBe('report-3.docx')
    expect(exportFileName(3, 'HTML')).toBe('report-3.html')
    // v5.14.0 / W64-07：xlsx 是第四种真实支持的格式，不是未知格式。
    expect(exportFileName(9, 'XLSX')).toBe('report-9.xlsx')
  })

  it('falls back to html for unknown formats', () => {
    expect(exportFileName(9, 'markdown')).toBe('report-9.html')
    expect(exportFileName(9, '')).toBe('report-9.html')
  })
})
