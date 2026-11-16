<script setup lang="ts">
/**
 * 实体选择器（v5.20.0 / FR-v5.20-02）。
 *
 * 解决的问题：执行计划「环境 ID」、环境采集「凭据 ID」原来是 `input type=number`，
 * 用户得先去别的页面把 ID 背下来再回来填——这不是"高级功能"，是让人猜。
 *
 * 三条设计约束：
 * 1. **可搜索**：`filterable`，输入中文名/编号都能命中；
 * 2. **自解释**：选项文案统一为「名称（ID）」，选中后框里显示的也是它，不是一串数字；
 * 3. **兜底不丢**：保留「高级：手输 ID」开关。ID 是稳定标识，某些场景（脚本、工单）
 *    用户手里只有数字，强制下拉等于把老路堵死。
 *
 * 组件不拉数据（只负责展示与交互），数据由页面给——这样它能同时服务环境、凭据、
 * 后续任何"选一个实体"的地方，而不用把三种接口塞进一个组件。
 */
import { computed, ref } from 'vue'
import { NInputNumber, NSelect } from 'naive-ui'
import { useI18n } from 'vue-i18n'

export interface EntityOption {
  /** 实体 ID（提交给后端的值）。 */
  value: number
  /** 展示名（名称/编号）。 */
  label: string
  /** 补充信息：型号、用户名、最近验证结果等；无则不显示。 */
  hint?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: number | null
    options: EntityOption[]
    placeholder?: string
    loading?: boolean
    /** 手输兜底的输入框占位。 */
    manualPlaceholder?: string
    disabled?: boolean
  }>(),
  {
    placeholder: '',
    loading: false,
    manualPlaceholder: '',
    disabled: false,
  },
)

const emit = defineEmits<{ 'update:modelValue': [number | null] }>()

const { t } = useI18n()

/** 是否处于「手输 ID」模式。默认关闭：绝大多数用户应该走下拉。 */
const manual = ref(false)

/**
 * 选项文案：`名称（ID）`，带补充信息时再拼一段。
 * 用全角括号是为了和界面其它「名称（编号）」写法一致，不是随手选的。
 */
const selectOptions = computed(() =>
  props.options.map((o) => ({
    value: o.value,
    label: o.hint ? `${o.label}（${o.value}）· ${o.hint}` : `${o.label}（${o.value}）`,
  })),
)

function onSelect(value: number | null): void {
  emit('update:modelValue', value ?? null)
}

function onManual(value: number | null): void {
  emit('update:modelValue', value ?? null)
}
</script>

<template>
  <div class="entity-picker" :class="{ 'is-manual': manual }">
    <NSelect
      v-if="!manual"
      :value="modelValue"
      :options="selectOptions"
      :placeholder="placeholder"
      :loading="loading"
      :disabled="disabled"
      filterable
      clearable
      :consistent-menu-width="false"
      data-testid="entity-picker"
      @update:value="onSelect"
    />
    <NInputNumber
      v-else
      :value="modelValue"
      :placeholder="manualPlaceholder || placeholder"
      :disabled="disabled"
      :min="1"
      :show-button="false"
      data-testid="entity-picker-manual"
      @update:value="onManual"
    />
    <button
      type="button"
      class="entity-picker__toggle"
      data-testid="entity-picker-toggle"
      @click="manual = !manual"
    >
      {{ manual ? t('common.entityBackToSelect') : t('common.entityManual') }}
    </button>
  </div>
</template>

<style scoped>
.entity-picker {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.entity-picker > :first-child {
  flex: 1;
  min-width: 0;
}
.entity-picker__toggle {
  flex: none;
  padding: 0;
  border: none;
  background: none;
  color: var(--accent);
  cursor: pointer;
  font-size: var(--font-size-sm);
  white-space: nowrap;
}
</style>
