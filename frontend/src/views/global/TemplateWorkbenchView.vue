<script setup lang="ts">
/**
 * 模板工作台（v4.9.2 / OPT-05 / FR-49-19、FR-49-21）。
 *
 * 全局模式 `/g/templates/:id`：增删改「用例项 / 用例集项」并持久化，刷新不丢。
 * 页面明确标注「此处编辑模板内容清单，不是公共用例本体」——本视图只调用
 * `templateProjectsApi` 的模板明细/保存接口，不引用、不调用任何写回公共用例本体的接口。
 * `builtin` 模板只读（UI 拦截 + 后端 4xx 兜底）。
 *
 * 新 i18n 键交付在 docs/evidence/opt-20261111/i18n/frontend-views.json。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import {
  templateProjectsApi,
  type TemplateDetailView,
  type TemplateItemRow,
} from '@/api/templateProjects'

type ItemKind = 'CASE' | 'CASE_SET'

const { t } = useI18n()
const route = useRoute()
const templateId = computed(() => String(route.params.id ?? ''))
const detail = ref<TemplateDetailView | null>(null)
const loading = ref(true)
const error = ref('')
const message = ref('')
const saving = ref(false)
const builtin = computed(() => detail.value?.template.builtin ?? false)

interface Draft {
  cases: TemplateItemRow[]
  caseSets: TemplateItemRow[]
  others: TemplateItemRow[]
}
const draft = ref<Draft>({ cases: [], caseSets: [], others: [] })

const newCase = reactive({ name: '', refId: '' })
const newSet = reactive({ name: '', refId: '' })

const editableGroups: {
  key: 'cases' | 'caseSets'
  kind: ItemKind
  form: { name: string; refId: string }
}[] = [
  { key: 'cases', kind: 'CASE', form: newCase },
  { key: 'caseSets', kind: 'CASE_SET', form: newSet },
]

function kindOf(itemType: string): string {
  const upper = itemType.toUpperCase()
  if (upper === 'CASESET') return 'CASE_SET'
  return upper
}

function split(d: TemplateDetailView): Draft {
  const all = d.items.length
    ? d.items
    : [...d.cases, ...d.caseSets, ...d.scenarios, ...d.assets]
  return {
    cases: all.filter((item) => kindOf(item.itemType) === 'CASE'),
    caseSets: all.filter((item) => kindOf(item.itemType) === 'CASE_SET'),
    others: all.filter((item) => {
      const kind = kindOf(item.itemType)
      return kind !== 'CASE' && kind !== 'CASE_SET'
    }),
  }
}

function allItems(): TemplateItemRow[] {
  return [...draft.value.cases, ...draft.value.caseSets, ...draft.value.others]
}

async function load(): Promise<void> {
  if (!templateId.value) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await templateProjectsApi.detail(templateId.value)
    draft.value = split(detail.value)
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("templateWorkbench.loadFailed")
  } finally {
    loading.value = false
  }
}

/** 只写 project_template_item 清单，绝不触碰公共用例本体。 */
async function persist(): Promise<void> {
  if (!detail.value || builtin.value) return
  saving.value = true
  message.value = ''
  try {
    await templateProjectsApi.saveItems(detail.value.template.id, allItems(), {
      name: detail.value.template.name,
      code: detail.value.template.code,
      kernel: detail.value.template.kernel,
      visibility: detail.value.template.visibility,
      description: detail.value.template.description,
    })
    message.value = t("templateWorkbench.saved")
    await load()
  } catch (ex) {
    message.value = (ex as { message?: string }).message ?? t("templateWorkbench.saveFailed")
  } finally {
    saving.value = false
  }
}

function addItem(kind: ItemKind, form: { name: string; refId: string }): void {
  if (builtin.value) return
  const name = form.name.trim()
  if (!name) {
    message.value = t("templateWorkbench.nameRequired")
    return
  }
  // 后端 insertItems 跳过 refId==null 的明细；不带引用 ID 会让「刷新不丢」失效。
  const refId = Number(form.refId.trim())
  if (!form.refId.trim() || !Number.isFinite(refId) || refId <= 0) {
    message.value = t("templateWorkbench.refIdRequired")
    return
  }
  const item: TemplateItemRow = {
    itemType: kind,
    refId,
    mode: 'reference',
    name,
  }
  if (kind === 'CASE') draft.value.cases = [...draft.value.cases, item]
  else draft.value.caseSets = [...draft.value.caseSets, item]
  form.name = ''
  form.refId = ''
  void persist()
}

function removeItem(kind: ItemKind, index: number): void {
  if (builtin.value) return
  const list = kind === 'CASE' ? draft.value.cases : draft.value.caseSets
  const next = list.filter((_, i) => i !== index)
  if (kind === 'CASE') draft.value.cases = next
  else draft.value.caseSets = next
  void persist()
}

function renameItem(kind: ItemKind, index: number, event: Event): void {
  if (builtin.value) return
  const value = (event.target as HTMLInputElement).value.trim()
  if (!value) return
  const list = kind === 'CASE' ? draft.value.cases : draft.value.caseSets
  list[index].name = value
  void persist()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ t("templateWorkbench.title") }}</h1>
      <p class="muted">{{ t("templateWorkbench.subtitle") }}</p>
    </header>

    <!-- FR-49-21：明确标注编辑对象是清单，不是公共用例本体 -->
    <p class="workbench-notice" data-testid="workbench-notice">
      {{ t("templateWorkbench.notCaseBody") }}
    </p>
    <p v-if="builtin" class="workbench-readonly" data-testid="workbench-readonly">
      {{ t("templateWorkbench.builtinReadonly") }}
    </p>
    <p v-if="message" class="workbench-message" data-testid="workbench-message">{{ message }}</p>
    <p v-if="error" class="workbench-error" data-testid="workbench-error">{{ error }}</p>
    <p v-if="loading" class="muted">{{ t("templateWorkbench.loading") }}</p>

    <template v-else-if="detail">
      <p class="muted">{{ detail.template.name }} · {{ detail.template.code }}</p>

      <div
        v-for="group in editableGroups"
        :key="group.key"
        class="workbench-group"
        :data-testid="`workbench-${group.key}`"
      >
        <h2 class="workbench-group-title">{{ t(`templateWorkbench.group.${group.key}`) }}</h2>

        <div v-if="!builtin" class="workbench-add">
          <input
            v-model="group.form.name"
            class="input"
            :placeholder='t("templateWorkbench.namePlaceholder")'
            :data-testid="`workbench-${group.key}-name`"
          />
          <input
            v-model="group.form.refId"
            class="input input--short"
            :placeholder='t("templateWorkbench.refIdPlaceholder")'
            :data-testid="`workbench-${group.key}-ref`"
          />
          <button
            type="button"
            class="btn"
            :data-testid="`workbench-${group.key}-add`"
            :disabled="saving"
            @click="addItem(group.kind, group.form)"
          >{{ t("templateWorkbench.add") }}</button>
        </div>

        <ul class="workbench-list">
          <li
            v-for="(item, index) in (group.key === 'cases' ? draft.cases : draft.caseSets)"
            :key="`${group.key}-${index}`"
            class="workbench-item"
            :data-testid="`workbench-${group.key}-item`"
          >
            <input
              class="input"
              :value="item.name"
              :disabled="builtin"
              :data-testid="`workbench-${group.key}-edit`"
              @change="renameItem(group.kind, index, $event)"
            />
            <span class="mono muted">{{ item.refId ?? '—' }}</span>
            <button
              v-if="!builtin"
              type="button"
              class="btn btn-ghost danger"
              :data-testid="`workbench-${group.key}-remove`"
              :disabled="saving"
              @click="removeItem(group.kind, index)"
            >{{ t("templateWorkbench.remove") }}</button>
          </li>
          <li v-if="(group.key === 'cases' ? draft.cases : draft.caseSets).length === 0" class="muted">
            {{ t("templateWorkbench.empty") }}
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-3);
}
.muted {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.workbench-notice {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--accent-soft);
  color: var(--text);
  font-size: var(--font-size-sm);
}
.workbench-readonly {
  color: var(--warn);
  font-size: var(--font-size-sm);
}
.workbench-message {
  color: var(--ok);
  font-size: var(--font-size-sm);
}
.workbench-error {
  color: var(--fail);
  font-size: var(--font-size-sm);
}
.workbench-group {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}
.workbench-group-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.workbench-add,
.workbench-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}
.workbench-list {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
}
.input {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  min-width: 180px;
}
.input--short {
  min-width: 90px;
}
.mono {
  font-family: var(--font-mono, monospace);
}
.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}
.btn-ghost {
  background: transparent;
}
.danger {
  color: var(--fail);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
