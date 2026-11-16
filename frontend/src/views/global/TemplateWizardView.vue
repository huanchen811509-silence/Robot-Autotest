<script setup lang="ts">
/**
 * 新建模板向导（v5.10.0 / FR-v5.10-05、FR-v5.10-06）：`/g/templates/new`。
 *
 * 五步：基本信息 → 内核与场景 → 内容明细 → 资产 → 冲突复核。
 *
 * 三条刻意的口径：
 * 1. **编号由服务端序列出**：前端只把「前缀 + 内核」交给 `/projects/next-code`（与项目编号
 *    同一套序列，scope 前缀不同），拿到的是**真号**而不是本地推算值。用户改前缀就重新取号。
 * 2. **前缀冲突硬拒**：同一前缀已被别的模板占用 → 复核步直接禁止提交并点名占用方。
 *    这与后端 `requirePrefixFree` 同一判据，前端只是提前告知，不代替后端校验。
 * 3. **空模板允许但要显式确认**：允许建空模板（有人先立骨架再补内容），但复核步必须勾
 *    「这是一个空模板」——否则列表里会冒出一堆打开就没内容的模板，没人知道是没建完还是坏了。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { projectApi } from '@/api/iam'
import { templateApi, type TemplateRow } from '@/api/globalTemplates'
import { globalAssetApi } from '@/api/globalAssets'
import WizardContentPicker from '@/components/WizardContentPicker.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const router = useRouter()

const STEP_KEYS = ['basics', 'kernel', 'content', 'assets', 'review'] as const
type StepKey = (typeof STEP_KEYS)[number]
const step = ref(0)
const current = computed<StepKey>(() => STEP_KEYS[Math.min(step.value, STEP_KEYS.length - 1)])

const form = reactive({
  name: '',
  prefix: 'PRJ',
  code: '',
  kernel: 'robot',
  visibility: 'private',
  description: '',
})

interface PrefixRow {
  code: string
  meaning: string
  builtin: number
  used: number
}
const prefixes = ref<PrefixRow[]>([])

/** 场景标签（FR-v5.10-06）：写成 itemType=SCENARIO 的明细行，`refId` 为空即「自由标签」。 */
const scenarioTags = ref<string[]>([])
const scenarioInput = ref('')

const contentSelection = ref<{
  templateKeys: string[]
  setIds: number[]
  sets: { id: number; name: string }[]
  caseIds: number[]
  assetIds: number[]
  scenarioIds: number[]
}>({ templateKeys: [], setIds: [], sets: [], caseIds: [], assetIds: [], scenarioIds: [] })

const assets = ref<{ id: number; name: string }[]>([])
const pickedAssetIds = ref<number[]>([])

const templates = ref<TemplateRow[]>([])
const emptyConfirmed = ref(false)
const busy = ref(false)
const message = ref('')
const error = ref<ErrorLine | null>(null)
const created = ref<number | null>(null)
const warnings = ref<string[]>([])

const emptyTemplate = computed(
  () =>
    contentSelection.value.caseIds.length === 0 &&
    contentSelection.value.setIds.length === 0 &&
    scenarioTags.value.length === 0 &&
    pickedAssetIds.value.length === 0,
)

/** 前缀冲突：已有模板用了同一前缀。硬拒（与后端同一判据）。 */
const prefixConflict = computed(() =>
  templates.value.find((row) => row.code?.toUpperCase().startsWith(`${form.prefix.toUpperCase()}-`)),
)

/** 重名：允许，但要提示（PRD 遗留 L-60-03 定稿：重名放行 + 复核确认）。 */
const nameDuplicate = computed(() =>
  form.name.trim() ? templates.value.find((row) => row.name === form.name.trim()) : undefined,
)

const canSubmit = computed(
  () => !prefixConflict.value && (!emptyTemplate.value || emptyConfirmed.value) && !!form.name.trim(),
)

/** 取号：真号来自服务端序列，不是本地推算。 */
async function refreshCode(): Promise<void> {
  try {
    const result = await projectApi.nextCode(form.prefix, form.kernel)
    form.code = result.code
  } catch {
    form.code = ''
  }
}

watch(
  () => [form.prefix, form.kernel],
  () => void refreshCode(),
)

onMounted(async () => {
  void refreshCode()
  try {
    prefixes.value = await templateApi.prefixes()
    if (prefixes.value.length && !prefixes.value.some((row) => row.code === form.prefix)) {
      form.prefix = prefixes.value[0].code
    }
  } catch {
    prefixes.value = []
  }
  try {
    templates.value = await templateApi.list({})
  } catch {
    templates.value = []
  }
  try {
    const rows = await globalAssetApi.list({ type: 'DEVICE' })
    assets.value = rows.map((row) => ({ id: row.id, name: row.name }))
  } catch {
    assets.value = []
  }
})

function addScenario(): void {
  const tag = scenarioInput.value.trim()
  if (!tag || scenarioTags.value.includes(tag)) {
    scenarioInput.value = ''
    return
  }
  scenarioTags.value.push(tag)
  scenarioInput.value = ''
}

function removeScenario(tag: string): void {
  scenarioTags.value = scenarioTags.value.filter((item) => item !== tag)
}

function toggleAsset(id: number): void {
  pickedAssetIds.value = pickedAssetIds.value.includes(id)
    ? pickedAssetIds.value.filter((item) => item !== id)
    : [...pickedAssetIds.value, id]
}

function stepProblem(): string | null {
  if (current.value === 'basics') {
    if (!form.name.trim()) return t('templateWizard.nameRequired')
    if (!form.prefix) return t('templateWizard.prefixRequired')
  }
  return null
}

function goNext(): void {
  message.value = ''
  const problem = stepProblem()
  if (problem) {
    message.value = problem
    return
  }
  if (step.value < STEP_KEYS.length - 1) step.value += 1
}

function goBack(): void {
  message.value = ''
  if (step.value > 0) step.value -= 1
}

async function submit(): Promise<void> {
  message.value = ''
  error.value = null
  if (!canSubmit.value) {
    message.value = prefixConflict.value
      ? t('templateWizard.prefixConflict', {
          prefix: form.prefix,
          name: prefixConflict.value.name,
        })
      : t('templateWizard.confirmEmptyFirst')
    return
  }
  busy.value = true
  warnings.value = []
  try {
    const items = [
      ...contentSelection.value.caseIds.map((id) => ({
        itemType: 'CASE', refId: id, mode: 'REFERENCE', name: '',
      })),
      ...contentSelection.value.sets.map((set) => ({
        itemType: 'CASE_SET', refId: set.id, mode: 'REFERENCE', name: set.name,
      })),
      ...scenarioTags.value.map((tag) => ({
        itemType: 'SCENARIO', refId: null, mode: 'TAG', name: tag,
      })),
      ...pickedAssetIds.value.map((id) => ({
        itemType: 'DEVICE', refId: id, mode: 'REFERENCE', name: '',
      })),
    ]
    const row = await templateApi.create({
      name: form.name.trim(),
      code: form.code || undefined,
      kernel: form.kernel,
      visibility: form.visibility,
      description: form.description.trim(),
      items,
    })
    created.value = row.id
    if (nameDuplicate.value) {
      warnings.value.push(t('templateWizard.duplicateName', { name: row.name }))
    }
    message.value = t('templateWizard.created')
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    busy.value = false
  }
}

function backToList(): void {
  void router.push('/g/templates')
}
</script>

<template>
  <div class="template-wizard">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ t('templateWizard.title') }}</h1>
        <p class="page-subtitle">{{ t('templateWizard.subtitle') }}</p>
      </div>
      <button type="button" class="btn" data-testid="template-wizard-back" @click="backToList">
        {{ t('templateWizard.backToList') }}
      </button>
    </div>

    <div v-if="created" class="card wizard-result" role="status">
      <h2 class="card-title">{{ t('templateWizard.created') }}</h2>
      <div v-if="warnings.length" class="wizard-warnings" role="status">
        <p v-for="line in warnings" :key="line">{{ line }}</p>
      </div>
      <div class="toolbar">
        <button type="button" class="btn btn-primary" data-testid="template-wizard-done" @click="backToList">
          {{ t('templateWizard.backToList') }}
        </button>
      </div>
    </div>

    <template v-else>
      <nav class="wizard-steps" :aria-label="t('templateWizard.stepNav')">
        <ol>
          <li
            v-for="(key, index) in STEP_KEYS"
            :key="key"
            :aria-current="index === step ? 'step' : undefined"
            :class="{ done: index < step, active: index === step }"
          >
            <span class="wizard-step__index" aria-hidden="true">{{ index + 1 }}</span>
            <span class="wizard-step__label">{{ t(`templateWizard.steps.${key}`) }}</span>
          </li>
        </ol>
      </nav>

      <ErrorLineView v-if="error" :error="error" />
      <p v-if="message" class="wizard-message" role="alert">{{ message }}</p>

      <section v-if="current === 'basics'" class="card wizard-card">
        <h2 class="card-title">{{ t('templateWizard.basicsTitle') }}</h2>
        <label class="wizard-field">
          <span>{{ t('templateWizard.name') }}</span>
          <input v-model="form.name" class="input wide" data-testid="template-name" type="text" />
        </label>
        <label class="wizard-field">
          <span>{{ t('templateWizard.prefix') }}</span>
          <select v-model="form.prefix" class="input wide" data-testid="template-prefix">
            <option v-for="row in prefixes" :key="row.code" :value="row.code">
              {{ row.code }} · {{ row.meaning }}
            </option>
          </select>
          <span class="wizard-hint">{{ t('templateWizard.prefixHint') }}</span>
        </label>
        <div class="wizard-field">
          <span>{{ t('templateWizard.code') }}</span>
          <input v-model="form.code" class="input wide" data-testid="template-code" type="text" />
          <span class="wizard-hint">{{ t('templateWizard.codeHint') }}</span>
        </div>
        <label class="wizard-field">
          <span>{{ t('templateWizard.description') }}</span>
          <textarea v-model="form.description" class="input wide" rows="3" />
        </label>
        <fieldset class="wizard-group">
          <legend>{{ t('templateWizard.visibility') }}</legend>
          <label v-for="value in ['private', 'team', 'public']" :key="value" class="wizard-radio">
            <input v-model="form.visibility" type="radio" :value="value" />
            <span>{{ t(`projectWizard.visibilities.${value}`) }}</span>
          </label>
        </fieldset>
      </section>

      <section v-else-if="current === 'kernel'" class="card wizard-card">
        <h2 class="card-title">{{ t('templateWizard.kernelTitle') }}</h2>
        <fieldset class="wizard-group">
          <legend>{{ t('projectWizard.kernelLabel') }}</legend>
          <label class="wizard-radio">
            <input v-model="form.kernel" type="radio" value="robot" data-testid="template-kernel-robot" />
            <span>{{ t('projectWizard.kernels.robot') }}</span>
          </label>
          <label class="wizard-radio">
            <input v-model="form.kernel" type="radio" value="pytest" data-testid="template-kernel-pytest" />
            <span>{{ t('projectWizard.kernels.pytest') }}</span>
          </label>
        </fieldset>
        <div class="wizard-field">
          <span>{{ t('templateWizard.scenarios') }}</span>
          <span class="wizard-field__row">
            <input v-model="scenarioInput" class="input" data-testid="template-scenario-input" type="text" />
            <button type="button" class="btn" data-testid="template-scenario-add" @click="addScenario">
              {{ t('templateWizard.addScenario') }}
            </button>
          </span>
          <span class="wizard-hint">{{ t('templateWizard.scenariosHint') }}</span>
        </div>
        <ul v-if="scenarioTags.length" class="wizard-members">
          <li v-for="tag in scenarioTags" :key="tag">
            <span class="role-chip">{{ tag }}</span>
            <button type="button" class="btn btn-ghost" @click="removeScenario(tag)">
              {{ t('common.remove') }}
            </button>
          </li>
        </ul>
      </section>

      <section v-else-if="current === 'content'" class="card wizard-card">
        <h2 class="card-title">{{ t('templateWizard.contentTitle') }}</h2>
        <p class="wizard-hint">{{ t('templateWizard.contentHint') }}</p>
        <WizardContentPicker v-model="contentSelection" />
      </section>

      <section v-else-if="current === 'assets'" class="card wizard-card">
        <h2 class="card-title">{{ t('templateWizard.assetsTitle') }}</h2>
        <p class="wizard-hint">{{ t('templateWizard.assetsHint') }}</p>
        <p v-if="assets.length === 0" class="wizard-hint">{{ t('templateWizard.noAssets') }}</p>
        <ul v-else class="wizard-groups">
          <li v-for="asset in assets" :key="asset.id">
            <label class="wizard-check">
              <input
                type="checkbox"
                :checked="pickedAssetIds.includes(asset.id)"
                :data-testid="`template-asset-${asset.id}`"
                @change="toggleAsset(asset.id)"
              />
              <span>{{ asset.name }}</span>
            </label>
          </li>
        </ul>
      </section>

      <section v-else class="card wizard-card">
        <h2 class="card-title">{{ t('templateWizard.reviewTitle') }}</h2>
        <dl class="wizard-review">
          <div><dt>{{ t('templateWizard.name') }}</dt><dd>{{ form.name || '—' }}</dd></div>
          <div><dt>{{ t('templateWizard.code') }}</dt><dd>{{ form.code || '—' }}</dd></div>
          <div>
            <dt>{{ t('projectWizard.kernelLabel') }}</dt>
            <dd>{{ t(`projectWizard.kernels.${form.kernel}`) }}</dd>
          </div>
          <div>
            <dt>{{ t('templateWizard.contentTitle') }}</dt>
            <dd>{{ t('templateWizard.contentSummary', {
              cases: contentSelection.caseIds.length,
              sets: contentSelection.setIds.length,
              scenarios: scenarioTags.length,
              assets: pickedAssetIds.length,
            }) }}</dd>
          </div>
        </dl>
        <p v-if="prefixConflict" class="wizard-message" role="alert" data-testid="template-prefix-conflict">
          {{ t('templateWizard.prefixConflict', { prefix: form.prefix, name: prefixConflict.name }) }}
        </p>
        <p v-if="nameDuplicate" class="wizard-hint" data-testid="template-name-duplicate">
          {{ t('templateWizard.duplicateNameHint', { name: nameDuplicate.name }) }}
        </p>
        <label v-if="emptyTemplate" class="wizard-check">
          <input v-model="emptyConfirmed" type="checkbox" data-testid="template-empty-confirm" />
          <span>{{ t('templateWizard.emptyConfirm') }}</span>
        </label>
        <div class="toolbar">
          <button
            type="button"
            class="btn btn-primary"
            :disabled="busy || !canSubmit"
            data-testid="template-create"
            @click="submit"
          >
            {{ busy ? t('templateWizard.creating') : t('templateWizard.create') }}
          </button>
        </div>
      </section>

      <div v-if="current !== 'review'" class="wizard-footer">
        <button v-if="step > 0" type="button" class="btn" data-testid="template-back-step" @click="goBack">
          {{ t('projectWizard.back') }}
        </button>
        <button type="button" class="btn btn-primary" data-testid="template-next" @click="goNext">
          {{ t('projectWizard.next') }}
        </button>
      </div>
    </template>
  </div>
</template>
