<script setup lang="ts">
/**
 * 工作台单张项目卡牌（v2.3 / UI-03，FR-UI-11 / FR-UI-13）。
 *
 * <p>字段分三层，顺序即阅读顺序：
 * ① **必有层** —— 名称 / 代码 / 启用状态 / 当前项目标记（任何项目都有）；
 * ② **概要层** —— 用例数 / 近 7 天运行数 / 近 7 天通过率 / 最近运行时间；
 * ③ **细节层** —— 设备数 / 成员数 / 可见性 / 我的角色。
 *
 * <p>两个动作语义**必须分离**（FR-UI-13）：
 * - 「进入」= 切项目 + 跳到该项目的总览；
 * - 「设为当前」= 只切项目，**不跳转**（用户可能想留在总览页继续扫其他项目）。
 * 两者共用 `auth.setProject`，差别只在有没有 `router.push`。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { ProjectCard as ProjectCardData } from '@/api/projectCards'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatNumber, formatPercent } from '@/utils/format'

const props = defineProps<{
  card: ProjectCardData
  /** 是否当前项目：由父级按 `auth.projectId` 计算，点击「设为当前」后标记会跟着移动。 */
  current: boolean
}>()

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

/** 与后端 `enum.memberRole` 对齐；后端给了未知角色时原样显示，不吞成「—」。 */
const ROLE_KEYS = ['owner', 'admin', 'pm', 'engineer', 'member', 'viewer'] as const

const passRate = computed(() => formatPercent(props.card.passRateBp, t('workbench.noData')))
const lastRun = computed(() =>
  props.card.lastRunAt ? formatDateTime(props.card.lastRunAt) : t('workbench.noData'),
)
const caseCount = computed(() => formatNumber(props.card.caseCount, t('workbench.noData')))
const runCount = computed(() => formatNumber(props.card.runCount, t('workbench.noData')))
const deviceCount = computed(() => formatNumber(props.card.deviceCount, t('workbench.noData')))
const memberCount = computed(() => formatNumber(props.card.memberCount, t('workbench.noData')))

const roleLabel = computed(() => {
  const role = props.card.myRole
  if (!role) return t('workbench.noData')
  const key = role.toLowerCase()
  return (ROLE_KEYS as readonly string[]).includes(key)
    ? t(`enum.memberRole.${key}`)
    : role
})

const visibilityLabel = computed(() => {
  if (props.card.visibility === 'private') return t('workbench.visPrivate')
  if (props.card.visibility === 'public') return t('workbench.visPublic')
  return props.card.visibility || t('workbench.noData')
})

function enter(): void {
  auth.setProject(props.card.id)
  void router.push('/overview')
}

function setCurrent(): void {
  auth.setProject(props.card.id)
}
</script>

<template>
  <article
    class="project-card"
    :class="{ 'is-current': current, 'is-disabled': !card.enabled }"
    data-testid="project-card"
    :aria-current="current ? 'true' : undefined"
  >
    <header class="project-card__head">
      <div class="project-card__identity">
        <h2 class="project-card__name">{{ card.name }}</h2>
        <span class="project-card__code">{{ card.code }}</span>
      </div>
      <div class="project-card__flags">
        <span v-if="current" class="badge badge--accent">{{ t('workbench.current') }}</span>
        <span class="badge" :class="card.enabled ? 'badge--ok' : 'badge--muted'">
          {{ card.enabled ? t('workbench.enabled') : t('workbench.disabled') }}
        </span>
      </div>
    </header>

    <p v-if="card.description" class="project-card__desc">{{ card.description }}</p>

    <!-- 概要层 -->
    <dl class="project-card__metrics">
      <div class="metric">
        <dt class="metric__label">{{ t('workbench.cases') }}</dt>
        <dd class="metric__value" data-testid="card-cases">{{ caseCount }}</dd>
      </div>
      <div class="metric">
        <dt class="metric__label">{{ t('workbench.runs7d') }}</dt>
        <dd class="metric__value" data-testid="card-runs">{{ runCount }}</dd>
      </div>
      <div class="metric">
        <dt class="metric__label">{{ t('workbench.passRate') }}</dt>
        <dd class="metric__value" data-testid="card-pass-rate">{{ passRate }}</dd>
      </div>
      <div class="metric metric--wide">
        <dt class="metric__label">{{ t('workbench.lastRun') }}</dt>
        <dd class="metric__value" data-testid="card-last-run">{{ lastRun }}</dd>
      </div>
    </dl>

    <!-- 细节层 -->
    <dl class="project-card__details">
      <div class="detail">
        <dt class="detail__label">{{ t('workbench.devices') }}</dt>
        <dd class="detail__value" data-testid="card-devices">{{ deviceCount }}</dd>
      </div>
      <div class="detail">
        <dt class="detail__label">{{ t('workbench.members') }}</dt>
        <dd class="detail__value" data-testid="card-members">{{ memberCount }}</dd>
      </div>
      <div class="detail">
        <dt class="detail__label">{{ t('workbench.visibility') }}</dt>
        <dd class="detail__value" data-testid="card-visibility">{{ visibilityLabel }}</dd>
      </div>
      <div class="detail">
        <dt class="detail__label">{{ t('workbench.myRole') }}</dt>
        <dd class="detail__value" data-testid="card-role">{{ roleLabel }}</dd>
      </div>
    </dl>

    <div class="project-card__actions">
      <button type="button" class="btn" data-testid="card-enter" @click="enter">
        {{ t('workbench.enter') }}
      </button>
      <button
        type="button"
        class="btn btn-ghost"
        data-testid="card-set-current"
        :disabled="current"
        @click="setCurrent"
      >
        {{ t('workbench.setCurrent') }}
      </button>
    </div>
  </article>
</template>

<style scoped>
.project-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  min-width: 0;
}

.project-card.is-current {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.project-card.is-disabled {
  opacity: 0.72;
}

.project-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.project-card__identity {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}

.project-card__name {
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text);
  overflow-wrap: anywhere;
}

.project-card__code {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: var(--font-size-xs);
}

.project-card__flags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: flex-end;
}

.badge {
  padding: 2px var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  white-space: nowrap;
}

.badge--accent {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--panel);
}

.badge--ok {
  color: var(--ok);
  border-color: var(--ok-border);
  background: var(--ok-soft);
}

.badge--muted {
  color: var(--text-muted);
  background: var(--surface-2);
}

.project-card__desc {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  overflow-wrap: anywhere;
}

.project-card__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  margin: 0;
}

.project-card__details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
  margin: 0;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.metric,
.detail {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.metric--wide {
  grid-column: 1 / -1;
}

.metric__label,
.detail__label {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.metric__value,
.detail__value {
  margin: 0;
  color: var(--text);
  font-size: var(--font-size-base);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.detail__value {
  font-size: var(--font-size-sm);
}

.project-card__actions {
  display: flex;
  gap: var(--space-3);
  margin-top: auto;
}

.project-card__actions .btn {
  margin-top: 0;
}
</style>
