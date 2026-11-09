<script setup lang="ts">
/**
 * 有据可依的 AI 回答卡片（v3.3.0 / FR-3.3-02..04）。
 *
 * 只做「事实 / 推测 / 建议 / 证据」四段式渲染：事实与推测必须在视觉上分开，
 * 否则用户会把模型推断当成报告结论。证据 chip 可点击跳转到对应页面。
 */
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { AiCitation, AiResult } from '@/api/aiWorkbench'

defineProps<{
  result: AiResult | null
  loading?: boolean
  error?: string
}>()
const { t } = useI18n()
const router = useRouter()

function openCitation(citation: AiCitation): void {
  if (!citation.path) return
  if (citation.path.startsWith('/')) {
    void router.push(citation.path)
  } else {
    window.open(citation.path, '_blank', 'noopener')
  }
}
</script>

<template>
  <section class="ai-answer" data-testid="ai-answer">
    <p v-if="loading" class="muted" data-testid="ai-answer-loading">{{ t('aiAnswer.loading') }}</p>
    <p v-if="error" class="error" data-testid="ai-answer-error">{{ error }}</p>

    <template v-if="result">
      <div class="ai-answer-head">
        <span class="badge" :data-mode="result.answer.mode">{{ result.answer.mode }}</span>
        <span
          class="verify"
          :class="result.verification.pass ? 'text-ok' : 'text-fail'"
          data-testid="ai-verify"
        >
          {{
            result.verification.pass
              ? t('aiAnswer.verifyPass')
              : t('aiAnswer.verifyFail')
          }}
        </span>
        <span v-if="result.answer.degraded" class="muted">{{ t('aiAnswer.degraded') }}</span>
        <span class="muted">{{ result.elapsedMs }}ms</span>
      </div>

      <p class="ai-text" data-testid="ai-answer-text">{{ result.answer.text }}</p>

      <div v-if="result.verification.unsupportedNumbers.length" class="ai-warn">
        {{ t('aiAnswer.unsupported', { numbers: result.verification.unsupportedNumbers.join(', ') }) }}
      </div>

      <div v-if="result.answer.facts.length" class="ai-block">
        <h4>{{ t('aiAnswer.facts') }}</h4>
        <ul>
          <li v-for="(fact, i) in result.answer.facts" :key="`f-${i}`">{{ fact }}</li>
        </ul>
      </div>

      <div v-if="result.answer.inferences.length" class="ai-block">
        <h4>{{ t('aiAnswer.inferences') }}</h4>
        <ul>
          <li v-for="(item, i) in result.answer.inferences" :key="`i-${i}`">{{ item }}</li>
        </ul>
      </div>

      <div v-if="result.answer.suggestions.length" class="ai-block">
        <h4>{{ t('aiAnswer.suggestions') }}</h4>
        <ul>
          <li v-for="(item, i) in result.answer.suggestions" :key="`s-${i}`">{{ item }}</li>
        </ul>
      </div>

      <div v-if="result.answer.citations.length" class="ai-citations">
        <span class="muted">{{ t('aiAnswer.citations') }}</span>
        <button
          v-for="citation in result.answer.citations"
          :key="citation.id"
          class="chip"
          type="button"
          data-testid="ai-citation"
          :title="citation.path"
          @click="openCitation(citation)"
        >
          [{{ citation.id }}] {{ citation.label }}
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-answer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
}
.ai-answer-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
}
.badge {
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 12px;
}
.badge[data-mode='GROUNDED'] { border-color: var(--ok-border); color: var(--ok); }
.badge[data-mode='NO_EVIDENCE'] { border-color: var(--fail-border); color: var(--fail); }
.badge[data-mode='DRAFT'] { border-color: var(--warn-border); color: var(--warn); }
.ai-text {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}
.ai-block h4 {
  margin: 0 0 4px;
  font-size: 13px;
}
.ai-block ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.6;
}
.ai-warn {
  padding: 6px 10px;
  border: 1px solid var(--warn-border);
  border-radius: 6px;
  font-size: 12px;
}
.ai-citations {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
}
.chip {
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
  font-size: 12px;
}
.chip:hover { border-color: var(--accent); }
.muted { color: var(--text-muted); }
.text-ok { color: var(--ok); }
.text-fail { color: var(--fail); }
.error { margin: 0; color: var(--fail); }
</style>
