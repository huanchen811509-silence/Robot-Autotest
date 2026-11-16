/**
 * 菜单能力集状态（v4.9.2 / OPT-08 / FR-49-31~34）。
 *
 * `ready` 是渲染闸门：能力集 load() 完成（成功或失败降级）之前为 false，
 * App.vue 据此不渲染导航，避免「先全显再收起」的闪烁。`public` 对所有角色可见。
 */
import { defineStore } from 'pinia'
import { menuCapabilitiesApi } from '@/api/menuCapabilities'

export const useMenuCapabilitiesStore = defineStore('menuCapabilities', {
  state: () => ({
    capabilities: [] as string[],
    ready: false,
  }),
  actions: {
    /** 拉取服务端能力集；失败降级为最小能力集（空集，只保留 public 入口）。 */
    async load(): Promise<void> {
      try {
        const caps = await menuCapabilitiesApi.get()
        this.capabilities = Array.isArray(caps) ? [...caps] : []
      } catch {
        this.capabilities = []
      } finally {
        this.ready = true
      }
    },
    /**
     * 只加载一次（v5.21.0 / FR-v5.21-02 / W71-06）。
     *
     * 路由守卫在 `App.vue` 挂载之前就会跑：直接输 URL / 刷新时 `ready` 还是 false，
     * 此时按能力判会"一个能力都没有"→ 把有权限的用户也踢到 403。
     * 守卫改为 `await ensureLoaded()` 再判，语义是"以服务端能力集为准"，而不是"没加载就先放行"。
     */
    async ensureLoaded(): Promise<void> {
      if (this.ready) return
      await this.load()
    },
    /** 单项能力判定；`public` 对所有角色可见。 */
    has(capability: string): boolean {
      if (capability === 'public') return true
      return this.capabilities.includes(capability)
    },
    /** 能力集合快照（供需要数组口径的调用方使用）。 */
    all(): string[] {
      return [...this.capabilities]
    },
  },
})
