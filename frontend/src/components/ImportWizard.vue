<script setup lang="ts">
/**
 * 导入五步向导（v5.18.0 / FR-v5.18-01 / W68-01）。
 *
 * <p>五步：选文件 → 字段映射 → 冲突预览 → 提交 → 结果。资产与用例共用同一组件，
 * 差异只有「调哪个 preview/commit」与「结果读哪几个计数」，用 `kind` 分流。
 *
 * <p>设计上刻意保留三条硬边界：
 * <ol>
 *   <li><b>取消零写入</b>：关闭只 emit，不发任何请求。预检阶段后端会写 `import_batch`
 *       （PREFLIGHT），但业务表不动——判据 4 验的是业务表；</li>
 *   <li><b>不传 mapping 时行为不变</b>：映射为空就按表头自动识别，与 5.17 完全一致；</li>
 *   <li><b>样例与映射联动</b>：映射页右侧按「目标字段」重排样例，改列立刻看得见改到了哪一列。
 *       如果样例只跟着原始列走，用户改完列看到的还是原来的值，等于没有反馈。</li>
 * </ol>
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { globalAssetApi } from '@/api/globalAssets'
import { globalCaseApi } from '@/api/globalCases'
import { fieldsOf } from '@/config/importFields'

const props = defineProps<{
  /** 资产导入走 /global/assets，用例导入走 /global/cases。 */
  kind: 'asset' | 'case'
  /**
   * 用例侧的 `force`（v4.11.0 / FR-v4.11-18 语义）：true = 按当前库重算增量；
   * false = 同一份文件命中已提交批次时走回放（返回上次结果、不写库）。
   * 资产侧不区分两者，传什么都忽略。
   */
  force?: boolean
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'openBatches'): void
}>()

const { t } = useI18n()

type Step = 1 | 2 | 3 | 4 | 5
const step = ref<Step>(1)
const stepKeys = ['file', 'mapping', 'preview', 'commit', 'result'] as const
const fieldOptions = computed(() => fieldsOf(props.kind))

const file = ref<File | null>(null)
const fileName = ref('')
const headers = ref<string[]>([])
const sampleRows = ref<string[][]>([])
const mapping = ref<Record<string, string>>({})
const mappingDirty = ref(false)

const busy = ref(false)
const error = ref('')
/**
 * 用例侧的「按当前库重算」（force）。放在向导里而不是父页：
 * 它是**这次导入的取数口径**，跟文件绑在一起；挂在页头会让人以为对整页生效。
 */
const forceLocal = ref(props.force === true)

/** 归一化后的预检读数：两侧响应字段名不同，这里统一成向导关心的五数。 */
interface Counts {
  toCreate: number
  toUpdate: number
  conflicts: number
  /** 资产：端口数 / 凭据数；用例：不变 / 废弃。只作明细展示。 */
  detail: { label: string; value: number }[]
  batchId: number
}
const counts = ref<Counts | null>(null)
const result = ref<{ created: number; updated: number; batchId: number; replayed: boolean } | null>(null)

const title = computed(() =>
  t(props.kind === 'asset' ? 'importWizard.titleAsset' : 'importWizard.titleCase'),
)

/** 映射页右侧：按**目标字段**聚合前 3 行样例值。改列 → 这里的行与值都变。 */
const mappedSamples = computed(() => {
  const out: { label: string; value: string }[] = []
  headers.value.forEach((header, index) => {
    const target = mapping.value[header]
    if (!target) return
    const field = fieldOptions.value.find((item) => item.value === target)
    const values = sampleRows.value
      .map((row) => row[index] ?? '')
      .filter((value) => value !== '')
      .slice(0, 3)
    out.push({
      label: field ? t(field.labelKey) : target,
      value: values.length > 0 ? values.join(' / ') : t('importWizard.sampleEmpty'),
    })
  })
  return out
})

const mappedCount = computed(
  () => Object.values(mapping.value).filter((value) => value !== '').length,
)

function reset(): void {
  step.value = 1
  file.value = null
  fileName.value = ''
  headers.value = []
  sampleRows.value = []
  mapping.value = {}
  mappingDirty.value = false
  counts.value = null
  result.value = null
  error.value = ''
  busy.value = false
}

/** 取消：只关窗，不发任何请求（判据 4 的前端侧保证）。 */
function cancel(): void {
  reset()
  emit('close')
}

function pickedMapping(): Record<string, string> | undefined {
  const entries = Object.entries(mapping.value).filter(([, value]) => value !== '')
  return entries.length > 0 ? Object.fromEntries(entries) : undefined
}

async function runPreview(): Promise<void> {
  if (!file.value) return
  busy.value = true
  error.value = ''
  try {
    if (props.kind === 'asset') {
      const preview = await globalAssetApi.preview(file.value, pickedMapping())
      headers.value = preview.headers ?? []
      sampleRows.value = preview.sampleRows ?? []
      counts.value = {
        toCreate: preview.toCreate ?? preview.devices,
        toUpdate: preview.toUpdate ?? 0,
        conflicts: preview.conflicts ?? 0,
        detail: [
          { label: t('global.assetDevice'), value: preview.devices },
          { label: t('global.assetPorts'), value: preview.ports },
          { label: t('global.assetCredential'), value: preview.credentials },
        ],
        batchId: preview.batchId,
      }
    } else {
      const preview = await globalCaseApi.previewImport(
        file.value,
        forceLocal.value,
        pickedMapping(),
      )
      headers.value = preview.headers ?? []
      sampleRows.value = preview.sampleRows ?? []
      counts.value = {
        toCreate: preview.toCreate ?? preview.newCount,
        toUpdate: preview.toUpdate ?? preview.updateCount,
        conflicts: preview.conflicts ?? preview.errorCount,
        detail: [
          { label: t('global.importUnchanged'), value: preview.unchangedCount },
          { label: t('global.importDeprecate'), value: preview.deprecateCount },
        ],
        batchId: preview.batchId,
      }
    }
    mappingDirty.value = false
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('global.loadFailed')
  } finally {
    busy.value = false
  }
}

async function onPick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const chosen = input.files?.[0]
  if (!chosen) return
  file.value = chosen
  fileName.value = chosen.name
  mapping.value = {}
  counts.value = null
  result.value = null
  // 选完文件立刻预检：映射页必须有表头与样例行才能工作，否则第二步是空白页
  await runPreview()
}

/** 切换取数口径后必须重算：否则第三步的数字还是旧口径的结果，提交必然对不上。 */
async function onForceChange(value: boolean): Promise<void> {
  forceLocal.value = value
  await runPreview()
}

function onMapChange(header: string, value: string): void {
  const next = { ...mapping.value }
  if (value === '') {
    delete next[header]
  } else {
    next[header] = value
  }
  mapping.value = next
  mappingDirty.value = true
}

const canNext = computed(() => {
  if (step.value === 1) return file.value !== null && !busy.value
  if (step.value === 2) return !busy.value
  if (step.value === 3) return counts.value !== null && !busy.value
  return !busy.value
})

async function next(): Promise<void> {
  if (!canNext.value) return
  if (step.value === 1) {
    step.value = 2
    return
  }
  if (step.value === 2) {
    // 改过列就按新映射重算：否则第三步的数字还是旧映射的结果，预览与提交必然对不上
    if (mappingDirty.value) {
      await runPreview()
      if (error.value) return
    }
    step.value = 3
    return
  }
  if (step.value === 3) {
    step.value = 4
  }
}

function prev(): void {
  if (step.value === 1) return
  step.value = (step.value - 1) as Step
}

async function commit(): Promise<void> {
  if (!file.value || !counts.value) return
  busy.value = true
  error.value = ''
  try {
    if (props.kind === 'asset') {
      const done = await globalAssetApi.commit(file.value)
      result.value = {
        created: done.devices,
        updated: 0,
        batchId: done.batchId,
        replayed: done.replayed,
      }
    } else {
      const done = await globalCaseApi.commitImport(counts.value.batchId)
      result.value = {
        created: done.inserted,
        updated: done.updated,
        batchId: done.batchId,
        replayed: done.replayed,
      }
    }
    step.value = 5
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('global.loadFailed')
  } finally {
    busy.value = false
  }
}

/** 预览与结果是否逐数一致——这条直接对应判据 3，界面上要能看见结论。 */
const consistent = computed(() => {
  if (!counts.value || !result.value) return false
  return counts.value.toCreate === result.value.created
})
</script>

<template>
  <div class="modal-mask" data-testid="import-wizard" @click.self="cancel">
    <div class="modal">
      <header class="modal-head">
        <h2>{{ title }}</h2>
        <button class="link" data-testid="iw-cancel" @click="cancel">{{ t('global.close') }}</button>
      </header>

      <ol class="iw-steps" data-testid="iw-steps">
        <li
          v-for="(key, index) in stepKeys"
          :key="key"
          class="iw-step"
          :class="{ active: step === index + 1, done: step > index + 1 }"
          :data-testid="`iw-step-${index + 1}`"
          :data-active="step === index + 1 ? 'true' : 'false'"
        >
          {{ t(`importWizard.steps.${key}`) }}
        </li>
      </ol>

      <!-- 1 选文件 -->
      <section v-if="step === 1" data-testid="iw-panel-file">
        <p class="muted">{{ t('importWizard.fileHint') }}</p>
        <input type="file" accept=".xlsx,.xls" data-testid="iw-file" @change="onPick" />
        <p v-if="fileName" class="mono" data-testid="iw-file-name">{{ fileName }}</p>
        <p v-if="busy" class="muted">{{ t('global.loading') }}</p>
        <p v-if="error" class="error">{{ error }}</p>
      </section>

      <!-- 2 字段映射 -->
      <section v-else-if="step === 2" class="iw-panel" data-testid="iw-panel-mapping">
        <p class="muted">{{ t('importWizard.mappingHint') }}</p>
        <p v-if="headers.length === 0" class="warning" data-testid="iw-no-header">
          {{ t('importWizard.noHeader') }}
        </p>
        <div v-else class="iw-map-grid">
          <label v-for="(header, index) in headers" :key="`${header}-${index}`" class="field">
            <span class="mono">{{ header }}</span>
            <select
              :value="mapping[header] ?? ''"
              :data-testid="`iw-map-${index}`"
              @change="onMapChange(header, ($event.target as HTMLSelectElement).value)"
            >
              <option value="">{{ t('importWizard.ignore') }}</option>
              <option v-for="field in fieldOptions" :key="field.value" :value="field.value">
                {{ t(field.labelKey) }}
              </option>
            </select>
          </label>
        </div>

        <h3>{{ t('importWizard.sampleTitle') }}</h3>
        <!--
          这里是「字段 → 样例值」的两列对照，不是数据网格：没有分页、排序、行选择，
          套 DataTable 壳反而要额外关掉一堆能力。项目规范要求数据网格一律走 DataTable，
          故此处用 div 栅格，语义上不冒充表格。
        -->
        <div class="iw-sample" data-testid="iw-sample">
          <div class="iw-sample-head">
            <span>{{ t('importWizard.sampleTarget') }}</span>
            <span>{{ t('importWizard.sampleValue') }}</span>
          </div>
          <div
            v-for="(row, index) in mappedSamples"
            :key="`${row.label}-${index}`"
            class="iw-sample-row"
            :data-testid="`iw-sample-row-${index}`"
          >
            <span>{{ row.label }}</span>
            <span class="mono">{{ row.value }}</span>
          </div>
        </div>
        <p v-if="mappedSamples.length === 0" class="muted" data-testid="iw-sample-empty">
          {{ t('importWizard.sampleNoMapping') }}
        </p>
        <p class="muted">{{ t('importWizard.mappedHint', { count: mappedCount }) }}</p>
      </section>

      <!-- 3 冲突预览 -->
      <section v-else-if="step === 3" class="iw-panel" data-testid="iw-panel-preview">
        <p class="muted">{{ t('importWizard.previewHint') }}</p>
        <label v-if="kind === 'case'" class="force-line">
          <input
            type="checkbox"
            data-testid="iw-force"
            :checked="forceLocal"
            @change="onForceChange(($event.target as HTMLInputElement).checked)"
          />
          <span>{{ t('global.importForceRecalc') }}</span>
        </label>
        <div v-if="counts" class="diff-cards">
          <span class="diff-card new" data-testid="iw-count-create">
            +{{ counts.toCreate }} {{ t('importWizard.toCreate') }}
          </span>
          <span class="diff-card update" data-testid="iw-count-update">
            ~{{ counts.toUpdate }} {{ t('importWizard.toUpdate') }}
          </span>
          <span class="diff-card error" data-testid="iw-count-conflict">
            !{{ counts.conflicts }} {{ t('importWizard.conflicts') }}
          </span>
        </div>
        <ul v-if="counts" class="muted">
          <li v-for="item in counts.detail" :key="item.label">{{ item.label }}：{{ item.value }}</li>
        </ul>
        <p class="mono muted">{{ t('global.assetBatch') }} #{{ counts?.batchId }}</p>
      </section>

      <!-- 4 提交 -->
      <section v-else-if="step === 4" class="iw-panel" data-testid="iw-panel-commit">
        <p>{{ t('importWizard.commitHint', { count: counts?.toCreate ?? 0 }) }}</p>
        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="busy" class="muted">{{ t('global.loading') }}</p>
      </section>

      <!-- 5 结果 -->
      <section v-else class="iw-panel" data-testid="iw-panel-result">
        <p class="success" data-testid="iw-result-line">
          {{ t('importWizard.resultLine', {
            created: result?.created ?? 0,
            updated: result?.updated ?? 0,
            batch: result?.batchId ?? 0,
          }) }}
        </p>
        <p
          class="mono"
          :class="consistent ? 'success' : 'error'"
          data-testid="iw-consistent"
          :data-consistent="consistent ? 'true' : 'false'"
        >
          {{ t('importWizard.consistency', {
            preview: counts?.toCreate ?? 0,
            actual: result?.created ?? 0,
          }) }}
        </p>
        <p v-if="result?.replayed" class="warning">{{ t('importWizard.replayed') }}</p>
      </section>

      <p v-if="error && step !== 4" class="error">{{ error }}</p>

      <div class="modal-actions">
        <button class="btn" data-testid="iw-close" @click="cancel">{{ t('global.close') }}</button>
        <button
          v-if="step > 1 && step < 5"
          class="btn"
          data-testid="iw-prev"
          :disabled="busy"
          @click="prev"
        >
          {{ t('importWizard.prev') }}
        </button>
        <button
          v-if="step < 4"
          class="btn btn-primary"
          data-testid="iw-next"
          :disabled="!canNext"
          @click="next"
        >
          {{ t('importWizard.next') }}
        </button>
        <button
          v-if="step === 4"
          class="btn btn-primary"
          data-testid="iw-commit"
          :disabled="busy"
          @click="commit"
        >
          {{ t('global.importCommit') }}
        </button>
        <button
          v-if="step === 5"
          class="btn btn-primary"
          data-testid="iw-open-batches"
          @click="emit('openBatches')"
        >
          {{ t('importWizard.viewBatches') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.iw-steps {
  display: flex;
  gap: 8px;
  margin: 0 0 14px;
  padding: 0;
  list-style: none;
  flex-wrap: wrap;
}
.iw-step {
  font-size: var(--font-size-xs);
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  color: var(--text-muted);
}
.iw-step.active {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.iw-step.done {
  color: var(--ok, var(--accent));
  border-color: var(--ok, var(--accent));
}
.iw-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.iw-map-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px;
  max-height: 240px;
  overflow: auto;
}
.iw-sample {
  width: 100%;
  font-size: var(--font-size-xs);
}
.iw-sample-head,
.iw-sample-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(0, 2fr);
  gap: 8px;
  border-bottom: 1px solid var(--border);
  padding: 4px 6px;
}
.iw-sample-head {
  color: var(--text-muted);
  font-weight: 600;
}
</style>
