<script setup lang="ts">
/**
 * 全局命令面板（NAV-04 / FR-v2.0-09 / v3.1.0「顺手」）：
 * Ctrl/Cmd+K 唤起，中英文搜索页面；输入 2 字符以上再并行搜索实体
 * （用例 / 运行 / 报告 / 计划 / 资产）。
 *
 * <p>实体结果与页面导航共用一条扁平列表，↑↓ 与 Enter 始终作用在
 * 「眼睛看到的顺序」上；分组标题只负责视觉分隔，不进键盘索引。
 */
import { computed, nextTick, onBeforeUnmount, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import enUS from '@/locales/en-US'
import { useAuthStore } from '@/stores/auth'
import { searchEntities, type SearchHit } from '@/api/search'
import {
  allNavLeaves,
  capabilitiesForRole,
  roleFromCodes,
} from '@/config/navigation'
import {
  searchEntries,
  loadRecent,
  pushRecent,
  loadFavorites,
  toggleFavorite,
  loadPaletteScope,
  savePaletteScope,
  type PaletteEntry,
  type PaletteScope,
} from '@/config/commandPalette'
import {
  createEntitySearch,
  entityKey,
  flattenHits,
  groupHits,
  type EntitySearchState,
} from '@/config/entitySearch'
import { formatDateTime } from '@/utils/format'
import AppIcon from '@/components/AppIcon.vue'
import { useFocusTrap } from '@/composables/useFocusTrap'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const inputEl = ref<HTMLInputElement | null>(null)
const paletteRef = ref<HTMLElement | null>(null)
const keyword = ref('')
const active = ref(0)
const recent = ref<string[]>(loadRecent())
const favorites = ref<string[]>(loadFavorites())
const scope = ref<PaletteScope>(loadPaletteScope())
const searchState = ref<EntitySearchState>({ hits: [], loading: false, error: false })

/** 英文别名直接从语言包按 key 路径取，中文界面也能用英文搜到。 */
function englishLabel(path: string): string {
  let node: unknown = enUS
  for (const part of path.split('.')) {
    node = (node as Record<string, unknown> | undefined)?.[part]
  }
  return typeof node === 'string' ? node : path
}

const capabilities = computed(() =>
  capabilitiesForRole(roleFromCodes(auth.user?.roles)),
)

const entries = computed<PaletteEntry[]>(() =>
  allNavLeaves()
    .filter((leaf) => !leaf.capability || capabilities.value.includes(leaf.capability))
    .map((leaf): PaletteEntry => ({
      key: leaf.key,
      label: t(leaf.labelKey),
      alias: englishLabel(leaf.labelKey),
      path: leaf.path,
      query: leaf.query,
      capability: leaf.capability,
    })).concat(extraEntries()),
)

/**
 * v2.0 W3/W4/W5 pages are tools rather than menu domains, so they are reachable
 * from the palette without adding a seventh top-level nav item (NAV-01).
 */
function extraEntries(): PaletteEntry[] {
  return [
    { key: 'tool-imports', label: t('imports.title'), alias: enUS.imports.title, path: '/imports' },
    { key: 'tool-groups', label: t('groups.title'), alias: enUS.groups.title, path: '/groups' },
    { key: 'tool-kernels', label: t('kernels.title'), alias: enUS.kernels.title, path: '/kernels' },
  ]
}

const navResults = computed<PaletteEntry[]>(() => {
  const matched = searchEntries(entries.value, keyword.value)
  if (keyword.value.trim()) return matched
  const priority = [...new Set([...favorites.value, ...recent.value])]
  const head = priority
    .map((key) => entries.value.find((entry) => entry.key === key))
    .filter((entry): entry is PaletteEntry => Boolean(entry))
  const rest = matched.filter((entry) => !priority.includes(entry.key))
  return [...head, ...rest]
})

const entityHits = computed(() => searchState.value.hits)
const entityGroups = computed(() => groupHits(entityHits.value))
/** 键盘索引的顺序：实体（按分组顺序）在前，页面导航在后。 */
const orderedEntities = computed(() => flattenHits(entityHits.value))
const entityIndexMap = computed(
  () => new Map(orderedEntities.value.map((hit, index) => [entityKey(hit), index])),
)
const navIndexMap = computed(
  () => new Map(navResults.value.map((entry, index) => [entry.key, orderedEntities.value.length + index])),
)
const rowCount = computed(() => orderedEntities.value.length + navResults.value.length)
const showEntitySearch = computed(() => keyword.value.trim().length >= 2)
const entityLoading = computed(() => searchState.value.loading)
const entityError = computed(() => searchState.value.error)

const scopeOptions = computed<{ value: PaletteScope; label: string }[]>(() => [
  { value: 'current', label: t('palette.scopeCurrent') },
  { value: 'all', label: t('palette.scopeAll') },
])

const runner = createEntitySearch({
  fetch: (request) =>
    searchEntities({
      q: request.q,
      projectId: request.projectId ?? undefined,
      scope: request.scope,
    }),
  onState: (state) => {
    searchState.value = state
  },
})

function runEntitySearch(): void {
  runner.search({
    q: keyword.value,
    scope: scope.value,
    // scope=all 也要带当前项目 id：后端用它做租户校验与可见项目展开的锚点。
    projectId: auth.projectId ?? null,
  })
}

function close(): void {
  // 关闭时推进序号并清空结果，避免关闭后返回的响应把状态写回来。
  runner.search({ q: '', scope: scope.value, projectId: null })
  emit('update:open', false)
}

function go(entry: PaletteEntry): void {
  recent.value = pushRecent(entry.key, recent.value)
  void router.push(entry.query ? { path: entry.path, query: entry.query } : { path: entry.path })
  close()
}

function openHit(hit: SearchHit): void {
  if (!hit.path) return
  recent.value = pushRecent(entityKey(hit), recent.value)
  void router.push(hit.path)
  close()
}

function setScope(value: PaletteScope): void {
  if (scope.value === value) return
  scope.value = value
  savePaletteScope(value)
  active.value = 0
  runEntitySearch()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    active.value = Math.min(active.value + 1, Math.max(rowCount.value - 1, 0))
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    active.value = Math.max(active.value - 1, 0)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const entity = orderedEntities.value[active.value]
    if (entity) {
      openHit(entity)
      return
    }
    const navOffset = active.value - orderedEntities.value.length
    const target = navResults.value[navOffset]
    if (target) go(target)
  }
}

function flipFavorite(key: string): void {
  favorites.value = toggleFavorite(key, favorites.value)
}

watch(keyword, () => {
  active.value = 0
  runEntitySearch()
})

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      close()
      return
    }
    keyword.value = ''
    active.value = 0
    scope.value = loadPaletteScope()
    recent.value = loadRecent()
    favorites.value = loadFavorites()
    searchState.value = { hits: [], loading: false, error: false }
    await nextTick()
    inputEl.value?.focus()
  },
)

useFocusTrap(paletteRef, toRef(props, 'open'), { onEscape: close })

onBeforeUnmount(() => runner.dispose())
</script>

<template>
  <div v-if="open" class="palette-backdrop" @click.self="close">
    <div
      ref="paletteRef"
      class="palette"
      role="dialog"
      aria-modal="true"
      :aria-label="t('palette.open')"
    >
      <input
        ref="inputEl"
        v-model="keyword"
        class="palette-input"
        type="text"
        :placeholder="t('palette.placeholder')"
        :aria-label="t('palette.open')"
        @keydown="onKeydown"
      />

      <div class="palette-scope" role="group" :aria-label="t('palette.scope')">
        <button
          v-for="option in scopeOptions"
          :key="option.value"
          type="button"
          class="palette-scope__item"
          :class="{ 'palette-scope__item--on': scope === option.value }"
          :aria-pressed="scope === option.value"
          @click="setScope(option.value)"
        >
          {{ option.label }}
        </button>
      </div>

      <p class="palette-hint">{{ t('palette.hint') }}</p>

      <div class="palette-list">
        <p v-if="entityLoading" class="palette-status">
          <AppIcon name="loader" :size="16" class="palette-spin" />
          {{ t('palette.searching') }}
        </p>
        <p v-else-if="entityError" class="palette-status palette-status--error">
          {{ t('palette.searchFailed') }}
          <button type="button" class="palette-retry" @click="runEntitySearch">
            {{ t('ui.retry') }}
          </button>
        </p>
        <p
          v-else-if="showEntitySearch && entityGroups.length === 0"
          class="palette-status"
        >
          {{ t('palette.noEntity') }}
        </p>

        <template v-if="entityGroups.length > 0">
          <p class="palette-group">{{ t('palette.entityResults') }}</p>
          <template v-for="group in entityGroups" :key="group.type">
            <p class="palette-group palette-group--type">
              <span>{{ t(`palette.entityGroups.${group.type}`) }}</span>
              <span class="palette-group-count">{{ group.hits.length }}</span>
            </p>
            <div
              v-for="hit in group.hits"
              :key="entityKey(hit)"
              class="palette-row"
              :class="{ active: entityIndexMap.get(entityKey(hit)) === active }"
              @mouseenter="active = entityIndexMap.get(entityKey(hit)) ?? active"
            >
              <button class="palette-entry palette-entry--entity" type="button" @click="openHit(hit)">
                <span class="palette-entity-main">
                  <span class="palette-entity-title">{{ hit.title }}</span>
                  <span class="palette-type">{{ t(`palette.entityGroups.${hit.type}`) }}</span>
                </span>
                <span class="palette-entity-meta">
                  <span v-if="hit.projectName" class="palette-meta-item">{{ hit.projectName }}</span>
                  <span v-if="hit.badge" class="palette-meta-item">{{ hit.badge }}</span>
                  <span v-if="hit.updatedAt" class="palette-meta-item">
                    {{ t('palette.updatedAt', { time: formatDateTime(hit.updatedAt) }) }}
                  </span>
                </span>
                <span v-if="hit.subtitle" class="palette-entity-sub">{{ hit.subtitle }}</span>
              </button>
            </div>
          </template>
        </template>

        <template v-if="navResults.length > 0">
          <p v-if="showEntitySearch" class="palette-group palette-group--type">
            <span>{{ t('palette.navigation') }}</span>
            <span class="palette-group-count">{{ navResults.length }}</span>
          </p>
          <div
            v-for="entry in navResults"
            :key="entry.key"
            class="palette-row"
            :class="{ active: navIndexMap.get(entry.key) === active }"
            @mouseenter="active = navIndexMap.get(entry.key) ?? active"
          >
            <button class="palette-entry" type="button" @click="go(entry)">
              <span>{{ entry.label }}</span>
              <span class="palette-alias">{{ entry.alias }} · {{ entry.path }}</span>
            </button>
            <button
              class="palette-star"
              type="button"
              :aria-label="t('palette.favorite')"
              :title="t('palette.favorite')"
              @click="flipFavorite(entry.key)"
            >
              {{ favorites.includes(entry.key) ? '★' : '☆' }}
            </button>
          </div>
        </template>

        <p
          v-if="rowCount === 0 && !entityLoading && !entityError && !showEntitySearch"
          class="palette-empty"
        >
          {{ t('palette.empty') }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.palette-backdrop {
  /* FR-v4.0-02：遮罩从顶栏下沿开始，顶栏仍可点；面板下移 8vh 保留原视觉重心 */
  position: fixed; top: var(--header-h, 0); right: 0; bottom: 0; left: 0;
  z-index: var(--z-modal); display: flex; justify-content: center;
  padding-top: 8vh; background: var(--overlay);
}
.palette {
  width: min(560px, 92vw); max-height: 72vh; display: flex; flex-direction: column;
  border: 1px solid var(--border); border-radius: var(--radius-xl); padding: var(--space-4);
  background: var(--panel); color: var(--text);
  box-shadow: var(--shadow);
}
/* 亮/暗底色由 --panel / --text 随 data-theme 切换，不在这里写死第二套色 */
.palette-input {
  width: 100%; box-sizing: border-box; padding: 10px 12px; font: inherit; font-size: var(--font-size-md);
  border-radius: var(--radius-xl); border: 1px solid var(--border-strong);
  background: transparent; color: inherit;
}
.palette-input:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 1px; }

.palette-scope { display: flex; gap: var(--space-1); margin-top: var(--space-2); }
.palette-scope__item {
  padding: 2px var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-xl);
  background: transparent; color: var(--text-muted); font: inherit; font-size: var(--font-size-xs);
  cursor: pointer;
}
.palette-scope__item:hover { background: var(--surface-hover); color: var(--text); }
.palette-scope__item--on {
  background: var(--accent-soft); border-color: var(--accent); color: var(--accent);
}
.palette-scope__item--on:hover { background: var(--accent-soft); color: var(--accent); }

.palette-hint { margin: var(--space-2) 2px; font-size: var(--font-size-xs); color: var(--text-muted); }
.palette-list { overflow: auto; display: flex; flex-direction: column; gap: 2px; }
.palette-empty { padding: 18px; text-align: center; font-size: var(--font-size-sm); color: var(--text-muted); }
.palette-status {
  display: flex; align-items: center; gap: var(--space-2);
  padding: var(--space-3); font-size: var(--font-size-sm); color: var(--text-muted);
}
.palette-status--error { color: var(--fail); }
.palette-retry {
  padding: 0; border: 0; background: none; color: var(--accent);
  font: inherit; font-size: var(--font-size-xs); cursor: pointer; text-decoration: underline;
}
.palette-retry:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.palette-spin { animation: palette-spin 1s linear infinite; }
@keyframes palette-spin { to { transform: rotate(360deg); } }

.palette-group {
  margin: var(--space-2) 0 2px; padding: 0 var(--space-2);
  font-size: var(--font-size-xs); color: var(--text-muted); text-transform: uppercase;
  letter-spacing: .04em;
}
.palette-group--type { display: flex; align-items: center; justify-content: space-between; }
.palette-group-count {
  min-width: 18px; text-align: center; border-radius: 9px;
  background: var(--surface-2); color: var(--text-muted);
}

.palette-row { display: flex; align-items: center; border-radius: var(--radius-lg); }
.palette-row.active { background: var(--surface-hover); }
.palette-entry {
  flex: 1; display: flex; justify-content: space-between; gap: var(--space-4); align-items: baseline;
  padding: var(--space-3) 10px; border: 0; background: transparent; color: inherit;
  font: inherit; font-size: var(--font-size-base); text-align: left; cursor: pointer;
}
.palette-alias { font-size: var(--font-size-xs); color: var(--text-muted); }

.palette-entry--entity { flex-direction: column; align-items: stretch; gap: 2px; }
.palette-entity-main { display: flex; align-items: center; gap: var(--space-2); min-width: 0; }
.palette-entity-title {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text);
}
.palette-type {
  flex: none; padding: 0 var(--space-1); border: 1px solid var(--border); border-radius: var(--radius-sm);
  font-size: var(--font-size-xs); color: var(--text-muted); background: var(--surface-2);
}
.palette-entity-meta { display: flex; gap: var(--space-3); flex-wrap: wrap; }
.palette-meta-item { font-size: var(--font-size-xs); color: var(--text-muted); }
.palette-entity-sub {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: var(--font-size-xs); color: var(--text-muted);
}

.palette-star {
  padding: 6px 10px; border: 0; background: transparent; color: inherit;
  cursor: pointer; font-size: var(--font-size-md);
}
.palette-star:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }

@media (prefers-reduced-motion: reduce) {
  .palette-spin { animation: none; }
  .palette-scope__item { transition: none; }
}
</style>
