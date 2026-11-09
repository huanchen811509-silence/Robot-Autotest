<script setup lang="ts">
/**
 * AI 证据工作台（v3.3.0 / FR-3.3-01..08）。
 *
 * 与旧版「通用助手」分开：这里所有回答都先装配证据，再流式吐字；
 * 无证据时明确拒答，不把模型常识包装成结论。
 *
 * 三个容易踩的点：
 * ① SSE 不能走 `request()`（会把流当 JSON 读死），必须用 fetch + 逐行解析；
 * ② delta 是 JSON 字符串片段，直接拼原文会带上引号；
 * ③ 草稿必须 args reviewed=true 才允许落库，前端不能替用户默认勾选。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { aiWorkbenchApi, type DraftCase, type PromptTemplate, type SessionRow } from '@/api/aiWorkbench'
import type { AiCitation } from '@/api/aiWorkbench'

const props = defineProps<{ projectId: number }>()
const { t } = useI18n()
const router = useRouter()

type Tab = 'chat' | 'draft'

const tab = ref<Tab>('chat')
const prompt = ref('')
const streaming = ref(false)
const streamText = ref('')
const citations = ref<AiCitation[]>([])
const streamId = ref<string | null>(null)
const errorText = ref('')
const quotaText = ref('')
const templates = ref<PromptTemplate[]>([])
const templateKey = ref('')
const sessions = ref<SessionRow[]>([])
const activeSession = ref(0)

// 草稿：生成后默认全部勾选，但「已审阅」必须用户主动勾，否则保存按钮不可用。
const moduleName = ref('')
const draftCount = ref(3)
const drafts = ref<DraftCase[]>([])
const selected = ref<boolean[]>([])
const reviewed = ref(false)
const draftBusy = ref(false)
const draftNotice = ref('')

let handle: ReturnType<typeof aiWorkbenchApi.stream> | null = null

const canSend = computed(() => prompt.value.trim().length > 0 && !streaming.value)
const canApply = computed(
  () => reviewed.value && !draftBusy.value && drafts.value.some((_, i) => selected.value[i]),
)

async function loadMeta(): Promise<void> {
  if (!props.projectId) return
  try {
    templates.value = await aiWorkbenchApi.templates(props.projectId)
  } catch {
    templates.value = []
  }
  try {
    sessions.value = await aiWorkbenchApi.sessions(props.projectId)
  } catch {
    sessions.value = []
  }
  try {
    const quota = await aiWorkbenchApi.quota(props.projectId)
    quotaText.value = quota.dailyQuota > 0
      ? t('aiWorkbench.quota', { used: quota.used, total: quota.dailyQuota })
      : t('aiWorkbench.quotaUnlimited')
  } catch {
    quotaText.value = ''
  }
}

function pickTemplate(): void {
  const found = templates.value.find((item) => item.key === templateKey.value)
  if (found) prompt.value = found.body
}

async function openSession(session: SessionRow): Promise<void> {
  activeSession.value = session.id
  try {
    const rows = await aiWorkbenchApi.messages(props.projectId, session.id)
    streamText.value = rows.map((row) => `${row.role}: ${row.content}`).join('\n\n')
    citations.value = []
  } catch {
    errorText.value = t('aiWorkbench.loadFailed')
  }
}

async function send(): Promise<void> {
  if (!canSend.value) return
  const text = prompt.value.trim()
  errorText.value = ''
  streamText.value = ''
  citations.value = []
  streaming.value = true
  handle = aiWorkbenchApi.stream(
    { projectId: props.projectId, sessionId: activeSession.value, prompt: text },
    {
      onMeta: (payload) => {
        streamId.value = String(payload.streamId ?? '') || null
      },
      onCitations: (list) => {
        citations.value = list
      },
      onDelta: (chunk) => {
        streamText.value += chunk
      },
      onDone: () => {
        streaming.value = false
        void loadMeta()
      },
      onError: (message) => {
        streaming.value = false
        errorText.value = message || t('aiWorkbench.streamFailed')
      },
    },
  )
  prompt.value = ''
  await handle.done
}

async function stop(): Promise<void> {
  if (!handle) return
  try {
    await handle.cancel()
  } catch {
    errorText.value = t('aiWorkbench.streamFailed')
  } finally {
    streaming.value = false
  }
}

function openCitation(citation: AiCitation): void {
  if (!citation.path) return
  if (citation.path.startsWith('/')) {
    void router.push(citation.path)
  } else {
    window.open(citation.path, '_blank', 'noopener')
  }
}

async function exportSession(): Promise<void> {
  if (activeSession.value <= 0) return
  try {
    const markdown = await aiWorkbenchApi.exportSession(props.projectId, activeSession.value)
    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `ai-session-${activeSession.value}.md`
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    errorText.value = t('aiWorkbench.exportFailed')
  }
}

/** 生成草稿：先拿服务端的确定性草稿列表，再让用户逐条确认。 */
async function generateDrafts(): Promise<void> {
  draftBusy.value = true
  draftNotice.value = ''
  reviewed.value = false
  try {
    drafts.value = await aiWorkbenchApi.drafts(props.projectId, moduleName.value, draftCount.value)
    selected.value = drafts.value.map(() => true)
    draftNotice.value = t('aiWorkbench.draftGenerated', { count: drafts.value.length })
  } catch {
    drafts.value = []
    selected.value = []
    draftNotice.value = t('aiWorkbench.draftFailed')
  } finally {
    draftBusy.value = false
  }
}

async function applyDrafts(): Promise<void> {
  if (!canApply.value) return
  draftBusy.value = true
  try {
    const chosen = drafts.value.filter((_, index) => selected.value[index])
    await aiWorkbenchApi.applyDrafts(props.projectId, chosen, reviewed.value)
    draftNotice.value = t('aiWorkbench.draftSaved', { count: chosen.length })
    drafts.value = []
    selected.value = []
    reviewed.value = false
  } catch {
    draftNotice.value = t('aiWorkbench.draftSaveFailed')
  } finally {
    draftBusy.value = false
  }
}

onMounted(() => void loadMeta())
watch(activeSession, (id) => {
  const session = sessions.value.find((item) => item.id === id)
  if (session) void openSession(session)
})
onBeforeUnmount(() => {
  if (streaming.value && handle) void handle.cancel()
})
</script>

<template>
  <section class="evidence" data-testid="ai-evidence">
    <div class="tabs" role="tablist">
      <button
        class="tab"
        type="button"
        role="tab"
        data-testid="tab-chat"
        :aria-selected="tab === 'chat'"
        :class="{ active: tab === 'chat' }"
        @click="tab = 'chat'"
      >
        {{ t('aiWorkbench.tabChat') }}
      </button>
      <button
        class="tab"
        type="button"
        role="tab"
        data-testid="tab-draft"
        :aria-selected="tab === 'draft'"
        :class="{ active: tab === 'draft' }"
        @click="tab = 'draft'"
      >
        {{ t('aiWorkbench.tabDraft') }}
      </button>
      <span v-if="quotaText" class="muted quota">{{ quotaText }}</span>
    </div>

    <template v-if="tab === 'chat'">
      <div class="row">
        <select v-model="templateKey" class="select" data-testid="template" @change="pickTemplate">
          <option value="">{{ t('aiWorkbench.selectTemplate') }}</option>
          <option v-for="item in templates" :key="item.key" :value="item.key">
            {{ item.name }}
          </option>
        </select>
        <select v-model.number="activeSession" class="select" data-testid="sessions">
          <option :value="0">{{ t('aiWorkbench.newSession') }}</option>
          <option v-for="item in sessions" :key="item.id" :value="item.id">
            {{ item.title || item.lastPreview || item.id }}
          </option>
        </select>
        <button
          class="btn btn-ghost"
          type="button"
          data-testid="export"
          :disabled="activeSession <= 0"
          @click="exportSession"
        >
          {{ t('aiWorkbench.export') }}
        </button>
      </div>

      <div class="history" data-testid="history">
        <p v-if="!streamText && !streaming" class="muted">{{ t('aiWorkbench.emptyHistory') }}</p>
        <p v-if="streamText" class="answer">{{ streamText }}</p>
        <p v-if="streaming" class="muted">{{ t('aiWorkbench.streaming') }}</p>
        <div v-if="citations.length" class="citations">
          <span class="muted">{{ t('aiWorkbench.citations') }}</span>
          <button
            v-for="citation in citations"
            :key="citation.id"
            class="chip"
            type="button"
            data-testid="citation"
            :title="citation.path"
            @click="openCitation(citation)"
          >
            [{{ citation.id }}] {{ citation.label }}
          </button>
        </div>
        <p v-if="errorText" class="error" data-testid="ai-error">{{ errorText }}</p>
      </div>

      <div class="composer">
        <textarea
          v-model="prompt"
          class="input"
          rows="3"
          data-testid="ai-evidence-input"
          :placeholder="t('aiWorkbench.promptPlaceholder')"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send"
        />
        <button
          v-if="!streaming"
          class="btn"
          type="button"
          data-testid="ai-evidence-send"
          :disabled="!canSend"
          @click="send"
        >
          {{ t('aiWorkbench.send') }}
        </button>
        <button
          v-else
          class="btn btn-ghost"
          type="button"
          data-testid="ai-evidence-stop"
          @click="stop"
        >
          {{ t('aiWorkbench.stop') }}
        </button>
      </div>
      <p v-if="streamId" class="muted">stream {{ streamId }}</p>
    </template>

    <template v-else>
      <div class="row">
        <input
          v-model="moduleName"
          class="input"
          type="text"
          data-testid="draft-module"
          :placeholder="t('aiWorkbench.modulePlaceholder')"
        />
        <input
          v-model.number="draftCount"
          class="input count"
          type="number"
          min="1"
          max="10"
          minlength="1"
          :aria-label="t('aiWorkbench.draftCount')"
        />
        <button
          class="btn"
          type="button"
          data-testid="draft-generate"
          :disabled="draftBusy"
          @click="generateDrafts"
        >
          {{ t('aiWorkbench.generate') }}
        </button>
      </div>

      <p v-if="draftNotice" class="notice" data-testid="draft-notice">{{ draftNotice }}</p>

      <ul v-if="drafts.length" class="drafts" data-testid="drafts">
        <li v-for="(draft, index) in drafts" :key="index">
          <label class="draft-item">
            <input v-model="selected[index]" type="checkbox" />
            <span>
              <strong>{{ draft.title }}</strong>
              <em class="muted">{{ draft.precondition }}</em>
              <em class="muted">{{ draft.steps }} → {{ draft.expected }}</em>
            </span>
          </label>
        </li>
      </ul>

      <label v-if="drafts.length" class="review">
        <input v-model="reviewed" type="checkbox" data-testid="draft-reviewed" />
        <span>{{ t('aiWorkbench.reviewedLabel') }}</span>
      </label>
      <button
        v-if="drafts.length"
        class="btn"
        type="button"
        data-testid="draft-apply"
        :disabled="!canApply"
        @click="applyDrafts"
      >
        {{ t('aiWorkbench.saveDrafts') }}
      </button>
      <p class="muted">{{ t('aiWorkbench.draftHint') }}</p>
    </template>
  </section>
</template>

<style scoped>
.evidence {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}
.tabs {
  display: flex;
  align-items: center;
  gap: 6px;
}
.tab {
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
  font-size: 12px;
}
.tab.active {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
.quota { margin-left: auto; font-size: 12px; }
.row { display: flex; flex-wrap: wrap; gap: 6px; }
.select,
.input {
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel);
  color: var(--text);
  font-size: 13px;
  font-family: inherit;
}
.select { flex: 1 1 40%; }
.count { width: 56px; flex: 0 0 auto; }
.history {
  flex: 1 1 auto;
  min-height: 120px;
  max-height: 320px;
  overflow-y: auto;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
}
.answer {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
.citations { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; font-size: 12px; }
.chip {
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
  font-size: 12px;
}
.composer { display: flex; gap: 8px; align-items: flex-end; }
.composer .input { flex: 1 1 auto; min-height: 84px; resize: vertical; }
.drafts { margin: 0; padding-left: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
.draft-item { display: flex; gap: 8px; align-items: flex-start; }
.draft-item span { display: flex; flex-direction: column; gap: 2px; }
.review { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.notice { margin: 0; font-size: 12px; }
.muted { margin: 0; color: var(--text-muted); font-size: 12px; }
.error { margin: 0; color: var(--fail); }
</style>
