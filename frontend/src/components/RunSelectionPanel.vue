<script setup lang="ts">
/**
 * 执行选择面板（W57-29/30）：四模式切换 + 用例集搜索分页 + 用例清单 + 来源下钻。
 *
 * 选区本身不持有选择状态：全部由父级 useRunSelection 驱动（props 进、事件出），
 * 这样「四模式各自保留草稿」「取消集合只摘该来源」都只在唯一真源里发生一次。
 *
 * 用例集列表去掉了旧实现的 `size:100` 硬上限：搜索与翻页都以事件抛给父级，
 * 由父级用 keyword + page 走后端分页。清单前 200 条与 >10000 条二次确认在这里提示。
 */
import { computed, ref } from 'vue'
import type { TestCase, TestSet } from '@/api/cases'
import type { RunMode, SourceEntry } from '@/composables/useRunSelection'
import SelectedSourcesDrawer from './SelectedSourcesDrawer.vue'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = defineProps<{
  caseSets: TestSet[]
  caseRows: TestCase[]
  caseTotal: number
  casePage: number
  casePageSize: number
  selectedCaseIds: number[]
  selectedCount: number
  selectedSetIds: number[]
  mode: RunMode
  sources: SourceEntry[]
  setNames?: Record<number, string>
  setTotal?: number
  setPage?: number
  setPageSize?: number
  allSelected?: boolean
}>()

const emit = defineEmits<{
  'toggle-set': [set: TestSet]
  'toggle-case': [caseId: number]
  'set-mode': [mode: RunMode]
  'set-all': [on: boolean]
  clear: []
  'search-sets': [keyword: string]
  'set-page': [page: number]
  'search-cases': [keyword: string]
  'case-page': [page: number]
}>()

const modes: RunMode[] = ['ALL', 'SET', 'CASE', 'MIXED']
const modeLabels: Record<RunMode, string> = {
  ALL: t('w57.runSelection.modeAll'),
  SET: t('w57.runSelection.modeSet'),
  CASE: t('w57.runSelection.modeCase'),
  MIXED: t('w57.runSelection.modeMixed'),
}

const setKeyword = ref('')
const caseKeyword = ref('')
const drawerOpen = ref(false)
const largeConfirmed = ref(false)

const showSets = computed(() => props.mode === 'SET' || props.mode === 'MIXED')
const showCases = computed(() => props.mode === 'CASE' || props.mode === 'MIXED')
const showLargeConfirm = computed(() => props.caseTotal > 10000)
const needsLargeConfirm = computed(() => showLargeConfirm.value && !largeConfirmed.value)
const overCap = computed(() => props.caseTotal > 200)

const setMaxPage = computed(() =>
  Math.max(1, Math.ceil((props.setTotal ?? props.caseSets.length) / (props.setPageSize ?? 20))),
)
const caseMaxPage = computed(() =>
  Math.max(1, Math.ceil(props.caseTotal / (props.casePageSize || 20))),
)
</script>

<template>
  <div class="run-selection" data-testid="run-selection">
    <div class="mode-bar">
      <span class="mode-label">{{ t('w57.runSelection.selectMode') }}</span>
      <button
        v-for="item in modes"
        :key="item"
        type="button"
        class="mode-btn"
        :class="{ active: mode === item }"
        :aria-pressed="mode === item"
        :data-testid="`mode-${item}`"
        @click="emit('set-mode', item)"
      >
        {{ modeLabels[item] }}
      </button>
      <span class="mode-spacer" />
      <button type="button" class="link-btn" data-testid="open-sources" @click="drawerOpen = true">
        {{ t('w57.runSelection.viewSources', { count: selectedCount }) }}
      </button>
      <button type="button" class="link-btn" data-testid="clear-selection" @click="emit('clear')">{{ t('w57.runSelection.clear') }}</button>
    </div>
    <p class="mode-hint" data-testid="mode-hint">{{ t('w57.runSelection.modeHint', { mode: modeLabels[mode] }) }}</p>

    <div v-if="mode === 'ALL'" class="all-panel" data-testid="all-panel">
      <span>{{ t('w57.runSelection.allHint') }}</span>
      <button
        type="button"
        class="mode-btn"
        :class="{ active: allSelected }"
        :disabled="needsLargeConfirm"
        data-testid="mode-all-confirm"
        @click="emit('set-all', !allSelected)"
      >
        {{ allSelected ? t('w57.runSelection.markedAll') : t('w57.runSelection.markAll') }}
      </button>
    </div>

    <div v-if="showLargeConfirm" class="large-confirm" data-testid="large-confirm-bar">
      {{ t('w57.runSelection.largeHint') }}
      <button
        type="button"
        class="mode-btn"
        :disabled="largeConfirmed"
        data-testid="large-confirm"
        @click="largeConfirmed = true"
      >
        {{ largeConfirmed ? t('w57.runSelection.confirmed') : t('w57.runSelection.confirmScale') }}
      </button>
    </div>

    <div class="selection-grid">
      <section v-if="showSets" class="card" data-testid="set-section">
        <h2>{{ t('w57.runSelection.caseSet') }}</h2>
        <div class="filters">
          <input
            v-model="setKeyword"
            class="input"
            type="search"
            :placeholder="t('w57.runSelection.searchSetPlaceholder')"
            :aria-label="t('w57.runSelection.searchSetAria')"
            data-testid="set-search"
            @keyup.enter="emit('search-sets', setKeyword)"
          />
          <button type="button" class="btn" data-testid="set-search-btn" @click="emit('search-sets', setKeyword)">
            {{ t('w57.runSelection.search') }}
          </button>
        </div>
        <ul class="set-list">
          <li v-for="set in caseSets" :key="set.id">
            <label class="set-row">
              <input
                type="checkbox"
                :checked="selectedSetIds.includes(set.id)"
                :data-testid="`pick-set-${set.id}`"
                @change="emit('toggle-set', set)"
              />
              <span class="set-name">{{ set.name }}</span>
              <span class="mono muted">{{ set.setCode }}</span>
            </label>
          </li>
        </ul>
        <p v-if="caseSets.length === 0" class="muted">{{ t('w57.runSelection.noSetMatch') }}</p>
        <div class="pager" data-testid="set-pager">
          <button type="button" class="btn" :disabled="(setPage ?? 1) <= 1" @click="emit('set-page', (setPage ?? 1) - 1)">
            {{ t('w57.runSelection.prev') }}
          </button>
          <span class="muted">{{ setPage ?? 1 }} / {{ setMaxPage }}</span>
          <button
            type="button"
            class="btn"
            :disabled="(setPage ?? 1) >= setMaxPage"
            @click="emit('set-page', (setPage ?? 1) + 1)"
          >
            {{ t('w57.runSelection.next') }}
          </button>
        </div>
      </section>

      <section v-if="showCases" class="card" data-testid="case-section">
        <h2>{{ t('w57.runSelection.modeCase') }}</h2>
        <div class="filters">
          <input
            v-model="caseKeyword"
            class="input"
            type="search"
            :placeholder="t('w57.runSelection.searchCasePlaceholder')"
            :aria-label="t('w57.runSelection.searchCaseAria')"
            data-testid="case-search"
            @keyup.enter="emit('search-cases', caseKeyword)"
          />
          <button type="button" class="btn" data-testid="case-search-btn" @click="emit('search-cases', caseKeyword)">
            {{ t('w57.runSelection.search') }}
          </button>
        </div>
        <p v-if="overCap" class="muted small" data-testid="case-list-cap">
          {{ t('w57.runSelection.caseCap', { total: caseTotal }) }}
        </p>
        <ul class="case-list">
          <li v-for="row in caseRows" :key="row.id">
            <label class="set-row">
              <input
                type="checkbox"
                :checked="selectedCaseIds.includes(row.id)"
                :data-testid="`pick-${row.id}`"
                @change="emit('toggle-case', row.id)"
              />
              <span class="mono">{{ row.caseCode }}</span>
              <span class="set-name">{{ row.title }}</span>
            </label>
          </li>
        </ul>
        <p v-if="caseRows.length === 0" class="muted">{{ t('w57.runSelection.noCaseMatch') }}</p>
        <div class="pager" data-testid="case-pager">
          <button type="button" class="btn" :disabled="casePage <= 1" @click="emit('case-page', casePage - 1)">
            {{ t('w57.runSelection.prev') }}
          </button>
          <span class="muted">{{ casePage }} / {{ caseMaxPage }}</span>
          <button
            type="button"
            class="btn"
            :disabled="casePage >= caseMaxPage"
            @click="emit('case-page', casePage + 1)"
          >
            {{ t('w57.runSelection.next') }}
          </button>
        </div>
      </section>
    </div>

    <p class="selected-count" data-testid="selected-count">{{ t('w57.runSelection.selectedCount', { count: selectedCount }) }}</p>

    <SelectedSourcesDrawer
      :open="drawerOpen"
      :sources="sources"
      :set-names="setNames"
      @close="drawerOpen = false"
    />
  </div>
</template>

<style scoped>
.run-selection {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.mode-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.mode-label {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.mode-spacer {
  flex: 1 1 auto;
}

.mode-btn {
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
  white-space: nowrap;
}

.mode-btn.active {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}

.mode-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.link-btn {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
  padding: 4px;
}

.mode-hint,
.small {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.all-panel,
.large-confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.large-confirm {
  color: var(--warn);
  border-color: var(--warn);
}

.selection-grid {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(240px, 1fr);
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.card {
  min-width: 0;
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.card h2 {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
}

.filters {
  display: flex;
  gap: 8px;
  margin-bottom: var(--space-3);
}

.input {
  flex: 1 1 140px;
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  box-sizing: border-box;
}

.set-list,
.case-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 240px;
  overflow: auto;
}

.set-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  cursor: pointer;
  min-width: 0;
}

.set-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--font-mono, monospace);
  flex: 0 0 auto;
}

.muted {
  color: var(--text-muted);
}

.pager {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: var(--space-3);
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.selected-count {
  margin: var(--space-3) 0 0;
  font-weight: 600;
}

@media (max-width: 1080px) {
  .selection-grid {
    grid-template-columns: 1fr;
  }
}
</style>
