/**
 * 能力判定的组合式用法（v5.21.0 / FR-v5.21-03 / W71-05）。
 *
 * 与 `v-can` 指令同源（都读 `stores/menuCapabilities`）。用于"不是藏一个元素，
 * 而是在逻辑分支里判"的场景：例如预检失败时把按钮文案换成原因、或用
 * `v-if` 控制整块区域（连占位都不要）。
 */
import { computed } from 'vue'
import { useMenuCapabilitiesStore } from '@/stores/menuCapabilities'

export function useCan() {
  const caps = useMenuCapabilitiesStore()
  /** 单项判定；未给码一律 false（白名单）。 */
  const can = (capability: string | undefined | null): boolean =>
    Boolean(capability) && caps.has(capability as string)
  const owned = computed(() => caps.all())
  return { can, owned }
}
