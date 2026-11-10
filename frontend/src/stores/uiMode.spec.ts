/**
 * 界面模式回归（4.9.2 修复）。
 *
 * 真实缺陷：登录发生在 SPA 内时外壳不重挂，`load()` 只跑过一次或根本没跑，
 * `globalEnter` 仍为 false —— 顶栏「全局」按钮能点、但点什么都没反应。
 * 这里锁两件事：
 *   ① 未加载能力码时 `setMode('global')` 必须先补 `load()`，再决定放行；
 *   ② 服务端明确不给全局能力时，切换必须失败且不改本地模式（最小权限）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiModeStore } from '@/stores/uiMode'

const capabilities = vi.fn()
const uiModeGet = vi.fn()
const uiModePut = vi.fn()

vi.mock('@/api/iam', () => ({
  projectApi: { capabilities: () => capabilities() },
  meApi: { uiMode: () => uiModeGet(), setUiMode: (mode: string) => uiModePut(mode) },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  capabilities.mockReset()
  uiModeGet.mockReset()
  uiModePut.mockReset().mockResolvedValue({})
})

describe('uiMode.setMode（登录后切换全局）', () => {
  it('能力码未加载时按需补 load()，有权限则切换成功', async () => {
    capabilities.mockResolvedValue({ globalEnter: true })
    uiModeGet.mockResolvedValue({ mode: 'project' })
    const store = useUiModeStore()
    expect(store.ready).toBe(false)

    await expect(store.setMode('global')).resolves.toBe(true)
    expect(capabilities).toHaveBeenCalledTimes(1)
    expect(store.mode).toBe('global')
    expect(uiModePut).toHaveBeenCalledWith('global')
  })

  it('服务端不给全局能力时切换失败，模式保持项目', async () => {
    capabilities.mockResolvedValue({ globalEnter: false })
    uiModeGet.mockResolvedValue({ mode: 'project' })
    const store = useUiModeStore()

    await expect(store.setMode('global')).resolves.toBe(false)
    expect(store.mode).toBe('project')
    expect(uiModePut).not.toHaveBeenCalled()
  })

  it('已加载后不再重复请求能力码', async () => {
    capabilities.mockResolvedValue({ globalEnter: true })
    uiModeGet.mockResolvedValue({ mode: 'project' })
    const store = useUiModeStore()
    await store.load()

    await expect(store.setMode('global')).resolves.toBe(true)
    expect(capabilities).toHaveBeenCalledTimes(1)
  })
})
