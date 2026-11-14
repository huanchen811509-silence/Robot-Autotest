<script setup lang="ts">
/**
 * 报告速览卡（FR-3.1-07 / FR-v5.8-01）。
 *
 * 解决什么问题：报告详情首屏先看到一棵很深的套件树，用户要滚很久才知道「这轮到底
 * 能不能交付」。速览只消费后端 `/summary` 的聚合数字，不在前端二次计算，保证与
 * 详情永远一致。
 *
 * 为什么不自己下结论（v5.8.0 / FR-v5.8-01 修订）
 * ----------------------------------------------
 * 本卡原来按 `failed === 0` 推「通过 / 全部通过」。这和判据结论是**两套口径**：
 * 一条用例可以是 `verdict=PASS` 而 `criteria_verdict=MISSING`（判据没声明或没采到），
 * 于是同一屏上速览说「全部通过」、结论卡说「未判定」，读者无从判断该信哪个。
 *
 * 现在改为：调用方传 `criteriaConclusion` 就以判据为准给出状态，正文只**陈述计数**
 * （多少条、通过/失败/跳过各多少），不再复述「通过」。判据缺失时显式写「未判定」
 * 并说明不按失败数推断。未传判据的旧调用保留计数口径文案，但当前全站只有报告
 * 详情页一处使用，且它一定传。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import StatusTag from '@/components/StatusTag.vue'
import SkeletonBlock from '@/components/ui/SkeletonBlock.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import { formatDateTime, formatDuration } from '@/utils/format'
import type { ReportSummary } from '@/api/reports'
import type { StatusTone } from '@/utils/statusLabels'

const props = withDefaults(
  defineProps<{
    summary?: ReportSummary | null
    loading?: boolean
    error?: string
    /**
     * 判据汇总结论（FR-v5.8-01）：`PASS` / `FAIL` / `UNDETERMINED`。
     * 传了就以它为准，**不再**用 `failed === 0` 推「通过」；空串表示调用方没有判据上下文。
     */
    criteriaConclusion?: string
  }>(),
  { summary: null, loading: false, error: '', criteriaConclusion: '' },
)

const emit = defineEmits<{ retry: []; showFailures: [] }>()

const { t } = useI18n()

const hasFailures = computed(() => (props.summary?.failed ?? 0) > 0)

/** 归一后的判据结论；空串表示「没有判据上下文」，此时才回退计数口径。 */
const verdict = computed(() => (props.criteriaConclusion || '').trim().toUpperCase())
const hasVerdict = computed(
  () => verdict.value === 'PASS' || verdict.value === 'FAIL' || verdict.value === 'UNDETERMINED',
)

const tone = computed<StatusTone>(() => {
  if (!props.summary || props.summary.conclusion === 'NO_DATA') return 'neutral'
  if (hasVerdict.value) {
    if (verdict.value === 'PASS') return 'ok'
    if (verdict.value === 'FAIL') return 'fail'
    return 'neutral'
  }
  return hasFailures.value ? 'fail' : 'ok'
})

const statusLabel = computed(() => {
  if (!props.summary || props.summary.conclusion === 'NO_DATA') return t('reportSummary.statusNoData')
  // 有判据时标签表达的是「判据结论」，用与结论卡片同一套文案，避免同屏两种说法
  if (hasVerdict.value) return t(`reports.conclusion.${verdict.value}`)
  return hasFailures.value ? t('reportSummary.statusFailed') : t('reportSummary.statusPassed')
})

const conclusion = computed(() => {
  const summary = props.summary
  if (!summary) return ''
  if (summary.conclusion === 'NO_DATA' || summary.total === 0) {
    return t('reportSummary.conclusionNoData')
  }
  if (verdict.value === 'UNDETERMINED') {
    return t('reports.conclusion.undeterminedNote')
  }
  // 有判据时正文只报数：结论由「结论」卡片给出，两处不能各说一套。
  if (hasVerdict.value) {
    return t('reportSummary.resultLine', {
      total: summary.total,
      passed: summary.passed,
      failed: summary.failed,
      skipped: summary.skipped,
    })
  }
  if (hasFailures.value) {
    return t('reportSummary.conclusionFailed', {
      failed: summary.failed,
      passRate: formatPassRate(summary.passRate),
    })
  }
  return summary.skipped > 0
    ? t('reportSummary.conclusionPassedWithSkipped', { skipped: summary.skipped })
    : t('reportSummary.conclusionPassed')
})

const topFailures = computed(() => (props.summary?.topFailures ?? []).slice(0, 3))

const metrics = computed(() => {
  const summary = props.summary
  if (!summary) return []
  return [
    { key: 'passRate', label: t('reportSummary.passRate'), value: formatPassRate(summary.passRate) },
    { key: 'total', label: t('reportSummary.total'), value: String(summary.total) },
    { key: 'failed', label: t('reportSummary.failed'), value: String(summary.failed), tone: 'fail' },
    { key: 'skipped', label: t('reportSummary.skipped'), value: String(summary.skipped) },
  ] as const
})

function formatPassRate(rate: number): string {
  return `${Number.isFinite(rate) ? rate.toFixed(1) : '0.0'}%`
}
</script>

<template>
  <section class="card report-summary" :aria-label="t('reportSummary.ariaLabel')">
    <div v-if="loading" class="report-summary__loading" aria-busy="true">
      <SkeletonBlock :rows="3" />
    </div>

    <ErrorState
      v-else-if="error"
      :title="t('reportSummary.errorTitle')"
      :description="error"
      @retry="emit('retry')"
    />

    <template v-else-if="summary">
      <div class="report-summary__head">
        <StatusTag :label="statusLabel" :tone="tone" />
        <p class="report-summary__conclusion" data-test="report-summary-conclusion">
          {{ conclusion }}
        </p>
        <span class="report-summary__duration muted">
          {{ t('reportSummary.duration', { value: formatDuration(summary.durationMs) }) }}
        </span>
      </div>

      <dl class="report-summary__metrics">
        <div
          v-for="item in metrics"
          :key="item.key"
          class="report-summary__metric"
          :class="{ 'report-summary__metric--fail': item.key === 'failed' && summary.failed > 0 }"
        >
          <dt>{{ item.label }}</dt>
          <dd>{{ item.value }}</dd>
        </div>
      </dl>

      <!-- v5.6.0 / FR-v5.6-24：抖动单列。不显示 0，避免每张卡都挂一行「抖动 0 条」。 -->
      <p v-if="summary.flaky > 0" class="report-summary__flaky" data-test="summary-flaky">
        {{ t('reportSummary.flaky', { count: summary.flaky }) }}
      </p>

      <div v-if="topFailures.length" class="report-summary__block">
        <h3 class="report-summary__block-title">{{ t('reportSummary.topFailures') }}</h3>
        <ol class="report-summary__failures">
          <li
            v-for="item in topFailures"
            :key="item.clusterKey"
            class="report-summary__failure"
            :title="item.clusterKey"
          >
            <span class="report-summary__failure-name">{{ item.display }}</span>
            <span class="report-summary__failure-count">
              {{ t('reportSummary.failureCount', { count: item.count }) }}
            </span>
            <!-- FR-v5.6-26：有历史才显示复现信息；null 表示「没有历史可比」，不能显示成 0 -->
            <span
              v-if="item.consecutiveRuns !== null && item.consecutiveRuns !== undefined"
              class="report-summary__failure-repro"
              data-test="repro"
            >
              {{
                t('reportSummary.repro', {
                  runs: item.consecutiveRuns,
                  rate: Math.round((item.reproRate ?? 0) * 1000) / 10,
                })
              }}
            </span>
          </li>
        </ol>
      </div>

      <div v-if="summary.risks.length" class="report-summary__block">
        <h3 class="report-summary__block-title">{{ t('reportSummary.risks') }}</h3>
        <ul class="report-summary__risks">
          <li v-for="risk in summary.risks" :key="risk">{{ risk }}</li>
        </ul>
      </div>

      <footer class="report-summary__foot">
        <span class="muted">
          {{ t('reportSummary.generatedAt', { at: formatDateTime(summary.generatedAt) }) }}
        </span>
        <button
          v-if="hasFailures"
          type="button"
          class="btn report-summary__jump"
          @click="emit('showFailures')"
        >
          {{ t('reportSummary.showFailures') }}
        </button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.report-summary {
  display: grid;
  gap: var(--space-3);
  max-width: none;
}

.report-summary__loading {
  min-height: 84px;
}

.report-summary__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.report-summary__conclusion {
  flex: 1;
  min-width: 220px;
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text);
}

.report-summary__duration {
  font-size: var(--font-size-xs);
  white-space: nowrap;
}

.report-summary__metrics {
  display: grid;
  gap: var(--space-3);
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
}

.report-summary__metric {
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface-2);
  min-width: 0;
}

.report-summary__metric dt {
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.report-summary__metric dd {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-lg);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--text);
}

.report-summary__metric--fail dd {
  color: var(--fail);
}

.report-summary__block {
  display: grid;
  gap: var(--space-2);
}

.report-summary__block-title {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  font-weight: 500;
}

.report-summary__failures,
.report-summary__risks {
  margin: 0;
  padding-left: var(--space-4);
  display: grid;
  gap: var(--space-1);
}

.report-summary__flaky {
  margin: var(--space-2) 0 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--warn-soft);
  color: var(--warn);
  font-size: var(--font-size-sm);
}

.report-summary__failure {
  display: flex;
  /* 展示名可能是长断言原文，复现信息跟在后面：允许换行比挤成一行更好读 */
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  font-size: var(--font-size-sm);
}

.report-summary__failure-name {
  min-width: 0;
  /* 展示名现在是断言原文，一两行才看得懂；单行 ellipsis 会把关键差异截掉 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
  line-height: 1.45;
}

.report-summary__failure-count {
  flex: none;
  color: var(--text-muted);
}

.report-summary__failure-repro {
  flex: none;
  font-size: 12px;
  color: var(--text-muted);
  opacity: 0.9;
}

.report-summary__risks {
  color: var(--warn);
  font-size: var(--font-size-sm);
}

.report-summary__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
  font-size: var(--font-size-xs);
}

.report-summary__jump {
  flex: none;
}

@media (width <= 900px) {
  .report-summary__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (width <= 480px) {
  .report-summary__metrics {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
