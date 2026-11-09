/**
 * FR-v3.11-02：环境与拓扑 5 页（路由 / 真实数据源 / 只读口径 / 显式写入）。
 *
 * 仓库未安装 @vue/test-utils，且本包不新增依赖，故做「路由表 + 源码级」断言：
 * 旧版 5 页行集恒为空，只验「不写」；v3.11 接了真实接口之后，判据必须跟着升级——
 *   - 只读 3 页（总览 / 准备 / 分析）源码里连写函数的名字都不能出现；
 *   - 可写 2 页（采集 / 拓扑）的写动作必须挂在显式按钮 + 二次确认上；
 *   - 5 页都要有空态与错误态的可判定 testid。
 * 这些判据配合 CDP 探针（scripts/env-pages-probe.mjs）里的真实网络请求断言一起用。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import router from '@/router'
import { NAVIGATION, isGroup, type NavGroup } from '@/config/navigation'
import { parseSpec } from '@/api/environment'

const VIEWS = [
  'EnvironmentOverviewView.vue',
  'EnvironmentCollectView.vue',
  'TopologySnapshotView.vue',
  'EnvironmentPrepareView.vue',
  'CollectionAnalysisView.vue',
] as const

const READ_ONLY_VIEWS = [
  'EnvironmentOverviewView.vue',
  'EnvironmentPrepareView.vue',
  'CollectionAnalysisView.vue',
] as const

const INTERACTIVE_VIEWS = [
  'EnvironmentCollectView.vue',
  'TopologySnapshotView.vue',
] as const

const ROUTES: Array<[path: string, name: string]> = [
  ['/environment/overview', 'environment-overview'],
  ['/environment/collect', 'environment-collect'],
  ['/environment/topology', 'environment-topology'],
  ['/environment/prepare', 'environment-prepare'],
  ['/environment/analysis', 'environment-analysis'],
]

const WRITE_RE = /method\s*:\s*['"](POST|PUT|DELETE|PATCH)['"]|requestMultipart/i

function source(file: string): string {
  try {
    return readFileSync(new URL(`./${file}`, import.meta.url), 'utf-8')
  } catch {
    return readFileSync(`src/views/environment/${file}`, 'utf-8')
  }
}

function apiSource(): string {
  return readFileSync('src/api/environment.ts', 'utf-8')
}

describe('FR-v3.11-02 环境与拓扑 5 页', () => {
  it('5 条路由都解析到真实页面（不落到 not-found）', () => {
    for (const [path, name] of ROUTES) {
      const matched = router.resolve(path).matched.at(-1)?.name
      expect(matched, path).toBe(name)
      expect(matched).not.toBe('not-found')
    }
  })

  it('5 页都接真实数据源（调用 environment / asset 的 API，不再用恒空数组）', () => {
    for (const file of VIEWS) {
      const text = source(file)
      expect(text, file).toMatch(/@\/api\/(environment|asset)/)
      expect(text, file).toContain('onMounted')
      expect(text, file).toMatch(/async function load\w*/)
    }
  })

  it('5 页都有空态与错误态的可判定 testid，且空态显示「0 条」', () => {
    const prefixes: Record<string, string> = {
      'EnvironmentOverviewView.vue': 'overview',
      'EnvironmentCollectView.vue': 'collect',
      'TopologySnapshotView.vue': 'topology',
      'EnvironmentPrepareView.vue': 'prepare',
      'CollectionAnalysisView.vue': 'analysis',
    }
    for (const file of VIEWS) {
      const text = source(file)
      const p = prefixes[file]
      expect(text, file).toContain(`data-testid="${p}-empty"`)
      expect(text, file).toContain(`data-testid="${p}-failed"`)
      expect(text, file).toContain('0 条')
      expect(text, file).toContain('只读')
    }
  })

  it('只读 3 页不发起写请求，连写函数名都不出现', () => {
    for (const file of READ_ONLY_VIEWS) {
      const text = source(file)
      expect(text, file).not.toMatch(WRITE_RE)
      expect(text, file).not.toContain('hostInventoryApi.collect')
      expect(text, file).not.toContain('envApi.create')
      expect(text, file).not.toContain('envApi.diff')
    }
  })

  it('可写 2 页的写动作都挂在显式按钮 + 二次确认上', () => {
    for (const file of INTERACTIVE_VIEWS) {
      const text = source(file)
      expect(text, file).toContain('confirming')
      expect(text, file).toContain('data-testid="' +
        (file.startsWith('Environment') ? 'collect-confirm' : 'topology-confirm') + '"')
      expect(text, file).not.toMatch(WRITE_RE)
    }
    expect(source('EnvironmentCollectView.vue')).toContain('hostInventoryApi.collect')
    expect(source('TopologySnapshotView.vue')).toContain('envApi.create')
  })

  it('环境准备页明确不触发真实设备写入', () => {
    expect(source('EnvironmentPrepareView.vue')).toContain('不触发真实设备写入')
    expect(source('EnvironmentPrepareView.vue')).not.toMatch(WRITE_RE)
  })

  it('API 层：取数函数一律 GET，写只出现在 collect / create / diff 三处', () => {
    const text = apiSource()
    const postCount = (text.match(/method:\s*'POST'/g) ?? []).length
    expect(postCount).toBe(3)
    for (const name of ['commands', 'list', 'detail', 'profiles', 'snapshots', 'latest']) {
      const start = text.indexOf(`  ${name}(`)
      expect(start, name).toBeGreaterThan(-1)
    }
    expect(text).not.toContain('method: \'PUT\'')
    expect(text).not.toContain('method: \'DELETE\'')
  })

  it('parseSpec：列表的 null / 损坏 JSON 一律按「没有详情」处理，不抛到界面外', () => {
    expect(parseSpec(null)).toBeNull()
    expect(parseSpec('{broken')).toBeNull()
    expect(parseSpec('{"host":"x"}')).toBeNull()
    const spec = parseSpec('{"host":"localhost","status":"PARTIAL","items":[' +
      '{"category":"GPU","label":"GPU","command":"nvidia-smi","collectedAt":"2026-11-09T00:00:00Z",' +
      '"value":null,"reason":"未采集到 GPU"}]}')
    expect(spec?.items).toHaveLength(1)
    expect(spec?.items[0].value).toBeNull()
    expect(spec?.items[0].reason).toContain('GPU')
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
