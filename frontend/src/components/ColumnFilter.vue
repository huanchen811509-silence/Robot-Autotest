<script setup lang="ts">
/**
 * 列筛选弹层（W57-17）。
 *
 * 刻意不接管表格数据：它只产出「这一列要哪些值 / 哪段模糊文本」，由宿主把它写进
 * `useTableFilters`（进而写进 URL）再重新取数。这样筛选、分页、排序、分享链接
 * 只有一份状态，不会出现「弹层里勾了、URL 里没有」的分叉。
 *
 * 颜色只用 `styles/theme.css` 的语义 token；标题容器限两行，避免长列名把表头撑高。
 */
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NInput, NPopover } from 'naive-ui'
import AppIcon from './AppIcon.vue'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    columnKey: string
    title: string
    mode?: 'multi' | 'text'
    options?: { label: string; value: string }[]
    selected?: string[]
    text?: string
  }>(),
  { mode: 'multi', options: () => [], selected: () => [], text: '' },
)

const emit = defineEmits<{
  'update:selected': [value: string[]]
  'update:text': [value: string]
  apply: []
  clear: []
}>()

const draftSelected = ref<string[]>([...props.selected])
const draftText = ref(props.text)
const keyword = ref('')

const visibleOptions = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.options
  return props.options.filter(
    (option) =>
      option.label.toLowerCase().includes(kw) || option.value.toLowerCase().includes(kw),
  )
})

const hasDraft = computed(() =>
  props.mode === 'multi' ? draftSelected.value.length > 0 : draftText.value.trim().length > 0,
)

function syncFromProps(): void {
  draftSelected.value = [...props.selected]
  draftText.value = props.text
  keyword.value = ''
}

// 外部（URL 回填 / 清空全部）改了值时，草稿跟着对齐，弹层不会显示上一轮的勾选。
watch(
  () => [props.selected, props.text],
  () => syncFromProps(),
  { deep: true },
)

function onPopoverToggle(show: boolean): void {
  if (show) syncFromProps()
}

function toggleValue(value: string, checked: boolean): void {
  draftSelected.value = checked
    ? [...new Set([...draftSelected.value, value])]
    : draftSelected.value.filter((item) => item !== value)
}

// 按 mode 只发出对应的一个更新事件：若同时发 update:selected 与 update:text，
// DataTable 的两个 handler 会串行执行——multi 模式下空的 update:text 会把刚提交的
// 多值筛掉（updateFilter(key, [])），text 模式同理。两种模式互斥地 emit 才不会互相覆盖。
function apply(): void {
  if (props.mode === 'multi') emit('update:selected', [...draftSelected.value])
  else emit('update:text', draftText.value.trim())
  emit('apply')
}

function clear(): void {
  draftSelected.value = []
  draftText.value = ''
  keyword.value = ''
  if (props.mode === 'multi') emit('update:selected', [])
  else emit('update:text', '')
  emit('clear')
}
</script>

<template>
  <div class="column-filter" data-check-semantic :data-testid="`column-filter-${columnKey}`">
    <NPopover trigger="click" placement="bottom-start" :width="240" @update:show="onPopoverToggle">
      <template #trigger>
        <button
          type="button"
          class="column-filter__trigger"
          :class="{ 'is-active': hasDraft }"
          :aria-label="t('w57.columnFilter.filterLabel', { title })"
          :data-testid="`column-filter-trigger-${columnKey}`"
        >
          <span class="column-filter__title">{{ title }}</span>
          <AppIcon name="sliders" :size="16" />
        </button>
      </template>

      <div class="column-filter__panel">
        <p class="column-filter__heading">{{ t('w57.columnFilter.filterLabel', { title }) }}</p>

        <template v-if="mode === 'multi'">
          <NInput
            v-model:value="keyword"
            size="small"
            clearable
            :placeholder="t('w57.columnFilter.searchOptions')"
            :aria-label="t('w57.columnFilter.searchOptions')"
            data-testid="column-filter-search"
          />
          <div class="column-filter__options" data-testid="column-filter-options">
            <label v-for="option in visibleOptions" :key="option.value" class="column-filter__option">
              <NCheckbox
                :checked="draftSelected.includes(option.value)"
                @update:checked="(checked: boolean) => toggleValue(option.value, checked)"
              />
              <span>{{ option.label }}</span>
            </label>
            <p v-if="visibleOptions.length === 0" class="column-filter__empty">{{ t('w57.columnFilter.noMatch') }}</p>
          </div>
        </template>

        <template v-else>
          <NInput
            v-model:value="draftText"
            size="small"
            clearable
            :placeholder="t('w57.columnFilter.inputKeyword')"
            :aria-label="t('w57.columnFilter.inputKeyword')"
            data-testid="column-filter-text"
          />
        </template>

        <div class="column-filter__actions">
          <NButton size="tiny" quaternary data-testid="column-filter-clear" @click="clear">
            {{ t('w57.columnFilter.clear') }}
          </NButton>
          <NButton size="tiny" type="primary" data-testid="column-filter-apply" @click="apply">
            {{ t('w57.columnFilter.apply') }}
          </NButton>
        </div>
      </div>
    </NPopover>
  </div>
</template>

<style scoped>
.column-filter {
  display: inline-flex;
  max-width: 100%;
}

.column-filter__trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  max-width: 100%;
  padding: 2px var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background-color 0.15s ease;
}

.column-filter__trigger:hover {
  color: var(--text);
  border-color: var(--border-strong);
}

.column-filter__trigger:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.column-filter__trigger.is-active {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
}

/* 标题容器 ≤2 行：长列名截断，不把表头/筛选条撑成三行 */
.column-filter__title {
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  max-width: 120px;
  text-align: left;
  word-break: break-all;
}

.column-filter__panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.column-filter__heading {
  margin: 0;
  color: var(--text);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.column-filter__options {
  max-height: 200px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.column-filter__option {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text);
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.column-filter__empty {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.column-filter__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  border-top: 1px solid var(--border);
  padding-top: var(--space-2);
}
</style>
