<script setup lang="ts">
import { confirmAction } from '@/composables/useConfirm'
/**
 * 全局（平台）用例集（W57-09；v5.35 / FR-UIE-01~06 重构版式）。
 *
 * 与项目内 `/cases/sets` 是两套数据面：这里维护的是跨项目的公共样例集，
 * 端点统一在 `/api/platform/case-sets`。只读用户不渲染任何写按钮，
 * 后端仍会二次校验（前端只做显隐）。
 *
 * v5.35 版式变更：原先是 320px 中栏里塞 5 列网格，集名称与描述在 1440/1920
 * 都不可见，成员行的三个按钮被挤成一字一行（单行高 224px）。现在改为「全宽列表 + 右侧抽屉」：
 * 列表铺满内容区，每行只留一个主操作，次级动作收进「更多」；抽屉里放成员表与详情。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import CaseDetailPanel from '@/components/CaseDetailPanel.vue'
import { useMenuCapabilitiesStore } from '@/stores/menuCapabilities'
import { globalCaseApi } from '@/api/globalCases'
import {
  platformCaseSetApi,
  type PlatformCaseSet,
  type PlatformCaseSetItem,
} from '@/api/platformCaseSets'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const route = useRoute()
const router = useRouter()

const capabilities = useMenuCapabilitiesStore()
/**
 * 写入口显隐与服务端同一道闸：capability 的 case:write 直接映射到 CASE:UPDATE。
 * 原来按「角色里有 admin」判，导致 PM 后端能写、前端却没入口（复核 P1-1）。
 */
const canWrite = computed(() => capabilities.has('case:write'))

const sets = ref<PlatformCaseSet[]>([])
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const message = ref('')
const saving = ref(false)

const formOpen = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ setCode: '', name: '', description: '' })

const activeSet = ref<PlatformCaseSet | null>(null)
const items = ref<PlatformCaseSetItem[]>([])
const itemsLoading = ref(false)

/** 行内「更多」菜单：同一时刻只开一个。 */
const setMenuId = ref<number | null>(null)
const memberMenuId = ref<number | null>(null)

/** 成员详情：抽屉里再展开一块，用统一详情面板（三类来源同一字段契约）。 */
const detailId = ref<number | null>(null)

/** 添加成员选择器：不再让用户手填数字用例 ID。 */
const pickerOpen = ref(false)
const pickerKeyword = ref('')
const pickerLoading = ref(false)
const pickerRows = ref<{ id: number; caseCode: string; title: string; categoryL1: string | null }[]>([])
const pickerPicked = ref<number[]>([])
const pickerSubmitting = ref(false)
const pickerTotal = ref(0)

function fail(e: unknown): string {
  return e instanceof Error ? e.message : t('w57.globalCaseSets.opFailed')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    sets.value = await platformCaseSetApi.list({ keyword: keyword.value.trim() || undefined, page: 1, size: 100 })
  } catch (e) {
    error.value = fail(e)
    sets.value = []
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  editingId.value = null
  form.value = { setCode: '', name: '', description: '' }
}

function openCreate(): void {
  resetForm()
  formOpen.value = true
}

function openEdit(row: PlatformCaseSet): void {
  editingId.value = row.id
  form.value = { setCode: row.setCode, name: row.name, description: row.description ?? '' }
  formOpen.value = true
  activeSet.value = null
  setMenuId.value = null
}

function closeForm(): void {
  formOpen.value = false
  resetForm()
}

const canSubmit = computed(
  () => form.value.setCode.trim().length > 0 && form.value.name.trim().length > 0 && !saving.value,
)

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const description = form.value.description.trim() || undefined
    if (editingId.value === null) {
      await platformCaseSetApi.create({
        setCode: form.value.setCode.trim(),
        name: form.value.name.trim(),
        description,
      })
      message.value = t('w57.globalCaseSets.created')
    } else {
      await platformCaseSetApi.update(editingId.value, {
        name: form.value.name.trim(),
        description,
      })
      message.value = t('w57.globalCaseSets.updated')
    }
    closeForm()
    await load()
  } catch (e) {
    error.value = fail(e)
  } finally {
    saving.value = false
  }
}

async function removeSet(row: PlatformCaseSet): Promise<void> {
  error.value = ''
  message.value = ''
  setMenuId.value = null
  // v5.9.0 / W59-20：删除前二次确认，并且把「多少条关联会解除、用例本身不受影响」说清楚。
  const ok = await confirmAction(
    t('w57.globalCaseSets.confirmDelete', { name: row.name, count: row.itemCount }),
    { tone: 'danger' },
  )
  if (!ok) return
  try {
    await platformCaseSetApi.remove(row.id)
    message.value = t('w57.globalCaseSets.deleted', { name: row.name })
    if (activeSet.value?.id === row.id) activeSet.value = null
    if (editingId.value === row.id) closeForm()
    await load()
  } catch (e) {
    error.value = fail(e)
  }
}

async function seedSamples(): Promise<void> {
  error.value = ''
  message.value = ''
  saving.value = true
  try {
    await platformCaseSetApi.seedSamples(true)
    message.value = t('w57.globalCaseSets.seedDone')
    await load()
  } catch (e) {
    error.value = fail(e)
  } finally {
    saving.value = false
  }
}

async function loadItems(): Promise<void> {
  if (!activeSet.value) return
  itemsLoading.value = true
  error.value = ''
  try {
    items.value = await platformCaseSetApi.items(activeSet.value.id)
  } catch (e) {
    error.value = fail(e)
    items.value = []
  } finally {
    itemsLoading.value = false
  }
}

async function openItems(row: PlatformCaseSet): Promise<void> {
  activeSet.value = row
  detailId.value = null
  setMenuId.value = null
  memberMenuId.value = null
  await loadItems()
  syncQuery()
}

function closeItems(): void {
  activeSet.value = null
  items.value = []
  detailId.value = null
  memberMenuId.value = null
  syncQuery()
}

function syncQuery(): void {
  const query: LocationQueryRaw = { ...route.query }
  if (activeSet.value) query.set = String(activeSet.value.id)
  else delete query.set
  if (detailId.value) query.detail = String(detailId.value)
  else delete query.detail
  void router.replace({ query })
}

function toggleSetMenu(id: number): void {
  setMenuId.value = setMenuId.value === id ? null : id
}

function toggleMemberMenu(id: number): void {
  memberMenuId.value = memberMenuId.value === id ? null : id
}

function openMember(item: PlatformCaseSetItem): void {
  detailId.value = detailId.value === item.id ? null : item.id
  syncQuery()
}

function closeMember(): void {
  detailId.value = null
  syncQuery()
}

onMounted(() => {
  const raw = route.query.set
  if (typeof raw !== 'string') return
  const id = Number(raw)
  if (!Number.isFinite(id)) return
  const hit = sets.value.find((row) => row.id === id)
  if (hit) {
    void openItems(hit).then(() => {
      const pick = route.query.detail
      if (typeof pick === 'string') detailId.value = Number(pick)
    })
  }
})

// ------------------------------------------------------------------ 选择器

async function openPicker(): Promise<void> {
  if (!activeSet.value) return
  pickerOpen.value = true
  pickerKeyword.value = ''
  pickerPicked.value = []
  await loadPicker()
}

async function loadPicker(): Promise<void> {
  pickerLoading.value = true
  try {
    const page = await globalCaseApi.page({
      keyword: pickerKeyword.value.trim() || undefined,
      lifecycleStatus: 'active',
      page: 1,
      size: 50,
    })
    pickerRows.value = page.items.map((row) => ({
      id: row.id,
      caseCode: row.caseCode ?? '',
      title: row.title ?? '',
      categoryL1: row.categoryL1 ?? null,
    }))
    pickerTotal.value = page.total
  } catch (e) {
    error.value = fail(e)
    pickerRows.value = []
  } finally {
    pickerLoading.value = false
  }
}

function togglePick(id: number): void {
  const at = pickerPicked.value.indexOf(id)
  if (at >= 0) pickerPicked.value.splice(at, 1)
  else pickerPicked.value.push(id)
}

async function confirmPicker(): Promise<void> {
  if (!activeSet.value || pickerPicked.value.length === 0) return
  pickerSubmitting.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await platformCaseSetApi.addItems(activeSet.value.id, pickerPicked.value)
    message.value = t('w57.globalCaseSets.pickerDone', {
      added: result.added,
      skipped: result.skipped,
    })
    pickerOpen.value = false
    await Promise.all([loadItems(), load()])
  } catch (e) {
    error.value = fail(e)
  } finally {
    pickerSubmitting.value = false
  }
}

// ------------------------------------------------------------------ 成员操作

async function removeItem(caseId: number): Promise<void> {
  if (!activeSet.value) return
  memberMenuId.value = null
  if (!(await confirmAction(t('w62.confirmRemoveItems', { count: 1 })))) return
  error.value = ''
  try {
    await platformCaseSetApi.removeItem(activeSet.value.id, caseId)
    if (detailId.value === caseId) closeMember()
    await Promise.all([loadItems(), load()])
  } catch (e) {
    error.value = fail(e)
  }
}

async function move(index: number, delta: number): Promise<void> {
  if (!activeSet.value) return
  memberMenuId.value = null
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const next = [...items.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  items.value = next
  error.value = ''
  try {
    await platformCaseSetApi.reorder(
      activeSet.value.id,
      next.map((item) => item.id),
    )
  } catch (e) {
    error.value = fail(e)
    await loadItems()
  }
}

const detailItem = computed<PlatformCaseSetItem | null>(
  () => items.value.find((row) => row.id === detailId.value) ?? null,
)

onMounted(() => void load())
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div class="head-text">
        <h1 class="page-title">{{ t('w57.globalCaseSets.title') }}</h1>
        <p class="muted">{{ t('w57.globalCaseSets.subtitle') }}</p>
      </div>
      <div v-if="canWrite" class="page-actions">
        <button type="button" class="btn" data-testid="seed-btn" :disabled="saving" @click="seedSamples">
          {{ t('w57.globalCaseSets.seed') }}
        </button>
        <button type="button" class="btn btn-primary" data-testid="create-btn" @click="openCreate">{{ t('w57.globalCaseSets.create') }}</button>
      </div>
    </header>

    <div class="toolbar">
      <input
        v-model="keyword"
        class="input"
        type="search"
        :placeholder="t('w57.globalCaseSets.searchPlaceholder')"
        :aria-label="t('w57.globalCaseSets.searchAria')"
        data-testid="search-input"
        @keyup.enter="load"
      />
      <button type="button" class="btn" data-testid="search-btn" @click="load">{{ t('w57.globalCaseSets.search') }}</button>
      <button type="button" class="btn" :disabled="loading" @click="load">{{ t('w57.globalCaseSets.refresh') }}</button>
      <span class="muted small" data-testid="set-total">{{ t('w57.globalCaseSets.setTotal', { count: sets.length }) }}</span>
    </div>

    <p v-if="error" class="error" data-testid="error">{{ error }}</p>
    <p v-if="message" class="success" data-testid="message">{{ message }}</p>

    <form v-if="formOpen && canWrite" class="card create-form" data-testid="create-form" @submit.prevent="submit">
      <h2 class="card-title">{{ editingId === null ? t('w57.globalCaseSets.create') : t('w57.globalCaseSets.editTitle') }}</h2>
      <label class="field">
        <span>{{ t('w57.globalCaseSets.code') }}</span>
        <input v-model="form.setCode" class="input" data-testid="input-set-code" :disabled="editingId !== null" />
      </label>
      <label class="field">
        <span>{{ t('w57.globalCaseSets.name') }}</span>
        <input v-model="form.name" class="input" data-testid="input-set-name" />
      </label>
      <label class="field">
        <span>{{ t('w57.globalCaseSets.desc') }}</span>
        <input v-model="form.description" class="input" data-testid="input-set-desc" />
      </label>
      <div class="toolbar">
        <button type="submit" class="btn btn-primary" data-testid="submit-create" :disabled="!canSubmit">{{ t('w57.globalCaseSets.save') }}</button>
        <button type="button" class="btn" @click="closeForm">{{ t('w57.globalCaseSets.cancel') }}</button>
      </div>
    </form>

    <div v-if="loading" class="muted" data-testid="loading">{{ t('w57.globalCaseSets.loading') }}</div>

    <table v-else-if="sets.length" class="list" data-testid="case-set-list">
      <thead>
        <tr>
          <th class="col-code">{{ t('w57.globalCaseSets.colCode') }}</th>
          <th>{{ t('w57.globalCaseSets.colName') }}</th>
          <th class="col-desc">{{ t('w57.globalCaseSets.colDesc') }}</th>
          <th class="col-count">{{ t('w57.globalCaseSets.colCount') }}</th>
          <th class="col-actions">{{ t('common.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in sets"
          :key="row.id"
          class="list-row"
          :class="{ active: activeSet?.id === row.id }"
          :data-testid="`case-set-row-${row.id}`"
          @click="openItems(row)"
        >
          <td class="col-code mono" :title="row.setCode">{{ row.setCode }}</td>
          <td class="cell-ellipsis" :title="row.name">{{ row.name }}</td>
          <td class="col-desc cell-ellipsis muted" :title="row.description ?? ''">{{ row.description || '—' }}</td>
          <td class="col-count muted">{{ t('w57.globalCaseSets.itemCount', { count: row.itemCount }) }}</td>
          <td class="col-actions row-actions" @click.stop>
            <button type="button" class="btn small primary" :data-testid="`manage-${row.id}`" @click="openItems(row)">
              {{ t('w57.globalCaseSets.members') }}
            </button>
            <span v-if="canWrite" class="more-wrap">
              <button type="button" class="btn small" :data-testid="`more-${row.id}`" @click="toggleSetMenu(row.id)">
                {{ t('w57.globalCaseSets.more') }}
              </button>
              <span v-if="setMenuId === row.id" class="menu">
                <button type="button" class="menu-item" :data-testid="`edit-${row.id}`" @click="openEdit(row)">
                  {{ t('w57.globalCaseSets.rename') }}
                </button>
                <button type="button" class="menu-item danger" :data-testid="`delete-${row.id}`" @click="removeSet(row)">
                  {{ t('w57.globalCaseSets.delete') }}
                </button>
              </span>
            </span>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else class="empty-state" data-testid="empty-state">
      <p class="empty-title">{{ t('w57.globalCaseSets.empty') }}</p>
      <p class="muted">{{ t('w57.globalCaseSets.emptyHint') }}</p>
      <button v-if="canWrite" type="button" class="btn btn-primary" @click="openCreate">
        {{ t('w57.globalCaseSets.create') }}
      </button>
    </div>

    <!-- 抽屉：成员表 + 详情 -->
    <div v-if="activeSet" class="drawer-mask" data-testid="drawer-mask" @click="closeItems" />
    <aside
      v-if="activeSet"
      class="drawer"
      data-testid="member-panel"
      role="dialog"
      aria-modal="true"
      @keydown.esc="closeItems"
    >
      <header class="drawer-head">
        <div class="drawer-title-wrap">
          <h2 class="drawer-title">
            {{ activeSet.name }}
            <span class="mono muted small">{{ activeSet.setCode }}</span>
          </h2>
          <p class="muted small">
            {{ t('w57.globalCaseSets.memberTitle', { name: activeSet.name, count: items.length }) }}
            · {{ t('w57.globalCaseSets.platformScriptNote') }}
          </p>
        </div>
        <div class="drawer-actions">
          <button v-if="canWrite" type="button" class="btn small primary" data-testid="add-case-btn" @click="openPicker">
            {{ t('w57.globalCaseSets.addCase') }}
          </button>
          <button type="button" class="btn small" data-testid="close-members" @click="closeItems">
            {{ t('w57.globalCaseSets.collapse') }}
          </button>
        </div>
      </header>

      <p v-if="itemsLoading" class="muted">{{ t('w57.globalCaseSets.loading') }}</p>
      <p v-else-if="items.length === 0" class="muted">{{ t('w57.globalCaseSets.noMember') }}</p>
      <table v-else class="member-table" data-testid="member-table">
        <thead>
          <tr>
            <th class="col-seq">#</th>
            <th class="col-code">{{ t('w57.globalCaseSets.colCode') }}</th>
            <th>{{ t('w57.globalCaseSets.colName') }}</th>
            <th class="col-cat">{{ t('w57.globalCaseSets.colCategory') }}</th>
            <th class="col-actions">{{ t('common.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(item, index) in items"
            :key="item.id"
            class="member-row"
            :class="{ active: detailId === item.id }"
            :data-testid="`member-row-${item.id}`"
          >
            <td class="col-seq muted">{{ index + 1 }}</td>
            <td class="col-code mono" @click="openMember(item)">{{ item.caseCode }}</td>
            <td class="cell-ellipsis" :title="item.title" @click="openMember(item)">{{ item.title }}</td>
            <td class="col-cat muted">{{ item.categoryL1 || t('w57.globalCaseSets.uncategorized') }}</td>
            <td class="col-actions row-actions" @click.stop>
              <button type="button" class="btn small" data-testid="member-detail-btn" @click="openMember(item)">
                {{ detailId === item.id ? t('w57.globalCaseSets.collapse') : t('w57.globalCaseSets.detail') }}
              </button>
              <span v-if="canWrite" class="more-wrap">
                <button type="button" class="btn small" :data-testid="`member-more-${item.id}`" @click="toggleMemberMenu(item.id)">
                  {{ t('w57.globalCaseSets.more') }}
                </button>
                <span v-if="memberMenuId === item.id" class="menu">
                  <button type="button" class="menu-item" :disabled="index === 0" :data-testid="`move-up-${item.id}`" @click="move(index, -1)">
                    {{ t('w57.globalCaseSets.moveUp') }}
                  </button>
                  <button type="button" class="menu-item" :disabled="index === items.length - 1" :data-testid="`move-down-${item.id}`" @click="move(index, 1)">
                    {{ t('w57.globalCaseSets.moveDown') }}
                  </button>
                  <button type="button" class="menu-item danger" :data-testid="`remove-item-${item.id}`" @click="removeItem(item.id)">
                    {{ t('w57.globalCaseSets.remove') }}
                  </button>
                </span>
              </span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 详情做成浮层：它要展示六组、二十多个字段，塞在 40 行成员表之后
           用户点了只会看到按钮变「收起」，以为没反应（实测）。 -->
      <div v-if="detailItem" class="detail-mask" data-testid="member-detail" @click.self="closeMember">
        <section class="detail-modal" role="dialog" aria-modal="true">
          <header class="member-detail-head">
            <span class="mono">{{ detailItem.caseCode }}</span>
            <span class="cell-ellipsis">{{ detailItem.title }}</span>
            <button type="button" class="btn small" @click="closeMember">{{ t('common.close') }}</button>
          </header>
          <CaseDetailPanel :source="'global'" :case-id="detailItem.id" />
        </section>
      </div>
    </aside>

    <!-- 添加成员选择器 -->
    <div v-if="pickerOpen" class="modal-mask" @click.self="pickerOpen = false">
      <div class="modal" data-testid="member-picker" role="dialog" aria-modal="true">
        <header class="drawer-head">
          <h3 class="card-title">{{ t('w57.globalCaseSets.pickerTitle') }}</h3>
          <button type="button" class="btn small" @click="pickerOpen = false">{{ t('common.close') }}</button>
        </header>
        <div class="toolbar">
          <input
            v-model="pickerKeyword"
            class="input"
            type="search"
            :placeholder="t('w57.globalCaseSets.pickerSearch')"
            @keyup.enter="loadPicker"
          />
          <button type="button" class="btn" @click="loadPicker">{{ t('w57.globalCaseSets.search') }}</button>
          <span class="muted small">{{ t('w57.globalCaseSets.pickerHint', { total: pickerTotal }) }}</span>
        </div>
        <p v-if="pickerLoading" class="muted">{{ t('w57.globalCaseSets.loading') }}</p>
        <ul v-else-if="pickerRows.length" class="picker-list">
          <li v-for="row in pickerRows" :key="row.id" class="picker-row">
            <label>
              <input
                type="checkbox"
                :checked="pickerPicked.includes(row.id)"
                @change="togglePick(row.id)"
              />
              <span class="mono">{{ row.caseCode }}</span>
              <span class="cell-ellipsis">{{ row.title }}</span>
              <span class="muted small">{{ row.categoryL1 || '—' }}</span>
            </label>
          </li>
        </ul>
        <p v-else class="muted">{{ t('w57.globalCaseSets.pickerEmpty') }}</p>
        <footer class="toolbar">
          <span class="muted small">{{ t('w57.globalCaseSets.pickerSelected', { count: pickerPicked.length }) }}</span>
          <button
            type="button"
            class="btn btn-primary"
            data-testid="picker-confirm"
            :disabled="pickerPicked.length === 0 || pickerSubmitting"
            @click="confirmPicker"
          >
            {{ t('w57.globalCaseSets.pickerConfirm') }}
          </button>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.page-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.head-text {
  min-width: 0;
  flex: 1 1 320px;
}

.page-title {
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.page-actions,
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.toolbar {
  margin: var(--space-3) 0;
}

.input {
  min-width: 0;
  max-width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  box-sizing: border-box;
}

.card {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.card-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: 520px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

/* ---- 全宽列表 ---- */
.list,
.member-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.list th,
.member-table th {
  text-align: left;
  font-weight: 600;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
}

.list td,
.member-table td {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  font-size: var(--font-size-sm);
  vertical-align: middle;
}

.list-row,
.member-row {
  cursor: pointer;
}

.list-row:hover,
.member-row:hover {
  background: var(--surface-2);
}

.list-row.active,
.member-row.active {
  background: var(--surface-2);
  box-shadow: inset 3px 0 0 var(--accent);
}

.col-code {
  width: 9.5rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.col-desc { width: 30%; }
.col-count { width: 7rem; }
.col-actions { width: 13rem; white-space: nowrap; }
.col-seq { width: 3rem; }
.col-cat { width: 9rem; }

.cell-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--font-mono, monospace);
}

/* ---- 行内「更多」 ---- */
.more-wrap {
  position: relative;
  display: inline-block;
  margin-left: var(--space-2);
}

.menu {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  z-index: 30;
  min-width: 7rem;
  display: flex;
  flex-direction: column;
  padding: 4px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
  box-shadow: 0 8px 24px rgb(0 0 0 / 24%);
}

.menu-item {
  text-align: left;
  padding: 6px 8px;
  border: none;
  background: none;
  color: var(--text);
  cursor: pointer;
  font: inherit;
  font-size: var(--font-size-sm);
  border-radius: var(--radius-sm);
}

.menu-item:hover {
  background: var(--surface-2);
}

.menu-item:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.menu-item.danger {
  color: var(--fail);
}

/* ---- 抽屉 ---- */
.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: rgb(0 0 0 / 40%);
}

.drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1201;
  width: min(1080px, 92vw);
  box-sizing: border-box;
  padding: var(--space-4);
  overflow: auto;
  background: var(--panel);
  border-left: 1px solid var(--border-strong);
  box-shadow: -12px 0 32px rgb(0 0 0 / 28%);
}

.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  padding-bottom: var(--space-2);
  margin-bottom: var(--space-3);
  border-bottom: 1px solid var(--border);
}

.drawer-title-wrap {
  min-width: 0;
}

.drawer-title {
  margin: 0 0 4px;
  font-size: var(--font-size-md);
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.drawer-actions {
  display: flex;
  gap: var(--space-2);
  flex: none;
}

.detail-mask {
  position: fixed;
  inset: 0;
  z-index: 1310;
  background: rgb(0 0 0 / 48%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-modal {
  width: min(52rem, 92vw);
  max-height: 86vh;
  overflow: auto;
  padding: var(--space-4);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.member-detail-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--border);
}

/* ---- 选择器 ---- */
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  background: rgb(0 0 0 / 44%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal {
  width: min(760px, 92vw);
  max-height: 82vh;
  overflow: auto;
  padding: var(--space-4);
  border-radius: var(--radius-md);
  background: var(--panel);
  border: 1px solid var(--border-strong);
}

.picker-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 44vh;
  overflow: auto;
}

.picker-row label {
  display: grid;
  grid-template-columns: 1.5rem 9rem minmax(0, 1fr) 8rem;
  gap: var(--space-2);
  align-items: center;
  padding: 6px 4px;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  font-size: var(--font-size-sm);
}

.muted {
  color: var(--text-muted);
}

.small {
  font-size: var(--font-size-sm);
}

.error {
  color: var(--fail);
}

.success {
  color: var(--ok);
}

.empty-state {
  margin-top: var(--space-4);
  padding: var(--space-5);
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.empty-title {
  font-weight: 600;
  margin: 0;
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn.small {
  padding: 3px 8px;
  font-size: var(--font-size-sm);
}

.btn.primary {
  border-color: var(--accent);
  color: var(--accent);
}

.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.btn.danger {
  color: var(--fail);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 900px) {
  .col-desc,
  .col-count {
    display: none;
  }
}
</style>
