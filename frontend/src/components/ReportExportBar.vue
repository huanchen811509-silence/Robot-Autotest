<script setup lang="ts">
/**
 * v5.14.0 / W64-07：测试报告四格式导出（列表与详情共用一份实现）。
 * v5.23.0 / W73-04+05+14：失败可见（显示后端原文与缺项）、内部版出路、导出中禁用 + toast。
 *
 * 后端 ReportExportService 实际支持 html/pdf/docx/xlsx 四格式（R-11）。
 * 缺 metadata 时外部版被门禁拒绝（400，message 带缺项与「改用 mode=internal」），
 * 这里把后端 message 原样呈现，并提供「内部版」一键降级，不再静默失败。
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton } from 'naive-ui'
import { ApiError } from '@/api/http'
import { exportFileName, testReportsApi } from '@/api/testReports'

const props = defineProps<{ reportId: number }>()

const { t } = useI18n()
/** 与后端 ReportExportService.extension() 的白名单一致，不多不少。 */
const FORMATS = ['html', 'pdf', 'docx', 'xlsx'] as const
const format = ref<string>('pdf')
const mode = ref<'external' | 'internal'>('external')
const busy = ref(false)
const message = ref('')
const failed = ref(false)

async function doExport(): Promise<void> {
  if (busy.value) return
  busy.value = true
  message.value = ''
  failed.value = false
  try {
    const blob = await testReportsApi.exportBlob(props.reportId, format.value, mode.value)
    const fileName = exportFileName(props.reportId, format.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    message.value = t('testReports.exportDone', { name: fileName, size: blob.size })
  } catch (e) {
    // W73-04：后端 400 的 message 已带缺项清单与处置建议，原样呈现，不再吞成一句通用文案。
    const detail = e instanceof ApiError ? e.message : (e instanceof Error ? e.message : '')
    message.value = detail
      ? t('testReports.exportFailedDetail', { detail })
      : t('testReports.exportFailed')
    failed.value = true
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="export-bar" data-testid="report-export-bar">
    <select v-model="format" data-testid="export-format" :aria-label="t('testReports.exportAction')">
      <option v-for="item in FORMATS" :key="item" :value="item">{{ item.toUpperCase() }}</option>
    </select>
    <select v-model="mode" data-testid="export-mode" :aria-label="t('testReports.exportModeLabel')">
      <option value="external">{{ t('testReports.exportModeExternal') }}</option>
      <option value="internal">{{ t('testReports.exportModeInternal') }}</option>
    </select>
    <NButton size="small" :loading="busy" :disabled="busy" data-testid="export-trigger" @click="doExport">
      {{ t('testReports.exportAction') }}
    </NButton>
    <span
      v-if="message"
      class="export-message"
      :class="failed ? 'export-message-error' : 'export-message-ok'"
      data-testid="export-message"
      role="status"
    >{{ message }}</span>
  </div>
</template>

<style scoped>
.export-bar {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.export-bar select {
  padding: 4px 6px;
  border-radius: 6px;
  font: inherit;
  border: 1px solid var(--border-strong);
  background: var(--panel);
  color: inherit;
}

/* 用 theme.css 的真实令牌：--fail / --ok。原先写的 --error-color / --success-color
   在 theme.css 里不存在，变量回落成硬编码色值——换个主题就不跟色了（门禁 15 判红）。 */
.export-message-error {
  color: var(--fail);
  font-weight: 500;
}
.export-message-ok {
  color: var(--ok);
}
</style>
