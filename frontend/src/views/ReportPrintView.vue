<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@/api/http'
import {
  REPORT_METADATA_KEYS,
  reportsApi,
  type ReportCaseNode,
  type ReportPrintData,
  type ReportSuiteNode,
} from '@/api/reports'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const { t } = useI18n()

const data = ref<ReportPrintData | null>(null)
const loading = ref(true)
const error = ref<ErrorLine | null>(null)

const runId = computed(() => String(route.params.runId ?? ''))

/** 扁平化套件树，保留缩进层级；根节点也算一层。 */
interface FlatSuite {
  node: ReportSuiteNode
  depth: number
  path: string
}

const flatSuites = computed<FlatSuite[]>(() => {
  const out: FlatSuite[] = []
  function walk(node: ReportSuiteNode, depth: number, path: string): void {
    out.push({ node, depth, path })
    for (const child of node.suites ?? []) {
      walk(child, depth + 1, `${path}/${child.name}`)
    }
  }
  if (data.value?.tree) walk(data.value.tree, 0, data.value.tree.name)
  return out
})

/** 失败详情：跨套件收集 FAIL 用例，便于单独出一段可打印清单。 */
const failedCases = computed(() => {
  const out: Array<{ path: string; item: ReportCaseNode }> = []
  for (const flat of flatSuites.value) {
    for (const item of flat.node.cases ?? []) {
      if (item.verdict?.toUpperCase() === 'FAIL') {
        out.push({ path: flat.path, item })
      }
    }
  }
  return out
})

/**
 * 命令列文案（FR-v3.9-03 ③「导出件含命令列」）：三态分开写，不留空。
 *
 * 打印页是给别人看的，空白单元格会被读成「这里本来就没有」，而实际可能是
 * 「还没采集」（v3.9.0 之前的报告）——两种结论完全不同，必须写出来。
 */
function commandText(item: ReportCaseNode): string {
  const raw = item.commandText
  if (raw === null || raw === undefined) return t('reports.command.notCollected')
  const trimmed = raw.trim()
  if (!trimmed || trimmed.toUpperCase() === 'NONE') return t('reports.command.none')
  return item.commandExitCode === null || item.commandExitCode === undefined
    ? trimmed
    : `${trimmed}（exit=${item.commandExitCode}）`
}

function metadataMissing(key: string): boolean {
  return data.value?.metadataMissing.includes(key) ?? false
}

function metadataValue(key: string): string {
  return data.value?.metadata?.[key] || t('reports.print.missing')
}

function printedAt(): string {
  return formatDateTime(new Date().toISOString())
}

function printPage(): void {
  window.print()
}

onMounted(async () => {
  document.body.classList.add('print-mode')
  const token = String(route.query.token ?? '')
  if (!token) {
    error.value = { summary: t('reports.print.invalidToken'), detail: null }
    loading.value = false
    return
  }
  try {
    data.value = await reportsApi.printData(runId.value, token)
  } catch (e) {
    // 打印链接是一次性 token，404 就等价于「链接无效」，比通用 404 文案更贴切
    error.value =
      e instanceof ApiError && e.codeName === 'NOT_FOUND'
        ? { summary: t('reports.print.invalidToken'), detail: null }
        : localizeError(e)
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  document.body.classList.remove('print-mode')
})
</script>

<template>
  <div class="print-page">
    <div class="print-toolbar">
      <button class="btn" type="button" @click="printPage">
        {{ t('reports.print.printButton') }}
      </button>
      <span v-if="loading" class="muted">{{ t('reports.print.loading') }}</span>
    </div>

    <div v-if="error" class="print-error">
      <h1>{{ t('reports.print.title') }}</h1>
      <p>{{ error.summary }}</p>
    </div>

    <template v-else-if="data">
      <p v-if="data.internal" class="print-internal-banner">
        {{ t('reports.print.internalWarning') }}
      </p>

      <header class="print-head">
        <h1>{{ t('reports.print.title') }}</h1>
        <p class="print-sub">
          RUN_ID：{{ data.runId }} |
          {{ data.exportable ? t('reports.print.exportable') : t('reports.print.notExportable') }}
        </p>
      </header>

      <section>
        <h2>{{ t('reports.print.metadataTitle') }}</h2>
        <table class="print-table">
          <tbody>
            <tr v-for="key in REPORT_METADATA_KEYS" :key="key">
              <th class="print-key">{{ key }}</th>
              <td :class="metadataMissing(key) ? 'print-missing' : ''">
                {{ metadataMissing(key) ? t('reports.print.missing') : metadataValue(key) }}
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <section>
        <h2>{{ t('reports.print.summaryTitle') }}</h2>
        <p class="print-counts">
          PASS {{ data.passCount }} FAIL {{ data.failCount }} SKIP {{ data.skipCount }}
        </p>
      </section>

      <section>
        <h2>{{ t('reports.print.treeTitle') }}</h2>
        <div
          v-for="flat in flatSuites"
          :key="flat.path"
          class="print-suite"
          :style="{ marginLeft: `${flat.depth * 12}px` }"
        >
          <h3>
            {{ flat.node.name }}
            <span class="print-status">[{{ flat.node.status }}]</span>
          </h3>
          <table v-if="flat.node.cases?.length" class="print-table print-case-table">
            <thead>
              <tr>
                <th>{{ t('reports.print.case') }}</th>
                <th>{{ t('reports.print.verdict') }}</th>
                <th>{{ t('reports.print.duration') }}</th>
                <th>{{ t('reports.command.label') }}</th>
                <th>{{ t('reports.print.failureMsg') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in flat.node.cases" :key="item.caseKey">
                <td>{{ item.name }}</td>
                <td :class="item.verdict?.toUpperCase() === 'FAIL' ? 'print-missing' : ''">
                  {{ item.verdict }}
                </td>
                <td>{{ item.durationMs }}</td>
                <td class="print-command">{{ commandText(item) }}</td>
                <td class="print-failure">{{ item.failureMsg ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2>{{ t('reports.print.failureTitle') }}</h2>
        <p v-if="!failedCases.length" class="muted">
          {{ t('reports.print.noFailure') }}
        </p>
        <div v-for="failed in failedCases" :key="failed.item.caseKey" class="print-failure-item">
          <p class="print-failure-title">
            {{ failed.item.name }}
            <span class="print-muted">（{{ failed.item.caseKey }}）</span>
          </p>
          <p class="print-failure">{{ failed.item.failureMsg }}</p>
        </div>
      </section>

      <footer class="print-foot">
        {{
          t('reports.print.footer', {
            runId: data.runId,
            time: printedAt(),
          })
        }}
      </footer>
    </template>

    <p v-else-if="loading" class="print-loading">{{ t('reports.print.loading') }}</p>
  </div>
</template>

<style scoped>
/*
 * 打印/导出专用页：由无头浏览器直接打开生成 PDF，必须永远是白底黑字 + 红色失败标记，
 * **不能**跟随亮/暗主题（跟随了会出现暗底 PDF，打印出来是黑纸）。
 * 所以整个样式块刻意写死色值，显式豁免色值检查，不是漏改。
 */
/* color-check: ignore-start 打印页必须固定白底黑字，与主题无关 */
.print-page {
  background: #ffffff;
  color: #1a1a1a;
  max-width: 210mm;
  margin: 0 auto;
  padding: 16mm 14mm;
  font-size: 12px;
  line-height: 1.5;
}

.print-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.print-internal-banner {
  color: #c00000;
  border: 2px solid #c00000;
  font-weight: 700;
  font-size: 14px;
  padding: 8px 12px;
  margin-bottom: 16px;
  text-align: center;
}

.print-head h1 {
  font-size: 22px;
  margin: 0 0 6px;
  text-align: center;
}

.print-sub {
  text-align: center;
  color: #444;
  margin: 0 0 18px;
}

/*
 * 全局 `.muted` 取的是 `var(--text-muted)`：暗色主题下是 #8b9bb0，压在白纸上只有 2.83:1。
 * 打印页既然是固定白纸，弱化色也得跟着固定，不能让它跟着平台主题漂。
 */
.print-page .muted {
  color: #666;
}

h2 {
  font-size: 15px;
  border-bottom: 1px solid #999;
  padding-bottom: 4px;
  margin: 22px 0 10px;
}

h3 {
  font-size: 13px;
  margin: 14px 0 6px;
}

.print-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.print-table th,
.print-table td {
  border: 1px solid #999;
  padding: 4px 6px;
  vertical-align: top;
  word-break: break-word;
  white-space: pre-wrap;
}

.print-key {
  width: 26%;
  background: #f2f2f2;
  text-align: left;
}

/* 五列（v3.9.0 追加命令列）：用例 22% / 结论 8% / 耗时 8% / 命令 32% / 失败消息 30%。 */
.print-case-table th:nth-child(1) {
  width: 22%;
}

.print-case-table th:nth-child(2) {
  width: 8%;
}

.print-case-table th:nth-child(3) {
  width: 8%;
}

.print-case-table th:nth-child(4) {
  width: 32%;
}

/* 命令用等宽字体：打印件里要能逐字对得上日志原文。 */
.print-command {
  font-family: 'JetBrains Mono', 'Cascadia Code', ui-monospace, monospace;
  font-size: 9pt;
  white-space: pre-wrap;
  word-break: break-all;
}

.print-missing {
  color: #c00000;
  font-weight: 700;
}

.print-failure {
  color: #a00000;
  white-space: pre-wrap;
}

.print-failure-item {
  margin-bottom: 10px;
  page-break-inside: avoid;
}

.print-failure-title {
  font-weight: 700;
  margin: 0 0 2px;
}

.print-muted,
.print-status {
  color: #666;
  font-weight: 400;
}

.print-foot {
  margin-top: 24px;
  padding-top: 8px;
  border-top: 1px solid #999;
  color: #444;
  font-size: 10px;
  text-align: center;
}

.print-error {
  text-align: center;
  padding: 40px 0;
}

.print-error p {
  color: #c00000;
  font-weight: 700;
}

.print-loading {
  text-align: center;
  padding: 40px 0;
}

@media print {
  @page {
    size: A4;
    margin: 14mm;
  }

  .print-toolbar {
    display: none;
  }

  .print-page {
    padding: 0;
    max-width: none;
    font-size: 11px;
  }
}
/* color-check: ignore-end */
</style>
