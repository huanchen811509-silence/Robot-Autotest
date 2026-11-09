/**
 * FR-v3.9-01 / ADR-39-01 · 下拉控件主题兜底的静态回归。
 *
 * 为什么要读 CSS 源文本而不是挂组件：jsdom 不会加载 main.css，也不做层叠计算，
 * 挂载断言只能测到 class 名，测不到「背景是否透明」。真实的计算样式由
 * `scripts/dark-select-probe.mjs` 在 CDP 里判（双主题、flagged=0）。
 * 这里守的是「兜底规则本身还在」——防止后来者把这几条删掉而探针没人跑。
 *
 * 注意：不能用 `import css from './main.css?raw'`。vitest 的 CSS 插件会把样式
 * 模块一律短路成空串（实测 length=0），`?raw` 也不例外，只能走 fs 直读。
 */

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 样式文件路径可由 SELECT_THEME_CSS 覆盖：门禁做负向自检时指向一份「删掉兜底规则」
 * 的临时副本，本用例必须变红，否则说明断言是空转。
 */
const cssPath = process.env.SELECT_THEME_CSS ?? 'src/styles/main.css'

/** 去掉注释，避免注释里的示例文字被当成规则命中。 */
const css = readFileSync(resolve(process.cwd(), cssPath), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '')

describe('下拉控件主题兜底（FR-v3.9-01）', () => {
  it('select 有全局不透明底色与显式 color-scheme', () => {
    const rule = css.match(/(^|\n)select\s*\{([^}]*)\}/)
    expect(rule, '缺少全局 select 兜底规则').toBeTruthy()
    const body = rule?.[2] ?? ''
    expect(body).toMatch(/background-color:\s*var\(--panel\)/)
    expect(body).toContain('color-scheme')
  })

  it('option / optgroup 显式给前景与背景', () => {
    const rule = css.match(/select\s+option[^{]*\{([^}]*)\}/)
    expect(rule, '缺少 select option 规则').toBeTruthy()
    const body = rule?.[1] ?? ''
    expect(body).toMatch(/background-color:\s*var\(--panel\)/)
    expect(body).toMatch(/color:\s*var\(--text\)/)
  })

  it('option:checked 用主题令牌锁色，不吃浏览器高亮', () => {
    const rule = css.match(/select\s+option:checked\s*\{([^}]*)\}/)
    expect(rule, '缺少 option:checked 令牌化规则').toBeTruthy()
    expect(rule?.[1] ?? '').toMatch(/background-color:\s*var\(--surface-selected\)/)
  })

  it('头部两个 select 不再写 background: transparent', () => {
    for (const cls of ['.app-project-select', '.app-lang-select']) {
      const rule = css.match(new RegExp(`\\${cls}\\s*\\{([^}]*)\\}`))
      expect(rule, `缺少 ${cls} 规则`).toBeTruthy()
      const body = rule?.[1] ?? ''
      expect(body, `${cls} 仍是透明底`).not.toMatch(/background:\s*transparent/)
      expect(body).toMatch(/background-color:\s*var\(--panel\)/)
    }
  })
})
