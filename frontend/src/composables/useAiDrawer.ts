
/** W7 / AIX-01: drawer open state, remembered width and side-rail collapse state. */
import { ref } from 'vue'

const OPEN_KEY = 'robot.ai.drawer.open'
const WIDTH_KEY = 'robot.ai.drawer.width'
const SIDE_KEY = 'robot.layout.side.collapsed'
const SIDE_MIGRATION_KEY = 'robot.layout.side.v23'
export const AI_DRAWER_DEFAULT = 340

function readBool(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key)
    return raw === null ? fallback : raw === 'true'
  } catch {
    return fallback
  }
}

/**
 * 侧栏折叠态的读取 + 一次性归位。
 *
 * v2.3 把折叠按钮从顶栏移到了侧栏头部：控制点换了位置，旧的「已折叠」状态
 * 就变成没人认领的遗留值——用户看到的是「菜单只剩图标、找不到文字」，
 * 而那个能展开的按钮换了地方，等于把菜单文字藏进了一个隐藏开关。
 * 所以升级后第一次加载时把折叠态归位为展开，并写下迁移标记（只归位一次，
 * 之后用户再手动折叠仍然尊重用户选择）。
 */
function readSideCollapsed(): boolean {
  try {
    if (localStorage.getItem(SIDE_MIGRATION_KEY) === null) {
      localStorage.setItem(SIDE_MIGRATION_KEY, '1')
      if (localStorage.getItem(SIDE_KEY) === 'true') {
        localStorage.setItem(SIDE_KEY, 'false')
        return false
      }
    }
    return readBool(SIDE_KEY, false)
  } catch {
    return false
  }
}

function readNumber(key: string, fallback: number): number {
  try {
    const raw = localStorage.getItem(key)
    const parsed = Number(raw)
    return Number.isFinite(parsed) && parsed >= 280 && parsed <= 520 ? parsed : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // private mode - in-memory state still works for this session
  }
}

const open = ref(readBool(OPEN_KEY, false))
const width = ref(readNumber(WIDTH_KEY, AI_DRAWER_DEFAULT))
const sideCollapsed = ref(readSideCollapsed())

export function useAiDrawer() {
  function toggleDrawer(): void {
    open.value = !open.value
    write(OPEN_KEY, String(open.value))
  }

  function setWidth(next: number): void {
    const bounded = Math.max(280, Math.min(520, Math.round(next)))
    width.value = bounded
    write(WIDTH_KEY, String(bounded))
  }

  function toggleSide(): void {
    sideCollapsed.value = !sideCollapsed.value
    write(SIDE_KEY, String(sideCollapsed.value))
  }

  return {
    open,
    width,
    sideCollapsed,
    toggleDrawer,
    setWidth,
    toggleSide,
  }
}
