<script setup lang="ts">
/**
 * 「加入用例集」按钮（FR-READY-04/05/06）。
 *
 * <p>只负责选目标（已有集 / 新建集），不执行入集——入集由父组件按「精确勾选」或
 * 「跨页筛选」两种模式分别调用 batchAddItems / addByBindingFilter。拆开是为了让
 * 目标选择与入集策略各自可测，也让公共库与项目库能复用同一个选择器。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, NModal, NRadioGroup, NRadio } from 'naive-ui'
import CaseSetPicker from './CaseSetPicker.vue'

const props = defineProps<{ projectId: number; disabled?: boolean; count?: number }>()
const emit = defineEmits<{
  (event: 'commit', target: { mode: 'existing'; setId: number }
    | { mode: 'new'; name: string; setCode: string }): void
}>()

const { t } = useI18n()
const show = ref(false)
const targetMode = ref<'existing' | 'new'>('existing')
const selectedSetId = ref<number | null>(null)
const newName = ref('')
const newCode = ref('')
const submitting = ref(false)

const canCommit = computed(() =>
  targetMode.value === 'existing'
    ? selectedSetId.value !== null
    : newName.value.trim().length > 0 && newCode.value.trim().length > 0,
)

function open(): void {
  show.value = true
}

function close(): void {
  show.value = false
}

async function commit(): Promise<void> {
  if (!canCommit.value || submitting.value) {
    return
  }
  if (targetMode.value === 'existing' && selectedSetId.value === null) {
    return
  }
  if (targetMode.value === 'new') {
    // 新建：先建集再入集，父组件凭返回的 setId 追加（此处 emit 携带新建信息）
    emit('commit', { mode: 'new', name: newName.value.trim(), setCode: newCode.value.trim() })
  } else {
    emit('commit', { mode: 'existing', setId: selectedSetId.value as number })
  }
  close()
}

defineExpose({ open })
</script>

<template>
  <NButton quaternary :disabled="props.disabled" @click="open">
    {{ t('cases.addToSet') }}
  </NButton>

  <NModal v-model:show="show" preset="card" :title="t('cases.addToSetTitle')" style="width: 520px">
    <p class="muted">{{ t('cases.addToSetHint') }}</p>
    <NRadioGroup v-model:value="targetMode" class="add-set-mode">
      <NRadio value="existing">{{ t('cases.addToSetExisting') }}</NRadio>
      <NRadio value="new">{{ t('cases.addToSetNew') }}</NRadio>
    </NRadioGroup>
    <div v-if="targetMode === 'existing'" class="add-set-field">
      <CaseSetPicker :project-id="props.projectId" v-model:model-value="selectedSetId" />
    </div>
    <div v-else class="add-set-field">
      <NInput v-model:value="newName" :placeholder="t('cases.addToSetNewName')" />
      <NInput v-model:value="newCode" :placeholder="t('cases.addToSetNewCode')" />
    </div>
    <template #footer>
      <NButton @click="close">{{ t('common.cancel') }}</NButton>
      <NButton type="primary" :disabled="!canCommit || submitting" @click="commit">
        {{ t('cases.addToSetConfirm') }}
      </NButton>
    </template>
  </NModal>
</template>

<style scoped>
.add-set-mode { margin-bottom: 12px; }
.add-set-field { display: flex; flex-direction: column; gap: 8px; }
</style>
