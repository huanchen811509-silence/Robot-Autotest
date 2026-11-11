<script setup lang="ts">
/**
 * 统一用例详情面板（v4.9.2 / OPT-02 / FR-49-07~09）。
 *
 * 三类来源（公共库本体 / 项目用例 / 同步副本）共用同一份
 * `CaseDetailView` 字段契约，按 `source` 走对应 API，只做展示、从不做写操作。
 * 六组（标识 / 分类 / 内容 / 执行 / 溯源 / 版本）覆盖参考用例表 14 列基线；
 * 源表无值或库无此列一律显式显示「源表无此列」，绝不留下空白单元格。
 *
 * 新 i18n 键交付在 docs/evidence/opt-20261111/i18n/frontend-views.json，
 * 由学徒统一合并进语言包；组件内只用这些键，不内联中文文案。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { caseDetailApi, type CaseDetailSource, type CaseDetailView } from '@/api/caseDetail'

/**
 * `source` 给默认值 'project' 的原因：项目用例行内展开（CasesView.vue，不在本批可改范围）
 * 仍按 `{ projectId, caseId }` 挂载本组件；默认值既保持旧调用可用，又让三类来源字段集一致。
 */
const props = withDefaults(defineProps<{
  caseId: string | number
  source?: CaseDetailSource
  projectId?: string | number | null
}>(), { source: 'project', projectId: null })

const { t } = useI18n()
const detail = ref<CaseDetailView | null>(null)
const loading = ref(true)
const error = ref('')

interface FieldRow {
  labelKey: string
  display: string
  testId: string
  masked?: boolean
}
interface FieldGroup {
  key: string
  testId: string
  rows: FieldRow[]
}

/** 空值 / 缺列统一收敛成占位文案，保证「空白单元格数 = 0」。 */
function text(value: string | number | boolean | null | undefined): string {
  if (value === null || value === undefined || value === '') return t("caseDetail.missingColumn")
  if (typeof value === 'boolean') return value ? t("caseDetail.yes") : t("caseDetail.no")
  return String(value)
}

function missingColumn(): string {
  return t("caseDetail.missingColumn")
}

const groups = computed<FieldGroup[]>(() => {
  const d = detail.value
  if (!d) return []
  return [
    {
      key: "caseDetail.group.identity", testId: 'identity', rows: [
        { labelKey: "caseDetail.field.caseCode", display: text(d.caseCode), testId: 'caseCode' },
        { labelKey: "caseDetail.field.title", display: text(d.title), testId: 'title' },
        { labelKey: "caseDetail.field.externalId", display: text(d.externalId), testId: 'externalId' },
        { labelKey: "caseDetail.field.sourceBadge", display: text(d.sourceBadge), testId: 'sourceBadge' },
      ],
    },
    {
      key: "caseDetail.group.category", testId: 'category', rows: [
        { labelKey: "caseDetail.field.categoryL1", display: text(d.categoryL1), testId: 'categoryL1' },
        { labelKey: "caseDetail.field.categoryL2", display: text(d.categoryL2), testId: 'categoryL2' },
        { labelKey: "caseDetail.field.caseKind", display: missingColumn(), testId: 'caseKind' },
        { labelKey: "caseDetail.field.safetyLevel", display: text(d.safetyLevel), testId: 'safetyLevel' },
      ],
    },
    {
      key: "caseDetail.group.content", testId: 'content', rows: [
        { labelKey: "caseDetail.field.precondition", display: missingColumn(), testId: 'precondition' },
        { labelKey: "caseDetail.field.steps", display: missingColumn(), testId: 'steps' },
        { labelKey: "caseDetail.field.expected", display: missingColumn(), testId: 'expected' },
        { labelKey: "caseDetail.field.testResource", display: text(d.testResource), testId: 'testResource' },
      ],
    },
    {
      key: "caseDetail.group.execution", testId: 'execution', rows: [
        { labelKey: "caseDetail.field.aSideCommand", display: text(d.aSideCommand), testId: 'aSideCommand' },
        { labelKey: "caseDetail.field.bSideCommand", display: text(d.bSideCommand), testId: 'bSideCommand' },
        { labelKey: "caseDetail.field.switchConfig", display: text(d.switchConfig), testId: 'switchConfig' },
        { labelKey: "caseDetail.field.testResult", display: text(d.testResult), testId: 'testResult' },
      ],
    },
    {
      key: "caseDetail.group.trace", testId: 'trace', rows: [
        { labelKey: "caseDetail.field.sourceSheet", display: text(d.sourceSheet), testId: 'sourceSheet' },
        { labelKey: "caseDetail.field.contentSha", display: text(d.contentSha), testId: 'contentSha' },
        { labelKey: "caseDetail.field.syncJobId", display: text(d.syncJobId), testId: 'syncJobId' },
        { labelKey: "caseDetail.field.localOverride", display: text(d.localOverride), testId: 'localOverride' },
        {
          labelKey: "caseDetail.field.keyMaskedCredential",
          display: text(d.keyMaskedCredential),
          testId: 'keyMaskedCredential',
          masked: true,
        },
      ],
    },
    {
      key: "caseDetail.group.version", testId: 'version', rows: [
        { labelKey: "caseDetail.field.versionNo", display: text(d.versionNo), testId: 'versionNo' },
        { labelKey: "caseDetail.field.lifecycleStatus", display: text(d.lifecycleStatus), testId: 'lifecycleStatus' },
        { labelKey: "caseDetail.field.priority", display: text(d.priority), testId: 'priority' },
      ],
    },
  ]
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await caseDetailApi.get({
      source: props.source,
      caseId: props.caseId,
      projectId: props.projectId,
    })
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("caseDetail.loadFailed")
  } finally {
    loading.value = false
  }
}

// mounted 即按 caseId 拉取：URL 复制后直达不依赖列表页状态；路由参数变化也跟着刷新。
onMounted(load)
watch([() => props.caseId, () => props.source, () => props.projectId], () => void load())
</script>

<template>
  <div class="case-detail" @click.stop>
    <div v-if="loading" class="case-detail-state" data-testid="case-detail-loading">
      {{ t("caseDetail.loading") }}
    </div>
    <p v-else-if="error" class="case-detail-state case-detail-error" data-testid="case-detail-error">
      {{ error }}
    </p>

    <template v-else-if="detail">
      <!-- 副本专属徽章：来源 external_id / 同步作业号 / 本地覆盖 / 来源徽章 -->
      <div v-if="source === 'copy'" class="case-detail-copy" data-testid="copy-badges">
        <span class="badge" data-testid="copy-external-id">
          {{ t("caseDetail.copy.externalId") }}：{{ text(detail.externalId) }}
        </span>
        <span class="badge" data-testid="copy-sync-job">
          {{ t("caseDetail.copy.syncJobId") }}：{{ text(detail.syncJobId) }}
        </span>
        <span class="badge" data-testid="copy-local-override">
          {{ t("caseDetail.copy.localOverride") }}：{{ text(detail.localOverride) }}
        </span>
        <span class="badge" data-testid="copy-source-badge">
          {{ t("caseDetail.copy.sourceBadge") }}：{{ text(detail.sourceBadge) }}
        </span>
      </div>

      <section
        v-for="group in groups"
        :key="group.key"
        class="case-detail-group"
        :data-testid="`case-group-${group.testId}`"
      >
        <h3 class="case-detail-group-title">{{ t(group.key) }}</h3>
        <dl class="case-detail-grid">
          <div
            v-for="row in group.rows"
            :key="row.testId"
            class="case-detail-row"
            :data-testid="`case-field-${row.testId}`"
          >
            <dt class="case-detail-label">{{ t(row.labelKey) }}</dt>
            <dd
              class="case-detail-value"
              :class="{ 'is-missing': row.display === missingColumn() }"
              :data-key-masked="row.masked ? 'keyMaskedCredential' : undefined"
            >{{ row.display }}</dd>
          </div>
        </dl>
      </section>
    </template>
  </div>
</template>

<style scoped>
.case-detail {
  padding: var(--space-3);
  background: var(--surface-2);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.case-detail-state {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.case-detail-error {
  color: var(--fail);
}
.case-detail-copy {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
.badge {
  padding: 2px var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--accent);
  background: var(--panel);
}
.case-detail-group-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  font-weight: 600;
}
.case-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
  margin: 0;
}
.case-detail-row {
  display: flex;
  gap: var(--space-2);
  min-width: 0;
  line-height: 1.6;
  font-size: var(--font-size-sm);
}
.case-detail-label {
  flex: none;
  width: 7em;
  color: var(--text-muted);
}
.case-detail-value {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--text);
}
.case-detail-value.is-missing {
  color: var(--warn);
  font-style: italic;
}
@media (max-width: 760px) {
  .case-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
