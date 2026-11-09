<script setup lang="ts">
/**
 * 运维中心（v3.3.0 / FR-3.3-09..12，v3.4.0 / FR-3.4-02）。
 *
 * 一页回答四个问题：服务达没达标（SLO / 错误预算）、出事有没有通知（告警）、
 * 数据能不能回来（备份演练）、现在还能不能起新运行（并发准入）。
 * 只读为主：规则 / 通道的增删仍在既有管理页，这里只做「看见 + 触发巡检」。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { opsApi, type AdmissionView, type OpsSummary } from '@/api/ops'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const summary = ref<OpsSummary | null>(null)
const admission = ref<AdmissionView | null>(null)
const loading = ref(false)
const errorText = ref('')
const notice = ref('')
const evaluating = ref(false)

const budgetUsedPct = computed(() => {
  const slo = summary.value?.slo
  if (!slo || slo.errorBudgetMinutes <= 0) return 0
  return Math.min(100, Math.round((slo.errorBudgetUsedMinutes / slo.errorBudgetMinutes) * 100))
})

function minutes(value: number | undefined): string {
  return `${Math.round((value ?? 0) / 60)}m`
}

function percent(value: number | undefined): string {
  return `${(value ?? 0).toFixed(1)}%`
}

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  errorText.value = ''
  notice.value = ''
  try {
    const [summaryData, admissionData] = await Promise.all([
      opsApi.summary(projectId.value),
      opsApi.admission(projectId.value),
    ])
    summary.value = summaryData
    admission.value = admissionData
  } catch (error) {
    summary.value = null
    admission.value = null
    errorText.value = error instanceof ApiError ? error.message : t('ops.loadFailed')
  } finally {
    loading.value = false
  }
}

async function evaluate(): Promise<void> {
  if (!projectId.value) return
  evaluating.value = true
  try {
    const result = await opsApi.evaluate(projectId.value)
    notice.value = t('ops.evaluated', {
      fired: result.fired.length,
      suppressed: result.suppressed,
      resolved: result.resolved.length,
    })
    await load()
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : t('ops.loadFailed')
  } finally {
    evaluating.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="page" data-testid="ops-center">
    <header class="page-head">
      <div>
        <h1>{{ t('ops.title') }}</h1>
        <p class="muted">{{ t('ops.subtitle') }}</p>
      </div>
      <button class="btn" type="button" :disabled="loading" @click="load">
        {{ t('ops.refresh') }}
      </button>
    </header>

    <p v-if="loading" class="muted">{{ t('common.loading') }}</p>
    <p v-if="errorText" class="error" data-testid="ops-error">{{ errorText }}</p>
    <p v-if="notice" class="notice" data-testid="ops-notice">{{ notice }}</p>

    <template v-if="summary">
      <section class="card" data-testid="ops-slo">
        <h2 class="card-title">{{ t('ops.slo') }}</h2>
        <dl class="grid">
          <dt>{{ t('ops.availability') }}</dt>
          <dd>{{ percent(summary.slo.availabilityPct) }}</dd>
          <dt>{{ t('ops.target') }}</dt>
          <dd>{{ percent(summary.slo.targetPct) }}</dd>
          <dt>{{ t('ops.p95') }}</dt>
          <dd>{{ summary.slo.p95Ms }}ms</dd>
          <dt>{{ t('ops.samples') }}</dt>
          <dd>{{ summary.slo.samples }}</dd>
          <dt>{{ t('ops.mttd') }}</dt>
          <dd>{{ minutes(summary.slo.mttdSeconds) }}</dd>
          <dt>{{ t('ops.mttr') }}</dt>
          <dd>{{ minutes(summary.slo.mttrSeconds) }}</dd>
          <dt>{{ t('ops.budgetUsed') }}</dt>
          <dd>{{ budgetUsedPct }}%</dd>
          <dt>{{ t('ops.budgetRemaining') }}</dt>
          <dd>{{ minutes(summary.slo.errorBudgetMinutes - summary.slo.errorBudgetUsedMinutes) }}</dd>
        </dl>
        <div class="budget" :aria-label="t('ops.budget')">
          <span class="budget-fill" :style="{ width: `${budgetUsedPct}%` }" />
        </div>
        <ul class="days">
          <li v-for="day in summary.slo.days" :key="day.day">
            {{ day.day }} · {{ percent(day.availabilityPct) }} · {{ day.p95Ms }}ms
          </li>
        </ul>
      </section>

      <section class="card" data-testid="ops-live">
        <h2 class="card-title">{{ t('ops.live') }}</h2>
        <dl class="grid">
          <dt>{{ t('ops.requests') }}</dt>
          <dd>{{ summary.live.total }}</dd>
          <dt>{{ t('ops.errors') }}</dt>
          <dd>{{ summary.live.errors }}</dd>
          <dt>{{ t('ops.availability') }}</dt>
          <dd>{{ percent(summary.live.availabilityPct) }}</dd>
          <dt>{{ t('ops.p95') }}</dt>
          <dd>{{ summary.live.p95Ms }}ms</dd>
        </dl>
      </section>

      <section class="card" data-testid="ops-backup">
        <h2 class="card-title">{{ t('ops.backup') }}</h2>
        <p v-if="!summary.backup.present" class="error">{{ t('ops.backupMissing') }}</p>
        <dl v-else class="grid">
          <dt>{{ t('ops.verdict') }}</dt>
          <dd>{{ summary.backup.verdict }}</dd>
          <dt>{{ t('ops.rto') }}</dt>
          <dd>{{ minutes(summary.backup.rtoSeconds) }}</dd>
          <dt>{{ t('ops.rpo') }}</dt>
          <dd>{{ minutes(summary.backup.rpoSeconds) }}</dd>
          <dt>{{ t('ops.tables') }}</dt>
          <dd>{{ summary.backup.tables }}</dd>
          <dt>{{ t('ops.generatedAt') }}</dt>
          <dd>{{ summary.backup.generatedAt }}</dd>
        </dl>
      </section>

      <section v-if="admission" class="card" data-testid="ops-admission">
        <h2 class="card-title">{{ t('ops.admission') }}</h2>
        <dl class="grid">
          <dt>{{ t('ops.inflight') }}</dt>
          <dd>{{ admission.inflight }} / {{ admission.maxInflight }}</dd>
          <dt>{{ t('ops.maxPerProject') }}</dt>
          <dd>{{ admission.maxPerProject }}</dd>
          <dt>{{ t('ops.maxPerNode') }}</dt>
          <dd>{{ admission.maxPerNode }}</dd>
        </dl>
        <p class="muted">
          {{ t('ops.byProject') }}:
          <span v-for="(count, id) in admission.byProject" :key="id">{{ id }}={{ count }} </span>
        </p>
      </section>

      <section class="card" data-testid="ops-alerts">
        <div class="card-head">
          <h2 class="card-title">{{ t('ops.alerts') }}</h2>
          <button class="btn" type="button" :disabled="evaluating" @click="evaluate">
            {{ t('ops.evaluate') }}
          </button>
        </div>
        <h3>{{ t('ops.rules') }}</h3>
        <ul class="list">
          <li v-for="rule in summary.rules" :key="rule.id">
            <strong>{{ rule.eventType }}</strong>
            <span class="muted">
              {{ t('ops.threshold') }} {{ rule.threshold }} ·
              {{ t('ops.window') }} {{ rule.windowMinutes }} ·
              {{ t('ops.cooldown') }} {{ rule.cooldownMinutes }} ·
              {{ rule.severity }} · {{ rule.enabled ? t('ops.enabled') : t('ops.disabled') }}
            </span>
          </li>
          <li v-if="!summary.rules.length" class="muted">{{ t('ops.empty') }}</li>
        </ul>

        <h3>{{ t('ops.channels') }}</h3>
        <ul class="list">
          <li v-for="channel in summary.channels" :key="channel.id">
            <strong>{{ channel.kind }}</strong>
            <span class="muted">{{ channel.target }} · {{ channel.hasSecret ? t('ops.hasSecret') : t('ops.noSecret') }}</span>
          </li>
          <li v-if="!summary.channels.length" class="muted">{{ t('ops.empty') }}</li>
        </ul>

        <h3>{{ t('ops.events') }}</h3>
        <ul class="list">
          <li v-for="event in summary.events" :key="event.id" data-testid="ops-event">
            <strong>{{ event.eventType }}</strong>
            <span class="muted">
              {{ event.severity }} · {{ event.status }} · {{ event.detectedAt }} ·
              {{ t('ops.delivery') }} {{ event.deliveryStatus }} ({{ event.attempts }})
            </span>
            <span>{{ event.summary }}</span>
          </li>
          <li v-if="!summary.events.length" class="muted">{{ t('ops.noEvents') }}</li>
        </ul>
      </section>

      <section class="card" data-testid="ops-releases">
        <h2 class="card-title">{{ t('ops.releases') }}</h2>
        <ul class="list">
          <li v-for="release in summary.releases" :key="release.id">
            <strong>{{ release.version }}</strong>
            <span class="muted">{{ release.status }} · {{ release.deployedAt }} · {{ release.note }}</span>
          </li>
          <li v-if="!summary.releases.length" class="muted">{{ t('ops.empty') }}</li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.card {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title { margin: 0 0 8px; font-size: 15px; }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px 16px;
  margin: 0;
  font-size: 13px;
}
.grid dt { color: var(--text-muted); }
.grid dd { margin: 0; }
.budget {
  height: 8px;
  margin: 12px 0 6px;
  border: 1px solid var(--border);
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-2);
}
.budget-fill {
  display: block;
  height: 100%;
  background: var(--accent);
}
.days { margin: 8px 0 0; padding-left: 18px; font-size: 12px; color: var(--text-muted); }
.list { margin: 0 0 10px; padding-left: 18px; display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
.list .muted { margin-left: 8px; }
.muted { color: var(--text-muted); }
.error { color: var(--fail); }
.notice { color: var(--ok); }
</style>
