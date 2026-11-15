<script setup lang="ts">
/**
 * v5.14.0 / W64-07：测试报告四格式导出（列表与详情共用一份实现）。
 *
 * 后端 ReportExportService 实际支持 html/pdf/docx/xlsx 四格式（R-11），
 * 但前端 exportBlob 一直是零消费者、且旧口径写死 3 种。这里把「格式选择 → 取 Blob
 * → 触发下载 → 回执文件名与大小」收在一个组件里，避免列表页与详情页各写一套。
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton } from 'naive-ui'
import { exportFileName, testReportsApi } from '@/api/testReports'

const props = defineProps<{ reportId: number }>()

const { t } = useI18n()
/** 与后端 ReportExportService.extension() 的白名单一致，不多不少。 */
const FORMATS = ['html', 'pdf', 'docx', 'xlsx'] as const
const format = ref<string>('pdf')
const busy = ref(false)
const message = ref('')

async function doExport(): Promise<void> {
  busy.value = true
  message.value = ''
  try {
    const blob = await testReportsApi.exportBlob(props.reportId, format.value)
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
  } catch {
    // 无快照、格式不支持、权限不足都归到这一句；详情由 ErrorLine 展示给别处。
    message.value = t('testReports.exportFailed')
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
    <NButton size="small" :loading="busy" data-testid="export-trigger" @click="doExport">
      {{ t('testReports.exportAction') }}
    </NButton>
    <span v-if="message" class="muted" data-testid="export-message">{{ message }}</span>
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
</style>
