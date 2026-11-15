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
import { testReportsApi, type TestReportSnapshot } from '@/api/testReports'
import ErrorLineView from '@/components/ErrorLine.vue'
import ReportExportBar from '@/components/ReportExportBar.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const id = computed(() => Number(route.params.id ?? 0))
const snapshot = ref<TestReportSnapshot | null>(null)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const freezing = ref(false)

async function load(): Promise<void> {
  error.value = null
  snapshot.value = null
  if (!id.value) return
  loading.value = true
  try {
    snapshot.value = await testReportsApi.snapshot(id.value)
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
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
      <ReportExportBar :report-id="id" />
    </div>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="loading" class="muted">{{ t('common.loading') }}</p>

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
          </dl>
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
  background: #fff;
}
</style>
