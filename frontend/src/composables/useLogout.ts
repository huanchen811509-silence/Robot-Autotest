/**
 * 退出登录的唯一入口（v2.3.1 修复）。
 *
 * 为什么单独抽一个 composable：原来的实现是 `await auth.logout()` 再跳转，
 * 而 `auth.logout()` 内部**先**等 `/auth/logout` 请求返回才做本地清理。
 * 于是后端慢、挂住或断网时，点「退出」的表现就是**什么都没发生**（请求还悬着），
 * 用户只能刷新页面。这是「退出按钮失效」的真实成因，不是按钮绑错了事件。
 *
 * 现在的顺序是：先本地清干净 → 再异步撤销服务端会话（失败不影响退出）→ 跳登录页。
 * 这样三条判据同时成立：
 *   1. 断网也能退出（本地清理由此成为唯一前提）；
 *   2. 服务端令牌尽量被撤销（不 await，但不吞异常之外的副作用）；
 *   3. 跳转前 `authenticated` 已经是 false，路由守卫不会把人弹回工作台。
 */
import type { Router } from 'vue-router'
import { authApi } from '@/api/iam'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { useAccent } from '@/composables/useAccent'
import { useDensity } from '@/composables/useDensity'

export async function performLogout(router: Router): Promise<void> {
  const auth = useAuthStore()

  // 1. 本地清干净：令牌、用户、项目、续期定时器
  auth.clear()

  // 2. 服务端撤销：不阻塞退出。失败只可能是令牌已失效，本地已经登出，无需回滚。
  void authApi.logout().catch(() => undefined)

  // 3. 外观回默认：偏好按用户走（服务端 user_preference），本地键是全局单值——
  //    不重置的话，下一个登录用户在服务端偏好到达前会先看到上一个用户的选择。
  useTheme().setMode('system')
  useAccent().setAccent('blue')
  useDensity().setDensity('comfortable')

  // 4. 回登录页（用 replace，避免后退键又回到需要登录的页面）
  await router.replace('/login')
}
