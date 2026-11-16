/**
 * 单测用能力集夹具（v5.21.0 / FR-v5.21-03 / W71-05）。
 *
 * 背景：写操作按钮改由 `v-can` / `useCan` 按**服务端能力集**控制显隐。视图级单测
 * 直接挂载组件、不走登录流程，能力集是空的 → 所有写按钮都被隐藏 → 测例在
 * "按钮找不到"上失败。这不是功能坏了，是夹具缺了一步。
 *
 * 用法：
 * ```ts
 * import { seedMenuCapabilities } from '@/test/seedMenuCapabilities'
 * beforeEach(() => { seedMenuCapabilities() })              // 管理员口径（全量）
 * beforeEach(() => { seedMenuCapabilities(['case:read']) }) // 只读口径
 * ```
 */
import { useMenuCapabilitiesStore } from '@/stores/menuCapabilities'

/** 与后端 `PermissionMatrix.MENU_CAPABILITIES` 对齐的全量口径（管理员）。 */
export const ALL_MENU_CAPABILITIES = [
  'settings:read',
  'ai:config',
  'user:write',
  'audit:read',
  'about',
  'changelog',
  'case:write',
  'execution:read',
  'project:create',
  'project:read',
  'case:read',
  'asset:read',
  'report:read',
  'env:read',
  'execution:write',
  'project:write',
]

export function seedMenuCapabilities(capabilities: string[] = ALL_MENU_CAPABILITIES): void {
  const store = useMenuCapabilitiesStore()
  store.capabilities = [...capabilities]
  store.ready = true
}
