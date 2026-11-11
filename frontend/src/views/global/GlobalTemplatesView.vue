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
import { templateProjectsApi, type TemplateItemRow } from '@/api/templateProjects'
import TemplateCard from '@/components/TemplateCard.vue'

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

/** FR-49-20：新建模板至少要关联 1 条用例或 1 个用例集。 */
const itemDraft = ref<TemplateItemRow[]>([])
const draftType = ref<'CASE' | 'CASE_SET'>('CASE')
const draftRefId = ref('')
const draftName = ref('')

function addDraftItem(): void {
  const name = draftName.value.trim()
  if (!name) return
  // 后端 insertItems 跳过 refId==null 的明细：引用项必须带引用 ID，否则新建后 itemCount 仍为 0。
  const refId = Number(draftRefId.value.trim())
  if (!draftRefId.value.trim() || !Number.isFinite(refId) || refId <= 0) {
    dialogError.value = t("templateCenter.refIdRequired")
    return
  }
  itemDraft.value = [...itemDraft.value, {
    itemType: draftType.value,
    refId,
    mode: 'reference',
    name,
  }]
  dialogError.value = ''
  draftRefId.value = ''
  draftName.value = ''
}

function removeDraftItem(index: number): void {
  itemDraft.value = itemDraft.value.filter((_, i) => i !== index)
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    name: '',
    code: '',
    kernel: 'robot',
    visibility: 'team',
    description: '',
  })
  itemDraft.value = []
  draftType.value = 'CASE'
  draftRefId.value = ''
  draftName.value = ''
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
      // FR-49-20：itemCount = 0 前端即拦截，错误文案可见，不发请求、不新增模板记录
      if (itemDraft.value.length === 0) {
        dialogError.value = t("templateCenter.needItems")
        return
      }
      await templateProjectsApi.create({
        name: form.name ?? '',
        code: form.code,
        kernel: form.kernel,
        visibility: form.visibility,
        description: form.description,
        items: itemDraft.value,
      })
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
      <!-- FR-49-17：用新 TemplateCard 替换旧展示，保留列表逻辑与既有管理动作 -->
      <div v-for="row in rows" :key="row.id" class="template-entry">
        <TemplateCard :template="row" />
        <footer class="template-entry__actions">
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
      </div>
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

        <!-- FR-49-20：新建模板必须关联至少 1 条用例或 1 个用例集 -->
        <div v-if="!editing" class="field">
          <span>{{ t("templateCenter.itemsTitle") }}</span>
          <p class="muted small">{{ t("templateCenter.itemsHint") }}</p>
          <div class="draft-row">
            <select v-model="draftType" class="select">
              <option value="CASE">{{ t("templateCenter.itemCase") }}</option>
              <option value="CASE_SET">{{ t("templateCenter.itemCaseSet") }}</option>
            </select>
            <input
              v-model="draftRefId"
              class="input mono"
              :placeholder='t("templateCenter.refIdPlaceholder")'
            />
            <input
              v-model="draftName"
              class="input"
              :placeholder='t("templateCenter.namePlaceholder")'
            />
            <button type="button" class="btn" data-testid="template-add-item" @click="addDraftItem">
              {{ t("templateCenter.addItem") }}
            </button>
          </div>
          <ul class="draft-list">
            <li v-for="(item, index) in itemDraft" :key="index" data-testid="template-draft-item">
              {{ item.itemType }} · {{ item.name }} · {{ item.refId ?? '—' }}
              <button type="button" class="link" @click="removeDraftItem(index)">
                {{ t('global.templateDelete') }}
              </button>
            </li>
          </ul>
          <p class="muted small" data-testid="template-items-count">
            {{ t("templateCenter.itemsCount", { count: itemDraft.length }) }}
          </p>
        </div>

        <p v-if="dialogError" class="error" data-testid="template-dialog-error">{{ dialogError }}</p>
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
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-3);
}

.template-entry {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 0;
}

.template-entry__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.draft-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.draft-list {
  list-style: none;
  margin: var(--space-1) 0 0;
  padding: 0;
  font-size: var(--font-size-sm);
}

.draft-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: 2px 0;
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
  color: var(--accent);
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
  color: var(--accent);
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
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
</style>
