<script setup lang="ts">
/**
 * 执行记录选择器（FR-v3.10-02）。
 *
 * 背景：对外报告草稿原来要**手打 runId**（`RUN-20261109...`），打错一个字符就是
 * 「报告建不出来」，而用户手里只有「上周二那次回归」这种信息。这里改成从本项目
 * 的运行记录里选：下拉显示 runId / 时间 / 结论 / 用例数，支持关键词与时间范围过滤。
 *
 * 过滤放在前端做，因为后端 `RunPageQuery` 目前只有 state/page/size 三个参数
 * （见 `api/execution.ts`）。也就是说：**只在已加载的最近 100 条里过滤**，
 * 更早的 run 需要先缩小时间范围或直接输 runId（高级入口）。
 * 这条限制写进 DEV-PLAN 偏差，别让人以为「搜不到 = 不存在」。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NInput, NSelect, NTag } from 'naive-ui'
import { executionApi, type ExecutionRun } from '@/api/execution'
import { filterRuns, runOptionLabel, type RunRange } from '@/utils/runPicker'

const props = defineProps<{ projectId: number; modelValue: string | null }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: string | null): void
  (event: 'select', run: ExecutionRun | null): void
}>()

const { t } = useI18n()

const runs = ref<ExecutionRun[]>([])
const loading = ref(false)
const failed = ref(false)
const keyword = ref('')
/** 时间范围：all / 7d / 30d。用固定档位而不是日期选择器——「选一次报告」不需要精确到天。 */
const range = ref<RunRange>('all')

const RANGE_OPTIONS = computed(() => [
  { value: 'all', label: t('testReports.rangeAll') },
  { value: '7d', label: t('testReports.range7d') },
  { value: '30d', label: t('testReports.range30d') },
])

/** 过滤口径见 `@/utils/runPicker`（时间基准、关键词只匹配 runId、时间倒序）。 */
const filtered = computed(() => filterRuns(runs.value, keyword.value, range.value))

const options = computed(() =>
  filtered.value.map((run) => ({
    value: run.runId,
    label: runOptionLabel(run),
    run,
  })),
)

const selected = computed(() => runs.value.find((run) => run.runId === props.modelValue) ?? null)

async function load(): Promise<void> {
  if (!props.projectId) {
    runs.value = []
    return
  }
  loading.value = true
  failed.value = false
  try {
    const page = await executionApi.page(props.projectId, { page: 1, size: 100 })
    runs.value = page.items
  } catch {
    failed.value = true
    runs.value = []
  } finally {
    loading.value = false
  }
}

function onPick(value: string | null): void {
  emit('update:modelValue', value)
  const run = runs.value.find((item) => item.runId === value) ?? null
  emit('select', run)
}

watch(() => props.projectId, () => void load())
onMounted(load)

defineExpose({ reload: load })
</script>

<template>
  <div class="run-picker">
    <NInput
      v-model:value="keyword"
      class="run-picker-search"
      clearable
      :placeholder="t('testReports.searchPlaceholder')"
      data-testid="run-picker-keyword"
    />
    <NSelect
      v-model:value="range"
      class="run-picker-range"
      :options="RANGE_OPTIONS"
      data-testid="run-picker-range"
    />
    <NSelect
      :value="modelValue"
      class="run-picker-select"
      filterable
      clearable
      :loading="loading"
      :options="options"
      :placeholder="t('testReports.runPickerPlaceholder')"
      :consistent-menu-width="false"
      data-testid="run-picker-select"
      @update:value="onPick"
    />
    <NTag v-if="failed" size="small" type="error" data-testid="run-picker-failed">
      {{ t('testReports.loadFailed') }}
    </NTag>
    <span v-else-if="!loading && filtered.length === 0" class="muted small" data-testid="run-picker-empty">
      {{ t('testReports.runPickerEmpty') }}
    </span>
    <span v-else-if="selected" class="muted small" data-testid="run-picker-summary">
      {{ t('testReports.caseCount', { n: selected.totalTests }) }}
    </span>
  </div>
</template>

<style scoped>
.run-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.run-picker-search {
  width: 200px;
}
.run-picker-range {
  width: 120px;
}
.run-picker-select {
  min-width: 420px;
}
</style>
