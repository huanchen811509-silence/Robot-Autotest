<script setup lang="ts">
/**
 * 报告模板（TPLX-01，v2.1；v3.9.0 重构为分类卡片 + 预置模板）。
 *
 * 页面只做一件事：**在白名单里挑字段、排顺序、改标题**，存成模板供导出使用。
 *
 * 三条刻意的边界（沿用 v2.1，未放宽）：
 * ① 可选字段**只来自 `GET /fields`**，页面不内联任何字段名 —— 内联了就是第二份白名单，
 *    白名单收紧后界面还能选到已禁用的字段（「选得上、导出被拒」的死角）；
 * ② 必填字段（reportId/title/version/frozenAt/disclaimer/watermark）勾选框禁用、不可移除：
 *    没有水印与免责声明的对外文件，等于绕过了 C5；
 * ③ 平台级模板只读：改一处会影响所有项目的导出，要改就复制成项目模板。
 *
 * v3.9.0（FR-v3.9-05）改的是**排布**，不是字段集：21 个字段原来是一列平铺 + 已选区再 21 行，
 * 实测整页 2.4 屏（原来对外报的「1.8 屏」是把内滚容器当成整页量错了对象）。
 * 现在按语义分成 4 张卡片、已选区改双列紧凑行、标题按需展开，整页压到 1 屏内。
 * 字段集仍由 `/fields` 决定，`ReportFieldWhitelist` 一个字没动。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  reportTemplateApi,
  type ReportTemplate,
  type ReportTemplatePayload,
} from '@/api/reportTemplates'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

/** 必填字段：与后端 `ReportFieldWhitelist.REQUIRED_FIELDS` 同口径（后端仍会再判一次）。 */
const REQUIRED_KEYS = ['reportId', 'title', 'version', 'frozenAt', 'disclaimer', 'watermark']

/**
 * 字段 key → 文案键。写成静态表而不是按字符串拼出来的键：
 * 拼键在 i18n 门禁里是盲区（`tools/check_i18n.py` 只收字面量键），
 * 拼错了要等页面上直接显示原始键名才会发现。
 */
const FIELD_LABELS: Record<string, string> = {
  reportId: 'reportTemplates.field.reportId',
  title: 'reportTemplates.field.title',
  version: 'reportTemplates.field.version',
  frozenAt: 'reportTemplates.field.frozenAt',
  conclusion: 'reportTemplates.field.conclusion',
  passCount: 'reportTemplates.field.passCount',
  failCount: 'reportTemplates.field.failCount',
  skipCount: 'reportTemplates.field.skipCount',
  passRate: 'reportTemplates.field.passRate',
  caseTotal: 'reportTemplates.field.caseTotal',
  caseCovered: 'reportTemplates.field.caseCovered',
  coverageRate: 'reportTemplates.field.coverageRate',
  failureDistribution: 'reportTemplates.field.failureDistribution',
  trend: 'reportTemplates.field.trend',
  projectName: 'reportTemplates.field.projectName',
  swBuild: 'reportTemplates.field.swBuild',
  caseKind: 'reportTemplates.field.caseKind',
  startedAt: 'reportTemplates.field.startedAt',
  finishedAt: 'reportTemplates.field.finishedAt',
  disclaimer: 'reportTemplates.field.disclaimer',
  watermark: 'reportTemplates.field.watermark',
}

/**
 * 四个分类（顺序即卡片顺序）。分类是**展示归属**，不参与白名单判定：
 * 某个 key 既不在分类里也不在 /fields 里，就永远不会被渲染。
 * 反过来，/fields 里出现分类未覆盖的新字段时会落到「其他」卡片——
 * 宁可多一张卡片，也不要新字段在界面上凭空消失。
 */
/**
 * 分类卡片的形状。显式声明接口（而不是靠数组字面量推断）：推断出来的
 * `labelKey` 只含四张卡片的字面量，后面「其他」卡片 push 进去就会被 TS 拒掉，
 * 而「其他」卡片恰恰是 v3.9.0 为「/fields 出现未分类新字段」准备的兜底。
 */
interface ReportFieldCategory {
  key: string
  labelKey: string
  keys: string[]
}

const CATEGORIES: ReportFieldCategory[] = [
  {
    key: 'identity',
    labelKey: 'reportTemplates.category.identity',
    keys: ['reportId', 'title', 'version', 'frozenAt'],
  },
  {
    key: 'stats',
    labelKey: 'reportTemplates.category.stats',
    keys: [
      'conclusion',
      'passCount',
      'failCount',
      'skipCount',
      'passRate',
      'caseTotal',
      'caseCovered',
      'coverageRate',
      'failureDistribution',
      'trend',
    ],
  },
  {
    key: 'env',
    labelKey: 'reportTemplates.category.env',
    keys: ['projectName', 'swBuild', 'caseKind', 'startedAt', 'finishedAt'],
  },
  {
    key: 'compliance',
    labelKey: 'reportTemplates.category.compliance',
    keys: ['disclaimer', 'watermark'],
  },
] as const

/** 预置模板：只声明「选哪些字段」，名称/描述仍由用户确认后保存。 */
const PRESETS = [
  {
    key: 'brief',
    labelKey: 'reportTemplates.preset.brief',
    keys: [
      'reportId',
      'title',
      'version',
      'frozenAt',
      'conclusion',
      'disclaimer',
      'watermark',
    ],
  },
  {
    key: 'standard',
    labelKey: 'reportTemplates.preset.standard',
    keys: [
      'reportId',
      'title',
      'version',
      'frozenAt',
      'conclusion',
      'passCount',
      'failCount',
      'skipCount',
      'passRate',
      'disclaimer',
      'watermark',
    ],
  },
  {
    key: 'full',
    labelKey: 'reportTemplates.preset.full',
    keys: [], // 空 = 全部可用字段（含「其他」分类），运行时按 /fields 展开
  },
  {
    key: 'audit',
    labelKey: 'reportTemplates.preset.audit',
    keys: [
      'reportId',
      'title',
      'version',
      'frozenAt',
      'conclusion',
      'passCount',
      'failCount',
      'skipCount',
      'passRate',
      'caseTotal',
      'caseCovered',
      'coverageRate',
      'failureDistribution',
      'trend',
      'projectName',
      'swBuild',
      'caseKind',
      'startedAt',
      'finishedAt',
      'disclaimer',
      'watermark',
    ],
  },
] as const

const templates = ref<ReportTemplate[]>([])
const availableFields = ref<string[]>([])
const selectedId = ref<number | null>(null)
const creating = ref(false)
const loading = ref(false)
const saving = ref(false)
const notice = ref('')
const errorText = ref('')
/** 当前展开标题编辑的字段 key；同一时刻只展开一个，避免整列输入框把高度撑回去。 */
const editingTitle = ref<string | null>(null)

const draft = reactive({
  name: '',
  description: '',
  keys: [] as string[],
  titles: {} as Record<string, string>,
})

const current = computed<ReportTemplate | null>(
  () => templates.value.find((item) => item.id === selectedId.value) ?? null,
)
/** 平台级模板整页只读；新建草稿不受限。 */
const readonly = computed(() => current.value?.platform === true)

const canSave = computed(() => !readonly.value && !saving.value && draft.name.trim().length > 0)

/**
 * 分类卡片：每张卡片只保留 `/fields` 里真的给出的字段。
 * 空分类直接不渲染 —— 画一张空卡片只会让人怀疑「是不是没加载完」。
 */
const categories = computed(() =>
  CATEGORIES.map((category) => ({
    key: category.key,
    labelKey: category.labelKey,
    keys: category.keys.filter((key) => availableFields.value.includes(key as string)) as string[],
  })).filter((category) => category.keys.length > 0),
)

/** 未归类的字段（白名单新增了字段但没同步分类时）。 */
const uncategorized = computed(() => {
  const covered = new Set(CATEGORIES.flatMap((category) => [...category.keys]))
  return availableFields.value.filter((key) => !covered.has(key as never))
})

const allCategories = computed(() => {
  const list = categories.value.map((category) => ({ ...category }))
  if (uncategorized.value.length > 0) {
    list.push({ key: 'other', labelKey: 'reportTemplates.category.other', keys: uncategorized.value })
  }
  return list
})

function labelOf(key: string): string {
  const path = FIELD_LABELS[key]
  return path ? t(path) : key
}

function isRequired(key: string): boolean {
  return REQUIRED_KEYS.includes(key)
}

function isSelected(key: string): boolean {
  return draft.keys.includes(key)
}

function resetDraft(): void {
  draft.name = ''
  draft.description = ''
  draft.keys = [...REQUIRED_KEYS].filter((key) => availableFields.value.includes(key))
  draft.titles = {}
  editingTitle.value = null
}

function fillDraft(template: ReportTemplate): void {
  draft.name = template.name
  draft.description = template.description ?? ''
  draft.keys = template.fields.map((field) => field.fieldKey)
  draft.titles = {}
  editingTitle.value = null
  for (const field of template.fields) {
    if (field.titleOverride) {
      draft.titles[field.fieldKey] = field.titleOverride
    }
  }
}

function select(template: ReportTemplate): void {
  creating.value = false
  selectedId.value = template.id
  notice.value = ''
  errorText.value = ''
  fillDraft(template)
}

function startNew(): void {
  creating.value = true
  selectedId.value = null
  notice.value = ''
  errorText.value = ''
  resetDraft()
}

/** 勾选/取消：新勾的排到末尾，取消的直接摘掉（必填项禁用，走不到这里）。 */
function toggle(key: string): void {
  if (readonly.value || isRequired(key)) return
  if (draft.keys.includes(key)) {
    draft.keys = draft.keys.filter((item) => item !== key)
  } else {
    draft.keys = [...draft.keys, key]
  }
}

function move(index: number, delta: number): void {
  if (readonly.value) return
  const to = index + delta
  if (to < 0 || to >= draft.keys.length) return
  const next = [...draft.keys]
  next[index] = draft.keys[to]
  next[to] = draft.keys[index]
  draft.keys = next
}

function removeField(key: string): void {
  if (readonly.value || isRequired(key)) return
  draft.keys = draft.keys.filter((item) => item !== key)
}

/** 点名称展开/收起标题编辑：默认收起，21 个输入框同时在场就是之前 2.4 屏的主因。 */
function toggleTitleEditor(key: string): void {
  if (readonly.value) return
  editingTitle.value = editingTitle.value === key ? null : key
}

/**
 * 套用预置模板。
 * 只在「可编辑草稿」上生效：平台模板是只读的，套用等于偷偷改别人的导出格式。
 * 套用后面板名称留空时补上预置名，用户还能改。
 */
function applyPreset(preset: (typeof PRESETS)[number]): void {
  if (readonly.value) return
  const wanted = preset.keys.length > 0 ? preset.keys : availableFields.value
  // 必填项永远在：预置只描述"多选哪些"，不能把合规项丢掉
  const keys = [...REQUIRED_KEYS.filter((key) => availableFields.value.includes(key))]
  for (const key of wanted) {
    if (availableFields.value.includes(key) && !keys.includes(key)) {
      keys.push(key)
    }
  }
  draft.keys = keys
  if (!draft.name.trim()) {
    draft.name = t(preset.labelKey)
  }
  notice.value = t('reportTemplates.presetApplied', { name: t(preset.labelKey) })
  errorText.value = ''
}

function payload(): ReportTemplatePayload {
  return {
    projectId: projectId.value,
    name: draft.name.trim(),
    description: draft.description.trim(),
    fields: draft.keys.map((key) => ({
      fieldKey: key,
      titleOverride: (draft.titles[key] ?? '').trim() || null,
    })),
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [list, fields] = await Promise.all([
      reportTemplateApi.list(projectId.value),
      reportTemplateApi.fields(),
    ])
    templates.value = list
    availableFields.value = fields
    if (selectedId.value !== null) {
      const still = list.find((item) => item.id === selectedId.value)
      if (still) {
        fillDraft(still)
      } else {
        selectedId.value = null
      }
    }
    if (selectedId.value === null && !creating.value && list.length > 0) {
      select(list.find((item) => item.isDefault) ?? list[0])
    }
    errorText.value = ''
  } catch (error) {
    templates.value = []
    handle(error)
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (!canSave.value) return
  saving.value = true
  try {
    if (creating.value || selectedId.value === null) {
      const created = await reportTemplateApi.create(payload())
      notice.value = t('reportTemplates.created')
      await load()
      select(created)
    } else {
      const updated = await reportTemplateApi.update(projectId.value, selectedId.value, payload())
      notice.value = t('reportTemplates.saved')
      await load()
      select(updated)
    }
    creating.value = false
    errorText.value = ''
  } catch (error) {
    handle(error)
  } finally {
    saving.value = false
  }
}

async function remove(): Promise<void> {
  if (!current.value || readonly.value) return
  try {
    await reportTemplateApi.remove(projectId.value, current.value.id)
    notice.value = t('reportTemplates.removed')
    selectedId.value = null
    creating.value = false
    resetDraft()
    await load()
  } catch (error) {
    handle(error)
  }
}

async function makeDefault(): Promise<void> {
  if (!current.value || readonly.value) return
  try {
    await reportTemplateApi.setDefault(projectId.value, current.value.id)
    notice.value = t('reportTemplates.defaultSet')
    await load()
  } catch (error) {
    handle(error)
  }
}

function handle(error: unknown): void {
  errorText.value = error instanceof ApiError ? error.message : t('reportTemplates.unknownError')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head compact-head">
      <div>
        <h1>{{ t('reportTemplates.title') }}</h1>
        <p class="muted">{{ t('reportTemplates.subtitle') }}</p>
      </div>
      <!-- 预置模板放在页头右侧：它是「先选模板再微调」的第一动作，
           原来藏在字段列表下面，用户根本不会翻到 -->
      <div class="presets" data-testid="presets">
        <span class="presets-label">{{ t('reportTemplates.presets') }}</span>
        <button
          v-for="preset in PRESETS"
          :key="preset.key"
          class="btn btn-ghost preset"
          type="button"
          :data-testid="`preset-${preset.key}`"
          :disabled="readonly"
          @click="applyPreset(preset)"
        >
          {{ t(preset.labelKey) }}
        </button>
      </div>
    </header>

    <p v-if="notice" class="notice" data-testid="notice">{{ notice }}</p>
    <p v-if="errorText" class="error" data-testid="error">{{ errorText }}</p>

    <div class="split">
      <section class="panel list-panel">
        <h2>{{ t('reportTemplates.listTitle') }}</h2>
        <p v-if="loading" class="muted">{{ t('reportTemplates.loading') }}</p>
        <ul class="list" data-testid="template-list">
          <li
            v-for="template in templates"
            :key="template.id"
            :class="{ active: template.id === selectedId }"
          >
            <button
              class="link"
              type="button"
              :data-testid="`pick-${template.id}`"
              @click="select(template)"
            >
              {{ template.name }}
            </button>
            <span v-if="template.platform" class="tag">{{ t('reportTemplates.platformTag') }}</span>
            <span v-if="template.isDefault" class="tag">{{ t('reportTemplates.defaultTag') }}</span>
            <span class="muted count">v{{ template.version }} · {{ template.fields.length }}</span>
          </li>
        </ul>
        <button class="btn" type="button" data-testid="new-template" @click="startNew">
          {{ t('reportTemplates.newTemplate') }}
        </button>
      </section>

      <section class="panel editor-panel">
        <p v-if="readonly" class="muted readonly" data-testid="readonly-hint">
          {{ t('reportTemplates.readonlyPlatform') }}
        </p>

        <div class="meta-row">
          <label class="field">
            <span>{{ t('reportTemplates.name') }}</span>
            <input
              v-model="draft.name"
              class="input"
              type="text"
              data-testid="template-name"
              :disabled="readonly"
              :placeholder="t('reportTemplates.namePlaceholder')"
            />
          </label>
          <label class="field">
            <span>{{ t('reportTemplates.description') }}</span>
            <input
              v-model="draft.description"
              class="input"
              type="text"
              data-testid="template-description"
              :disabled="readonly"
              :placeholder="t('reportTemplates.descriptionPlaceholder')"
            />
          </label>
        </div>

        <div class="section-head">
          <h3>{{ t('reportTemplates.availableTitle') }}</h3>
          <span class="muted small">{{ t('reportTemplates.availableHint') }}</span>
        </div>

        <div class="field-cards" data-testid="available-fields">
          <div
            v-for="category in allCategories"
            :key="category.key"
            class="field-card"
            :data-category="category.key"
          >
            <h4>
              {{ t(category.labelKey) }}
              <span class="muted count">{{ category.keys.length }}</span>
            </h4>
            <div class="chips">
              <label
                v-for="key in category.keys"
                :key="key"
                class="chip"
                :class="{ on: isSelected(key), locked: isRequired(key) }"
                :title="isRequired(key) ? t('reportTemplates.requiredWhy') : ''"
              >
                <input
                  type="checkbox"
                  :data-field="key"
                  :checked="isSelected(key)"
                  :disabled="readonly || isRequired(key)"
                  @change="toggle(key)"
                />
                <span>{{ labelOf(key) }}</span>
                <span v-if="isRequired(key)" class="lock">{{ t('reportTemplates.requiredTag') }}</span>
              </label>
            </div>
          </div>
        </div>

        <div class="section-head">
          <h3>{{ t('reportTemplates.selectedCount', { count: draft.keys.length }) }}</h3>
          <span class="muted small">{{ t('reportTemplates.selectedHint') }}</span>
        </div>

        <ol class="selected" data-testid="selected-fields">
          <li v-for="(key, index) in draft.keys" :key="key" :data-field="key">
            <span class="idx">{{ index + 1 }}</span>
            <button
              class="name"
              type="button"
              :data-toggle-title="key"
              :disabled="readonly"
              :title="t('reportTemplates.toggleTitle')"
              @click="toggleTitleEditor(key)"
            >
              {{ labelOf(key) }}
            </button>
            <input
              v-show="editingTitle === key"
              v-model="draft.titles[key]"
              class="input title"
              type="text"
              :data-title="key"
              :disabled="readonly"
              :placeholder="t('reportTemplates.titlePlaceholder')"
            />
            <button
              class="btn btn-ghost"
              type="button"
              data-action="up"
              :aria-label="t('reportTemplates.moveUp')"
              :disabled="readonly || index === 0"
              @click="move(index, -1)"
            >
              ↑
            </button>
            <button
              class="btn btn-ghost"
              type="button"
              data-action="down"
              :aria-label="t('reportTemplates.moveDown')"
              :disabled="readonly || index === draft.keys.length - 1"
              @click="move(index, 1)"
            >
              ↓
            </button>
            <button
              class="btn btn-ghost"
              type="button"
              data-action="remove"
              :aria-label="t('reportTemplates.removeField')"
              :disabled="readonly || isRequired(key)"
              @click="removeField(key)"
            >
              ×
            </button>
          </li>
        </ol>

        <div class="actions">
          <button class="btn" type="button" data-testid="save" :disabled="!canSave" @click="save">
            {{ t('reportTemplates.save') }}
          </button>
          <button
            class="btn btn-ghost"
            type="button"
            data-testid="delete"
            :disabled="readonly || !current"
            @click="remove"
          >
            {{ t('reportTemplates.delete') }}
          </button>
          <button
            class="btn btn-ghost"
            type="button"
            data-testid="set-default"
            :disabled="readonly || !current || current.isDefault"
            @click="makeDefault"
          >
            {{ t('reportTemplates.setDefault') }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 页头压成一行：标题 + 预置模板，省掉三行纵向空间 */
.compact-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.compact-head h1 {
  margin: 0 0 4px;
  font-size: 20px;
}
.presets {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.presets-label {
  font-size: 13px;
  color: var(--text-muted);
}
.preset {
  padding: 4px 10px;
}

.split {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(240px, 2.4fr);
  gap: 16px;
  align-items: start;
}
.panel {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}
.list-panel {
  max-height: 640px;
  overflow: auto;
}
.list,
.selected {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
}
.list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.list li.active .link {
  font-weight: 600;
}
.link {
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  color: var(--accent);
  font: inherit;
}
.tag {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 0 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.count {
  font-size: 12px;
}
.meta-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
}
.field {
  display: block;
}
.field span {
  display: block;
  margin-bottom: 4px;
  color: var(--text-muted);
  font-size: 12px;
}
.section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 10px 0 6px;
}
.section-head h3 {
  margin: 0;
  font-size: 14px;
}
.small {
  font-size: 12px;
}

/* 4 张分类卡片：2×2，字段做成一枚枚 chip，勾选即入已选区 */
.field-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 10px;
}
.field-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--surface-2);
}
.field-card h4 {
  margin: 0 0 6px;
  font-size: 13px;
  display: flex;
  gap: 6px;
  align-items: baseline;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 12px;
  font-size: 12px;
  cursor: pointer;
  background: var(--panel);
  white-space: nowrap;
}
.chip.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
.chip.locked {
  cursor: not-allowed;
  opacity: 0.85;
}
.chip input {
  margin: 0;
}
.lock {
  font-size: 11px;
  color: var(--text-muted);
}
.readonly {
  margin: 0 0 8px;
}

/* 已选区：双列紧凑行，标题按需展开 —— 21 行输入框同时在场是高度失控的主因 */
.selected {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 2px 12px;
  max-height: 232px;
  overflow: auto;
}
.selected li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 1px 0;
  min-height: 28px;
}
.idx {
  width: 18px;
  text-align: right;
  color: var(--text-muted);
  font-size: 12px;
  flex: 0 0 auto;
}
.name {
  flex: 1 1 auto;
  min-width: 0;
  text-align: left;
  background: none;
  border: none;
  padding: 0;
  font: inherit;
  color: var(--text);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.name:hover {
  text-decoration: underline;
}
.title {
  flex: 1 1 120px;
  min-width: 0;
  height: 24px;
  padding: 0 6px;
}
.actions {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}
.error {
  color: var(--fail);
}
</style>
