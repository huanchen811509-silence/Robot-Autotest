<script setup lang="ts">
/**
 * 场景管理页（v4.9.2 / OPT-07 / FR-49-29）。
 *
 * 路由 `/scenarios` 可达，支持创建 / 编组 / 删除；场景复用
 * `test_set.kind=scenario` 三态，不新建表。页面显式说明
 * 「场景仍限一层（FR-49-29 降级，授权人：大师）」。
 * 同页挂载 `CategoryTree` 承接 FR-49-26/28/30 的分类筛选与批量组集。
 *
 * 新 i18n 键交付在 docs/evidence/opt-20261111/i18n/frontend-views.json。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { scenarioApi, caseSetApi, type ScenarioRow, type TestSet } from '@/api/cases'
import CategoryTree from '@/components/CategoryTree.vue'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)
const scenarios = ref<ScenarioRow[]>([])
const violations = ref<string[]>([])
const sets = ref<TestSet[]>([])
const loading = ref(true)
const error = ref('')
const message = ref('')

const createName = ref('')
const createCode = ref('')
const createSetIds = ref<number[]>([])
const editingId = ref<number | null>(null)
const editSetIds = ref<number[]>([])
const confirmDeleteId = ref<number | null>(null)

/** 场景只存一层用例集 id 逗号串，不改中间表。 */
function parseIds(raw: string | null): number[] {
  if (!raw) return []
  return raw
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((value) => Number.isFinite(value) && value > 0)
}

function toggleIn(list: number[], id: number): number[] {
  return list.includes(id) ? list.filter((item) => item !== id) : [...list, id]
}

function toggleCreateSet(id: number): void {
  createSetIds.value = toggleIn(createSetIds.value, id)
}

function toggleEditSet(id: number): void {
  editSetIds.value = toggleIn(editSetIds.value, id)
}

async function load(): Promise<void> {
  if (!projectId.value) {
    loading.value = false
    error.value = t("scenarios.noProject")
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await scenarioApi.list(projectId.value)
    scenarios.value = result.items
    violations.value = result.oneLevelViolations
    sets.value = (await caseSetApi.page(projectId.value, { page: 1, size: 100 })).items
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("scenarios.loadFailed")
  } finally {
    loading.value = false
  }
}

async function createScenario(): Promise<void> {
  if (!createName.value.trim() || !createCode.value.trim()) {
    error.value = t("scenarios.nameRequired")
    return
  }
  try {
    await scenarioApi.create(projectId.value, {
      setCode: createCode.value.trim(),
      name: createName.value.trim(),
      caseSetIds: createSetIds.value,
    })
    createName.value = ''
    createCode.value = ''
    createSetIds.value = []
    message.value = t("scenarios.created")
    await load()
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("scenarios.createFailed")
  }
}

function startEdit(row: ScenarioRow): void {
  editingId.value = row.id
  editSetIds.value = parseIds(row.case_set_ids)
  confirmDeleteId.value = null
}

async function regroup(): Promise<void> {
  if (editingId.value === null) return
  try {
    await scenarioApi.regroup(projectId.value, editingId.value, editSetIds.value)
    editingId.value = null
    message.value = t("scenarios.regrouped")
    await load()
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("scenarios.regroupFailed")
  }
}

async function removeScenario(): Promise<void> {
  if (confirmDeleteId.value === null) return
  try {
    await scenarioApi.remove(projectId.value, confirmDeleteId.value)
    confirmDeleteId.value = null
    message.value = t("scenarios.deleted")
    await load()
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("scenarios.deleteFailed")
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ t("scenarios.title") }}</h1>
      <p class="muted">{{ t("scenarios.subtitle") }}</p>
    </header>

    <!-- FR-49-29 降级说明：仍限一层 -->
    <p class="one-level-hint" data-testid="scenario-one-level-hint">
      {{ t("scenarios.oneLevelHint") }}
    </p>
    <p v-if="violations.length" class="error" data-testid="scenario-violations">
      {{ t("scenarios.violations", { count: violations.length }) }}
    </p>
    <p v-if="error" class="error" data-testid="scenario-error">{{ error }}</p>
    <p v-if="message" class="ok" data-testid="scenario-message">{{ message }}</p>
    <p v-if="loading" class="muted">{{ t("scenarios.loading") }}</p>

    <template v-else>
      <div class="scenario-create" data-testid="scenario-create">
        <h2>{{ t("scenarios.createTitle") }}</h2>
        <label class="field">
          <span>{{ t("scenarios.name") }}</span>
          <input v-model="createName" class="input" data-testid="scenario-name" />
        </label>
        <label class="field">
          <span>{{ t("scenarios.code") }}</span>
          <input v-model="createCode" class="input" data-testid="scenario-code" />
        </label>
        <div class="chips">
          <span class="muted">{{ t("scenarios.caseSets") }}</span>
          <button
            v-for="set in sets"
            :key="set.id"
            type="button"
            class="chip"
            :class="{ active: createSetIds.includes(set.id) }"
            :data-testid="`create-set-${set.id}`"
            @click="toggleCreateSet(set.id)"
          >{{ set.name }}</button>
        </div>
        <button
          type="button"
          class="btn btn-primary"
          data-testid="scenario-create-submit"
          @click="createScenario"
        >{{ t("scenarios.create") }}</button>
      </div>

      <ul class="scenario-list" data-testid="scenario-list">
        <li
          v-for="row in scenarios"
          :key="row.id"
          class="scenario-item"
          :data-testid="`scenario-${row.id}`"
        >
          <div class="scenario-item__head">
            <strong>{{ row.name }}</strong>
            <span class="muted">
              {{ t("scenarios.caseSetCount", { count: parseIds(row.case_set_ids).length }) }}
            </span>
            <button
              type="button"
              class="btn"
              :data-testid="`scenario-edit-${row.id}`"
              @click="startEdit(row)"
            >{{ t("scenarios.regroup") }}</button>
            <button
              type="button"
              class="btn danger"
              :data-testid="`scenario-delete-${row.id}`"
              @click="confirmDeleteId = row.id"
            >{{ t("scenarios.delete") }}</button>
          </div>

          <div v-if="editingId === row.id" class="scenario-edit" :data-testid="`scenario-edit-panel-${row.id}`">
            <div class="chips">
              <button
                v-for="set in sets"
                :key="set.id"
                type="button"
                class="chip"
                :class="{ active: editSetIds.includes(set.id) }"
                :data-testid="`edit-set-${set.id}`"
                @click="toggleEditSet(set.id)"
              >{{ set.name }}</button>
            </div>
            <button
              type="button"
              class="btn btn-primary"
              data-testid="scenario-regroup-submit"
              @click="regroup"
            >{{ t("scenarios.saveRegroup") }}</button>
          </div>

          <div v-if="confirmDeleteId === row.id" class="scenario-confirm" :data-testid="`scenario-confirm-${row.id}`">
            <span>{{ t("scenarios.confirmDelete") }}</span>
            <button
              type="button"
              class="btn danger"
              data-testid="scenario-delete-confirm"
              @click="removeScenario"
            >{{ t("scenarios.confirm") }}</button>
          </div>
        </li>
        <li v-if="scenarios.length === 0" class="muted">{{ t("scenarios.empty") }}</li>
      </ul>

      <details class="category-section">
        <summary>{{ t("scenarios.categorySection") }}</summary>
        <CategoryTree :project-id="projectId" />
      </details>
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
.one-level-hint {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--warn);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  font-size: var(--font-size-sm);
}
.scenario-create,
.scenario-item,
.category-section {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  padding: var(--space-3);
  margin-bottom: var(--space-3);
}
.scenario-create h2 {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: var(--space-2);
  font-size: var(--font-size-sm);
}
.input {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin: var(--space-2) 0;
}
.chip {
  padding: 2px 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text-muted);
  cursor: pointer;
  font-size: var(--font-size-xs);
}
.chip.active {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.scenario-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.scenario-item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}
.scenario-edit,
.scenario-confirm {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
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
.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
.danger {
  color: var(--fail);
}
.muted {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.error {
  color: var(--fail);
  font-size: var(--font-size-sm);
}
.ok {
  color: var(--ok);
  font-size: var(--font-size-sm);
}
.category-section summary {
  cursor: pointer;
  color: var(--text-muted);
}
.category-section > :not(summary) {
  margin-top: var(--space-3);
}
</style>
