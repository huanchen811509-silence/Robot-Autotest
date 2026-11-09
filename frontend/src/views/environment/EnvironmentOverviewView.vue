<script setup lang="ts">
/**
 * FR-v3.11-02 · 环境总览（只读）。
 *
 * 四张卡片的事实各来自真实 GET：设备数取资产分页的 total、环境数取画像清单、
 * 最近采集取主机清单列表 + 详情（未采到的类别数从 spec_json 里数出来，不猜）。
 * 本页不发起任何写请求，也没有自动采集；异常项只是「直达采集页」的链接。
 * 只读口径沿用 v2.2：读不到就显示空态或错误态，绝不补一行示例数据。
 * 空态显示「0 条」：沿用 env.common.empty 文案键，不硬编码中文，也不补造示例行。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, type DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import { useAuthStore } from '@/stores/auth'
import { deviceApi } from '@/api/asset'
import { envApi, hostInventoryApi, parseSpec } from '@/api/environment'
import { formatDateTime } from '@/utils/format'

interface Row {
  id: string
  name: string
  status: string
  nodeCount: string
  latestAt: string
}

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const rows = ref<Row[]>([])
const deviceTotal = ref(0)
const latestCollect = ref('—')
const missingCount = ref(0)
const loading = ref(false)
const failed = ref(false)

const columns = computed<DataTableColumns<Row>>(() => [
  { title: t('env.overview.colName'), key: 'name' },
  { title: t('env.overview.colStatus'), key: 'status' },
  { title: t('env.overview.colNodeCount'), key: 'nodeCount' },
  { title: t('env.common.colLatestAt'), key: 'latestAt' },
])

async function load() {
  if (!projectId.value) { rows.value = []; return }
  loading.value = true
  failed.value = false
  try {
    const [profiles, devices, hosts] = await Promise.all([
      envApi.profiles(projectId.value),
      deviceApi.page(projectId.value, { page: 1, size: 1 }),
      hostInventoryApi.list(projectId.value, { limit: 20 }),
    ])
    deviceTotal.value = devices.total ?? 0
    rows.value = profiles.map((p) => ({
      id: p.name,
      name: p.name,
      status: p.profileType || t('env.common.notCollected'),
      nodeCount: `${p.snapshotCount} ${t('env.overview.snapshotUnit')}`,
      latestAt: p.latestAt ? formatDateTime(p.latestAt) : t('env.common.notCollected'),
    }))
    const latest = hosts[0]
    if (latest) {
      latestCollect.value = `${latest.status} · ${formatDateTime(latest.collectedAt)}`
      const detail = await hostInventoryApi.detail(projectId.value, latest.id)
      missingCount.value = parseSpec(detail.specJson)?.items.filter((i) => !i.value).length ?? 0
    } else {
      latestCollect.value = t('env.common.notCollected')
      missingCount.value = 0
    }
  } catch {
    rows.value = []
    deviceTotal.value = 0
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(projectId, load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('env.overview.title') }}</h1>
      <p class="muted">{{ t('env.common.subtitle') }}</p>
    </header>

    <div v-if="failed" class="banner error" data-testid="overview-failed">
      {{ t('env.common.loadFailed') }}
      <NButton size="small" tertiary data-testid="overview-retry" @click="load">
        {{ t('env.common.retry') }}
      </NButton>
    </div>

    <section class="cards">
      <article class="card">
        <span class="label">{{ t('env.overview.deviceCount') }}</span>
        <strong data-testid="overview-devices">{{ deviceTotal }}</strong>
      </article>
      <article class="card">
        <span class="label">{{ t('env.overview.envCount') }}</span>
        <strong data-testid="overview-envs">{{ rows.length }}</strong>
      </article>
      <article class="card">
        <span class="label">{{ t('env.overview.latestCollect') }}</span>
        <strong class="small" data-testid="overview-latest">{{ latestCollect }}</strong>
      </article>
      <article class="card" :class="{ warn: missingCount > 0 }">
        <span class="label">{{ t('env.overview.missingItems') }}</span>
        <strong data-testid="overview-missing">{{ missingCount }}</strong>
        <RouterLink v-if="missingCount > 0" to="/environment/collect" data-testid="overview-goto">
          {{ t('env.overview.gotoCollect') }}
        </RouterLink>
      </article>
    </section>

    <section class="panel">
      <h2>{{ t('env.overview.panel') }}</h2>
      <p v-if="loading" class="muted" data-testid="overview-loading">{{ t('env.common.loading') }}</p>
      <DataTable v-else :columns="columns" :data="rows" :row-key="(row: Row) => row.id">
        <template #empty>
          <p class="empty" data-testid="overview-empty">{{ t('env.common.empty') }}</p>
        </template>
      </DataTable>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.panel { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.panel h2 { margin: 0 0 12px; font-size: 15px; }
.empty { font-size: 13px; opacity: .7; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.card { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 14px 16px; display: grid; gap: 6px; }
.card.warn { border-color: color-mix(in srgb, var(--warn) 55%, transparent); }
.label { font-size: 12px; opacity: .7; }
.card strong { font-size: 24px; }
.card strong.small { font-size: 14px; }
.card a { font-size: 12px; }
.banner { border-radius: 8px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.banner.error { border: 1px solid color-mix(in srgb, var(--fail) 50%, transparent); }
</style>
