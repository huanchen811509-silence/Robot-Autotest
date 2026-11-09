<script setup lang="ts">
/**
 * 通知中心（v3.1.0「顺手」第二批）。
 *
 * - 铃铛入口放在 AppHeader 工具区，未读数 60s 轮询；页面隐藏时暂停，可见后立即补一次。
 * - 列表按四类分组，未读高亮；点通知先标已读，再按 runKey 跳到运行详情。
 * - 偏好弹窗保存后立即生效；请求失败回滚开关并提示，不留「看起来开了其实没开」的假象。
 */
import { computed, getCurrentInstance, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NModal, NSwitch } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import {
  NOTIFICATION_TYPES,
  notificationApi,
  type NotificationItem,
  type NotificationPreference,
} from '@/api/notifications'
import AppIcon from '@/components/AppIcon.vue'
import SidePanel from '@/components/ui/SidePanel.vue'
import SkeletonBlock from '@/components/ui/SkeletonBlock.vue'
import ErrorState from '@/components/ui/ErrorState.vue'

const POLL_INTERVAL_MS = 60_000
const PAGE_SIZE = 20

const { t, locale } = useI18n()
const auth = useAuthStore()

const panelOpen = ref(false)
const prefsOpen = ref(false)
const filter = ref<'unread' | 'all'>('unread')
const items = ref<NotificationItem[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadFailed = ref(false)
const loadingMore = ref(false)
const unreadCount = ref(0)
const feedback = ref('')

const prefTypes = NOTIFICATION_TYPES
const prefs = ref<Record<string, boolean>>({})
/** 最近一次服务端确认过的值：保存失败时回到这里，而不是回到「刚切换过」的当前值。 */
const prefsSaved = ref<Record<string, boolean>>({})
const prefsLoading = ref(false)
const prefsSaving = ref(false)
const prefsMessage = ref('')
const prefsError = ref('')

let pollTimer: ReturnType<typeof setInterval> | undefined

const badgeText = computed(() => (unreadCount.value > 99 ? '99+' : String(unreadCount.value)))

/** 已知四类按固定顺序，未知类型追加在后面，保证客户端升级前也不丢通知。 */
const groups = computed(() => {
  const map = new Map<string, NotificationItem[]>()
  for (const item of items.value) {
    const list = map.get(item.type)
    if (list) list.push(item)
    else map.set(item.type, [item])
  }
  const orderedTypes = [...NOTIFICATION_TYPES, ...Array.from(map.keys()).filter(
    (type) => !(NOTIFICATION_TYPES as string[]).includes(type),
  )]
  return orderedTypes
    .filter((type) => map.has(type))
    .map((type) => ({ type, items: map.get(type) ?? [] }))
})

const canLoadMore = computed(() => items.value.length < total.value)

function typeLabel(type: string): string {
  return t(`notifications.types.${type}`, type)
}

function formatTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(locale.value, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// 在 setup 阶段取一次：事件回调里 getCurrentInstance() 已经返回 null。
// AppHeader 单测不挂 router，因此这里允许为空，点击时降级为整页跳转。
const appRouter = getCurrentInstance()?.appContext.config.globalProperties.$router as
  | { push: (target: string) => Promise<unknown> | void }
  | undefined

function routerPush(path: string): void {
  if (appRouter) void appRouter.push(path)
  else window.location.assign(path)
}

async function loadUnreadCount(): Promise<void> {
  if (!auth.authenticated || !auth.projectId) {
    unreadCount.value = 0
    return
  }
  try {
    unreadCount.value = await notificationApi.unreadCount(auth.projectId)
  } catch {
    // 计数失败不弹错：通知是辅助信息，不打断主流程
  }
}

async function load(reset = true): Promise<void> {
  if (!auth.projectId) {
    items.value = []
    total.value = 0
    return
  }
  if (reset) {
    loading.value = true
    page.value = 1
    feedback.value = ''
  } else {
    loadingMore.value = true
  }
  loadFailed.value = false
  try {
    const result = await notificationApi.page({
      projectId: auth.projectId,
      status: filter.value,
      page: page.value,
      size: PAGE_SIZE,
    })
    const fetched = result.items ?? []
    items.value = reset ? fetched : [...items.value, ...fetched]
    total.value = result.total ?? items.value.length
  } catch {
    loadFailed.value = true
    if (reset) {
      items.value = []
      total.value = 0
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function openPanel(): void {
  panelOpen.value = true
  feedback.value = ''
  void load()
  void loadUnreadCount()
}

function closePanel(): void {
  panelOpen.value = false
}

function togglePanel(): void {
  if (panelOpen.value) closePanel()
  else openPanel()
}

function setFilter(value: 'unread' | 'all'): void {
  if (filter.value === value) return
  filter.value = value
  void load()
}

function removeLocally(id: number): void {
  if (filter.value !== 'unread') return
  items.value = items.value.filter((item) => item.id !== id)
  total.value = Math.max(0, total.value - 1)
}

async function markRead(item: NotificationItem): Promise<void> {
  if (item.status !== 'read' && auth.projectId) {
    try {
      await notificationApi.markRead(auth.projectId, item.id)
      item.status = 'read'
      item.readAt = new Date().toISOString()
      unreadCount.value = Math.max(0, unreadCount.value - 1)
      removeLocally(item.id)
    } catch {
      feedback.value = t('notifications.markReadFailed')
      return
    }
  }
  if (item.runKey) {
    closePanel()
    routerPush(`/runs?runId=${encodeURIComponent(item.runKey)}`)
  }
}

async function markAllRead(): Promise<void> {
  if (!auth.projectId) return
  try {
    await notificationApi.markAllRead(auth.projectId)
    unreadCount.value = 0
    feedback.value = t('notifications.allReadDone')
    if (filter.value === 'unread') {
      items.value = []
      total.value = 0
    } else {
      items.value = items.value.map((item) => ({
        ...item,
        status: 'read',
        readAt: new Date().toISOString(),
      }))
    }
  } catch {
    feedback.value = t('notifications.markReadFailed')
  }
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !canLoadMore.value) return
  page.value += 1
  await load(false)
}

function openPreferences(): void {
  prefsOpen.value = true
  prefsMessage.value = ''
  prefsError.value = ''
  void loadPreferences()
}

async function loadPreferences(): Promise<void> {
  prefsLoading.value = true
  const next: Record<string, boolean> = {}
  for (const type of prefTypes) next[type] = true
  try {
    const serverPrefs = await notificationApi.preferences(auth.projectId ?? 0)
    for (const pref of serverPrefs) {
      if (pref.type in next) next[pref.type] = pref.enabled
    }
  } catch {
    // 读取失败按全开渲染；保存时才真正报错
  } finally {
    prefs.value = { ...next }
    prefsSaved.value = { ...next }
    prefsLoading.value = false
  }
}

async function savePreferences(): Promise<void> {
  const fallback: Record<string, boolean> = {}
  for (const type of prefTypes) fallback[type] = true
  const previous =
    Object.keys(prefsSaved.value).length > 0 ? { ...prefsSaved.value } : fallback
  const payload: NotificationPreference[] = prefTypes.map((type) => ({
    type,
    enabled: Boolean(prefs.value[type]),
  }))
  prefsSaving.value = true
  prefsMessage.value = ''
  prefsError.value = ''
  try {
    await notificationApi.savePreferences(payload)
    prefsSaved.value = { ...prefs.value }
    prefsMessage.value = t('notifications.preferencesSaved')
    // 立即生效：重新拉一次未读计数，关掉「运行完成」后徽标应马上下降
    void loadUnreadCount()
    if (panelOpen.value) void load()
  } catch {
    prefs.value = previous
    prefsError.value = t('notifications.preferencesSaveFailed')
  } finally {
    prefsSaving.value = false
  }
}

function onVisibilityChange(): void {
  if (document.hidden) {
    stopPolling()
  } else {
    void loadUnreadCount()
    startPolling()
  }
}

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function startPolling(): void {
  stopPolling()
  if (!auth.authenticated || !auth.projectId || document.hidden) return
  pollTimer = setInterval(() => {
    void loadUnreadCount()
  }, POLL_INTERVAL_MS)
}

watch(
  () => [auth.authenticated, auth.projectId],
  () => {
    unreadCount.value = 0
    startPolling()
    void loadUnreadCount()
    if (panelOpen.value) void load()
  },
)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  startPolling()
  void loadUnreadCount()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopPolling()
})
</script>

<template>
  <div class="notification-entry">
    <button
      type="button"
      class="app-icon-btn notification-bell"
      :aria-label="
        unreadCount > 0
          ? t('notifications.openWithUnread', { count: unreadCount })
          : t('notifications.open')
      "
      :title="t('notifications.open')"
      @click="togglePanel"
    >
      <AppIcon name="bell" :size="20" />
      <span v-if="unreadCount > 0" class="notification-badge" aria-hidden="true">
        {{ badgeText }}
      </span>
      <span v-if="unreadCount > 0" class="sr-only">
        {{ t('notifications.badgeCount', { count: unreadCount }) }}
      </span>
    </button>

    <SidePanel v-model:open="panelOpen" :title="t('notifications.title')" width="400px">
      <div class="notification-toolbar">
        <div class="notification-filters">
          <button
            type="button"
            class="notification-filter"
            :class="{ 'notification-filter--active': filter === 'unread' }"
            @click="setFilter('unread')"
          >
            {{ t('notifications.unread') }}<span v-if="unreadCount > 0">({{ badgeText }})</span>
          </button>
          <button
            type="button"
            class="notification-filter"
            :class="{ 'notification-filter--active': filter === 'all' }"
            @click="setFilter('all')"
          >
            {{ t('notifications.all') }}
          </button>
        </div>
        <div class="notification-actions">
          <button
            type="button"
            class="btn btn-ghost"
            :disabled="unreadCount === 0"
            @click="markAllRead"
          >
            {{ t('notifications.allRead') }}
          </button>
          <button
            type="button"
            class="notification-prefs"
            :aria-label="t('notifications.preferences')"
            :title="t('notifications.preferences')"
            @click="openPreferences"
          >
            <AppIcon name="sliders" :size="16" />
          </button>
        </div>
      </div>

      <p v-if="feedback" class="notification-feedback">{{ feedback }}</p>

      <SkeletonBlock v-if="loading" :rows="5" />
      <ErrorState
        v-else-if="loadFailed"
        :title="t('notifications.loadFailed')"
        :description="t('notifications.loadFailedHint')"
        @retry="load()"
      />
      <div v-else-if="items.length === 0" class="notification-empty">
        <AppIcon name="inbox" :size="24" />
        <p>{{ filter === 'unread' ? t('notifications.emptyUnread') : t('notifications.empty') }}</p>
      </div>

      <div v-else class="notification-groups">
        <section v-for="group in groups" :key="group.type" class="notification-group">
          <h3 class="notification-group__title">{{ typeLabel(group.type) }}</h3>
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            class="notification-item"
            :class="{ 'notification-item--unread': item.status !== 'read' }"
            :aria-label="item.message"
            @click="markRead(item)"
          >
            <span class="notification-item__dot" aria-hidden="true" />
            <span class="notification-item__body">
              <span class="notification-item__message">{{ item.message }}</span>
              <span class="notification-item__meta">
                <span>{{ formatTime(item.createdAt) }}</span>
                <span v-if="item.runKey">{{ t('notifications.viewRun') }}</span>
              </span>
            </span>
          </button>
        </section>

        <button
          v-if="canLoadMore"
          type="button"
          class="btn btn-ghost notification-more"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? t('notifications.loadingMore') : t('notifications.loadMore') }}
        </button>
      </div>
    </SidePanel>

    <NModal
      v-model:show="prefsOpen"
      preset="card"
      class="notification-prefs-modal"
      :title="t('notifications.preferencesTitle')"
      :style="{ maxWidth: '420px' }"
    >
      <p class="notification-prefs-hint">{{ t('notifications.preferencesHint') }}</p>
      <SkeletonBlock v-if="prefsLoading" :rows="4" />
      <div v-else class="notification-prefs-list">
        <label v-for="type in prefTypes" :key="type" class="notification-prefs-row">
          <span>{{ typeLabel(type) }}</span>
          <NSwitch v-model:value="prefs[type]" :disabled="prefsSaving" />
        </label>
      </div>
      <p v-if="prefsMessage" class="notification-prefs-success" role="status">{{ prefsMessage }}</p>
      <p v-if="prefsError" class="notification-prefs-error" role="alert">{{ prefsError }}</p>
      <template #footer>
        <div class="notification-prefs-footer">
          <button type="button" class="btn btn-ghost" @click="prefsOpen = false">
            {{ t('common.close') }}
          </button>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="prefsSaving || prefsLoading"
            @click="savePreferences"
          >
            {{ t('common.save') }}
          </button>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.notification-entry {
  display: inline-flex;
}

.notification-bell {
  position: relative;
}

.notification-badge {
  position: absolute;
  top: -4px;
  right: -6px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--fail);
  color: var(--accent-contrast);
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.notification-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.notification-filters {
  display: inline-flex;
  gap: var(--space-1);
}

.notification-filter {
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
  cursor: pointer;
}

.notification-filter--active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.notification-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.notification-prefs {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text-muted);
  cursor: pointer;
}

.notification-prefs:hover {
  color: var(--text);
  background: var(--surface-hover);
}

.notification-feedback {
  margin: 0 0 var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.notification-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-8) 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.notification-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.notification-group__title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-xs);
  font-weight: 500;
  color: var(--text-muted);
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-2) var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.notification-item:hover {
  background: var(--surface-hover);
}

.notification-item--unread {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.notification-item__dot {
  width: 6px;
  height: 6px;
  margin-top: 6px;
  border-radius: 50%;
  background: transparent;
  flex: none;
}

.notification-item--unread .notification-item__dot {
  background: var(--accent);
}

.notification-item__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.notification-item__message {
  font-size: var(--font-size-sm);
  color: var(--text);
  overflow-wrap: anywhere;
}

.notification-item__meta {
  display: flex;
  gap: var(--space-2);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.notification-more {
  align-self: center;
}

.notification-prefs-hint {
  margin: 0 0 var(--space-3);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.notification-prefs-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.notification-prefs-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
  font-size: var(--font-size-sm);
}

.notification-prefs-success {
  margin: var(--space-3) 0 0;
  color: var(--ok);
  font-size: var(--font-size-xs);
}

.notification-prefs-error {
  margin: var(--space-3) 0 0;
  color: var(--fail);
  font-size: var(--font-size-xs);
}

.notification-prefs-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
}
</style>
