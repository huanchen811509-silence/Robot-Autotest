/**
 * CHANGELOG 解析（NAV-03 / FR-v2.0-12）。
 * 放在纯 TS 模块而不是 `<script setup>`：Vue SFC 的 setup 块不允许 export，
 * 而且解析规则单独可测（版本倒序、四类齐全、当前版本标记）。
 */

export interface ChangelogSection {
  key: string
  items: string[]
}

export interface ChangelogRelease {
  title: string
  current: boolean
  sections: ChangelogSection[]
}

/** 小节锚点 → i18n key；中文/英文文案一律由语言包提供，代码里不写死自然语言。 */
export const CHANGELOG_SECTION_LABELS: Record<string, string> = {
  added: 'changelog.added',
  changed: 'changelog.changed',
  fixed: 'changelog.fixed',
  breaking: 'changelog.breaking',
}

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/**
 * 行内 Markdown 渲染（版本迭代页 / 关于页共用）。
 *
 * CHANGELOG.md 的条目里有 `**加粗**` 与 `` `行内代码` `` 两类标记；
 * 直接 `{{ item }}` 会把星号与反引号原样显示出来，所以这里先转义 HTML，
 * 再把这两类标记转成标签。转义先做，仓库内容也不可能注入脚本。
 * 只支持这两类（CHANGELOG 实际用到的全部标记），不做整段 Markdown 解析。
 */
export function renderInlineMarkdown(text: string): string {
  const codes: string[] = []
  let html = text.replace(/[&<>"']/g, (ch) => HTML_ESCAPES[ch])
  // 先摘出行内代码，避免代码里的 `**` 被当成加粗
  html = html.replace(/`([^`]+)`/g, (_, code: string) => {
    codes.push(`<code>${code}</code>`)
    return `\u0000${codes.length - 1}\u0000`
  })
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  return html.replace(/\u0000(\d+)\u0000/g, (_, index: string) => codes[Number(index)])
}

export function parseChangelog(markdown: string): ChangelogRelease[] {
  const result: ChangelogRelease[] = []
  let release: ChangelogRelease | null = null
  let section: ChangelogSection | null = null
  for (const line of markdown.split('\n')) {
    const text = line.trim()
    if (text.startsWith('## ')) {
      release = {
        title: text.slice(3).replace(/<!--.*?-->/g, '').trim(),
        current: /<!--\s*current\s*-->/.test(text) || text.includes('(current)'),
        sections: [],
      }
      result.push(release)
      section = null
    } else if (text.startsWith('### ') && release) {
      const anchor = /<!--\s*([a-z]+)\s*-->/.exec(text)
      section = {
        key: anchor ? anchor[1] : text.slice(4).replace(/<!--.*?-->/g, '').trim(),
        items: [],
      }
      release.sections.push(section)
    } else if (text.startsWith('- ') && section) {
      section.items.push(text.slice(2).trim())
    }
  }
  return result
}
