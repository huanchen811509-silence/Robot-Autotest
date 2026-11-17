<script setup lang="ts">
/**
 * v5.14.0 / W64-05 / FR-v5.14-04：测试报告详情（快照预览）。
 *
 * 与草案最大的差异：DRAFT 创建时后端就物化了 snapshot_html（R-10），所以这里没有
 * 「尚未生成快照 + 生成」分支；DRAFT 真正缺的是**冻结**（POST /{id}/freeze 已存在），
 * 冻结件才是对外交付物。无快照只对历史脏数据出现，给可解释的空态，不假装有按钮。
 *
 * 安全：iframe 用 sandbox=""（既无 allow-same-origin 也无 allow-scripts），
 * srcdoc 绑定服务端清洗后的 HTML。两层防线缺一不可，规则见 ReportHtmlSanitizer。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { NButton } from 'naive-ui'
import {
  testReportsApi,
  type EhtSections,
  type TestReportSnapshot,
  type TestReportTemplateRef,
} from '@/api/testReports'
import ErrorLineView from '@/components/ErrorLine.vue'
import ReportExportBar from '@/components/ReportExportBar.vue'
import CaseScriptLink from '@/components/CaseScriptLink.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const id = computed(() => Number(route.params.id ?? 0))
const snapshot = ref<TestReportSnapshot | null>(null)
const templateRef = ref<TestReportTemplateRef | null>(null)
const sections = ref<EhtSections | null>(null)
// W73-03：报告元数据状态（来自 render internal 视图，含 runId / metadataMissing）
const reportMeta = ref<{ runId: string; exportable: boolean; missing: string[] } | null>(null)
const genericTemplate = computed(
  () => !!templateRef.value
    && ['PLATFORM_ANY', 'PLATFORM_FALLBACK'].includes(templateRef.value.templateSource ?? ''),
)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const freezing = ref(false)
// W73-18：客户视图预览（external 脱敏字段表，只读、无导出按钮）
const customerView = ref<Record<string, unknown> | null>(null)
const customerLoading = ref(false)
const customerError = ref<ErrorLine | null>(null)

async function previewCustomer(): Promise<void> {
  customerLoading.value = true
  customerError.value = null
  customerView.value = null
  try {
    // external=true → 后端 ReportViewRenderer.external 白名单 + 逐值脱敏
    customerView.value = await testReportsApi.render(id.value, true)
  } catch (e) {
    customerError.value = localizeError(e)
  } finally {
    customerLoading.value = false
  }
}

function closeCustomer(): void {
  customerView.value = null
  customerError.value = null
}

/** 客户视图只展示字符串/数字，对象值降级为 JSON 文本，避免 [object Object]。 */
function cellText(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

async function load(): Promise<void> {
  error.value = null
  snapshot.value = null
  templateRef.value = null
  sections.value = null
  if (!id.value) return
  loading.value = true
  try {
    snapshot.value = await testReportsApi.snapshot(id.value)
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
  // 模板回指与三段式装配都是元信息补充：失败不该让整份报告预览打不开。
  try {
    templateRef.value = await testReportsApi.template(id.value)
  } catch {
    templateRef.value = null
  }
  try {
    sections.value = await testReportsApi.sections(id.value)
  } catch {
    sections.value = null
  }
  // W73-03：拿内部视图的 runId / 缺项，给 DRAFT 报告一个明确的补录出口。
  // 失败不影响主预览（元信息补充，与主链路解耦）。
  try {
    const internal = await testReportsApi.render(id.value, false)
    reportMeta.value = {
      runId: String(internal.runId ?? ''),
      exportable: Boolean(internal.exportable),
      missing: Array.isArray(internal.metadataMissing) ? internal.metadataMissing.map(String) : [],
    }
  } catch {
    reportMeta.value = null
  }
}

async function freeze(): Promise<void> {
  freezing.value = true
  error.value = null
  try {
    await testReportsApi.freeze(id.value)
    await load()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    freezing.value = false
  }
}

function back(): void {
  void router.push({ name: 'test-reports' })
}

function jumpTo(which: 'content' | 'meta'): void {
  const el = document.getElementById(which === 'content' ? 'snapshot-frame' : 'snapshot-meta')
  el?.scrollIntoView?.({ block: 'start' })
}

watch(id, () => void load(), { immediate: true })
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">{{ t('testReports.detailTitle') }}</h1>
      <NButton quaternary data-testid="back-to-list" @click="back">
        {{ t('testReports.backToList') }}
      </NButton>
      <NButton
        quaternary
        data-testid="preview-customer"
        :loading="customerLoading"
        @click="previewCustomer"
      >
        {{ t('testReports.previewCustomer') }}
      </NButton>
      <ReportExportBar :report-id="id" />
    </div>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="loading" class="muted">{{ t('common.loading') }}</p>

    <section v-if="customerView" class="card customer-preview" data-testid="customer-preview">
      <div class="customer-banner" role="status" data-testid="customer-banner">
        {{ t('testReports.customerBanner') }}
      </div>
      <p class="muted small">{{ t('testReports.customerHint') }}</p>
      <ErrorLineView v-if="customerError" :error="customerError" />
      <table class="customer-table" data-testid="customer-table">
        <tbody>
          <tr v-for="(value, key) in customerView" :key="String(key)">
            <th>{{ String(key) }}</th>
            <td data-testid="customer-cell">{{ cellText(value) }}</td>
          </tr>
        </tbody>
      </table>
      <NButton quaternary data-testid="close-customer" @click="closeCustomer">
        {{ t('testReports.closeCustomer') }}
      </NButton>
    </section>

    <div v-else-if="snapshot" class="report-detail-layout">
      <aside class="report-nav card">
        <a href="#" @click.prevent="jumpTo('content')">{{ t('testReports.sectionContent') }}</a>
        <a href="#" @click.prevent="jumpTo('meta')">{{ t('testReports.sectionMeta') }}</a>
      </aside>

      <div class="report-main">
        <section id="snapshot-meta" class="card">
          <h2 class="card-title">{{ t('testReports.sectionMeta') }}</h2>
          <dl>
            <dt>{{ t('testReports.colTitle') }}</dt>
            <dd>{{ snapshot.title }}</dd>
            <dt>{{ t('testReports.stateLabel') }}</dt>
            <dd>{{ snapshot.state }}</dd>
            <dt>{{ t('testReports.hashLabel') }}</dt>
            <dd class="mono">{{ snapshot.hash || '—' }}</dd>
            <dt>{{ t('testReports.templateLabel') }}</dt>
            <dd>{{ templateRef?.name || '—' }}</dd>
            <dt>{{ t('testReports.templateKernelLabel') }}</dt>
            <dd>{{ templateRef?.kernel || '—' }}</dd>
          </dl>
          <p v-if="genericTemplate" class="muted" data-testid="generic-template-hint">
            {{ t('testReports.genericTemplateHint') }}
          </p>
          <p v-if="snapshot.frozen" class="muted" data-testid="frozen-hint">
            {{ t('testReports.frozenHint') }}
          </p>
          <NButton
            v-else-if="snapshot.state === 'DRAFT'"
            type="primary"
            :loading="freezing"
            data-testid="freeze-report"
            @click="freeze"
          >
            {{ t('testReports.freezeAction') }}
          </NButton>
          <!-- W73-03：DRAFT 缺项时，给出补录路径（执行报告页已支持就地补录，W69-08） -->
          <div
            v-if="snapshot.state === 'DRAFT' && reportMeta && reportMeta.missing.length > 0"
            class="meta-fix-hint"
            data-testid="meta-fix-hint"
          >
            <p>{{ t('testReports.metaFixHint', { keys: reportMeta.missing.join('、') }) }}</p>
            <RouterLink
              v-if="reportMeta.runId"
              :to="'/reports/' + reportMeta.runId"
              data-testid="meta-fix-link"
            >
              {{ t('testReports.metaFixLink') }}
            </RouterLink>
          </div>
        </section>

        <section v-if="sections" id="evidence-card" class="card" data-testid="evidence-card">
          <h2 class="card-title">{{ t('testReports.sectionEvidence') }}</h2>
          <p class="muted" data-testid="evidence-counts">
            {{ t('testReports.evidenceCounts', {
              passed: sections.counts.passed,
              failed: sections.counts.failed,
              missing: sections.counts.missingEvidence,
              undetermined: sections.counts.undetermined,
              total: sections.counts.total,
            }) }}
          </p>
          <p v-if="!sections.diff.hasBaseline" class="muted" data-testid="no-baseline">
            {{ t('testReports.noBaseline') }}
          </p>
          <ul v-if="sections.evidence.length" class="evidence-list">
            <li
              v-for="row in sections.evidence"
              :key="row.nodeRef"
              class="evidence-row"
              data-testid="evidence-row"
            >
              <code class="mono" data-testid="evidence-node-ref">{{ row.nodeRef }}</code>
              <CaseScriptLink
                :script-path="row.evidencePath"
                :binding-state="row.evidencePath ? 'BOUND_VERIFIED' : 'NOT_APPLICABLE'"
                :full="true"
              />
            </li>
          </ul>
          <p v-else class="muted" data-testid="evidence-empty">
            {{ sections.kernel === 'pytest'
              ? t('testReports.evidenceEmpty')
              : t('testReports.evidenceNotApplicable') }}
          </p>
        </section>

        <section class="card">
          <h2 class="card-title">{{ t('testReports.sectionContent') }}</h2>
          <iframe
            v-if="snapshot.hasSnapshot"
            id="snapshot-frame"
            data-testid="snapshot-frame"
            sandbox=""
            :srcdoc="snapshot.html"
            :title="snapshot.title"
          />
          <p v-else class="muted" data-testid="snapshot-missing">
            {{ t('testReports.snapshotMissing') }}
          </p>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.meta-fix-hint {
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-left: 3px solid var(--warn, #e8b339);
  background: var(--panel);
  font-size: var(--font-size-sm);
}
.meta-fix-hint a { color: var(--accent); text-decoration: underline; }

.customer-preview {
  margin-bottom: var(--space-4);
}
.customer-banner {
  padding: var(--space-2) var(--space-3);
  border: 2px solid var(--warn, #e8b339);
  border-radius: var(--radius-sm);
  color: var(--warn, #e8b339);
  font-weight: 600;
  margin-bottom: var(--space-3);
}
.customer-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--font-size-sm);
}
.customer-table th,
.customer-table td {
  border-bottom: 1px solid var(--border);
  padding: var(--space-2);
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
}
.customer-table th {
  width: 14rem;
  color: var(--text-muted);
}

.report-detail-layout {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 16px;
}

.report-nav {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-self: start;
}

.report-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

iframe {
  width: 100%;
  min-height: 560px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--panel);
}
</style>
