<script setup lang="ts">
/**
 * 工作台单张项目卡牌（v2.3 / UI-03，FR-UI-11 / FR-UI-13）。
 *
 * <p>字段分三层，顺序即阅读顺序：
 * ① **必有层** —— 名称 / 代码 / 启用状态 / 当前项目标记（任何项目都有）；
 * ② **概要层** —— 用例数 / 近 7 天运行数 / 近 7 天通过率 / 最近运行时间；
 * ③ **细节层** —— 设备数 / 成员数 / 可见性 / 我的角色。
 *
 * <p>动作语义**必须分离**（FR-UI-13）：
 * - 「进入」= 切项目 + 跳到该项目的总览；
 * - 「设为当前」= 只切项目，**不跳转**（用户可能想留在总览页继续扫其他项目）。
 * 两者共用 `auth.setProject`，差别只在有没有 `router.push`。
 * - 「停用 / 启用」「彻底删除」= v4.3.0 `FR-v4.3-09`，卡片只发事件，由父级统一调接口并刷新列表。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { ProjectCard as ProjectCardData } from '@/api/projectCards'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatNumber, formatPercent } from '@/utils/format'

const props = withDefaults(defineProps<{
  card: ProjectCardData
  /** 是否当前项目：由父级按 `auth.projectId` 计算，点击「设为当前」后标记会跟着移动。 */
  current: boolean
  /** 是否显示「停用 / 启用」：对应后端 PROJECT/UPDATE（ADMIN + PM）。 */
  canManage?: boolean
  /** 是否显示「彻底删除」：对应后端 PROJECT/DELETE（仅 ADMIN）。 */
  canDelete?: boolean
  /** 是否显示「切换来源」：同样仅 ADMIN（FR-ORIGIN-04）。 */
  canChangeOrigin?: boolean
  /** 该卡片的动作请求是否在途，避免重复点击。 */
  busy?: boolean
}>(), { canManage: false, canDelete: false, canChangeOrigin: false, busy: false })

const emit = defineEmits<{
  toggleEnabled: [card: ProjectCardData]
  requestDelete: [card: ProjectCardData]
  requestChangeOrigin: [card: ProjectCardData]
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
/**
 * v5.22.0 / FR-v5.22-06 判据 06-3：`runCount === 0` 显示 `—`，不显示 `0`。
 *
 * <p>为什么不显示 0：这个数是「近 7 天运行数」，`0` 与「从没跑过」在界面上长得一模一样，
 * 而这两种情况要给的下一步完全不同（前者等一等，后者该点「发起第一次运行」）。
 * `null` / 字段缺失仍显示 `—`（后端没采集），与 0 一样落到同一个占位符，但在 title 上区分开。
 */
const runCount = computed(() =>
  props.card.runCount === 0
    ? t('workbench.noData')
    : formatNumber(props.card.runCount, t('workbench.noData')),
)
/** 从没跑过：卡片要给「发起第一次运行」入口（判据 06-3）。 */
const neverRun = computed(() => props.card.runCount === 0)
const runsTitle = computed(() =>
  neverRun.value ? t('workbench.runsZeroHint') : t('workbench.runs7d'),
)
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

/** 测试内核徽章：robot 是默认值（存量项目后端给 robot），未知值原样显示。 */
const kernelLabel = computed(() => {
  const kernel = (props.card.testKernel ?? 'robot').toLowerCase()
  if (kernel === 'robot') return t('workbench.kernelRobot')
  if (kernel === 'pytest') return t('workbench.kernelPytest')
  return props.card.testKernel || t('workbench.noData')
})

/**
 * 是否带业务数据（仅用于 USER 项目的提示文案，不再用于置灰）。
 *
 * <p>FR-ORIGIN 修正：USER 项目走级联真删，带数据也应能点删除——旧的「有数据只能停用」
 * 是 BUILTIN 口径，不能套到 USER 上（否则切了来源还是删不掉，正是大师反馈的问题）。
 */
const hasData = computed(
  () => props.card.caseCount > 0 || props.card.runCount > 0 || props.card.deviceCount > 0,
)

/** FR-ORIGIN-03：来源徽标；缺省按 BUILTIN（fail-safe，不把未知项目误标成可删的自建）。 */
const originLabel = computed(() =>
  (props.card.origin ?? 'BUILTIN').toUpperCase() === 'USER'
    ? t('workbench.originUser')
    : t('workbench.originBuiltin'),
)
/** FR-ORIGIN-05：程序自带（含未标注）删除锁定；USER 项目才可能可删。 */
const deleteLockedByOrigin = computed(
  () => (props.card.origin ?? 'BUILTIN').toUpperCase() !== 'USER',
)
/**
 * 删除按钮悬停提示三分支：来源锁 > USER 有数据（级联提示） > 可直接删。
 * 注意 USER 项目不再因 hasData 置灰——它走的是级联真删，不是老的「只能停用」。
 */
const deleteTitle = computed(() => {
  if (deleteLockedByOrigin.value) return t('workbench.deleteOriginBlocked')
  if (hasData.value) return t('workbench.deleteCascadeHint')
  return t('workbench.delete')
})

const toggleLabel = computed(() => {
  if (props.busy) return t('workbench.processing')
  return props.card.enabled ? t('workbench.disable') : t('workbench.enable')
})

function enter(): void {
  auth.setProject(props.card.id)
  void router.push('/overview')
}

/**
 * v5.22.0 / FR-v5.22-06 判据 06-3：从「从没跑过」直接去发起页。
 *
 * <p>先 `setProject` 再跳：发起页读的是当前项目上下文，不切项目就会拿着上一个项目的
 * 用例集去发起，用户会以为「我的用例不见了」。
 */
function startFirstRun(): void {
  auth.setProject(props.card.id)
  void router.push('/execution-plans')
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
        <!-- 测试内核：一眼看出这个项目跑的是 RF 还是 pytest -->
        <span class="badge badge--muted" :title="t('workbench.kernel')" data-testid="card-kernel">
          {{ kernelLabel }}
        </span>
        <!-- FR-ORIGIN-03：来源徽标，区分程序自带 / 用户自建 -->
        <span class="badge badge--muted" :title="t('workbench.origin')" data-testid="card-origin">
          {{ originLabel }}
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
        <dt class="metric__label" :title="runsTitle">{{ t('workbench.runs7d') }}</dt>
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
      <!-- v5.22.0 / FR-v5.22-06 判据 06-3：从没跑过的项目，给一条明确的下一条路。 -->
      <button
        v-if="neverRun"
        type="button"
        class="btn btn-first-run"
        data-testid="card-first-run"
        :title="t('workbench.firstRunHint')"
        @click="startFirstRun"
      >
        {{ t('workbench.firstRun') }}
      </button>
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
      <button
        v-if="canManage"
        type="button"
        class="btn btn-ghost"
        data-testid="card-toggle"
        :disabled="busy"
        @click="emit('toggleEnabled', card)"
      >
        {{ toggleLabel }}
      </button>
      <button
        v-if="canChangeOrigin"
        type="button"
        class="btn btn-ghost"
        data-testid="card-change-origin"
        :disabled="busy"
        :title="t('workbench.changeOriginHint')"
        @click="emit('requestChangeOrigin', card)"
      >
        {{ t('workbench.changeOrigin') }}
      </button>
      <button
        v-if="canDelete"
        type="button"
        class="btn btn-ghost danger"
        data-testid="card-delete"
        :disabled="busy || deleteLockedByOrigin"
        :title="deleteTitle"
        @click="emit('requestDelete', card)"
      >
        {{ t('workbench.delete') }}
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
  flex-wrap: wrap; /* 窄卡片放不下时整按钮换行，不让单个按钮的文字折行 */
  gap: var(--space-3);
  margin-top: auto;
}

.project-card__actions .btn {
  margin-top: 0;
  white-space: nowrap; /* 按钮文字保持一行（如「发起运行」不再折成「发起运/行」） */
}

/* v5.22.0 / FR-v5.22-06 判据 06-3：从没跑过的项目，这条是唯一该点的动作，给主色描边。
   不用 `.btn-primary`：那是各组件自己的 scoped 类，全局只有 `.btn` / `.btn-ghost` / `.danger`。
   缺陷修复：全局 `.btn` 是**实心主色**（background: var(--accent)）。原先这里只改了 color/border，
   忘了改背景 → 字色与底色同为 --accent，完全看不见（UI 实测 color=background=rgb(251,146,60)）。
   描边按钮三要素必须齐全：透明底 + 主色字 + 主色边；hover 用 accent-soft。 */
.project-card__actions .btn-first-run {
  background: transparent;
  color: var(--accent);
  border: 1px solid var(--accent);
  font-weight: 600;
}

.project-card__actions .btn-first-run:hover:not(:disabled) {
  background: var(--accent-soft);
}
</style>
