/** 版本迭代页解析（NAV-03 / FR-v2.0-12）+ 仓库 CHANGELOG 四类齐全守门。 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parseChangelog, renderInlineMarkdown } from '@/config/changelog'

const MARKDOWN = `## v2.0（2026-11-06）<!-- current -->
### 新增 <!-- added -->
- 统一入口
### 变更 <!-- changed -->
- 菜单 IA
### 修复 <!-- fixed -->
- 顶栏选择器
### 破坏性变更 <!-- breaking -->
- 无

## v1.0（2026-11-05）
### 新增 <!-- added -->
- 执行域
### 变更 <!-- changed -->
- 口径
### 修复 <!-- fixed -->
- 表数
### 破坏性变更 <!-- breaking -->
- 无
`

describe('parseChangelog', () => {
  it('按版本倒序解析，当前版本被标记', () => {
    const releases = parseChangelog(MARKDOWN)
    expect(releases).toHaveLength(2)
    expect(releases[0].current).toBe(true)
    expect(releases[1].current).toBe(false)
    expect(releases[0].title).toContain('v2.0')
  })

  it('四类小标题齐全，条目不串到别的分组', () => {
    const sections = parseChangelog(MARKDOWN)[0].sections
    expect(sections.map((s) => s.key)).toEqual(['added', 'changed', 'fixed', 'breaking'])
    expect(sections[0].items).toEqual(['统一入口'])
    expect(sections[3].items).toEqual(['无'])
  })

  it('无锚点的旧格式回退到标题文字', () => {
    const sections = parseChangelog('## v1.0（2026-11-05）(current)\n### 新增\n- 旧条目\n')[0].sections
    expect(sections[0].key).toBe('新增')
  })

  it('空输入返回空数组', () => {
    expect(parseChangelog('')).toEqual([])
  })
})

describe('renderInlineMarkdown', () => {
  it('加粗与行内代码转成标签，原始标记不残留', () => {
    expect(renderInlineMarkdown('**项目工作区导出**：`GET /api/x`')).toBe(
      '<strong>项目工作区导出</strong>：<code>GET /api/x</code>',
    )
  })

  it('代码段里的星号不当作加粗', () => {
    expect(renderInlineMarkdown('命令 `a ** b` 保持原样')).toBe('命令 <code>a ** b</code> 保持原样')
  })

  it('先转义 HTML，CHANGELOG 内容不能注入标签', () => {
    expect(renderInlineMarkdown('<img src=x onerror=alert(1)>')).toBe(
      '&lt;img src=x onerror=alert(1)&gt;',
    )
  })

  it('无标记的纯文本原样返回', () => {
    expect(renderInlineMarkdown('修复：业务表 88 → 89 张')).toBe('修复：业务表 88 → 89 张')
  })
})

describe('仓库 CHANGELOG.md 是页面真源', () => {
  const releases = parseChangelog(
    // vitest 的 import.meta.url 在 happy-dom 下不是 file: scheme，按 cwd（frontend/）相对定位
    readFileSync(resolve(process.cwd(), '../CHANGELOG.md'), 'utf-8'),
  )

  it('至少一个版本且四类齐全', () => {
    expect(releases.length).toBeGreaterThan(0)
    for (const release of releases) {
      expect(release.sections).toHaveLength(4)
    }
  })

  it('恰好一个当前版本，且排在第一', () => {
    expect(releases.filter((release) => release.current)).toHaveLength(1)
    expect(releases[0].current).toBe(true)
  })
})
