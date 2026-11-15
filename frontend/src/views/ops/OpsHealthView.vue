<script setup lang="ts">
/**
 * 运维 · 健康总览（v5.16.0 / FR-v5.16-04~05）。
 *
 * 六类指标全部来自 `GET /api/ops/health`，L1 事实来自 `GET /api/ops/facts`。
 * 取不到的指标显示后端给的 `hint` 原话，**不填 0 冒充正常** —— 磁盘读数失败
 * 与磁盘真的 0 余量是两件事，这里不许混。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NTag, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import { opsConsole } from '@/api/opsV516'
import type { FactsView, HealthOverview } from '@/api/opsV516'

interface FactRow {
  id: string
  label: string
  value: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const health = ref<HealthOverview | null>(null)
const facts = ref<FactsView | null>(null)
const loading = ref(false)
const errorText = ref('')
const showDoctor = ref(false)

const diskTone = computed(() => {
  const level = health.value?.disk.level ?? 'UNKNOWN'
  if (level === 'CRITICAL') return 'error'
  if (level === 'WARN') return 'warning'
  if (level === 'OK') return 'success'
  return 'default'
})

const factRows = computed<FactRow[]>(() => {
  const f = facts.value
  if (!f) return []
  return [
    { id: 'version', label: t('opsV516.health.version'), value: f.version || t('opsV516.common.notCollected') },
    {
      id: 'manifest',
      label: t('opsV516.health.manifest'),
      value: f.manifestVersion ?? t('opsV516.common.notCollected'),
    },
    {
      id: 'match',
      label: t('opsV516.health.match'),
      value: f.manifestVersion
        ? f.versionMatch
          ? t('opsV516.health.matchYes')
          : t('opsV516.health.matchNo')
        : t('opsV516.common.notCollected'),
    },
    {
      id: 'upgrade',
      label: t('opsV516.health.lastUpgrade'),
      value: f.lastUpgradeAt
        ? `${f.lastUpgradeAt} ${f.lastUpgradeResult ?? ''}`.trim()
        : t('opsV516.common.notCollected'),
    },
    { id: 'root', label: t('opsV516.health.installRoot'), value: f.installRoot },
    { id: 'data', label: t('opsV516.health.dataDir'), value: f.dataDir ?? t('opsV516.common.notCollected') },
    {
      id: 'releases',
      label: t('opsV516.health.releases'),
      value: f.releases.length
        ? f.releases.map((r) => r.name).join(' / ')
        : t('opsV516.health.noRelease'),
    },
    { id: 'doctorCmd', label: t('opsV516.health.doctorCmd'), value: f.doctorCommand },
  ]
})

const columns = computed<DataTableColumns<FactRow>>(() => [
  { title: t('opsV516.health.colItem'), key: 'label', width: 200 },
  { title: t('opsV516.health.colValue'), key: 'value', ellipsis: { tooltip: true } },
])

async function load() {
  if (!projectId.value) return
  loading.value = true
  errorText.value = ''
  try {
    const [h, f] = await Promise.all([
      opsConsole.healthOverview(projectId.value),
      opsConsole.facts(projectId.value),
    ])
    health.value = h
    facts.value = f
  } catch {
    errorText.value = t('opsV516.common.loadFailed')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="ops-page">
    <header class="ops-head">
      <h2>{{ t('opsV516.health.title') }}</h2>
      <NButton size="small" :loading="loading" @click="load">{{ t('opsV516.common.refresh') }}</NButton>
    </header>
    <p class="ops-desc">{{ t('opsV516.health.desc') }}</p>
    <p v-if="errorText" class="ops-error">{{ errorText }}</p>

    <div v-if="health" class="ops-grid">
      <article class="ops-card">
        <h3>{{ t('opsV516.health.service') }}</h3>
        <p>{{ t('opsV516.health.backend') }}：<NTag size="small" type="success">{{ health.service.backend }}</NTag></p>
        <p>{{ t('opsV516.health.frontend') }}：<NTag size="small">{{ health.service.frontend }}</NTag></p>
        <p>{{ t('opsV516.health.database') }}：<NTag size="small" :type="health.database.up ? 'success' : 'error'">
          {{ health.service.database }}</NTag></p>
        <p class="ops-muted">{{ health.service.jdbcUrl ?? t('opsV516.common.notCollected') }}</p>
      </article>

      <article class="ops-card">
        <h3>{{ t('opsV516.health.dbSize') }}</h3>
        <p>{{ t('opsV516.health.sizeMb') }}：{{ health.database.sizeMb ?? t('opsV516.common.notCollected') }}</p>
        <p>{{ t('opsV516.health.connections') }}：{{ health.database.connections ?? t('opsV516.common.notCollected') }}</p>
        <p>{{ t('opsV516.health.tables') }}：{{ health.database.tables ?? t('opsV516.common.notCollected') }}</p>
        <p v-if="health.database.hint" class="ops-muted">{{ health.database.hint }}</p>
      </article>

      <article class="ops-card">
        <h3>{{ t('opsV516.health.disk') }}</h3>
        <p>
          <NTag size="small" :type="diskTone">{{ health.disk.level }}</NTag>
          {{ health.disk.usablePercent }}%
        </p>
        <p class="ops-muted">
          {{ t('opsV516.health.total') }} {{ Math.round(health.disk.totalBytes / 1073741824) }} GiB
        </p>
        <p v-if="health.disk.hint" class="ops-muted">{{ health.disk.hint }}</p>
      </article>

      <article class="ops-card">
        <h3>{{ t('opsV516.health.recentCollect') }}</h3>
        <p v-if="health.recentCollect.at">{{ health.recentCollect.at }} · {{ health.recentCollect.result }}</p>
        <p v-else class="ops-muted">{{ health.recentCollect.hint ?? t('opsV516.common.notCollected') }}</p>
      </article>

      <article class="ops-card">
        <h3>{{ t('opsV516.health.recentBackup') }}</h3>
        <p v-if="health.recentBackup">{{ health.recentBackup.name }}</p>
        <p v-if="health.recentBackup" class="ops-muted">
          {{ Math.round(health.recentBackup.sizeBytes / 1024) }} KiB ·
          {{ health.recentBackup.verifyState }}
        </p>
        <p v-else class="ops-muted">{{ t('opsV516.health.noBackup') }}</p>
      </article>

      <article class="ops-card">
        <h3>{{ t('opsV516.health.lastDoctor') }}</h3>
        <p v-if="health.lastDoctor" class="ops-muted">{{ health.lastDoctor }}</p>
        <p v-else class="ops-muted">{{ t('opsV516.health.noDoctor') }}</p>
        <NButton size="tiny" quaternary @click="showDoctor = !showDoctor">
          {{ showDoctor ? t('opsV516.common.hide') : t('opsV516.common.show') }}
        </NButton>
        <pre v-if="showDoctor" class="ops-pre">{{ facts?.doctorCommand }}</pre>
      </article>
    </div>

    <h3 class="ops-sub">{{ t('opsV516.health.facts') }}</h3>
    <DataTable
      :columns="columns"
      :data="factRows"
      :loading="loading"
      :row-key="(row: FactRow) => row.id"
      :empty-title="t('opsV516.common.empty')"
      :empty-description="t('opsV516.health.factsEmpty')"
    />
  </section>
</template>

<style scoped>
.ops-page { display: flex; flex-direction: column; gap: 12px; }
.ops-head { display: flex; align-items: center; justify-content: space-between; }
.ops-desc { color: var(--text-muted); margin: 0; }
.ops-sub { margin: 8px 0 0; }
.ops-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.ops-card { border: 1px solid var(--border-strong); border-radius: var(--radius-sm); padding: 12px; background: var(--panel); }
.ops-card h3 { margin: 0 0 8px; font-size: 13px; }
.ops-card p { margin: 4px 0; }
.ops-muted { color: var(--text-muted); font-size: 12px; }
.ops-error { color: var(--fail); margin: 0; }
.ops-pre { background: var(--surface-2); padding: 8px; border-radius: var(--radius-sm); overflow-x: auto; }
</style>
