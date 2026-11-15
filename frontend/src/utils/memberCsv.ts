/**
 * 用例集成员清单 CSV（v5.12 / W62-05）。
 * 表头由调用方从 i18n 给，这里不硬编码任何语言；BOM 保证 Excel 打开中文不乱码。
 */
export interface CsvMemberRow {
  caseCode: string
  title: string
  category?: string | null
  scriptPath?: string | null
}

function escapeCell(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value)
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** 固定五列：序号 / 编号 / 标题 / 分类 / 脚本路径。 */
export function buildMemberCsv(
  rows: readonly CsvMemberRow[],
  header: readonly string[],
): string {
  if (header.length !== 5) throw new Error('CSV header must have exactly 5 columns')
  const lines = [header.map(escapeCell).join(',')]
  rows.forEach((row, index) => {
    lines.push([
      index + 1,
      row.caseCode,
      row.title,
      row.category ?? '',
      row.scriptPath ?? '',
    ].map(escapeCell).join(','))
  })
  return '\uFEFF' + lines.join('\r\n')
}
