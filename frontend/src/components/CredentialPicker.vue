<script setup lang="ts">
/**
 * 凭证选择器（W57-41 / 42）。
 *
 * 数据来源 `GET /api/global/assets/{id}/credentials/options`，排序由后端给出 tier
 * （0 本设备成功 / 1 同型号或同域成功 / 2 已配置 / 3 其它），前端再补两条纪律：
 *  - **默认选中**：仅当存在「本设备已验证成功」且有验证时间的凭证才自动选，并标出时间；
 *    没有任何验证记录时不自动选（避免把没验证过的凭据当默认）；
 *  - **失败项排末位**：最近一次验证非 SUCCESS 的项一律沉底并标出失败原因。
 *
 * 支持搜索（服务端 keyword，>50 条截断时必需）、切换手工输入
 * （用户名 / 口令或私钥 / 类型 / 连接参数 / 端口 /「另存为凭据」默认关闭）。
 * 切换模式、关闭（宿主调 `resetSensitive()`）、提交后立即清空口令与私钥。
 *
 * 文案全部中文字符串；颜色只用语义 token。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { NCheckbox, NInput, NSelect } from 'naive-ui'
import {
  credentialOptionsApi,
  type CredentialOption,
  type ManualCollectOutcome,
  type ManualCollectPayload,
} from '@/api/credentialOptions'

import { useI18n } from 'vue-i18n'
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    /** 设备 ID；0 表示新建态无设备上下文（用 fallbackOptions，不打 options 端点） */
    assetId: number
    modelValue: number | null
    /** 无设备上下文时的候选（例如新建设备表单沿用既有凭据列表） */
    fallbackOptions?: CredentialOption[]
    label?: string
    disabled?: boolean
  }>(),
  { fallbackOptions: () => [], label: '', disabled: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  submitted: [value: ManualCollectOutcome]
  failed: [message: string]
}>()

const displayLabel = computed(() => props.label || t('w57.credentialPicker.fallbackLabel'))

const TIER_LABELS: Record<number, string> = {
  0: t('w57.credentialPicker.tier0'),
  1: t('w57.credentialPicker.tier1'),
  2: t('w57.credentialPicker.tier2'),
  3: t('w57.credentialPicker.tierOther'),
}

const CRED_TYPE_OPTIONS = [
  { label: 'ssh', value: 'ssh' },
  { label: 'network', value: 'network' },
  { label: 'bmc', value: 'bmc' },
  { label: 'snmp', value: 'snmp' },
  { label: 'token', value: 'token' },
]

const options = ref<CredentialOption[]>([])
const loading = ref(false)
const errorMsg = ref('')
const keyword = ref('')
const mode = ref<'list' | 'manual'>('list')
const selectedId = ref<number | null>(props.modelValue)
const autoSelected = ref(false)
const autoSelectedTime = ref('')
const submitting = ref(false)
const truncated = ref(false)
let autoSelectAttempted = false
let searchTimer: ReturnType<typeof setTimeout> | undefined

const manual = reactive({
  username: '',
  password: '',
  privateKey: '',
  credType: 'ssh',
  connectOptions: '',
  port: '',
  saveAsCredential: false,
  saveAsName: '',
})

function isFailed(option: CredentialOption): boolean {
  const result = (option.lastResult ?? '').trim().toUpperCase()
  return result !== '' && result !== 'SUCCESS' && result !== 'OK'
}

/** 四级排序 + 失败项沉底；后端已按 tier 排过，这里保证前端展示口径一致且不受搜索回填打乱。 */
const sortedOptions = computed<CredentialOption[]>(() => {
  const list = [...options.value]
  list.sort((a, b) => {
    const fa = isFailed(a) ? 1 : 0
    const fb = isFailed(b) ? 1 : 0
    if (fa !== fb) return fa - fb
    if (a.tier !== b.tier) return a.tier - b.tier
    return (b.lastVerifiedAt ?? '').localeCompare(a.lastVerifiedAt ?? '')
  })
  return list
})

const filteredOptions = computed<CredentialOption[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return sortedOptions.value
  return sortedOptions.value.filter((option) =>
    `${option.name} ${option.username} ${option.credType}`.toLowerCase().includes(kw),
  )
})

function tierLabel(tier: number): string {
  return TIER_LABELS[tier] ?? t('w57.credentialPicker.tierOther')
}

function failureReason(option: CredentialOption): string {
  return t('w57.credentialPicker.verifyFailed', { reason: option.lastResult })
}

/** 默认选「本设备已验证成功」且有验证时间的第一条；无记录不自动选。 */
function autoSelectIfNeeded(): void {
  if (autoSelectAttempted) return
  if (props.modelValue !== null || selectedId.value !== null) {
    autoSelectAttempted = true
    return
  }
  autoSelectAttempted = true
  const candidate = sortedOptions.value.find(
    (option) =>
      option.tier === 0 &&
      !isFailed(option) &&
      (option.lastVerifiedAt ?? '').trim().length > 0,
  )
  if (!candidate) return
  selectedId.value = candidate.credentialId
  autoSelected.value = true
  autoSelectedTime.value = candidate.lastVerifiedAt
  emit('update:modelValue', candidate.credentialId)
}

async function loadOptions(search = ''): Promise<void> {
  errorMsg.value = ''
  if (props.assetId <= 0) {
    options.value = [...props.fallbackOptions]
    autoSelectIfNeeded()
    return
  }
  loading.value = true
  try {
    const result = await credentialOptionsApi.options(props.assetId, search)
    options.value = result.items
    truncated.value = result.truncated
    autoSelectIfNeeded()
  } catch (error) {
    // 选项拉不到不该把整个表单拖垮：降级为空列表 + 可读说明
    options.value = []
    errorMsg.value = error instanceof Error ? error.message : t('w57.credentialPicker.loadFailed')
  } finally {
    loading.value = false
  }
}

function onKeywordInput(value: string): void {
  keyword.value = value
  if (searchTimer) clearTimeout(searchTimer)
  // >50 条时后端截断，必须把 keyword 发到服务端；本地过滤只作为即时反馈
  searchTimer = setTimeout(() => {
    if (props.assetId > 0) void loadOptions(value)
  }, 250)
}

/** 切换 / 关闭 / 提交后立即清空口令与私钥等敏感字段。 */
function clearSensitive(): void {
  manual.username = ''
  manual.password = ''
  manual.privateKey = ''
  manual.connectOptions = ''
  manual.port = ''
  manual.saveAsName = ''
  manual.saveAsCredential = false
}

function switchMode(next: 'list' | 'manual'): void {
  if (mode.value === next) return
  clearSensitive()
  mode.value = next
  if (next === 'manual') {
    selectedId.value = null
    emit('update:modelValue', null)
  }
}

function selectCredential(id: number): void {
  selectedId.value = id
  autoSelected.value = false
  emit('update:modelValue', id)
}

function clearSelection(): void {
  clearSensitive()
  selectedId.value = null
  autoSelected.value = false
  emit('update:modelValue', null)
}

/**
 * 端口没有独立的后端字段（`ManualCollectRequest` 只有 connectOptions），
 * 这里把 port 折进连接参数串，随 POST 一起提交。
 */
function buildConnectOptions(): string | undefined {
  const parts: string[] = []
  if (manual.connectOptions.trim()) parts.push(manual.connectOptions.trim())
  if (manual.port.trim()) parts.push(`port=${manual.port.trim()}`)
  return parts.length > 0 ? parts.join('; ') : undefined
}

async function submitManual(): Promise<void> {
  if (props.assetId <= 0) {
    errorMsg.value = t('w57.credentialPicker.missingDeviceId')
    emit('failed', errorMsg.value)
    clearSensitive()
    return
  }
  if (!manual.username.trim()) {
    errorMsg.value = t('w57.credentialPicker.needUsername')
    emit('failed', errorMsg.value)
    return
  }
  if (!manual.password && !manual.privateKey) {
    errorMsg.value = t('w57.credentialPicker.needPassword')
    emit('failed', errorMsg.value)
    return
  }
  const payload: ManualCollectPayload = {
    username: manual.username.trim(),
    password: manual.password || undefined,
    privateKey: manual.privateKey || undefined,
    credType: manual.credType,
    connectOptions: buildConnectOptions(),
    confirm: true,
    saveAsCredential: manual.saveAsCredential,
    saveAsName: manual.saveAsCredential ? manual.saveAsName.trim() || undefined : undefined,
  }
  submitting.value = true
  errorMsg.value = ''
  try {
    const outcome = await credentialOptionsApi.collectManual(props.assetId, payload)
    emit('submitted', outcome)
  } catch (error) {
    const message = error instanceof Error ? error.message : t('w57.credentialPicker.submitFailed')
    errorMsg.value = message
    emit('failed', message)
  } finally {
    // 无论成败，提交后立刻清空敏感字段，截屏/录屏都带不走明文
    clearSensitive()
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (next) => {
    selectedId.value = next
  },
)
watch(
  () => props.assetId,
  () => {
    autoSelectAttempted = false
    void loadOptions(keyword.value)
  },
)

onMounted(() => void loadOptions())
onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
  clearSensitive()
})

/** 供宿主在弹层关闭时调用，保证敏感字段不留在内存里。 */
defineExpose({ resetSensitive: clearSensitive })
</script>

<template>
  <div class="credential-picker" data-check-semantic :data-testid="`credential-picker-${displayLabel}`">
    <div class="credential-picker__head">
      <span class="credential-picker__label">{{ displayLabel }}</span>
      <div class="credential-picker__modes" role="group" :aria-label="t('w57.credentialPicker.sourceAria')">
        <button
          type="button"
          class="credential-picker__mode"
          :class="{ 'is-active': mode === 'list' }"
          data-testid="credential-mode-list"
          @click="switchMode('list')"
        >
          {{ t('w57.credentialPicker.fromVault') }}
        </button>
        <button
          type="button"
          class="credential-picker__mode"
          :class="{ 'is-active': mode === 'manual' }"
          data-testid="credential-mode-manual"
          @click="switchMode('manual')"
        >
          {{ t('w57.credentialPicker.manualInput') }}
        </button>
      </div>
    </div>

    <template v-if="mode === 'list'">
      <NInput
        :value="keyword"
        size="small"
        clearable
        :placeholder="t('w57.credentialPicker.searchPlaceholder')"
        :aria-label="t('w57.credentialPicker.searchAria')"
        data-testid="credential-search"
        @update:value="onKeywordInput"
      />
      <p v-if="loading" class="credential-picker__hint">{{ t('w57.credentialPicker.loading') }}</p>
      <p v-else-if="errorMsg" class="credential-picker__error" data-testid="credential-error">
        {{ errorMsg }}
      </p>
      <p v-else-if="filteredOptions.length === 0" class="credential-picker__hint">
        {{ t('w57.credentialPicker.noOptions') }}
      </p>

      <p v-if="autoSelected" class="credential-picker__auto" data-testid="credential-auto">
        {{ t('w57.credentialPicker.autoSelected', { time: autoSelectedTime }) }}
      </p>

      <ul v-if="filteredOptions.length > 0" class="credential-picker__list" data-testid="credential-options">
        <li v-for="option in filteredOptions" :key="option.credentialId">
          <button
            type="button"
            class="credential-picker__option"
            :class="{ 'is-selected': selectedId === option.credentialId, 'is-failed': isFailed(option) }"
            :disabled="disabled"
            :data-testid="`credential-option-${option.credentialId}`"
            @click="selectCredential(option.credentialId)"
          >
            <span class="credential-picker__option-main">
              <strong>{{ option.name }}</strong>
              <span class="credential-picker__meta">{{ option.credType }} · {{ option.username }}</span>
            </span>
            <span class="credential-picker__option-side">
              <span class="credential-picker__tier" :data-tier="option.tier">{{ tierLabel(option.tier) }}</span>
              <span v-if="option.lastVerifiedAt" class="credential-picker__time">
                {{ option.lastVerifiedAt }}
              </span>
              <span v-if="isFailed(option)" class="credential-picker__reason">{{ failureReason(option) }}</span>
              <span v-else-if="option.boundDevices > 0" class="credential-picker__meta">
                {{ t('w57.credentialPicker.boundDevices', { count: option.boundDevices }) }}
              </span>
            </span>
          </button>
        </li>
      </ul>

      <p v-if="truncated" class="credential-picker__hint">
        {{ t('w57.credentialPicker.truncated') }}
      </p>
      <button
        v-if="selectedId !== null"
        type="button"
        class="credential-picker__clear"
        data-testid="credential-clear"
        @click="clearSelection"
      >
        {{ t('w57.credentialPicker.clearSelection') }}
      </button>
    </template>

    <template v-else>
      <div class="credential-picker__manual">
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.username') }}</span>
          <NInput v-model:value="manual.username" size="small" data-testid="credential-manual-username" />
        </label>
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.password') }}</span>
          <NInput
            v-model:value="manual.password"
            type="password"
            size="small"
            show-password-on="click"
            data-testid="credential-manual-password"
          />
        </label>
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.privateKey') }}</span>
          <NInput
            v-model:value="manual.privateKey"
            type="textarea"
            size="small"
            :autosize="{ minRows: 1, maxRows: 3 }"
            data-testid="credential-manual-private-key"
          />
        </label>
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.type') }}</span>
          <NSelect v-model:value="manual.credType" size="small" :options="CRED_TYPE_OPTIONS" />
        </label>
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.connectOptions') }}</span>
          <NInput v-model:value="manual.connectOptions" size="small" data-testid="credential-manual-options" />
        </label>
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.port') }}</span>
          <NInput v-model:value="manual.port" size="small" data-testid="credential-manual-port" />
        </label>
        <label class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.saveAsCredential') }}</span>
          <NCheckbox
            v-model:checked="manual.saveAsCredential"
            data-testid="credential-save-as"
          />
        </label>
        <label v-if="manual.saveAsCredential" class="credential-picker__field">
          <span>{{ t('w57.credentialPicker.credentialName') }}</span>
          <NInput v-model:value="manual.saveAsName" size="small" data-testid="credential-save-as-name" />
        </label>
      </div>

      <p v-if="errorMsg" class="credential-picker__error" data-testid="credential-error">{{ errorMsg }}</p>
      <div class="credential-picker__actions">
        <button
          type="button"
          class="credential-picker__submit"
          :disabled="submitting"
          data-testid="credential-manual-submit"
          @click="submitManual"
        >
          {{ submitting ? t('w57.credentialPicker.submitting') : t('w57.credentialPicker.submitCollect') }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.credential-picker {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 0;
  padding: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.credential-picker__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.credential-picker__label {
  color: var(--text);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.credential-picker__modes {
  display: inline-flex;
  gap: var(--space-1);
}

.credential-picker__mode {
  padding: 2px var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text-muted);
  font-size: var(--font-size-xs);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background-color 0.15s ease;
}

.credential-picker__mode:hover {
  color: var(--text);
  border-color: var(--border-strong);
}

.credential-picker__mode:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.credential-picker__mode.is-active {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
}

.credential-picker__hint,
.credential-picker__error {
  margin: 0;
  font-size: var(--font-size-xs);
}

.credential-picker__hint {
  color: var(--text-muted);
}

.credential-picker__error {
  color: var(--fail);
}

.credential-picker__auto {
  margin: 0;
  padding: var(--space-1) var(--space-2);
  border-left: 3px solid var(--ok);
  background: var(--ok-soft);
  color: var(--text);
  font-size: var(--font-size-xs);
  line-height: 1.6;
}

.credential-picker__list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.credential-picker__option {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.credential-picker__option:hover {
  border-color: var(--border-strong);
  background: var(--surface-hover);
}

.credential-picker__option:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.credential-picker__option.is-selected {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.credential-picker__option.is-failed {
  border-left: 3px solid var(--fail);
}

.credential-picker__option-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.credential-picker__option-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex: 0 0 auto;
}

.credential-picker__meta,
.credential-picker__time {
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.credential-picker__tier {
  padding: 0 var(--space-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}

.credential-picker__tier[data-tier='0'] {
  color: var(--ok);
  border-color: var(--ok-border);
  background: var(--ok-soft);
}

.credential-picker__reason {
  color: var(--fail);
  font-size: var(--font-size-xs);
}

.credential-picker__clear,
.credential-picker__submit {
  align-self: flex-start;
  padding: 2px var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.credential-picker__clear:hover,
.credential-picker__submit:hover {
  border-color: var(--border-strong);
  background: var(--surface-hover);
}

.credential-picker__clear:focus-visible,
.credential-picker__submit:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}

.credential-picker__submit {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--accent-contrast);
}

.credential-picker__manual {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
}

.credential-picker__field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.credential-picker__actions {
  display: flex;
  justify-content: flex-end;
}
</style>
