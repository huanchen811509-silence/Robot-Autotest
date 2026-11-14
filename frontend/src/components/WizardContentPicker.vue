<script setup lang="ts">
/**
 * 向导「内容」步四类来源（W57-12/13）：模板 / 用例集 / 用例 / 测试资产。
 *
 * 可组合、可跳过；默认只展开「用例」分区（用例量最大，其余三类按需展开）。
 * 选择状态用 `v-model` 放在父级向导里持久保存——组件被 v-if 销毁（返回上一步）
 * 再回来时靠 `modelValue` 恢复，不丢选择；分类树也通过 `model-value` 恢复。
 * 每次更新都做去重；提交时若某些用例集已被并发删除，由父级把冲突名单传进
 * `conflicts` 并在此**指名**显示、提供移除入口。
 */
import { onMounted, ref } from 'vue'
import { platformCaseSetApi } from '@/api/platformCaseSets'
import { globalAssetApi } from '@/api/globalAssets'
import CaseCategoryTree from './CaseCategoryTree.vue'
import type { CategoryGroup, TreeCase } from '@/composables/useCategorySelection'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

interface ContentSetRef {
  id: number
  name: string
}
interface ContentSelection {
  templateKeys: string[]
  setIds: number[]
  sets: ContentSetRef[]
  caseIds: number[]
  assetIds: number[]
}

const props = defineProps<{
  modelValue: ContentSelection
  /** 提交时报「集合已删」的冲突项：指名显示。 */
  conflicts?: ContentSetRef[]
  existingCaseIds?: number[]
  initialGroups?: CategoryGroup[]
  caseLoader?: (l1Name: string, l2Name: string) => Promise<{ items: TreeCase[]; total: number }>
  /** 注入选项后不再自行请求（单测 / 已有数据的父级用）。 */
  setOptions?: ContentSetRef[]
  assetOptions?: ContentSetRef[]
}>()

const emit = defineEmits<{ 'update:modelValue': [value: ContentSelection] }>()

type SectionKey = 'template' | 'set' | 'case' | 'asset'
const sectionKeys: SectionKey[] = ['template', 'set', 'case', 'asset']
const sectionLabels: Record<SectionKey, string> = {
  template: t('w57.wizardContent.sectionTemplate'),
  set: t('w57.wizardContent.sectionSet'),
  case: t('w57.wizardContent.sectionCase'),
  asset: t('w57.wizardContent.sectionAsset'),
}
/** 默认只展开「用例」分区。 */
const openSections = ref<Set<SectionKey>>(new Set<SectionKey>(['case']))

const sets = ref<ContentSetRef[]>(props.setOptions ?? [])
const assets = ref<ContentSetRef[]>(props.assetOptions ?? [])
const loadError = ref('')

onMounted(async () => {
  try {
    if (!props.setOptions) {
      const rows = await platformCaseSetApi.list({})
      sets.value = rows.map((row) => ({ id: row.id, name: row.name }))
    }
    if (!props.assetOptions) {
      const rows = await globalAssetApi.list({ type: 'DEVICE', availability: 'active' })
      assets.value = rows.map((row) => ({ id: row.id, name: row.name }))
    }
  } catch {
    loadError.value = t('w57.wizardContent.loadFailed')
  }
})

function toggleSection(key: SectionKey): void {
  const next = new Set(openSections.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  openSections.value = next
}

/** 所有更新都经过这里：四类各自去重（提交去重）。 */
function emitChange(next: ContentSelection): void {
  emit('update:modelValue', {
    templateKeys: [...new Set(next.templateKeys)],
    setIds: [...new Set(next.setIds)],
    sets: next.sets.filter((item, index, all) => all.findIndex((x) => x.id === item.id) === index),
    caseIds: [...new Set(next.caseIds)],
    assetIds: [...new Set(next.assetIds)],
  })
}

function toggleSet(option: ContentSetRef): void {
  const current = props.modelValue
  const active = current.setIds.includes(option.id)
  emitChange({
    ...current,
    setIds: active ? current.setIds.filter((id) => id !== option.id) : [...current.setIds, option.id],
    sets: active ? current.sets.filter((item) => item.id !== option.id) : [...current.sets, option],
  })
}

function toggleAsset(option: ContentSetRef): void {
  const current = props.modelValue.assetIds
  const next = current.includes(option.id) ? current.filter((id) => id !== option.id) : [...current, option.id]
  emitChange({ ...props.modelValue, assetIds: next })
}

function onTreeChange(ids: number[]): void {
  emitChange({ ...props.modelValue, caseIds: ids })
}

function removeConflict(id: number): void {
  const current = props.modelValue
  emitChange({
    ...current,
    setIds: current.setIds.filter((item) => item !== id),
    sets: current.sets.filter((item) => item.id !== id),
  })
}

function dedupe(): void {
  emitChange({ ...props.modelValue })
}
</script>

<template>
  <div class="wizard-content" data-testid="wizard-content">
    <p class="picker-hint">{{ t('w57.wizardContent.hint') }}</p>

    <!-- 已选摘要：常显，不随分区折叠消失 -->
    <div class="summary" data-testid="selection-summary">
      <span>{{ t('w57.wizardContent.selectedTemplate', { count: modelValue.templateKeys.length }) }}</span>
      <span>{{ t('w57.wizardContent.selectedSet', { count: modelValue.setIds.length }) }}</span>
      <span>{{ t('w57.wizardContent.selectedCase', { count: modelValue.caseIds.length }) }}</span>
      <span>{{ t('w57.wizardContent.selectedAsset', { count: modelValue.assetIds.length }) }}</span>
      <button type="button" class="link-btn" data-testid="dedupe" @click="dedupe">{{ t('w57.wizardContent.dedupe') }}</button>
    </div>

    <!-- 集合已删的并发错误：指名 + 移除 -->
    <div v-if="conflicts && conflicts.length" class="conflicts" data-testid="set-conflicts">
      <p>{{ t('w57.wizardContent.conflict', { names: conflicts.map((item) => item.name).join('、') }) }}</p>
      <button
        v-for="item in conflicts"
        :key="item.id"
        type="button"
        class="link-btn"
        :data-testid="`remove-conflict-${item.id}`"
        @click="removeConflict(item.id)"
      >
        {{ t('w57.wizardContent.remove', { name: item.name }) }}
      </button>
    </div>

    <p v-if="loadError" class="picker-error">{{ loadError }}</p>

    <section v-for="key in sectionKeys" :key="key" class="picker-section" :data-testid="`section-${key}`">
      <button
        type="button"
        class="section-head"
        :aria-expanded="openSections.has(key)"
        :data-testid="`section-toggle-${key}`"
        @click="toggleSection(key)"
      >
        <span class="caret">{{ openSections.has(key) ? '▾' : '▸' }}</span>
        <span class="section-title">{{ sectionLabels[key] }}</span>
        <span class="section-skip">{{ t('w57.wizardContent.skippable') }}</span>
      </button>

      <div v-if="openSections.has(key)" class="section-body" :data-testid="`section-body-${key}`">
        <template v-if="key === 'template'">
          <p class="picker-hint" data-testid="template-inherited">
            {{ t('w57.wizardContent.templateInherited') }}
          </p>
        </template>

        <template v-else-if="key === 'set'">
          <label v-for="item in sets" :key="item.id" class="picker-item">
            <input
              type="checkbox"
              :checked="modelValue.setIds.includes(item.id)"
              :data-testid="`set-${item.id}`"
              @change="toggleSet(item)"
            />
            <span class="picker-name">{{ item.name }}</span>
          </label>
          <p v-if="sets.length === 0" class="picker-empty">{{ t('w57.wizardContent.noSet') }}</p>
        </template>

        <template v-else-if="key === 'case'">
          <CaseCategoryTree
            :initial-groups="initialGroups"
            :model-value="modelValue.caseIds"
            :existing-case-ids="existingCaseIds"
            :case-loader="caseLoader"
            @change="onTreeChange"
          />
        </template>

        <template v-else>
          <label v-for="item in assets" :key="item.id" class="picker-item">
            <input
              type="checkbox"
              :checked="modelValue.assetIds.includes(item.id)"
              :data-testid="`asset-${item.id}`"
              @change="toggleAsset(item)"
            />
            <span class="picker-name">{{ item.name }}</span>
          </label>
          <p v-if="assets.length === 0" class="picker-empty">{{ t('w57.wizardContent.noAsset') }}</p>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.wizard-content {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.picker-hint,
.picker-empty {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.picker-error {
  color: var(--warn);
  font-size: var(--font-size-sm);
}

.summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  margin: 10px 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  font-size: var(--font-size-sm);
}

.conflicts {
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px solid var(--warn);
  border-radius: var(--radius-sm);
  color: var(--warn);
  font-size: var(--font-size-sm);
}

.conflicts p {
  margin: 0 0 6px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.link-btn {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
  padding: 2px 6px;
  font-size: var(--font-size-sm);
}

.picker-section {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  margin-top: 10px;
  overflow: hidden;
}

.section-head {
  width: 100%;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 0;
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.section-head:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.caret {
  color: var(--text-muted);
}

.section-title {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-skip {
  margin-left: auto;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.section-body {
  padding: 10px 12px;
  border-top: 1px solid var(--border);
  max-height: 320px;
  overflow: auto;
}

.picker-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: var(--font-size-sm);
}

.picker-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
</style>
