/**
 * 退出登录回归（v2.3.1 缺陷修复）。
 *
 * 盯的是「点了退出什么都没发生」这个现场：原实现 `await auth.logout()` 会先等
 * `/auth/logout` 返回，后端慢/挂住时本地令牌一直不清、也不跳转——按钮看起来失效。
 *
 * 所以这里的第一条用例就用一个**永不 resolve** 的登出请求：
 * 只要 `performLogout` 还依赖它，本地清理与跳转就都会被挂住，断言必红。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Router } from 'vue-router'
import { authApi } from '@/api/iam'
import { useAuthStore } from '@/stores/auth'
import { performLogout } from '@/composables/useLogout'

vi.mock('@/api/iam', () => ({
  authApi: { logout: vi.fn() },
}))

const logoutMock = vi.mocked(authApi.logout)

function fakeRouter(): { router: Router; replaced: string[] } {
  const replaced: string[] = []
  const router = {
    replace: (to: string) => {
      replaced.push(to)
      return Promise.resolve()
    },
  } as unknown as Router
  return { router, replaced }
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  logoutMock.mockReset()
  logoutMock.mockResolvedValue(undefined)
  const auth = useAuthStore()
  auth.token = 'token-123'
  localStorage.setItem('auth_token', 'token-123')
  auth.projectId = 7
})

describe('退出登录（FR-UI-17 修复）', () => {
  it('服务端撤销请求挂住时，本地仍然清干净并跳登录页', async () => {
    // 永不 resolve：模拟后端无响应
    logoutMock.mockReturnValue(new Promise<void>(() => {}))
    const { router, replaced } = fakeRouter()

    await performLogout(router)

    const auth = useAuthStore()
    expect(auth.token).toBeNull()
    expect(auth.authenticated).toBe(false)
    expect(localStorage.getItem('auth_token')).toBeNull()
    expect(replaced).toEqual(['/login'])
  })

  it('服务端撤销失败（令牌已过期）不抛错、不影响退出', async () => {
    logoutMock.mockRejectedValue(new Error('401'))
    const { router, replaced } = fakeRouter()

    await expect(performLogout(router)).resolves.toBeUndefined()
    expect(useAuthStore().authenticated).toBe(false)
    expect(replaced).toEqual(['/login'])
  })

  it('本地先清、再发撤销请求：请求发出去时 token 已经从本地拿掉了', async () => {
    let tokenWhenRevoked: string | null = 'sentinel'
    logoutMock.mockImplementation(() => {
      tokenWhenRevoked = localStorage.getItem('auth_token')
      return Promise.resolve()
    })
    const { router } = fakeRouter()

    await performLogout(router)

    expect(tokenWhenRevoked).toBeNull()
    expect(logoutMock).toHaveBeenCalledTimes(1)
  })
})
