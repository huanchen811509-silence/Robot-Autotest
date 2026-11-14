<script setup lang="ts">
/**
 * 全局（平台）用例集（W57-09）。
 *
 * 与项目内 `/cases/sets` 是两套数据面：这里维护的是跨项目的公共样例集，
 * 端点统一在 `/api/platform/case-sets`。只读用户不渲染任何写按钮，
 * 后端仍会二次校验（前端只做显隐）。
 */
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  platformCaseSetApi,
  type PlatformCaseSet,
  type PlatformCaseSetItem,
} from '@/api/platformCaseSets'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const auth = useAuthStore()
const canWrite = computed(() => auth.isAdmin)

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
const newCaseId = ref<number | null>(null)

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
  newCaseId.value = null
  await loadItems()
}

function closeItems(): void {
  activeSet.value = null
  items.value = []
  newCaseId.value = null
}

async function addItem(): Promise<void> {
  if (!activeSet.value || newCaseId.value === null) return
  error.value = ''
  try {
    await platformCaseSetApi.addItem(activeSet.value.id, newCaseId.value)
    newCaseId.value = null
    await loadItems()
  } catch (e) {
    error.value = fail(e)
  }
}

async function removeItem(caseId: number): Promise<void> {
  if (!activeSet.value) return
  error.value = ''
  try {
    await platformCaseSetApi.removeItem(activeSet.value.id, caseId)
    await loadItems()
  } catch (e) {
    error.value = fail(e)
  }
}

async function move(index: number, delta: number): Promise<void> {
  if (!activeSet.value) return
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

    <ul v-else-if="sets.length" class="set-list" data-testid="case-set-list">
      <li v-for="row in sets" :key="row.id" class="set-row" :data-testid="`case-set-row-${row.id}`">
        <span class="set-code" :title="row.setCode">{{ row.setCode }}</span>
        <span class="set-name" :title="row.name">{{ row.name }}</span>
        <span class="set-desc" :title="row.description ?? ''">{{ row.description || '—' }}</span>
        <span class="muted small">{{ t('w57.globalCaseSets.itemCount', { count: row.itemCount }) }}</span>
        <span class="row-actions">
          <button type="button" class="btn small" :data-testid="`manage-${row.id}`" @click="openItems(row)">{{ t('w57.globalCaseSets.members') }}</button>
          <template v-if="canWrite">
            <button type="button" class="btn small" :data-testid="`edit-${row.id}`" @click="openEdit(row)">{{ t('w57.globalCaseSets.rename') }}</button>
            <button type="button" class="btn small danger" :data-testid="`delete-${row.id}`" @click="removeSet(row)">{{ t('w57.globalCaseSets.delete') }}</button>
          </template>
        </span>
      </li>
    </ul>

    <div v-else class="empty-state" data-testid="empty-state">
      <p class="empty-title">{{ t('w57.globalCaseSets.empty') }}</p>
      <p class="muted">{{ t('w57.globalCaseSets.emptyHint') }}</p>
    </div>

    <div v-if="activeSet" class="card member-panel" data-testid="member-panel">
      <div class="member-head">
        <h2 class="card-title">{{ t('w57.globalCaseSets.memberTitle', { name: activeSet.name, count: items.length }) }}</h2>
        <button type="button" class="btn small" data-testid="close-members" @click="closeItems">{{ t('w57.globalCaseSets.collapse') }}</button>
      </div>

      <p v-if="itemsLoading" class="muted">{{ t('w57.globalCaseSets.loading') }}</p>
      <template v-else>
        <div v-if="canWrite" class="toolbar">
          <input
            v-model.number="newCaseId"
            class="input small-input"
            type="number"
            min="1"
            :placeholder="t('w57.globalCaseSets.caseId')"
            :aria-label="t('w57.globalCaseSets.caseId')"
            data-testid="add-case-input"
          />
          <button type="button" class="btn" data-testid="add-case-btn" :disabled="newCaseId === null" @click="addItem">
            {{ t('w57.globalCaseSets.addCase') }}
          </button>
        </div>
        <p v-if="items.length === 0" class="muted">{{ t('w57.globalCaseSets.noMember') }}</p>
        <ul v-else class="member-list">
          <li v-for="(item, index) in items" :key="item.id" class="member-row" :data-testid="`member-row-${item.id}`">
            <span class="order">{{ index + 1 }}</span>
            <span class="mono">{{ item.caseCode }}</span>
            <span class="set-name" :title="item.title">{{ item.title }}</span>
            <span class="muted small">{{ item.categoryL1 || t('w57.globalCaseSets.uncategorized') }}</span>
            <span v-if="canWrite" class="row-actions">
              <button type="button" class="btn small" :disabled="index === 0" :data-testid="`move-up-${item.id}`" @click="move(index, -1)">{{ t('w57.globalCaseSets.moveUp') }}</button>
              <button type="button" class="btn small" :disabled="index === items.length - 1" :data-testid="`move-down-${item.id}`" @click="move(index, 1)">{{ t('w57.globalCaseSets.moveDown') }}</button>
              <button type="button" class="btn small danger" :data-testid="`remove-item-${item.id}`" @click="removeItem(item.id)">{{ t('w57.globalCaseSets.remove') }}</button>
            </span>
          </li>
        </ul>
      </template>
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
.toolbar,
.row-actions {
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

.small-input {
  width: 140px;
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
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
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

.set-list,
.member-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.set-row,
.member-row {
  display: grid;
  grid-template-columns: 8rem minmax(0, 1.2fr) minmax(0, 1.4fr) 4rem auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  margin-bottom: 6px;
}

.member-row {
  grid-template-columns: 2.5rem 9rem minmax(0, 1fr) 8rem auto;
}

.set-code,
.set-name,
.set-desc,
.mono,
.order {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: var(--font-size-sm);
}

.order {
  color: var(--text-muted);
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
}

.empty-title {
  font-weight: 600;
  margin: 0 0 6px;
}

.member-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
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

@media (max-width: 1080px) {
  .set-row,
  .member-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .set-desc,
  .member-row .muted {
    display: none;
  }
}
</style>
