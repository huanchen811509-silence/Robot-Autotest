<script setup lang="ts">
/** W6 / AIX-01..05 / FR-AI-05: right-hand drawer. Usable even when no CLI is installed. */
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import {
  aiPanelApi,
  canRequestExecution,
  type AiActionResult,
  type AiChatResponse,
  type AiPanelState,
} from '@/api/aiPanel'
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

const canExecute = computed(() =>
  state.value ? canRequestExecution(state.value, confirmed.value) : false)
const degraded = computed(() =>
  state.value?.mode === 'DEGRADED' || state.value?.mode === 'DISABLED')
const disabled = computed(() => state.value?.enabled === false)

async function load(): Promise<void> {
  failed.value = false
  try {
    state.value = await aiPanelApi.state(props.projectId)
    // 开关关闭时不再开会话（后端会明确禁用），避免无谓的 403。
    if (state.value.enabled !== false) {
      sessionId.value = await aiPanelApi.open(props.projectId, 0)
    }
  } catch {
    failed.value = true
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
  chatBusy.value = true
  failed.value = false
  try {
    const reply: AiChatResponse = await aiPanelApi.chat(
      props.projectId, sessionId.value, fullPrompt)
    if (reply.sessionId > 0) sessionId.value = reply.sessionId
    messages.value.push({
      role: 'ai',
      text: reply.answer || t(LABEL.historyEmpty),
      degraded: reply.degraded,
    })
  } catch {
    failed.value = true
    messages.value.push({ role: 'ai', text: t(LABEL.error) })
  } finally {
    chatBusy.value = false
    await nextTick()
    if (historyEl.value) historyEl.value.scrollTop = historyEl.value.scrollHeight
  }
}

function close(): void {
  emit('update:open', false)
}

function widen(): void {
  emit('update:width', props.width + 20 > 520 ? 280 : props.width + 20)
}

// Only probe / open a session when the drawer is actually visible: otherwise every
// page load would fire two backend calls for a panel nobody opened.
watch(() => props.open, (visible) => { if (visible) void load() }, { immediate: true })
</script>

<template>
  <aside v-if="open" class="ai-drawer" :aria-label="t('aiPanel.title')">
    <header class="ai-head">
      <h2>{{ t('aiPanel.title') }}</h2>
      <button class="btn btn-ghost" type="button" :aria-label="t('aiPanel.close')" @click="close">
        <AppIcon name="close" :size="16" />
      </button>
    </header>
    <p class="ai-muted">{{ t('aiPanel.subtitle') }}</p>

    <p v-if="failed" class="ai-line ai-error">{{ t('aiPanel.failed') }}</p>
    <p v-if="disabled" class="ai-line ai-error" data-testid="ai-disabled">{{ t(LABEL.disabled) }}</p>
    <p v-else-if="degraded" class="ai-line ai-degraded" data-testid="ai-degraded">{{ t(LABEL.degraded) }}</p>
    <dl v-if="state" class="ai-facts">
      <dt>{{ t('aiPanel.mode') }}</dt>
      <dd>{{ state.mode }}</dd>
      <dt>{{ t('aiPanel.maxLevel') }}</dt>
      <dd>{{ state.maxLevel }}</dd>
      <dt>{{ t('aiPanel.timeout') }}</dt>
      <dd>{{ state.timeoutSeconds }}</dd>
      <dt>{{ t('aiPanel.width') }}</dt>
      <dd>{{ width }}</dd>
    </dl>

    <section
      ref="historyEl"
      class="ai-history"
      data-testid="ai-history"
      style="overflow-y: auto; max-height: 240px"
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
      <input
        v-model="prompt"
        class="ai-input"
        data-testid="ai-input"
        type="text"
        :placeholder="t(LABEL.inputPlaceholder)"
        :disabled="chatBusy || disabled"
        @keydown.enter="submitChat"
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
      <button class="btn btn-ghost" type="button" @click="widen">
        {{ t('aiPanel.width') }}
      </button>
    </div>

    <p v-if="result" class="ai-line" :class="{ 'ai-degraded': result.degraded }">
      {{ result.level }} / {{ result.action }} / {{ result.summary }}
    </p>
    <p v-else class="ai-muted">{{ t('aiPanel.noResult') }}</p>
  </aside>
</template>

<style scoped>
.ai-drawer { padding: 20px 16px; display: grid; gap: 12px; align-content: start; }
.ai-head { display: flex; align-items: center; justify-content: space-between; }
.ai-head h2 { margin: 0; font-size: 16px; }
.ai-muted { margin: 0; color: var(--text-muted); font-size: 12px; }
.ai-facts { margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; font-size: 13px; }
.ai-facts dt { color: var(--text-muted); }
.ai-facts dd { margin: 0; }
.ai-history {
  display: grid;
  gap: 8px;
  align-content: start;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
}
.ai-msg { display: flex; gap: 8px; word-break: break-word; }
.ai-msg-role { flex: 0 0 24px; color: var(--text-muted); }
.ai-msg-text { white-space: pre-wrap; }
.ai-msg-user .ai-msg-role { font-weight: 600; }
.ai-loading { margin: 0; color: var(--text-muted); }
.ai-chat-input { display: flex; gap: 8px; }
.ai-input {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
}
.ai-actions { display: grid; gap: 8px; }
.ai-confirm { display: flex; align-items: center; gap: 8px; font-size: 13px; }
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
