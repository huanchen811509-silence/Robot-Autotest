/**
 * 能力指令 `v-can`（v5.21.0 / FR-v5.21-03 / W71-05）。
 *
 * 目的：写操作按钮按角色显隐，判据与服务端能力集**同源**（`stores/menuCapabilities`）。
 * 在这之前，页面上所有按钮都是无条件渲染的——viewer 能点开「新建用例」表单，
 * 填完保存才收到 403；页面看起来"能用"，实际每一步都失败。
 *
 * 三条约定：
 *
 * 1. **白名单**：`v-can` 不带值 = 隐藏。少写一个码就把按钮放出来是最危险的默认值。
 * 2. **隐藏而不是禁用**：`disabled` 会让用户以为"再点点就能用"，还会引出
 *    "为什么禁用"的解释成本（v5.20 的 W70-02 刚把禁用态的原因补齐，能用隐藏就别再欠新债）。
 *    这里用 `display:none` 而不是 `el.remove()`：能力集可能在挂载后补齐（路由守卫已经
 *    await 过一次，但组件在单测里可能先挂载），移除后无法再恢复。
 * 3. **它只是"给不给入口"**：真正的越权仍由后端 403 兜底，前端藏按钮不是安全边界。
 *
 * 用法：
 * ```vue
 * <button v-can="'case:write'" @click="create">新建用例</button>
 * <button v-can="'project:write'" @click="manageMembers">成员</button>
 * ```
 */
import type { Directive, DirectiveBinding } from 'vue'
import { useMenuCapabilitiesStore } from '@/stores/menuCapabilities'

const HIDDEN_ATTR = 'data-can-hidden'

function apply(el: HTMLElement, binding: DirectiveBinding<string | undefined>): void {
  const code = (binding.value ?? '').trim()
  const caps = useMenuCapabilitiesStore()
  const allowed = Boolean(code) && caps.has(code)
  if (allowed) {
    if (el.getAttribute(HIDDEN_ATTR) === '1') {
      el.style.removeProperty('display')
      el.removeAttribute(HIDDEN_ATTR)
    }
    return
  }
  el.style.display = 'none'
  el.setAttribute(HIDDEN_ATTR, '1')
}

export const can: Directive<HTMLElement, string | undefined> = {
  mounted: apply,
  updated: apply,
}
