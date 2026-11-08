
/** W7 / AIX-01: drawer open state, remembered width and side-rail collapse state. */
import { ref } from 'vue'

const OPEN_KEY = 'robot.ai.drawer.open'
const WIDTH_KEY = 'robot.ai.drawer.width'
const AGENT_KEY = 'robot.ai.drawer.agent'
const MODEL_KEY = 'robot.ai.drawer.model'
const SIDE_KEY = 'robot.layout.side.collapsed'
const SIDE_MIGRATION_KEY = 'robot.layout.side.v23'

/**
 * 抽屉宽度三档（v2.6 / FR-AI-14）。
 *
 * 为什么是 340–760：340 是原有默认值，再窄对话读不动；760 是「主内容还剩多少」的交换比——
 * 1440 视口减去 200 侧栏再减 760，主区仍有 480，表格还能用。上限不再往上走，
 * 因为再宽就该开独立页面而不是抽屉。默认取 420：比原 340 宽一档，对应「对话框要更大」的诉求。
 */
export const AI_DRAWER_MIN = 340
export const AI_DRAWER_MAX = 760
export const AI_DRAWER_DEFAULT = 420

/** 宽度钳制：拖拽、按钮、恢复偏好三条入口共用同一套边界，避免各处各写一份。 */
export function clampWidth(next: number): number {
  if (!Number.isFinite(next)) {
    return AI_DRAWER_DEFAULT
  }
  return Math.max(AI_DRAWER_MIN, Math.min(AI_DRAWER_MAX, Math.round(next)))
}

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
    return Number.isFinite(parsed) ? clampWidth(parsed) : fallback
  } catch {
    return fallback
  }
}

function readText(key: string): string {
  try {
    return localStorage.getItem(key) ?? ''
  } catch {
    return ''
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
/** 上次选的 agent / 模型：空串表示「用首选 / 已保存配置」，与后端 null 语义对应。 */
const agent = ref(readText(AGENT_KEY))
const model = ref(readText(MODEL_KEY))

export function useAiDrawer() {
  function toggleDrawer(): void {
    open.value = !open.value
    write(OPEN_KEY, String(open.value))
  }

  function setWidth(next: number): void {
    const bounded = clampWidth(next)
    width.value = bounded
    write(WIDTH_KEY, String(bounded))
  }

  function setAgent(next: string): void {
    agent.value = (next ?? '').trim()
    write(AGENT_KEY, agent.value)
    // 换 agent 时清掉模型：上一家的模型名换一家多半不存在，
    // 留着会让「选了模型却跑不起来」，不如让它回到「默认模型」。
    setModel('')
  }

  function setModel(next: string): void {
    model.value = (next ?? '').trim()
    write(MODEL_KEY, model.value)
  }

  function toggleSide(): void {
    sideCollapsed.value = !sideCollapsed.value
    write(SIDE_KEY, String(sideCollapsed.value))
  }

  return {
    open,
    width,
    agent,
    model,
    sideCollapsed,
    toggleDrawer,
    setWidth,
    setAgent,
    setModel,
    toggleSide,
  }
}
