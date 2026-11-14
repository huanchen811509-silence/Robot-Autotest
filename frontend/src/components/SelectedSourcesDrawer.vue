<script setup lang="ts">
/**
 * 来源下钻抽屉（W57-30）：把「这条用例为什么被选中」摊开给人看。
 *
 * 一个用例可以同时来自多个集合与一次手工勾选；取消集合只摘掉对应来源，
 * 所以这里按来源逐行显示，而不是只列一个去重后的用例 id 清单。
 */
import type { RunSource, SourceEntry } from '@/composables/useRunSelection'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = defineProps<{
  open: boolean
  sources: SourceEntry[]
  setNames?: Record<number, string>
}>()

const emit = defineEmits<{ close: [] }>()

function sourceLabel(source: RunSource): string {
  if (source.startsWith('set:')) {
    const id = Number(source.slice(4))
    const name = props.setNames?.[id]
    return t('w57.selectedSources.setLabel', { name: name ?? `#${id}` })
  }
  if (source.startsWith('case:')) return t('w57.selectedSources.manual')
  return source
}
</script>

<template>
  <div v-if="open" class="drawer-mask" data-testid="sources-drawer" @click.self="emit('close')">
    <aside class="drawer" role="dialog" :aria-label="t('w57.selectedSources.aria')">
      <header class="drawer-head">
        <h2>{{ t('w57.selectedSources.title', { count: sources.length }) }}</h2>
        <button type="button" class="drawer-close" data-testid="drawer-close" @click="emit('close')">{{ t('w57.selectedSources.close') }}</button>
      </header>
      <p v-if="sources.length === 0" class="drawer-empty">{{ t('w57.selectedSources.empty') }}</p>
      <ul v-else class="drawer-list">
        <li v-for="entry in sources" :key="entry.caseId" class="drawer-row" :data-testid="`source-row-${entry.caseId}`">
          <span class="drawer-case">{{ t('w57.selectedSources.caseNo', { id: entry.caseId }) }}</span>
          <ul class="drawer-sources">
            <li v-for="source in entry.sources" :key="source" class="drawer-source" :data-testid="`source-${entry.caseId}-${source.replace(':', '-')}`">
              {{ sourceLabel(source) }}
            </li>
          </ul>
        </li>
      </ul>
    </aside>
  </div>
</template>

<style scoped>
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  justify-content: flex-end;
  z-index: 60;
}

.drawer {
  width: min(420px, 92vw);
  height: 100%;
  overflow: auto;
  box-sizing: border-box;
  padding: 16px;
  background: var(--panel);
  border-left: 1px solid var(--border);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.drawer-head h2 {
  margin: 0;
  font-size: var(--font-size-md);
}

.drawer-close {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  cursor: pointer;
  padding: 4px 10px;
}

.drawer-list,
.drawer-sources {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}

.drawer-row {
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}

.drawer-case {
  font-weight: 600;
}

.drawer-sources {
  margin-top: 4px;
  padding-left: 12px;
}

.drawer-source {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  padding: 2px 0;
}

.drawer-empty {
  color: var(--text-muted);
}
</style>
