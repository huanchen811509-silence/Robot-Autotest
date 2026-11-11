/**
 * 应用骨架的无障碍入口（FR-3.1-10）。
 *
 * 跳过链接依赖「锚点 + 主内容 id」同时存在才有意义，少一个就失效。
 * 这里用 `?raw` 读模板做结构断言；视觉焦点样式由 main.css 的 .skip-link 负责。
 */
import { describe, expect, it } from 'vitest'
import appSource from '@/App.vue?raw'

describe('App 骨架', () => {
  it('提供跳到主内容的 skip link，且指向 <main id="main">', () => {
    expect(appSource).toContain('class="skip-link"')
    expect(appSource).toContain('href="#main"')
    expect(appSource).toMatch(/<main[^>]*id="main"/)
    expect(appSource).toContain("t('a11y.skipToMain')")
  })

  it('主内容可被程序聚焦（tabindex=-1），且快捷键面板挂在应用内', () => {
    expect(appSource).toMatch(/<main[^>]*tabindex="-1"/)
    expect(appSource).toContain('<ShortcutPanel')
  })
})

/**
 * v4.11.0 / FR-v4.11-03：全局侧栏也必须接能力过滤。
 *
 * <p>v4.10.0 之前全局 5 项都没写 `capability`，所以「不过滤」看不出问题；本版全局侧栏
 * 扩到 5 组 14 叶（含「用户管理 / 审计日志」），再不过滤就是**向所有角色暴露管理入口**。
 * 判据只有一条：`nav` 计算属性对两种模式走同一个过滤函数，不存在「全局直接返回原树」的分支。
 */
describe('App 侧栏能力过滤（FR-v4.11-03）', () => {
  it('两种模式共用 filterNavByCapabilityPredicate，不存在不过滤的旁路', () => {
    const navBlock = /const nav = computed<NavNode\[\]>\(\(\) => \{([\s\S]*?)\n\}\)/.exec(appSource)
    expect(navBlock, '找不到 nav 计算属性').not.toBeNull()
    const body = navBlock![1]

    expect(body).toContain('filterNavByCapabilityPredicate')
    expect(body).toContain('GLOBAL_NAVIGATION')
    expect(body).toContain('NAVIGATION')
    // 树的选择必须出现在过滤器调用**之后**（即作为它的实参），而不是被提前 return 出去
    expect(body.indexOf('GLOBAL_NAVIGATION')).toBeGreaterThan(
      body.indexOf('filterNavByCapabilityPredicate('),
    )
    // 不存在「直接返回未过滤的树」这种旁路
    expect(body).not.toMatch(/return\s+GLOBAL_NAVIGATION/)
    expect(body).not.toMatch(/return\s+NAVIGATION/)
  })
})
