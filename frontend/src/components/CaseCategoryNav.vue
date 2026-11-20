<script lang="ts">
/**
 * 中栏筛选选择（v5.11.0）。
 *
 * `uncategorized` 与 `l1/l2` 互斥：选「未分类」时 l1/l2 一律为空。
 * 定义在普通 `<script>` 里而不是 `<script setup>`，因为后者不允许 ES 导出。
 */
export interface CategorySelection {
  l1: string
  l2: string
  uncategorized: boolean
}
</script>

<script setup lang="ts">
/**
 * 用例清单中栏的两级分类导航（v5.11.0 / W61-03 / FR-v5.11-02）。
 *
 * 数据面复用 `GET /api/global/cases/grouped`（不新增端点、不建分类表）。
 * 与 `CaseCategoryTree` 的区别：树是「勾选用例」，这里是「换筛选」——
 * 不拉用例明细、不渲染勾选框，所以 3000 分类下也不会因为展开而卡。
 *
 * 计数口径（FR-02 判据 4）：**当前选中节点显示右表的真实 total**，
 * 其余节点显示接口给的分组计数。这样「同一分类下两处数字相等」不是靠约定，
 * 而是同一个数字。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { globalGroupedApi, type GroupedCase } from '@/api/platformCaseSets'

const props = withDefaults(
  defineProps<{
    modelValue?: CategorySelection
    /** 直接给数据（单测 / 父级已拉过时不重复请求）。 */
    groups?: GroupedCase[]
    /** 全部用例数（右表在无分类筛选时的 total）。 */
    allCount?: number
    /** 右表当前筛出的条数；用于让选中节点与右表逐字相等。 */
    activeCount?: number
    /** 未分类总数；不传则按接口返回的各 L1 uncategorized 求和。 */
    uncategorizedCount?: number
  }>(),
  {
    modelValue: () => ({ l1: '', l2: '', uncategorized: false }),
    allCount: 0,
    activeCount: 0,
    uncategorizedCount: undefined,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: CategorySelection] }>()

const { t } = useI18n()
const rows = ref<GroupedCase[]>(props.groups ?? [])
const failed = ref('')
/**
 * 展开状态记忆（v5.32.0 / FR-v5.32-05）。
 *
 * 优先用 URL 里已选的一级分类（复制链接要能复现同一屏），
 * 否则读本地上次展开的集合；两边都空时才全收起。
 * 隐私模式下 `localStorage` 会直接抛，读写都包 try —— 记不住不算错误。
 */
const EXPANDED_KEY = 'case-category-expanded'

function readExpandedMemory(): Set<number> {
  try {
    const raw = window.localStorage.getItem(EXPANDED_KEY)
    const parsed = raw ? (JSON.parse(raw) as unknown) : null
    return new Set(Array.isArray(parsed) ? parsed.filter((n) => typeof n === 'number') : [])
  } catch {
    return new Set()
  }
}

function persistExpandedMemory(value: Set<number>): void {
  try {
    window.localStorage.setItem(EXPANDED_KEY, JSON.stringify([...value]))
  } catch {
    // 存不进去不影响本次会话的展开状态
  }
}

const expanded = ref<Set<number>>(
  (props.groups ?? []).some((row) => row.l1Name === props.modelValue.l1)
    ? new Set((props.groups ?? [])
        .filter((row) => row.l1Name === props.modelValue.l1)
        .map((row) => row.l1Id))
    : readExpandedMemory(),
)
const loading = ref(false)

const uncategorizedTotal = computed(() => {
  if (props.uncategorizedCount != null) return props.uncategorizedCount
  return rows.value.reduce((sum, row) => sum + (row.uncategorized ?? 0), 0)
})

const allLabel = computed(() => t('w61.category.all'))
const uncategorizedLabel = computed(() => t('w61.category.uncategorized'))

function isAll(): boolean {
  return !props.modelValue.l1 && !props.modelValue.l2 && !props.modelValue.uncategorized
}

function isUncategorized(): boolean {
  return props.modelValue.uncategorized
}

function isL1(row: GroupedCase): boolean {
  return !props.modelValue.uncategorized
    && props.modelValue.l1 === row.l1Name
    && !props.modelValue.l2
}

function isL2(row: GroupedCase, l2Name: string): boolean {
  return !props.modelValue.uncategorized
    && props.modelValue.l1 === row.l1Name
    && props.modelValue.l2 === l2Name
}

/** 选中节点的数字取右表 total，未选中取接口计数。 */
function countOf(selected: boolean, fallback: number): number {
  return selected ? props.activeCount : fallback
}

function select(next: CategorySelection): void {
  emit('update:modelValue', next)
}

function selectAll(): void {
  select({ l1: '', l2: '', uncategorized: false })
}

function selectUncategorized(): void {
  select({ l1: '', l2: '', uncategorized: true })
}

function selectL1(row: GroupedCase): void {
  expanded.value = new Set([...expanded.value, row.l1Id])
  select({ l1: row.l1Name, l2: '', uncategorized: false })
}

function selectL2(row: GroupedCase, l2Name: string): void {
  select({ l1: row.l1Name, l2: l2Name, uncategorized: false })
}

function toggleL1(row: GroupedCase): void {
  const next = new Set(expanded.value)
  if (next.has(row.l1Id)) next.delete(row.l1Id)
  else next.add(row.l1Id)
  expanded.value = next
  persistExpandedMemory(next)
}

async function load(): Promise<void> {
  if (props.groups) return
  loading.value = true
  failed.value = ''
  try {
    rows.value = await globalGroupedApi.grouped()
    // 首屏默认展开当前选中的一级分类，用户点了 L1 直接能看到二级
    const hit = rows.value.find((row) => row.l1Name === props.modelValue.l1)
    if (hit) expanded.value = new Set([hit.l1Id])
  } catch {
    failed.value = t('global.loadFailed')
  } finally {
    loading.value = false
  }
}

function expandSelected(): void {
  const hit = rows.value.find((row) => row.l1Name === props.modelValue.l1)
  if (hit && !expanded.value.has(hit.l1Id)) {
    expanded.value = new Set([...expanded.value, hit.l1Id])
  }
}

onMounted(async () => {
  await load()
  expandSelected()
})
watch(() => props.groups, (next) => { if (next) rows.value = next })
watch(
  () => props.modelValue.l1,
  (l1) => {
    const hit = rows.value.find((row) => row.l1Name === l1)
    if (hit && !expanded.value.has(hit.l1Id)) {
      expanded.value = new Set([...expanded.value, hit.l1Id])
    }
  },
)
</script>

<template>
  <nav class="category-nav" data-testid="category-nav" :aria-label="t('global.categories')">
    <button
      type="button"
      class="nav-item root"
      :class="{ active: isAll() }"
      :aria-current="isAll() ? 'true' : undefined"
      data-testid="category-all"
      @click="selectAll"
    >
      <span class="nav-label">{{ allLabel }}</span>
      <span class="nav-count" data-testid="category-count-all">
        {{ countOf(isAll(), allCount) }}
      </span>
    </button>

    <button
      type="button"
      class="nav-item root"
      :class="{ active: isUncategorized() }"
      :aria-current="isUncategorized() ? 'true' : undefined"
      data-testid="category-uncategorized"
      @click="selectUncategorized"
    >
      <span class="nav-label">{{ uncategorizedLabel }}</span>
      <span class="nav-count" data-testid="category-count-uncategorized">
        {{ countOf(isUncategorized(), uncategorizedTotal) }}
      </span>
    </button>

    <p v-if="failed" class="nav-error">{{ failed }}</p>
    <p v-else-if="loading" class="nav-loading">{{ t('common.loading') }}</p>

    <ul v-else class="nav-tree">
      <li v-for="row in rows" :key="row.l1Id" class="nav-group">
        <div class="nav-l1">
          <button
            type="button"
            class="nav-caret"
            :aria-expanded="expanded.has(row.l1Id)"
            :aria-label="row.l1Name"
            :data-testid="`category-l1-toggle-${row.l1Id}`"
            @click="toggleL1(row)"
          >{{ expanded.has(row.l1Id) ? '▾' : '▸' }}</button>
          <button
            type="button"
            class="nav-item"
            :class="{ active: isL1(row) }"
            :aria-current="isL1(row) ? 'true' : undefined"
            :data-testid="`category-l1-${row.l1Id}`"
            @click="selectL1(row)"
          >
            <span class="nav-label">{{ row.l1Name }}</span>
            <span class="nav-count" :data-testid="`category-count-l1-${row.l1Id}`">
              {{ countOf(isL1(row), row.total) }}
            </span>
          </button>
        </div>
        <ul v-if="expanded.has(row.l1Id) && row.l2s.length" class="nav-l2">
          <li v-for="l2 in row.l2s" :key="l2.l2Id">
            <button
              type="button"
              class="nav-item child"
              :class="{ active: isL2(row, l2.l2Name) }"
              :aria-current="isL2(row, l2.l2Name) ? 'true' : undefined"
              :data-testid="`category-l2-${l2.l2Id}`"
              @click="selectL2(row, l2.l2Name)"
            >
              <span class="nav-label">{{ l2.l2Name }}</span>
              <span class="nav-count" :data-testid="`category-count-l2-${l2.l2Id}`">
                {{ countOf(isL2(row, l2.l2Name), l2.count) }}
              </span>
            </button>
          </li>
        </ul>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.category-nav {
  /* 宽度交给父栅格（220px）：写死 240 会溢出这一列并挤压右侧表格 */
  width: 100%;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  max-height: 640px;
  overflow-y: auto;
  padding-right: var(--space-2);
  border-right: 1px solid var(--border);
}
.nav-tree,
.nav-l2 {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.nav-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.nav-l1 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.nav-item {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text);
  cursor: pointer;
  font-size: var(--font-size-sm);
  text-align: left;
}
.nav-item.child {
  /*
   * 二级缩进 = 一级文字起点（padding 6 + caret 20 + gap 4 = 30）再右移一个层级（4）。
   * 原先写 var(--space-4)=12px，子项反而比母项左移 18px，看着像「子项挂到了上一个一级分类下」。
   */
  padding-left: 34px;
  color: var(--text-muted);
}
.nav-item:hover {
  background: var(--surface-2);
}
.nav-item.active {
  border-color: var(--accent);
  color: var(--accent);
}
.nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav-count {
  flex: none;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}
.nav-caret {
  flex: none;
  width: 20px;
  border: none;
  background: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
}
.nav-error {
  color: var(--fail);
  font-size: var(--font-size-sm);
}
.nav-loading {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
</style>
