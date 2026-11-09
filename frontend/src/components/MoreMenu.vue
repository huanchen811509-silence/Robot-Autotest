<script setup lang="ts">
/**
 * 行内 / 工具条「更多」菜单（FR-v3.10-01 判据②）。
 *
 * 为什么单开一个组件：计划页原来每行 4 个文字按钮 + 工具条 5 个入口挤在一屏，
 * 一屏可点控件二十多个，主操作根本认不出来。收敛方式是**保留一个触发点**，
 * 把次级动作收进菜单——而不是把按钮改小或藏起来一部分。
 *
 * 不用原生 `select`：这里放的是「动作」不是「选项」，`select` 会丢掉危险项
 * （删除）的语义色，也无法做禁用原因提示。
 */
import { computed } from 'vue'
import { NButton, NDropdown } from 'naive-ui'

export interface MoreMenuItem {
  key: string
  label: string
  /** 危险动作（删除等）：菜单里标红，且不给快捷键默认焦点 */
  danger?: boolean
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    items: MoreMenuItem[]
    /** 触发器文案，由调用方传入已翻译文本 */
    label: string
    testid?: string
    disabled?: boolean
  }>(),
  { testid: undefined, disabled: false },
)

const emit = defineEmits<{ (event: 'select', key: string): void }>()

const options = computed(() =>
  props.items.map((item) => ({
    key: item.key,
    label: item.label,
    disabled: item.disabled ?? false,
    // naive-ui 用 props.class 给危险项上色，这里保持与按钮语义一致
    props: item.danger ? { class: 'more-item-danger' } : {},
  })),
)

function onSelect(key: string): void {
  emit('select', key)
}
</script>

<template>
  <NDropdown
    trigger="click"
    :options="options"
    :disabled="disabled"
    placement="bottom-end"
    @select="onSelect"
  >
    <NButton
      text
      size="small"
      class="more-menu"
      :data-testid="testid"
      :disabled="disabled"
      :title="label"
    >
      {{ label }}
    </NButton>
  </NDropdown>
</template>

<style scoped>
.more-menu {
  white-space: nowrap;
}
</style>
