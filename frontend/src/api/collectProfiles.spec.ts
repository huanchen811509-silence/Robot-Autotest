/** W63-12：详情页不得再合成 id=0 的假凭据。 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

function source(rel: string): string {
  for (const base of [process.cwd(), join(process.cwd(), '..')]) {
    const full = join(base, rel)
    try { return readFileSync(full, 'utf-8') } catch { /* next candidate */ }
  }
  throw new Error(`source not found: ${rel}`)
}

describe('假凭据清理（W63-12）', () => {
  it('assetDetail.ts 不再构造 id: 0 的 credentials 行', () => {
    const src = source('frontend/src/api/assetDetail.ts')
    expect(src).not.toMatch(/id:\s*0/)
    expect(src).toContain('credentials: []')
    expect(src).toContain('W63-12')
  })

  it('画像 API 端点与后端契约一致', () => {
    const src = source('frontend/src/api/collectProfiles.ts')
    expect(src).toContain('/assets/collect-profiles')
    expect(src).toContain('/global/assets/')
    expect(src).toContain('/projects/')
    expect(src).toContain('/assets/collect-history')
  })
})
