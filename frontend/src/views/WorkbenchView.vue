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
import { projectApi } from '@/api/iam'
import { roleFromCodes } from '@/config/navigation'
import { useAuthStore } from '@/stores/auth'
import ProjectCard from '@/components/ProjectCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { useFocusTrap } from '@/composables/useFocusTrap'

type SortKey = 'recent' | 'name'

/**
 * v4.11.0 / FR-v4.11-29：全局模式「我参与的」复用本页卡片，用 `scope` 区分。
 *
 * 不用 `myRole != null` 判——全局 admin 无成员关系时后端也返回 `admin`（A14），
 * 该判据对 admin 恒真。只认 `own || joined` 两个布尔。
 */
const props = withDefaults(defineProps<{ scope?: 'all' | 'mine' }>(), { scope: 'all' })

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
  // `?scope=mine`：只要「我创建或我加入」，与 admin 的全局可见性无关。
  const scoped =
    props.scope === 'mine'
      ? cards.value.filter((card) => card.own === true || card.joined === true)
      : [...cards.value]
  const rows = kw
    ? scoped.filter(
        (card) =>
          card.name.toLowerCase().includes(kw) || card.code.toLowerCase().includes(kw),
      )
    : scoped
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

/* ------------------------------------------------------------------
 * v4.3.0 FR-v4.3-09：项目停用（归档）与彻底删除
 *
 * 停用 = 复用 `PUT /projects/{id}` 的 enabled → status=0，数据全保留、可随时启用；
 * 彻底删除 = `DELETE /projects/{id}`，后端只允许「无业务数据」的项目，ADMIN 专属，
 * 界面要求输入项目代码二次确认，失败原因（含各类条数）原样展示。
 * ------------------------------------------------------------------ */

const actionError = ref<ErrorLine | null>(null)
const busyId = ref<number | null>(null)
const deleteTarget = ref<ProjectCardData | null>(null)
const deleteCode = ref('')
const deleteBusy = ref(false)
const deleteError = ref<ErrorLine | null>(null)

/**
 * 删除确认弹层用自己的遮罩，不用 naive-ui `NModal`。
 *
 * <p>原因：`NModal` 自带焦点管理，与页面上的全局快捷键/焦点陷阱叠在一起时，
 * 输入框拿不到键盘输入（v4.3.0 实测：弹窗能打开、点进去打不出字）。
 * 这里改成 `<Teleport>` + `useFocusTrap`：遮罩与卡片都由本项目控制，
 * 打开即把焦点送进容器（首个可聚焦元素就是编码输入框），Esc 关闭、Tab 循环不外逃。
 */
const deleteCardRef = ref<HTMLElement | null>(null)
const deleteOpen = computed(() => deleteTarget.value !== null)
useFocusTrap(deleteCardRef, deleteOpen, { onEscape: () => closeDelete() })

/** 停用/启用对应后端 PROJECT/UPDATE（ADMIN + PM）；删除对应 PROJECT/DELETE（仅 ADMIN）。 */
const canManage = computed(() => {
  const role = roleFromCodes(auth.user?.roles)
  return role === 'admin' || role === 'pm'
})
const canDelete = computed(() => auth.isAdmin)
const deleteMatched = computed(
  () => deleteTarget.value !== null && deleteCode.value.trim() === deleteTarget.value.code,
)

/** 顶栏项目下拉与卡片共用同一份项目列表，动作后要一起刷新，避免状态不同步。 */
async function refreshProjects(): Promise<void> {
  await load()
  try {
    await auth.loadProjects()
  } catch {
    // 列表刷新失败不影响当前操作结果：卡片已按新状态渲染
  }
}

async function toggleEnabled(card: ProjectCardData): Promise<void> {
  busyId.value = card.id
  actionError.value = null
  try {
    await projectApi.update(card.id, {
      name: card.name,
      description: card.description,
      enabled: !card.enabled,
      ownerId: card.ownerId,
      visibility: card.visibility,
      testKernel: card.testKernel ?? 'robot',
    })
    await refreshProjects()
  } catch (e) {
    actionError.value = localizeError(e)
  } finally {
    busyId.value = null
  }
}

function requestDelete(card: ProjectCardData): void {
  deleteTarget.value = card
  deleteCode.value = ''
  deleteError.value = null
}

function closeDelete(): void {
  if (deleteBusy.value) return
  deleteTarget.value = null
}

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target || !deleteMatched.value) return
  deleteBusy.value = true
  deleteError.value = null
  try {
    await projectApi.remove(target.id)
    deleteTarget.value = null
    await refreshProjects()
  } catch (e) {
    deleteError.value = localizeError(e)
  } finally {
    deleteBusy.value = false
  }
}
</script>

<template>
  <div class="workbench">
    <h1 class="page-title">
      {{ props.scope === 'mine' ? t('workbench.mineTitle') : t('workbench.title') }}
    </h1>
    <p class="page-hint">
      {{ props.scope === 'mine' ? t('workbench.mineSubtitle') : t('workbench.subtitle') }}
    </p>

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

    <ErrorLineView v-if="actionError" :error="actionError" />

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
        :can-manage="canManage"
        :can-delete="canDelete"
        :busy="busyId === card.id"
        @toggle-enabled="toggleEnabled"
        @request-delete="requestDelete"
      />
    </div>

    <Teleport to="body">
      <div
        v-if="deleteTarget"
        class="delete-mask"
        data-testid="delete-mask"
        @click.self="closeDelete"
      >
        <section
          ref="deleteCardRef"
          class="delete-card"
          role="dialog"
          aria-modal="true"
          tabindex="-1"
          :aria-label="t('workbench.deleteTitle')"
        >
          <h2 class="delete-card__title">{{ t('workbench.deleteTitle') }}</h2>
          <p class="delete-card__hint">
            {{ t('workbench.deleteHint', { code: deleteTarget.code }) }}
          </p>
          <label class="delete-card__field" for="delete-code-input">
            <span>{{ t('workbench.deleteCodeLabel') }}</span>
            <input
              id="delete-code-input"
              v-model="deleteCode"
              class="input"
              type="text"
              autocomplete="off"
              spellcheck="false"
              data-testid="delete-code"
              :placeholder="deleteTarget.code"
            />
          </label>
          <ErrorLineView v-if="deleteError" :error="deleteError" />
          <p v-if="deleteCode.trim() && !deleteMatched" class="delete-card__warn">
            {{ t('workbench.deleteMismatch') }}
          </p>
          <footer class="delete-card__actions">
            <button type="button" class="btn btn-ghost" :disabled="deleteBusy" @click="closeDelete">
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="btn danger"
              data-testid="delete-confirm"
              :disabled="!deleteMatched || deleteBusy"
              @click="confirmDelete"
            >
              {{ deleteBusy ? t('workbench.deleting') : t('workbench.deleteConfirm') }}
            </button>
          </footer>
        </section>
      </div>
    </Teleport>
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

.delete-mask {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  /* 与全局浮层口径一致：从顶栏下沿开始，遮罩不压顶栏（FR-v4.0-02）。 */
  top: var(--header-h, 0);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
  background: rgba(15, 23, 42, 0.45);
}

.delete-card {
  width: 420px;
  max-width: 90vw;
  padding: var(--space-5);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow);
}

.delete-card:focus {
  outline: none;
}

.delete-card__title {
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text);
}

.delete-card__hint {
  margin: var(--space-3) 0 var(--space-4);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.6;
}

.delete-card__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--font-size-sm);
}

.delete-card__warn {
  margin: var(--space-2) 0 0;
  color: var(--fail);
  font-size: var(--font-size-sm);
}

.delete-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-5);
}
</style>
