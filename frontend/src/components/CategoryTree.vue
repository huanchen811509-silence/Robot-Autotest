<script setup lang="ts">
/**
 * 两级分类树与按分类组集（v4.9.2 / OPT-07 / FR-49-26、FR-49-28、FR-49-30）。
 *
 * 二级必属所选一级（切换一级即清空二级，非法组合直接拒绝）；
 * 支持「全选某一级 / 全选某二级 / 跨表交集」三种筛选；
 * 批量加入后前端显式展示「筛选条数 = 加入条数」；
 * CSV 导出走 `export/category.csv`（每行含 external_id）。
 *
 * 新 i18n 键交付在 docs/evidence/opt-20261111/i18n/frontend-views.json。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { globalCaseApi, type CaseCategoryRow } from '@/api/globalCases'
import { caseSetApi, type TestSet } from '@/api/cases'

const props = defineProps<{ projectId: number | string }>()

const { t } = useI18n()
const categories = ref<CaseCategoryRow[]>([])
const sets = ref<TestSet[]>([])
const l1Id = ref<number | null>(null)
const l2Id = ref<number | null>(null)
const mode = ref<'l1' | 'l2' | 'intersect'>('l1')
const targetSetId = ref<number | null>(null)
const intersectSetId = ref<number | null>(null)
const matched = ref<number | null>(null)
const added = ref<number | null>(null)
const itemSource = ref('')
const busy = ref(false)
const error = ref('')
const exported = ref('')

const MODES: readonly ('l1' | 'l2' | 'intersect')[] = ['l1', 'l2', 'intersect']

const l1Options = computed(() => categories.value.filter((item) => item.level === 1))
const l2Options = computed(() =>
  categories.value.filter((item) => item.level === 2 && item.parentId === l1Id.value),
)
const selectedL1 = computed(() => l1Options.value.find((item) => item.id === l1Id.value))
const selectedL2 = computed(() => l2Options.value.find((item) => item.id === l2Id.value))
const categoryL1 = computed(() => selectedL1.value?.name ?? '')
const categoryL2 = computed(() => selectedL2.value?.name ?? '')

/** 二级必属所选一级：选了二级却不是当前一级的子节点即为非法组合。 */
const pairValid = computed(() => {
  if (l2Id.value === null) return true
  const node = categories.value.find((item) => item.id === l2Id.value)
  return node?.parentId === l1Id.value
})

const filterParams = computed(() => ({
  categoryL1: categoryL1.value || undefined,
  categoryL2: mode.value === 'l2' && categoryL2.value ? categoryL2.value : undefined,
  intersectWithSetId:
    mode.value === 'intersect' && intersectSetId.value !== null
      ? Number(intersectSetId.value)
      : undefined,
}))

const countsEqual = computed(
  () => matched.value !== null && added.value !== null && matched.value === added.value,
)

function l2For(parentId: number): CaseCategoryRow[] {
  return categories.value.filter((item) => item.level === 2 && item.parentId === parentId)
}

function selectL1(id: number): void {
  if (l1Id.value !== id) {
    l1Id.value = id
    l2Id.value = null
  }
  error.value = ''
}

function selectL2(parentId: number, id: number): void {
  l1Id.value = parentId
  l2Id.value = id
  mode.value = 'l2'
  error.value = ''
}

async function load(): Promise<void> {
  try {
    categories.value = await globalCaseApi.categoriesInScope(props.projectId)
  } catch {
    categories.value = []
  }
  try {
    sets.value = (await caseSetApi.page(Number(props.projectId), { page: 1, size: 100 })).items
  } catch {
    sets.value = []
  }
}

/** 三种筛选共用一套条件，先看筛选条数再批量加入。 */
async function runFilter(): Promise<void> {
  if (!pairValid.value) {
    error.value = t("categoryTree.invalidPair")
    return
  }
  busy.value = true
  error.value = ''
  try {
    const page = await globalCaseApi.page({
      categoryL1: filterParams.value.categoryL1,
      categoryL2: filterParams.value.categoryL2,
      page: 1,
      size: 200,
    })
    matched.value = page.total
    added.value = null
    itemSource.value = ''
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("categoryTree.filterFailed")
  } finally {
    busy.value = false
  }
}

async function addToSet(): Promise<void> {
  if (!pairValid.value) {
    error.value = t("categoryTree.invalidPair")
    return
  }
  if (targetSetId.value === null) {
    error.value = t("categoryTree.needTargetSet")
    return
  }
  busy.value = true
  error.value = ''
  try {
    const result = await caseSetApi.addByCategoryFilter(
      Number(props.projectId),
      targetSetId.value,
      filterParams.value,
    )
    matched.value = result.matched
    added.value = result.added
    itemSource.value = result.itemSource
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("categoryTree.addFailed")
  } finally {
    busy.value = false
  }
}

async function exportCsv(): Promise<void> {
  if (!pairValid.value) {
    error.value = t("categoryTree.invalidPair")
    return
  }
  try {
    const blob = await globalCaseApi.exportCategoryCsv({
      projectId: props.projectId,
      ...filterParams.value,
    })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'category-cases.csv'
    anchor.click()
    URL.revokeObjectURL(url)
    exported.value = t("categoryTree.exported")
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("categoryTree.exportFailed")
  }
}

onMounted(load)
</script>

<template>
  <section class="category-tree" data-testid="category-tree">
    <h2 class="category-tree__title">{{ t("categoryTree.title") }}</h2>

    <div class="category-tree__modes" role="group">
      <label v-for="option in MODES" :key="option">
        <input v-model="mode" type="radio" :value="option" :data-testid="`mode-${option}`" />
        <span>{{ t(`categoryTree.mode.${option}`) }}</span>
      </label>
    </div>

    <div class="category-tree__body">
      <div class="category-tree__nodes">
        <button type="button" class="tree-node" data-testid="tree-all" @click="selectL1(0)">
          {{ t("categoryTree.all") }}
        </button>
        <div v-for="l1 in l1Options" :key="l1.id" class="tree-l1">
          <button
            type="button"
            class="tree-node"
            :class="{ active: l1Id === l1.id }"
            :data-testid="`tree-l1-${l1.id}`"
            @click="selectL1(l1.id)"
          >
            {{ l1.name }} <span class="count">{{ l1.caseCount }}</span>
          </button>
          <div v-if="l1Id === l1.id" class="tree-l2">
            <button
              v-for="l2 in l2For(l1.id)"
              :key="l2.id"
              type="button"
              class="tree-node tree-node--l2"
              :class="{ active: l2Id === l2.id }"
              :data-testid="`tree-l2-${l2.id}`"
              @click="selectL2(l1.id, l2.id)"
            >
              {{ l2.name }} <span class="count">{{ l2.caseCount }}</span>
            </button>
          </div>
        </div>
      </div>

      <div class="category-tree__panel">
        <p v-if="!pairValid" class="category-tree__error" data-testid="pair-invalid">
          {{ t("categoryTree.invalidPair") }}
        </p>
        <p class="muted">{{ t("categoryTree.selected", { l1: categoryL1 || '—', l2: categoryL2 || '—' }) }}</p>

        <label v-if="mode === 'intersect'" class="field">
          <span>{{ t("categoryTree.intersectSet") }}</span>
          <select v-model="intersectSetId" class="select" data-testid="intersect-set">
            <option :value="null">-</option>
            <option v-for="set in sets" :key="set.id" :value="set.id">{{ set.name }}</option>
          </select>
        </label>

        <label class="field">
          <span>{{ t("categoryTree.targetSet") }}</span>
          <select v-model="targetSetId" class="select" data-testid="target-set">
            <option :value="null">-</option>
            <option v-for="set in sets" :key="set.id" :value="set.id">{{ set.name }}</option>
          </select>
        </label>

        <div class="category-tree__actions">
          <button type="button" class="btn" data-testid="run-filter" :disabled="busy" @click="runFilter">
            {{ t("categoryTree.filter") }}
          </button>
          <button type="button" class="btn btn-primary" data-testid="add-to-set" :disabled="busy" @click="addToSet">
            {{ t("categoryTree.addToSet") }}
          </button>
          <button type="button" class="btn" data-testid="export-csv" @click="exportCsv">
            {{ t("categoryTree.exportCsv") }}
          </button>
        </div>

        <p class="muted">{{ t("categoryTree.externalIdHint") }}</p>
        <p v-if="matched !== null" class="category-tree__counts" data-testid="filter-counts">
          {{ t("categoryTree.matched", { count: matched }) }}
          <template v-if="added !== null">
            {{ t("categoryTree.equalSign") }} {{ t("categoryTree.added", { count: added }) }}
          </template>
        </p>
        <p v-if="added !== null" :class="countsEqual ? 'ok' : 'category-tree__error'" data-testid="count-check">
          {{ countsEqual ? t("categoryTree.countEqual") : t("categoryTree.countMismatch") }}
        </p>
        <p v-if="itemSource" class="muted">{{ t("categoryTree.itemSource", { source: itemSource }) }}</p>
        <p v-if="exported" class="ok" data-testid="export-done">{{ exported }}</p>
        <p v-if="error" class="category-tree__error" data-testid="category-tree-error">{{ error }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.category-tree {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--panel);
}
.category-tree__title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.category-tree__modes {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  font-size: var(--font-size-sm);
  margin-bottom: var(--space-2);
}
.category-tree__body {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: var(--space-3);
}
.category-tree__nodes {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--border);
  padding-right: var(--space-2);
}
.tree-node {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  width: 100%;
  padding: 4px 6px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  font-size: var(--font-size-sm);
}
.tree-node.active {
  background: var(--accent-soft);
  color: var(--accent);
}
.tree-node--l2 {
  padding-left: var(--space-4);
  color: var(--text-muted);
}
.count {
  color: var(--text-muted);
}
.category-tree__panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--font-size-sm);
}
.select,
.btn {
  padding: 6px 10px;
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
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.category-tree__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
.category-tree__counts {
  font-weight: 600;
  font-size: var(--font-size-sm);
}
.category-tree__error {
  color: var(--fail);
  font-size: var(--font-size-sm);
}
.ok {
  color: var(--ok);
  font-size: var(--font-size-sm);
}
.muted {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
@media (max-width: 760px) {
  .category-tree__body {
    grid-template-columns: 1fr;
  }
}
</style>
