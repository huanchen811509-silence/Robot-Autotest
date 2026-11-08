/**
 * MENU-02：环境与拓扑只读页（路由 / 只读 / 空态的纯断言）。
 *
 * 仓库未安装 @vue/test-utils，且本包不新增依赖，故只做「路由表 + 源码 + 配置」级断言，
 * 不挂载组件、不发请求；用例文件 *.spec.ts 会被 vite.config.ts 的 include 命中。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import router from '@/router'
import { NAVIGATION, isGroup, type NavGroup } from '@/config/navigation'

const VIEWS = [
  'EnvironmentOverviewView.vue',
  'EnvironmentCollectView.vue',
  'TopologySnapshotView.vue',
  'EnvironmentPrepareView.vue',
  'CollectionAnalysisView.vue',
] as const

const ROUTES: Array<[path: string, name: string]> = [
  ['/environment/overview', 'environment-overview'],
  ['/environment/collect', 'environment-collect'],
  ['/environment/topology', 'environment-topology'],
  ['/environment/prepare', 'environment-prepare'],
  ['/environment/analysis', 'environment-analysis'],
]

function source(file: string): string {
  try {
    return readFileSync(new URL(`./${file}`, import.meta.url), 'utf-8')
  } catch {
    // vite-node 若把 import.meta.url 解析成非 file:// URL，回退到 vitest 的 CWD（frontend/）
    return readFileSync(`src/views/environment/${file}`, 'utf-8')
  }
}

describe('MENU-02 环境与拓扑只读页', () => {
  it('5 条路由都解析到真实页面（不落到 not-found）', () => {
    for (const [path, name] of ROUTES) {
      const matched = router.resolve(path).matched.at(-1)?.name
      expect(matched, path).toBe(name)
      expect(matched).not.toBe('not-found')
    }
  })

  it('5 个视图均为只读：无写请求、空态显示「0 条」', () => {
    for (const file of VIEWS) {
      const text = source(file)
      expect(text, file).not.toMatch(/method\s*:\s*['"](POST|PUT|DELETE|PATCH)['"]/i)
      expect(text, file).not.toContain('requestMultipart')
      expect(text, file).toContain('0 条')
      expect(text, file).toContain('只读')
    }
  })

  it('环境准备页明确不触发真实设备写入', () => {
    expect(source('EnvironmentPrepareView.vue')).toContain('不触发真实设备写入')
  })

  it('「环境与拓扑」一级分组含 5 个二级项，路径与路由一致', () => {
    const group = NAVIGATION.find((node) => node.key === 'environment') as NavGroup
    expect(group).toBeTruthy()
    expect(isGroup(group)).toBe(true)
    expect(group.children.length).toBeGreaterThanOrEqual(5)
    const paths = group.children.map((child) => child.path)
    for (const [path] of ROUTES) expect(paths, path).toContain(path)
  })
})
