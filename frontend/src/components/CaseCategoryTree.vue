<script setup lang="ts">
/**
 * 公共用例分类树（W57-10/11，向导「用例」分区复用 W57-12/13）。
 *
 * 数据面：`GET /api/global/cases/grouped` 给一级 → 二级（带 count）的结构；
 * 二级下的**用例明细在展开时懒加载**（默认走 globalCaseApi.page 按分类取前 200 条），
 * 不在首屏把整库拉下来。勾选态交给 useCategorySelection（唯一真源 = 用例 id 集合）。
 *
 * 键盘：展开/折叠一律用原生 `<button>`、勾选一律用原生 `<input type=checkbox>`，
 * Enter/Space/Tab 由浏览器原生保证，不再手写一套 keydown 映射。
 */
import { computed, onMounted, ref } from 'vue'
import {
  useCategorySelection,
  type CategoryGroup,
  type CategoryL2,
  type TreeCase,
} from '@/composables/useCategorySelection'
import { globalCaseApi } from '@/api/globalCases'
import { globalGroupedApi } from '@/api/platformCaseSets'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = withDefaults(defineProps<{
  /** 提供后不再自行请求 grouped（单测/父级已有数据时用）。 */
  initialGroups?: CategoryGroup[]
  /** 已在本集的用例 id：置灰且不可再勾。 */
  existingCaseIds?: number[]
  maxSelect?: number
  /** false = 纯展示（归组视图），不渲染勾选框。 */
  selectable?: boolean
  /** 展开二级时懒加载用例；不传则按分类调 globalCaseApi.page。 */
  caseLoader?: (l1Name: string, l2Name: string) => Promise<{ items: TreeCase[]; total: number }>
  /** 初始已选用例：组件被销毁重建（向导返回上一步）时从父级 v-model 恢复。 */
  modelValue?: number[]
}>(), {
  // Vue 对 Boolean 类型 prop 有 boolean casting：不显式给默认值时，未传会被当成 false，
  // 那会让整棵树的勾选框全部消失（归组视图才该传 false）。这里必须写死 true。
  selectable: true,
})

const emit = defineEmits<{
  change: [ids: number[]]
  'update:modelValue': [ids: number[]]
}>()

const selection = useCategorySelection({ maxSelect: props.maxSelect ?? 1000 })
if (props.modelValue?.length) selection.selected.value = new Set(props.modelValue)
const keyword = selection.keyword

const groups = ref<CategoryGroup[]>(props.initialGroups ?? [])
const loadError = ref('')
/** 一级默认展开到二级；二级默认折叠（归组视图「默认折叠到二级」）。 */
const expandedL1 = ref<Set<number>>(new Set((props.initialGroups ?? []).map((g) => g.l1Id)))
const expandedL2 = ref<Set<string>>(new Set())
const casesByL2 = ref<Record<string, { items: TreeCase[]; total: number }>>({})

const existingSet = computed(() => new Set(props.existingCaseIds ?? []))
const visible = computed(() => props.selectable !== false)

async function defaultCaseLoader(
  l1Name: string,
  l2Name: string,
): Promise<{ items: TreeCase[]; total: number }> {
  const page = await globalCaseApi.page({
    categoryL1: l1Name,
    categoryL2: l2Name,
    page: 1,
    size: 200,
  })
  return {
    items: page.items.map((row) => ({ id: row.id, title: row.title, caseCode: row.caseCode })),
    total: page.total,
  }
}

function loader(): (l1Name: string, l2Name: string) => Promise<{ items: TreeCase[]; total: number }> {
  return props.caseLoader ?? defaultCaseLoader
}

function l2Key(l1: CategoryGroup, l2: CategoryL2): string {
  return `${l1.l1Id}:${l2.l2Id}`
}

/** 懒加载一个二级节点的用例并缓存；重复展开不再打接口。 */
async function ensureCases(
  l1: CategoryGroup,
  l2: CategoryL2,
): Promise<{ items: TreeCase[]; total: number }> {
  const key = l2Key(l1, l2)
  const cached = casesByL2.value[key]
  if (cached) return cached
  const data = await loader()(l1.l1Name, l2.l2Name)
  casesByL2.value = { ...casesByL2.value, [key]: data }
  return data
}

function casesOf(l1: CategoryGroup, l2: CategoryL2): TreeCase[] {
  return (casesByL2.value[l2Key(l1, l2)]?.items ?? []).slice(0, 200)
}

function idsOf(l1: CategoryGroup, l2: CategoryL2): number[] {
  return casesOf(l1, l2).map((item) => item.id)
}

function l1CaseIds(l1: CategoryGroup): number[] {
  return l1.l2s.flatMap((l2) => idsOf(l1, l2))
}

function emitChange(): void {
  emit('change', selection.selectedIds.value)
  emit('update:modelValue', selection.selectedIds.value)
}

function toggleExpandL1(l1Id: number): void {
  const next = new Set(expandedL1.value)
  if (next.has(l1Id)) next.delete(l1Id)
  else next.add(l1Id)
  expandedL1.value = next
}

async function toggleExpandL2(l1: CategoryGroup, l2: CategoryL2): Promise<void> {
  const key = l2Key(l1, l2)
  const next = new Set(expandedL2.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
    await ensureCases(l1, l2)
  }
  expandedL2.value = next
}

/** 勾一级：先把其下所有二级用例加载齐，再整批选/不选。 */
async function pickL1(l1: CategoryGroup): Promise<void> {
  for (const l2 of l1.l2s) await ensureCases(l1, l2)
  selection.toggleL1(l1CaseIds(l1))
  emitChange()
}

async function pickL2(l1: CategoryGroup, l2: CategoryL2): Promise<void> {
  await ensureCases(l1, l2)
  selection.toggleL2(idsOf(l1, l2))
  emitChange()
}

function pickCase(id: number): void {
  if (existingSet.value.has(id)) return
  selection.toggleCase(id)
  emitChange()
}

/** 搜索只影响可见项：一级名命中显示整组；否则只留命中的二级。已选不变。 */
const visibleGroups = computed<CategoryGroup[]>(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return groups.value
  return groups.value
    .map((group) => {
      if (selection.isVisible(group.l1Name)) return group
      return { ...group, l2s: group.l2s.filter((l2) => selection.isVisible(l2.l2Name)) }
    })
    .filter((group) => selection.isVisible(group.l1Name) || group.l2s.length > 0)
})

onMounted(async () => {
  if (props.initialGroups) return
  try {
    const data = await globalGroupedApi.grouped()
    groups.value = data
    expandedL1.value = new Set(data.map((g) => g.l1Id))
  } catch {
    loadError.value = t('w57.caseCategoryTree.loadFailed')
  }
})

defineExpose({ selection, selectedIds: selection.selectedIds, groups, ensureCases })
</script>

<template>
  <div class="category-tree" data-testid="category-tree">
    <div class="tree-toolbar">
      <input
        v-model="keyword"
        class="tree-search"
        type="search"
        :placeholder="t('w57.caseCategoryTree.searchPlaceholder')"
        :aria-label="t('w57.caseCategoryTree.searchAria')"
        data-testid="tree-search"
      />
      <span class="tree-count" data-testid="tree-selected-count">{{ t('w57.caseCategoryTree.selected', { count: selection.count.value }) }}</span>
      <button type="button" class="tree-clear" data-testid="tree-clear" @click="selection.clear(); emitChange()">
        {{ t('w57.caseCategoryTree.clear') }}
      </button>
    </div>

    <p v-if="loadError" class="tree-error">{{ loadError }}</p>
    <p v-if="selection.atLimit.value" class="tree-limit" data-testid="tree-limit">
      {{ t('w57.caseCategoryTree.maxReached', { max: selection.maxSelect }) }}
    </p>

    <p v-if="visibleGroups.length === 0" class="tree-empty" data-testid="tree-empty">
      {{ t('w57.caseCategoryTree.empty') }}
    </p>

    <ul class="tree-l1-list">
      <li v-for="l1 in visibleGroups" :key="l1.l1Id" class="tree-l1">
        <div class="tree-row">
          <button
            type="button"
            class="tree-toggle"
            :aria-expanded="expandedL1.has(l1.l1Id)"
            :data-testid="`l1-toggle-${l1.l1Id}`"
            @click="toggleExpandL1(l1.l1Id)"
          >
            <span class="tree-caret">{{ expandedL1.has(l1.l1Id) ? '▾' : '▸' }}</span>
            <span class="tree-label" :title="l1.l1Name">{{ l1.l1Name }}</span>
          </button>
          <span class="tree-meta">{{ t('w57.caseCategoryTree.l1Total', { count: l1.total }) }}</span>
          <input
            v-if="visible"
            type="checkbox"
            class="tree-check"
            :checked="selection.isChecked(l1CaseIds(l1))"
            :data-state="selection.stateOf(l1CaseIds(l1))"
            :data-testid="`l1-check-${l1.l1Id}`"
            :aria-label="t('w57.caseCategoryTree.selectL1', { name: l1.l1Name })"
            @change="pickL1(l1)"
          />
        </div>

        <ul v-if="expandedL1.has(l1.l1Id)" class="tree-l2-list">
          <li v-if="l1.uncategorized > 0" class="tree-uncategorized">
            {{ t('w57.caseCategoryTree.uncategorized', { count: l1.uncategorized }) }}
          </li>
          <li v-for="l2 in l1.l2s" :key="l2.l2Id" class="tree-l2">
            <div class="tree-row tree-row--l2">
              <button
                type="button"
                class="tree-toggle"
                :aria-expanded="expandedL2.has(l2Key(l1, l2))"
                :data-testid="`l2-toggle-${l1.l1Id}-${l2.l2Id}`"
                @click="toggleExpandL2(l1, l2)"
              >
                <span class="tree-caret">{{ expandedL2.has(l2Key(l1, l2)) ? '▾' : '▸' }}</span>
                <span class="tree-label" :title="l2.l2Name">{{ l2.l2Name }}</span>
              </button>
              <span
                v-if="l2.count > 200"
                class="tree-meta"
                :data-testid="`l2-cap-${l1.l1Id}-${l2.l2Id}`"
              >{{ t('w57.caseCategoryTree.l2Cap', { count: l2.count }) }}</span>
              <span v-else class="tree-meta">{{ t('w57.caseCategoryTree.l2Count', { count: l2.count }) }}</span>
              <input
                v-if="visible"
                type="checkbox"
                class="tree-check"
                :checked="selection.isChecked(idsOf(l1, l2))"
                :data-state="selection.stateOf(idsOf(l1, l2))"
                :data-testid="`l2-check-${l1.l1Id}-${l2.l2Id}`"
                :aria-label="t('w57.caseCategoryTree.selectL2', { name: l2.l2Name })"
                @change="pickL2(l1, l2)"
              />
            </div>

            <div v-if="expandedL2.has(l2Key(l1, l2))" class="tree-cases" :data-testid="`l2-cases-${l1.l1Id}-${l2.l2Id}`">
              <p
                v-if="(casesByL2[l2Key(l1, l2)]?.total ?? 0) > 200"
                class="tree-over-limit"
                data-testid="tree-over-limit"
              >
                {{ t('w57.caseCategoryTree.l2Total', { total: casesByL2[l2Key(l1, l2)]?.total }) }}
              </p>
              <label v-for="item in casesOf(l1, l2)" :key="item.id" class="tree-case">
                <input
                  v-if="visible"
                  type="checkbox"
                  :checked="selection.isCaseChecked(item.id)"
                  :disabled="existingSet.has(item.id)"
                  :data-testid="`tree-case-${item.id}`"
                  @change="pickCase(item.id)"
                />
                <span :class="['tree-case-title', { 'is-existing': existingSet.has(item.id) }]" :title="item.title">
                  {{ item.caseCode ? `${item.caseCode} · ` : '' }}{{ item.title }}
                </span>
                <span v-if="existingSet.has(item.id)" class="tree-existing">{{ t('w57.caseCategoryTree.existing') }}</span>
              </label>
              <p v-if="casesOf(l1, l2).length === 0" class="tree-empty-small">{{ t('w57.caseCategoryTree.noCases') }}</p>
            </div>
          </li>
        </ul>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.category-tree {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.tree-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.tree-search {
  flex: 1 1 220px;
  min-width: 0;
  max-width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  box-sizing: border-box;
}

.tree-count {
  white-space: nowrap;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.tree-clear,
.tree-toggle {
  border: 0;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  font: inherit;
}

.tree-clear {
  padding: 4px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.tree-toggle:focus-visible,
.tree-check:focus-visible,
.tree-clear:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.tree-l1-list,
.tree-l2-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.tree-l2-list {
  padding-left: 18px;
}

.tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 4px 2px;
  border-bottom: 1px solid var(--border);
}

.tree-row--l2 {
  border-bottom-style: dashed;
}

.tree-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1 1 auto;
  padding: 0;
  text-align: left;
}

.tree-caret {
  color: var(--text-muted);
  flex: 0 0 auto;
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.tree-meta {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  font-variant-numeric: tabular-nums;
}

.tree-check {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
}

.tree-check[data-state='some'] {
  accent-color: var(--warn);
}

.tree-uncategorized {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  padding: 4px 2px;
}

.tree-cases {
  padding: 4px 2px 8px 18px;
  max-height: 240px;
  overflow: auto;
}

.tree-case {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: var(--font-size-sm);
  min-width: 0;
}

.tree-case-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1 1 auto;
}

.tree-case-title.is-existing {
  color: var(--text-muted);
  opacity: 0.6;
}

.tree-existing {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.tree-error,
.tree-limit {
  color: var(--warn);
  font-size: var(--font-size-sm);
}

.tree-empty,
.tree-empty-small,
.tree-over-limit {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.tree-over-limit {
  color: var(--warn);
}
</style>
