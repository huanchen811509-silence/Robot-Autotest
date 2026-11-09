<script setup lang="ts">
/**
 * 用例集选择器（FR-v3.10-03）。
 *
 * <p>只做一件事：把本项目的用例集列出来供选择，选中的是**用例集**而不是某次执行。
 * 「取哪一次执行」由创建接口决定（默认该集最近一次），这里不掺和——否则
 * 「选了 A 集却传了 B 集的 run」这类错误会在界面上被合法化。
 *
 * <p>为什么单独成组件：`TestReportsView` 的单测要验「切来源后提交什么」，
 * 而 naive-ui 的下拉在 happy-dom 下不渲染选项、点不到。拆出来之后视图侧用桩组件
 * 驱动选中事件，本组件自己只负责取数与空态。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NSelect, NTag } from 'naive-ui'
import { caseSetApi, type TestSet } from '@/api/cases'

const props = defineProps<{ projectId: number; modelValue: number | null }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: number | null): void
  (event: 'select', value: TestSet | null): void
}>()

const { t } = useI18n()

const sets = ref<TestSet[]>([])
const loading = ref(false)
const failed = ref(false)

const options = computed(() =>
  sets.value.map((item) => ({ value: item.id, label: `${item.setCode} · ${item.name}` })),
)

async function load(): Promise<void> {
  if (!props.projectId) {
    sets.value = []
    return
  }
  loading.value = true
  failed.value = false
  try {
    const page = await caseSetApi.page(props.projectId, { page: 1, size: 100 })
    sets.value = page.items
  } catch {
    failed.value = true
    sets.value = []
  } finally {
    loading.value = false
  }
}

function onPick(value: number | null): void {
  emit('update:modelValue', value)
  emit('select', sets.value.find((item) => item.id === value) ?? null)
}

watch(() => props.projectId, () => void load())
onMounted(load)

defineExpose({ reload: load })
</script>

<template>
  <div class="case-set-picker">
    <NSelect
      class="picker"
      filterable
      clearable
      :value="modelValue"
      :loading="loading"
      :options="options"
      :placeholder="t('testReports.caseSetPlaceholder')"
      data-testid="case-set-select"
      @update:value="onPick"
    />
    <NTag v-if="failed" size="small" type="error" data-testid="case-set-failed">
      {{ t('testReports.loadFailed') }}
    </NTag>
    <span v-else-if="!loading && sets.length === 0" class="muted small" data-testid="case-set-empty">
      {{ t('testReports.caseSetEmpty') }}
    </span>
  </div>
</template>

<style scoped>
.case-set-picker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.picker {
  min-width: 240px;
}
</style>
