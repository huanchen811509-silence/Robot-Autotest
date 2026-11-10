import { defineStore } from 'pinia'
import { meApi, projectApi } from '@/api/iam'

export type UiMode = 'global' | 'project'

const MODE_KEY = 'ui_mode'
const PROJECT_PATH_KEY = 'last_project_path'

function readMode(): UiMode {
  return localStorage.getItem(MODE_KEY) === 'global' ? 'global' : 'project'
}

/**
 * 界面模式（v4.5.0 / FR-v4.5-01/02/06）。
 *
 * - 模式持久化到 `app_user.default_ui_mode`，同时写 localStorage 兜底；
 * - 无权限时回落 project，且**不覆盖**服务端记录：降权再恢复后仍能回到全局；
 * - 切换模式不丢当前项目；记录项目模式下最后停留的路径，往返可还原。
 */
export const useUiModeStore = defineStore('uiMode', {
  state: () => ({
    mode: readMode() as UiMode,
    globalEnter: false,
    ready: false,
  }),
  getters: {
    isGlobal: (state) => state.mode === 'global',
  },
  actions: {
    /** 登录后调用：能力码以服务端为准。 */
    async load(): Promise<void> {
      try {
        const [capability, preference] = await Promise.all([
          projectApi.capabilities(),
          meApi.uiMode(),
        ])
        this.globalEnter = capability.globalEnter === true
        this.mode = this.globalEnter && preference.mode === 'global' ? 'global' : 'project'
      } catch {
        // 能力码失败不阻断登录：按最小权限走项目模式
        this.globalEnter = false
        this.mode = 'project'
      }
      this.ready = true
      localStorage.setItem(MODE_KEY, this.mode)
    },
    async setMode(mode: UiMode): Promise<boolean> {
      // 登录发生在 SPA 内时外壳不会重挂，load() 只跑过一次或根本没跑；
      // 能力码没拿到就把按钮变成死键（点「全局」无反应），这里按需补一次。
      if (!this.ready) await this.load()
      if (mode === 'global' && !this.globalEnter) return false
      this.mode = mode
      localStorage.setItem(MODE_KEY, mode)
      try {
        await meApi.setUiMode(mode)
      } catch {
        // 本地已切；服务端失败不回滚，下次登录以服务端值为准
      }
      return true
    },
    /** 记录项目模式下最后停留的站内路径（不含 /g 前缀）。 */
    rememberProjectPath(path: string): void {
      if (!path.startsWith('/g')) localStorage.setItem(PROJECT_PATH_KEY, path)
    },
    projectPath(): string {
      const path = localStorage.getItem(PROJECT_PATH_KEY)
      return path && !path.startsWith('/g') ? path : '/workbench'
    },
    clear(): void {
      this.mode = 'project'
      this.globalEnter = false
      this.ready = false
      localStorage.removeItem(MODE_KEY)
      localStorage.removeItem(PROJECT_PATH_KEY)
    },
  },
})
