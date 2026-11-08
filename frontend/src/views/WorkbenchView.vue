<script setup lang="ts">
/**
 * 工作台项目总览（v2.3 / UI-03，FR-UI-10..14）。
 *
 * <p>落地页从「单项目看板」改成「跨项目卡牌页」后的唯一列表页：
 * - 一次请求拉全部卡牌（FR-UI-14），**绝不为每张卡再打一次 overview**；
 * - 搜索 / 排序都是本地计算，输入不触发网络；
 * - 四态齐全：加载骨架 / 空态 / 错误态 / 有数据。错误态**不渲染任何卡牌**，
 *   避免「接口挂了但页面还挂着上次的数」被当成真实状态。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { projectCardsApi, type ProjectCard as ProjectCardData } from '@/api/projectCards'
import { useAuthStore } from '@/stores/auth'
import ProjectCard from '@/components/ProjectCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

type SortKey = 'recent' | 'name'

const { t } = useI18n()
const auth = useAuthStore()

const cards = ref<ProjectCardData[]>([])
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const keyword = ref('')
const sortBy = ref<SortKey>('recent')

/**
 * 当前项目以本地 `auth.projectId` 为准（点击「设为当前」后标记要立刻移动）；
 * `auth.projectId` 为空时才退回到后端 `isCurrent` 的判定。
 */
const serverCurrentId = computed(() => cards.value.find((card) => card.isCurrent)?.id ?? null)

function isCurrent(card: ProjectCardData): boolean {
  return card.id === (auth.projectId ?? serverCurrentId.value)
}

function lastRunValue(value: string | null): number {
  if (!value) return 0
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? 0 : time
}

const filtered = computed<ProjectCardData[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  const rows = kw
    ? cards.value.filter(
        (card) =>
          card.name.toLowerCase().includes(kw) || card.code.toLowerCase().includes(kw),
      )
    : [...cards.value]
  return rows.sort((a, b) => {
    if (sortBy.value === 'name') return a.name.localeCompare(b.name)
    return lastRunValue(b.lastRunAt) - lastRunValue(a.lastRunAt)
  })
})

/** 页面级唯一的数据入口：`onMounted` 调一次，重试按钮是唯一的手动重发点。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    cards.value = await projectCardsApi.list('7d', auth.projectId)
  } catch (e) {
    error.value = localizeError(e)
    cards.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="workbench">
    <h1 class="page-title">{{ t('workbench.title') }}</h1>
    <p class="page-hint">{{ t('workbench.subtitle') }}</p>

    <div class="toolbar">
      <input
        v-model="keyword"
        class="input workbench-search"
        type="search"
        data-testid="workbench-search"
        :placeholder="t('workbench.searchPlaceholder')"
        :aria-label="t('workbench.searchPlaceholder')"
      />
      <div class="sort-group" role="group" :aria-label="t('workbench.sortRecent')">
        <button
          type="button"
          class="btn btn-ghost sort-btn"
          :class="{ 'is-active': sortBy === 'recent' }"
          :aria-pressed="sortBy === 'recent'"
          data-testid="sort-recent"
          @click="sortBy = 'recent'"
        >
          {{ t('workbench.sortRecent') }}
        </button>
        <button
          type="button"
          class="btn btn-ghost sort-btn"
          :class="{ 'is-active': sortBy === 'name' }"
          :aria-pressed="sortBy === 'name'"
          data-testid="sort-name"
          @click="sortBy = 'name'"
        >
          {{ t('workbench.sortName') }}
        </button>
      </div>
      <span v-if="!loading && !error" class="muted" data-testid="workbench-count">
        {{ t('workbench.count', { count: filtered.length }) }}
      </span>
    </div>

    <div v-if="loading" class="workbench-skeleton" aria-busy="true" data-testid="workbench-loading">
      <div v-for="n in 6" :key="n" class="skeleton-card" />
    </div>

    <div v-else-if="error" class="workbench-error" role="alert" data-testid="workbench-error">
      <p class="workbench-error__title">{{ t('workbench.loadFailed') }}</p>
      <ErrorLineView :error="error" />
      <button type="button" class="btn" data-testid="workbench-retry" @click="load">
        {{ t('workbench.retry') }}
      </button>
    </div>

    <EmptyState
      v-else-if="filtered.length === 0"
      data-testid="workbench-empty"
      illustration="no-data"
      :title="cards.length === 0 ? t('workbench.empty') : t('common.noResult')"
      :description="cards.length === 0 ? t('workbench.emptyHint') : t('common.noResultHint')"
    />

    <div v-else class="project-cards" data-testid="project-cards">
      <ProjectCard
        v-for="card in filtered"
        :key="card.id"
        :card="card"
        :current="isCurrent(card)"
      />
    </div>
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  flex-direction: column;
}

.workbench-search {
  min-width: 240px;
}

.sort-group {
  display: flex;
  gap: var(--space-2);
}

.sort-btn {
  margin-top: 0;
}

.sort-btn.is-active {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
}

.project-cards,
.workbench-skeleton {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--space-4);
  align-items: stretch;
}

.skeleton-card {
  height: 200px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
}

.workbench-error {
  padding: var(--space-5);
  background: var(--fail-soft);
  border: 1px solid var(--fail-border);
  border-radius: var(--radius-lg);
}

.workbench-error__title {
  margin: 0 0 var(--space-3);
  color: var(--fail);
  font-size: var(--font-size-base);
}
</style>
