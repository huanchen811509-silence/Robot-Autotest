<script setup lang="ts">
/**
 * 模板中心（v4.8.0 / FR-v4.8-01..09、v4.8.1 / FR-v4.8-11..13）。
 *
 * 全局二级菜单入口：列表/筛选/启停/复制/删除；内置模板只读（可停用不可删）；
 * 共享范围 private / team / public，只有管理员可提升为 public。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { templateApi, type TemplateRequest, type TemplateRow } from '@/api/globalTemplates'

const { t } = useI18n()
const auth = useAuthStore()

const rows = ref<TemplateRow[]>([])
const keyword = ref('')
const visibility = ref('')
const loading = ref(false)
const failed = ref('')
const canPromote = computed(() => auth.isAdmin)

async function load() {
  loading.value = true
  failed.value = ''
  try {
    rows.value = await templateApi.list({ keyword: keyword.value, visibility: visibility.value })
  } catch {
    failed.value = t('global.loadFailed')
  } finally {
    loading.value = false
  }
}

const dialogOpen = ref(false)
const editing = ref<TemplateRow | null>(null)
const form = reactive<TemplateRequest>({
  name: '',
  code: '',
  kernel: 'robot',
  visibility: 'team',
  description: '',
})
const saving = ref(false)
const dialogError = ref('')

function openCreate() {
  editing.value = null
  Object.assign(form, {
    name: '',
    code: '',
    kernel: 'robot',
    visibility: 'team',
    description: '',
  })
  dialogError.value = ''
  dialogOpen.value = true
}

function openEdit(row: TemplateRow) {
  if (row.builtin) return
  editing.value = row
  Object.assign(form, {
    name: row.name,
    code: row.code,
    kernel: row.kernel,
    visibility: row.visibility,
    description: row.description,
  })
  dialogError.value = ''
  dialogOpen.value = true
}

async function save() {
  saving.value = true
  dialogError.value = ''
  try {
    if (editing.value) {
      await templateApi.update(editing.value.id, { ...form, code: undefined })
    } else {
      await templateApi.create(form)
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    dialogError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: TemplateRow) {
  await templateApi.setStatus(row.id, row.status === 'active' ? 'disabled' : 'active')
  await load()
}

async function copyTemplate(row: TemplateRow) {
  await templateApi.copy(row.id)
  await load()
}

async function removeTemplate(row: TemplateRow) {
  if (!window.confirm(t('global.templateDeleteConfirm', { name: row.name }))) return
  try {
    await templateApi.remove(row.id)
    await load()
  } catch (error) {
    window.alert(error instanceof Error ? error.message : t('global.loadFailed'))
  }
}

function visibilityLabel(value: string) {
  if (value === 'private') return t('global.visibilityPrivate')
  if (value === 'public') return t('global.visibilityPublic')
  return t('global.visibilityTeam')
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('global.templatesTitle') }}</h1>
        <p class="muted">{{ t('global.templatesHint') }}</p>
      </div>
      <button class="btn btn-primary" @click="openCreate">{{ t('global.templateCreate') }}</button>
    </header>

    <div class="filters">
      <input
        v-model="keyword"
        class="input"
        :placeholder="t('global.searchPlaceholder')"
        @keyup.enter="load"
      />
      <select v-model="visibility" class="select" @change="load">
        <option value="">{{ t('global.allVisibility') }}</option>
        <option value="private">{{ t('global.visibilityPrivate') }}</option>
        <option value="team">{{ t('global.visibilityTeam') }}</option>
        <option value="public">{{ t('global.visibilityPublic') }}</option>
      </select>
    </div>

    <p v-if="failed" class="error">{{ failed }}</p>
    <div v-if="loading" class="muted">{{ t('global.loading') }}</div>

    <div v-else class="template-grid">
      <article v-for="row in rows" :key="row.id" class="template-card">
        <header class="card-head">
          <strong>{{ row.name }}</strong>
          <span v-if="row.builtin" class="badge builtin">{{ t('global.templateBuiltin') }}</span>
          <span class="badge" :class="row.status">{{ row.status }}</span>
        </header>
        <p class="mono muted">{{ row.code }}</p>
        <p class="muted small">
          {{ row.kernel }} · {{ visibilityLabel(row.visibility) }} ·
          {{ t('global.templateItems', { count: row.itemCount }) }}
        </p>
        <p class="desc">{{ row.description }}</p>
        <footer class="card-actions">
          <button v-if="!row.builtin" class="link" @click="openEdit(row)">
            {{ t('global.templateEdit') }}
          </button>
          <button class="link" @click="toggleStatus(row)">
            {{ row.status === 'active' ? t('global.templateDisable') : t('global.templateEnable') }}
          </button>
          <button class="link" @click="copyTemplate(row)">{{ t('global.templateCopy') }}</button>
          <button v-if="!row.builtin" class="link danger" @click="removeTemplate(row)">
            {{ t('global.templateDelete') }}
          </button>
        </footer>
      </article>
      <p v-if="rows.length === 0" class="muted empty">{{ t('global.empty') }}</p>
    </div>

    <div v-if="dialogOpen" class="modal-mask" @click.self="dialogOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ editing ? t('global.templateEdit') : t('global.templateCreate') }}</h2>
          <button class="link" @click="dialogOpen = false">{{ t('global.close') }}</button>
        </header>
        <label class="field">
          <span>{{ t('global.templateName') }}</span>
          <input v-model="form.name" class="input" />
        </label>
        <label class="field">
          <span>{{ t('global.templateCode') }}</span>
          <input
            v-model="form.code"
            class="input mono"
            :disabled="!!editing"
            placeholder="API-RF-202611-001"
          />
        </label>
        <label class="field">
          <span>{{ t('global.templateKernel') }}</span>
          <select v-model="form.kernel" class="select">
            <option value="robot">robot</option>
            <option value="pytest">pytest</option>
          </select>
        </label>
        <label class="field">
          <span>{{ t('global.templateVisibility') }}</span>
          <select v-model="form.visibility" class="select">
            <option value="private">{{ t('global.visibilityPrivate') }}</option>
            <option value="team">{{ t('global.visibilityTeam') }}</option>
            <option v-if="canPromote" value="public">{{ t('global.visibilityPublic') }}</option>
          </select>
        </label>
        <label class="field">
          <span>{{ t('global.templateDescription') }}</span>
          <textarea v-model="form.description" class="input" rows="3" />
        </label>
        <p v-if="dialogError" class="error">{{ dialogError }}</p>
        <div class="modal-actions">
          <button class="btn" @click="dialogOpen = false">{{ t('global.close') }}</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">
            {{ t('global.save') }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.filters {
  display: flex;
  gap: var(--space-2);
  margin: var(--space-3) 0;
}

.input,
.select {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

.input {
  min-width: 200px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-3);
}

.template-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--panel);
}

.card-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid var(--border);
}

.badge.active {
  color: var(--ok);
}

.badge.disabled {
  color: var(--text-muted);
}

.badge.builtin {
  color: var(--accent, #2563eb);
}

.mono {
  font-family: var(--font-mono, monospace);
}

.small {
  font-size: 12px;
}

.desc {
  font-size: 13px;
  min-height: 36px;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.empty {
  grid-column: 1 / -1;
  text-align: center;
  padding: var(--space-4);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal {
  width: min(560px, 92vw);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: var(--space-2);
  font-size: 13px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.error {
  color: var(--fail);
}

.link {
  border: 0;
  background: transparent;
  color: var(--accent, #2563eb);
  cursor: pointer;
  padding: 0;
}

.link.danger {
  color: var(--fail);
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn-primary {
  background: var(--accent, #2563eb);
  border-color: var(--accent, #2563eb);
  color: #fff;
}
</style>
