<script setup lang="ts">
/**
 * W6 / AIX-01..05 / FR-AI-05：右侧工作助手抽屉。
 *
 * v2.6 三处改动（FR-AI-14..16）：
 * ① 宽度可调 —— 左缘拖拽（340–760），并保留方向键与档位按钮两条无鼠标路径；
 *    此前「调宽」按钮只改显示数字，grid 第三列仍硬编码 340px，等于假的。
 * ② 可选 Agent 与模型 —— 清单来自 /api/ai/agents（不含端点与密钥），按次覆盖注入。
 * ③ 输入输出放大 —— 历史区撑满剩余高度，输入改多行 textarea（Enter 发送 / Shift+Enter 换行）。
 */
import { computed, nextTick, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AiEvidencePanel from '@/components/AiEvidencePanel.vue'
import { useFocusTrap } from '@/composables/useFocusTrap'
import {
  aiPanelApi,
  canRequestExecution,
  type AiActionResult,
  type AiAgentItem,
  type AiChatResponse,
  type AiPanelState,
} from '@/api/aiPanel'
import { AI_DRAWER_MAX, AI_DRAWER_MIN, useAiDrawer } from '@/composables/useAiDrawer'
// 文案统一走 i18n：本 JSON 只存键名，组件用 t(LABEL.xxx) 取词条（zh/en 双语在 locales）。
// 单独放键名映射而不是塞在组件里，让这批文案后续切 i18n 时只改一处。
import LABEL from './aiPanelLabels.json'

interface ChatMessage {
  role: 'user' | 'ai'
  text: string
  degraded?: boolean
}

const props = defineProps<{ open: boolean; width: number; projectId: number }>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  'update:width': [value: number]
}>()

const { t } = useI18n()
const { agent: agentKey, model: modelId, setAgent, setModel } = useAiDrawer()
const state = ref<AiPanelState | null>(null)
const result = ref<AiActionResult | null>(null)
const sessionId = ref(0)
const confirmed = ref(false)
const failed = ref(false)
const busy = ref(false)
const chatBusy = ref(false)
const prompt = ref('')
const messages = ref<ChatMessage[]>([])
const historyEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLTextAreaElement | null>(null)
const agents = ref<AiAgentItem[]>([])
const defaultAgent = ref('')
const fallbackNote = ref('')
const resizing = ref(false)
/** v3.3.0：助手（旧通用对话）与证据问答分开；默认助手，保持既有行为与快捷键。 */
const mode = ref<'assistant' | 'evidence'>('assistant')

/** 宽度预设陈列：快速选择档位（拖拽与键盘保留，都过同一套钳制）。 */
const WIDTH_PRESETS = [
  { px: AI_DRAWER_MIN, label: 'aiPanel.presetNarrow' },
  { px: 420, label: 'aiPanel.presetStandard' },
  { px: 520, label: 'aiPanel.presetWide' },
  { px: AI_DRAWER_MAX, label: 'aiPanel.presetMax' },
] as const

const canExecute = computed(() =>
  state.value ? canRequestExecution(state.value, confirmed.value) : false)
const degraded = computed(() =>
  state.value?.mode === 'DEGRADED' || state.value?.mode === 'DISABLED')
const disabled = computed(() => state.value?.enabled === false)

/** 当前 agent 条目的可选模型：目录建议值 + 已保存的模型（去重，避免下拉里没有当前值）。 */
const currentAgent = computed<AiAgentItem | null>(() =>
  agents.value.find((item) => item.key === agentKey.value) ?? null)
const modelOptions = computed<string[]>(() => {
  const list = [...(currentAgent.value?.models ?? [])]
  const saved = currentAgent.value?.defaultModel
  if (saved && !list.includes(saved)) {
    list.unshift(saved)
  }
  return list
})

async function load(): Promise<void> {
  failed.value = false
  try {
    state.value = await aiPanelApi.state(props.projectId)
    // 开关关闭时不再开会话（后端会明确禁用），避免无谓的 403。
    if (state.value.enabled !== false) {
      sessionId.value = await aiPanelApi.open(props.projectId, 0)
      await loadAgents()
    }
  } catch {
    failed.value = true
  }
}

/**
 * agent 清单失败不当作整页失败：清单只影响「能不能选」，对话本身还能用首选 agent。
 * 所以这里单独 try/catch，不让 it 把 failed 置真。
 */
async function loadAgents(): Promise<void> {
  try {
    const payload = await aiPanelApi.agents(props.projectId)
    agents.value = payload.items ?? []
    defaultAgent.value = payload.defaultKey ?? ''
    // 上次选的 agent 已经不在了（卸载 / 被管理员关掉）：清掉，回到「自动」
    if (agentKey.value && !agents.value.some((item) => item.key === agentKey.value)) {
      setAgent('')
    }
  } catch {
    agents.value = []
  }
}

async function act(kind: string): Promise<void> {
  if (!state.value) return
  busy.value = true
  failed.value = false
  try {
    if (kind === 'suggest') {
      result.value = await aiPanelApi.suggest(props.projectId, sessionId.value, {
        projectId: String(props.projectId),
        sessionId: String(sessionId.value),
      })
    } else if (kind === 'terminate') {
      result.value = await aiPanelApi.terminate(props.projectId, sessionId.value)
      confirmed.value = false
      await load()
    } else {
      result.value = await aiPanelApi.execute(
        props.projectId, sessionId.value, 'run', confirmed.value)
      confirmed.value = false
    }
  } catch {
    failed.value = true
  } finally {
    busy.value = false
  }
}

/** 多轮上下文：把可见历史一并作为 prompt 传给后端，统一经 ContextPacker 脱敏。 */
async function submitChat(): Promise<void> {
  const text = prompt.value.trim()
  if (!text || chatBusy.value || disabled.value) return
  const turns: string[] = []
  for (const m of messages.value) {
    turns.push((m.role === 'user' ? t(LABEL.roleUser) : t(LABEL.roleAi)) + ': ' + m.text)
  }
  turns.push(t(LABEL.roleUser) + ': ' + text)
  const fullPrompt = turns.join('\n')

  messages.value.push({ role: 'user', text })
  prompt.value = ''
  resizeInput()
  chatBusy.value = true
  failed.value = false
  fallbackNote.value = ''
  try {
    const reply: AiChatResponse = await aiPanelApi.chatWith({
      projectId: props.projectId,
      sessionId: sessionId.value,
      prompt: fullPrompt,
      // 空串不传：后端按「首选 agent + 已保存模型」处理，与老行为一致
      cli: agentKey.value || undefined,
      model: modelId.value || undefined,
    })
    if (reply.sessionId > 0) sessionId.value = reply.sessionId
    if (reply.cliFallback) {
      fallbackNote.value = t('aiPanel.fallback', { cli: reply.cli })
    }
    messages.value.push({
      role: 'ai',
      text: reply.answer || t('aiPanel.emptyAnswer'),
      degraded: reply.degraded,
    })
  } catch {
    failed.value = true
    messages.value.push({ role: 'ai', text: t(LABEL.error) })
  } finally {
    chatBusy.value = false
    await nextTick()
    scrollToBottom()
  }
}

function close(): void {
  emit('update:open', false)
}

const drawerRef = ref<HTMLElement | null>(null)
useFocusTrap(drawerRef, toRef(props, 'open'), { onEscape: close })

function scrollToBottom(): void {
  if (historyEl.value) historyEl.value.scrollTop = historyEl.value.scrollHeight
}

/** 输入框按内容长高，封顶 240px（再高就该滚动而不是挤掉历史区）。 */
function resizeInput(): void {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 240)}px`
}

function onInputKeydown(event: KeyboardEvent): void {
  // Enter 发送、Shift+Enter 换行：对话框常见约定，避免误发长文本
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void submitChat()
  }
}

/** 换 agent：清掉模型（上一家的模型名换一家多半不存在），并让下一轮按新选择走。 */
function onAgentChange(event: Event): void {
  setAgent((event.target as HTMLSelectElement).value)
  fallbackNote.value = ''
}

function onModelChange(event: Event): void {
  setModel((event.target as HTMLSelectElement).value)
}

/**
 * 拖拽调宽：抽屉在右侧，往左拖是变宽，所以用「起点宽度 − 位移」。
 * 监听挂在 window 上而不是手柄上——鼠标移出手柄（很快）时也要继续跟随。
 */
function startResize(event: PointerEvent): void {
  if (event.button !== 0) return
  // 悬浮（窄屏）态下改宽度会立刻被视口截断，没有意义，直接不响应
  const startX = event.clientX
  const startWidth = props.width
  resizing.value = true
  const onMove = (move: PointerEvent): void => {
    emit('update:width', startWidth - (move.clientX - startX))
  }
  const onUp = (): void => {
    resizing.value = false
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

/** 键盘调宽：左右方向键各 20px（一个档位的五分之一，够细但不磨人）。 */
function onResizeKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    emit('update:width', props.width + 20)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    emit('update:width', props.width - 20)
  } else if (event.key === 'Home') {
    event.preventDefault()
    emit('update:width', AI_DRAWER_MIN)
  } else if (event.key === 'End') {
    event.preventDefault()
    emit('update:width', AI_DRAWER_MAX)
  }
}

watch(() => props.open, (visible) => { if (visible) void load() }, { immediate: true })
watch(prompt, () => nextTick(resizeInput))
</script>

<template>
  <aside
    v-if="open"
    ref="drawerRef"
    class="ai-drawer"
    :class="{ 'ai-resizing': resizing }"
    :aria-label="t('aiPanel.title')"
  >
    <!-- 拖拽手柄：宽 6px 的边界条，键盘也能用（左右键 20px / Home 最窄 / End 最宽） -->
    <div
      class="ai-resizer"
      role="separator"
      aria-orientation="vertical"
      :aria-label="t('aiPanel.resize')"
      :title="t('aiPanel.resize')"
      :aria-valuenow="width"
      :aria-valuemin="AI_DRAWER_MIN"
      :aria-valuemax="AI_DRAWER_MAX"
      tabindex="0"
      data-testid="ai-resizer"
      @pointerdown.prevent="startResize"
      @keydown="onResizeKeydown"
    />

    <header class="ai-head">
      <h2>{{ t('aiPanel.title') }}</h2>
      <button class="btn btn-ghost" type="button" :aria-label="t('aiPanel.close')" @click="close">
        <AppIcon name="close" :size="16" />
      </button>
    </header>
    <p class="ai-muted">{{ t('aiPanel.subtitle') }}</p>

    <div class="ai-mode" role="tablist" data-testid="ai-mode">
      <button
        class="btn btn-ghost ai-mode-btn"
        type="button"
        role="tab"
        data-testid="ai-mode-assistant"
        :aria-selected="mode === 'assistant'"
        :class="{ active: mode === 'assistant' }"
        @click="mode = 'assistant'"
      >
        {{ t('aiPanel.modeAssistant') }}
      </button>
      <button
        class="btn btn-ghost ai-mode-btn"
        type="button"
        role="tab"
        data-testid="ai-mode-evidence"
        :aria-selected="mode === 'evidence'"
        :class="{ active: mode === 'evidence' }"
        @click="mode = 'evidence'"
      >
        {{ t('aiPanel.modeEvidence') }}
      </button>
    </div>

    <p v-if="failed" class="ai-line ai-error">{{ t('aiPanel.failed') }}</p>
    <p v-if="disabled" class="ai-line ai-error" data-testid="ai-disabled">{{ t(LABEL.disabled) }}</p>
    <p v-else-if="degraded" class="ai-line ai-degraded" data-testid="ai-degraded">{{ t(LABEL.degraded) }}</p>

    <template v-if="mode === 'assistant'">
    <!-- Agent / 模型：清单来自后端探测，只列本机真能跑的；空清单时明确说明走降级 -->
    <div class="ai-picker" data-testid="ai-picker">
      <label class="ai-field">
        <span class="ai-field-label">{{ t('aiPanel.agent') }}</span>
        <select
          class="ai-select"
          data-testid="ai-agent"
          :value="agentKey"
          :disabled="disabled || agents.length === 0"
          @change="onAgentChange"
        >
          <option value="">{{ t('aiPanel.agentDefault') }}</option>
          <option v-for="item in agents" :key="item.key" :value="item.key">{{ item.name }}</option>
        </select>
      </label>
      <label class="ai-field">
        <span class="ai-field-label">{{ t('aiPanel.model') }}</span>
        <select
          class="ai-select"
          data-testid="ai-model"
          :value="modelId"
          :disabled="disabled || modelOptions.length === 0"
          @change="onModelChange"
        >
          <option value="">{{ t('aiPanel.modelDefault') }}</option>
          <option v-for="option in modelOptions" :key="option" :value="option">{{ option }}</option>
        </select>
      </label>
    </div>
    <p v-if="!disabled && agents.length === 0" class="ai-muted" data-testid="ai-no-agents">
      {{ t('aiPanel.noAgents') }}
    </p>
    <p v-if="fallbackNote" class="ai-line ai-degraded" data-testid="ai-fallback">
      {{ fallbackNote }}
    </p>

    <section
      ref="historyEl"
      class="ai-history"
      data-testid="ai-history"
      :aria-label="t(LABEL.roleAi)"
    >
      <p v-if="messages.length === 0" class="ai-muted">{{ t(LABEL.historyEmpty) }}</p>
      <div
        v-for="(m, i) in messages"
        :key="i"
        class="ai-msg"
        :class="{ 'ai-msg-user': m.role === 'user', 'ai-degraded': m.degraded }"
      >
        <span class="ai-msg-role">{{ m.role === 'user' ? t(LABEL.roleUser) : t(LABEL.roleAi) }}</span>
        <span class="ai-msg-text">{{ m.text }}</span>
      </div>
      <p v-if="chatBusy" class="ai-loading" data-testid="ai-loading">{{ t(LABEL.loading) }}</p>
      <p v-if="failed" class="ai-line ai-error" data-testid="ai-error">{{ t(LABEL.error) }}</p>
    </section>

    <div class="ai-chat-input">
      <textarea
        ref="inputEl"
        v-model="prompt"
        class="ai-input"
        data-testid="ai-input"
        rows="3"
        :placeholder="t(LABEL.inputPlaceholder)"
        :disabled="chatBusy || disabled"
        @input="resizeInput"
        @keydown="onInputKeydown"
      />
      <button
        class="btn"
        type="button"
        data-testid="ai-send"
        :disabled="chatBusy || disabled || !prompt.trim()"
        @click="submitChat"
      >
        {{ t(LABEL.send) }}
      </button>
    </div>

    <div class="ai-actions">
      <button class="btn" type="button" :disabled="busy || !state" @click="act('suggest')">
        {{ t('aiPanel.suggest') }}
      </button>
      <label class="ai-confirm">
        <input v-model="confirmed" type="checkbox" />
        <span>{{ t('aiPanel.confirm') }}</span>
      </label>
      <button class="btn" type="button" :disabled="busy || !canExecute" @click="act('execute')">
        {{ t('aiPanel.execute') }}
      </button>
      <button class="btn btn-ghost" type="button" :disabled="busy || !state" @click="act('terminate')">
        {{ t('aiPanel.terminate') }}
      </button>
    </div>
    </template>

    <!-- v3.3.0 证据问答：SSE 流式 + 引用 + 模板 + 会话导出 + 用例草稿审阅 -->
    <AiEvidencePanel v-else :project-id="projectId" />

    <!-- 宽度预设陈列（v2.6.1 大师反馈「拖拽不直观」）：一排档位直接点，当前档高亮；
         拖拽与键盘（±20 / Home / End）保留，三条路径共用同一套钳制与持久化。 -->
    <div class="ai-width-presets" role="group" :aria-label="t('aiPanel.width')">
      <button
        v-for="preset in WIDTH_PRESETS"
        :key="preset.px"
        class="btn btn-ghost ai-width-preset"
        type="button"
        data-testid="ai-width-preset"
        :class="{ 'ai-width-preset--active': width === preset.px }"
        :aria-pressed="width === preset.px"
        @click="emit('update:width', preset.px)"
      >
        {{ preset.px }} · {{ t(preset.label) }}
      </button>
    </div>

    <dl v-if="state" class="ai-facts">
      <dt>{{ t('aiPanel.mode') }}</dt>
      <dd>{{ state.mode }}</dd>
      <dt>{{ t('aiPanel.maxLevel') }}</dt>
      <dd>{{ state.maxLevel }}</dd>
      <dt>{{ t('aiPanel.timeout') }}</dt>
      <dd>{{ state.timeoutSeconds }}</dd>
    </dl>

    <p v-if="result" class="ai-line" :class="{ 'ai-degraded': result.degraded }">
      {{ result.level }} / {{ result.action }} / {{ result.summary }}
    </p>
    <p v-else class="ai-muted">{{ t('aiPanel.noResult') }}</p>
  </aside>
</template>

<style scoped>
/* 竖向弹性：只有历史区会长，其余按内容排。滚动交给历史区，外壳不滚。 */
.ai-drawer {
  position: relative;
  height: 100%;
  min-height: 0;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.ai-drawer.ai-resizing { user-select: none; }

/* 左缘拖拽条：视觉 1px 竖线，热区 6px（太细点不中，太粗又挡内容） */
.ai-resizer {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  background: transparent;
  border: 0;
  padding: 0;
  touch-action: none;
}
.ai-resizer::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 1px;
  background: transparent;
}
.ai-resizer:hover::after,
.ai-resizer:focus-visible::after {
  background: var(--accent);
}
.ai-resizer:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.ai-head { display: flex; align-items: center; justify-content: space-between; }
.ai-head h2 { margin: 0; font-size: 16px; }
.ai-muted { margin: 0; color: var(--text-muted); font-size: 12px; }

/* 助手 / 证据问答：两个模式按钮，当前模式实心高亮 */
.ai-mode { display: flex; gap: 6px; }
.ai-mode-btn { padding: 4px 10px; font-size: 12px; }
.ai-mode-btn.active {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.ai-picker { display: flex; gap: 8px; }
.ai-field { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.ai-field-label { color: var(--text-muted); font-size: 12px; }
.ai-select {
  width: 100%;
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel);
  color: var(--text);
  font-size: 13px;
}

/* 历史区撑满剩余高度：这是「输出区要更大」的落点 */
.ai-history {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.6;
}
.ai-msg { display: flex; gap: 8px; word-break: break-word; }
.ai-msg-role { flex: 0 0 28px; color: var(--text-muted); }
.ai-msg-text { white-space: pre-wrap; flex: 1 1 auto; min-width: 0; }
.ai-msg-user .ai-msg-role { font-weight: 600; }
.ai-loading { margin: 0; color: var(--text-muted); }

.ai-chat-input { display: flex; gap: 8px; align-items: flex-end; }
.ai-input {
  flex: 1;
  min-width: 0;
  min-height: 90px;
  max-height: 240px;
  resize: none;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
}
.ai-input:focus { border-color: var(--accent); outline: none; }

.ai-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.ai-actions .ai-confirm { grid-column: 1 / -1; }

/* 宽度预设陈列：四个档位一排，当前档实心高亮（aria-pressed 同步）。 */
.ai-width-presets { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.ai-width-preset { padding: 4px 10px; font-size: 12px; }
.ai-width-preset--active {
  background: var(--accent);
  color: var(--accent-contrast);
  border-color: var(--accent);
}
.ai-confirm { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.ai-facts { margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; font-size: 12px; }
.ai-facts dt { color: var(--text-muted); }
.ai-facts dd { margin: 0; }
.ai-line {
  margin: 0;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
  word-break: break-word;
}
.ai-degraded { border-color: var(--warn-border); }
.ai-error { border-color: var(--fail-border); }
</style>
