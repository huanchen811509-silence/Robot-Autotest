<script setup lang="ts">
/**
 * MENU-03：测试总表看板（只读）。
 * EVID-04：当前轮结论四态化；有证据的用例可查看原始流弹窗、下载真实 JSON，
 * 无证据时显示「证据缺失」且下载按钮禁用，不假装有数据。
 *
 * 同屏并列显示 historical_status 与 current_status 两列及各自计数。
 * 历史列来自外部导入的历史结论，仅作对照；本平台当前通过率只取 current_status，
 * 绝不把 ED202 历史「通过 330」等历史数值当成本平台当前通过率。
 * 行集初始为空、显示「0 条」，不编造 / 硬编码任何业务数据，不发起写请求。
 */
import { computed, h, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import {
  summarizeBoard,
  hasEvidence,
  buildEvidenceJson,
  renderEvidenceLog,
  type CaseBoardRow,
  type BoardStatus,
} from './caseBoard'

const { t } = useI18n()

const rows = ref<CaseBoardRow[]>([])
const summary = computed(() => summarizeBoard(rows.value))

/** 当前轮四态 → 用户可见文案，全部走 t()；动态拼键会绕过 i18n 字面量键自检。 */
function statusLabel(status: BoardStatus | null): string {
  switch (status) {
    case 'passed':
      return t('caseBoard.status.passed')
    case 'failed':
      return t('caseBoard.status.failed')
    case 'untested':
      return t('caseBoard.status.untested')
    case 'missing_evidence':
      return t('caseBoard.status.missing_evidence')
    default:
      return t('caseBoard.untested')
  }
}

const dialogVisible = ref(false)
const dialogCaseKey = ref('')
const dialogLog = ref('')

function closeDialog(): void {
  dialogVisible.value = false
}

/** 有证据才能打开弹窗；内容与下载同源，均由 buildEvidenceJson 的 null 门禁把关。 */
function openDialog(row: CaseBoardRow): void {
  const evidence = row.evidence
  if (buildEvidenceJson(row) == null || !evidence) return
  const stepLine = t('caseBoard.evidence.stepLine', {
    no: evidence.stepNo,
    desc: evidence.stepDescription,
  })
  dialogLog.value = renderEvidenceLog(evidence, stepLine)
  dialogCaseKey.value = row.caseKey
  dialogVisible.value = true
}

/** 有证据时用 Blob + <a download> 导出真实 JSON；无证据直接返回，不触发下载。 */
function downloadJson(row: CaseBoardRow): void {
  const json = buildEvidenceJson(row)
  if (json == null) return
  const blob = new Blob([json], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${row.caseKey}.evidence.json`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

const columns = computed<DataTableColumns<CaseBoardRow>>(() => [
  { title: t('caseBoard.caseKey'), key: 'caseKey' },
  {
    title: 'historical_status',
    key: 'historical_status',
    render: (row) =>
      h('span', { 'data-testid': 'historical-cell' }, row.historical_status ?? '—'),
  },
  {
    title: 'current_status',
    key: 'current_status',
    render: (row) =>
      h('span', { 'data-testid': 'current-cell' }, statusLabel(row.current_status)),
  },
  {
    title: t('caseBoard.evidence.column'),
    key: 'evidence',
    render: (row) => {
      const available = hasEvidence(row)
      return h('div', { class: 'ev-actions' }, [
        available
          ? h(
              'button',
              {
                class: 'link-btn',
                type: 'button',
                'data-testid': 'view-evidence',
                onClick: () => openDialog(row),
              },
              t('caseBoard.evidence.view'),
            )
          : h(
              'span',
              { class: 'muted', 'data-testid': 'evidence-missing' },
              t('caseBoard.evidence.missing'),
            ),
        h(
          'button',
          {
            class: 'link-btn',
            type: 'button',
            'data-testid': 'download-btn',
            disabled: !available,
            onClick: () => downloadJson(row),
          },
          t('caseBoard.evidence.download'),
        ),
      ])
    },
  },
])
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('caseBoard.title') }}</h1>
      <p class="muted">{{ t('caseBoard.subtitle') }}</p>
    </header>

    <section class="counters">
      <div class="counter">
        <span class="label">{{ t('caseBoard.historicalPass') }}</span>
        <strong data-testid="historical-pass">{{ summary.historicalPass }}</strong>
      </div>
      <div class="counter">
        <span class="label">{{ t('caseBoard.currentPass') }}</span>
        <strong data-testid="current-pass">{{ summary.currentPass }}</strong>
      </div>
      <div class="counter">
        <span class="label">{{ t('caseBoard.currentUntested') }}</span>
        <strong data-testid="current-untested">{{ summary.currentUntested }}</strong>
      </div>
    </section>

    <p class="note">{{ t('caseBoard.note') }}</p>

    <section class="panel">
      <h2>{{ t('caseBoard.panel') }}</h2>
      <DataTable :columns="columns" :data="rows" :row-key="(row: CaseBoardRow) => row.caseKey">
        <template #empty>
          <p class="empty" data-testid="empty">{{ t('caseBoard.empty') }}</p>
        </template>
      </DataTable>
    </section>

    <div
      v-if="dialogVisible"
      class="dialog-mask"
      role="dialog"
      data-testid="evidence-dialog"
      :aria-label="t('caseBoard.evidence.dialogTitle')"
      @click.self="closeDialog"
    >
      <div class="dialog-panel">
        <header class="dialog-head">
          <h2>{{ t('caseBoard.evidence.dialogTitle') }}</h2>
          <span class="muted">{{ dialogCaseKey }}</span>
        </header>
        <pre class="dialog-log" data-testid="evidence-log">{{ dialogLog }}</pre>
        <footer class="dialog-foot">
          <button class="link-btn" type="button" data-testid="close-dialog" @click="closeDialog">
            {{ t('caseBoard.evidence.close') }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.counters { display: flex; gap: 14px; flex-wrap: wrap; }
.counter { flex: 1 1 180px; border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 14px 16px; display: grid; gap: 8px; }
.counter .label { font-size: 12px; opacity: .7; }
.counter strong { font-size: 22px; }
.note { font-size: 13px; padding: 8px 12px; border-radius: 6px; border: 1px solid color-mix(in srgb, currentColor 18%, transparent); }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.grid { width: 100%; border-collapse: collapse; font-size: 13px; }
.grid th, .grid td { border: 1px solid color-mix(in srgb, currentColor 14%, transparent); padding: 6px 10px; text-align: left; }
.ev-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.dialog-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: grid; place-items: center; padding: 24px; }
.dialog-panel { width: 100%; max-width: 760px; max-height: 90vh; overflow: auto; background: var(--panel); border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; display: grid; gap: 14px; }
.dialog-head { display: flex; align-items: baseline; gap: 10px; }
.dialog-head h2 { margin: 0; font-size: 15px; }
.dialog-log { margin: 0; padding: 12px; font-size: 13px; white-space: pre-wrap; word-break: break-all; border: 1px solid color-mix(in srgb, currentColor 14%, transparent); border-radius: 6px; }
.dialog-foot { display: flex; justify-content: flex-end; }
</style>
