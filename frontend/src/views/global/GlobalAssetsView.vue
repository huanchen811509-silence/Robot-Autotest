<script setup lang="ts">
/**
 * 公共资产库（v4.7.0 / FR-v4.7-01..09、v4.7.1 / FR-v4.7-11..13、v4.9.3 采集与状态）。
 *
 * 资产来自「当前测试环境」分节导入：服务器/交换机/端口各自成节，按业务键 upsert，
 * 源文件已无的资产软失效（deprecated）；被项目引用的资产删除返回 409。
 * 导入按整份工作簿的 SHA-256 幂等；采集记录挂到设备上，可对单台设备显式补采。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { NButton, type DataTableColumns } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import {
  globalAssetApi,
  type AssetImportResult,
  type AssetPreview,
  type GlobalAssetRow,
} from '@/api/globalAssets'
import DataTable from '@/components/DataTable.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

type AssetTab = 'DEVICE' | 'CREDENTIAL'

/** URL 用复数小写词表（与项目模式 `/assets?tab=devices` 一致），接口用枚举名。 */
const TAB_TO_TYPE: Record<string, AssetTab> = { devices: 'DEVICE', credentials: 'CREDENTIAL' }
const TYPE_TO_TAB: Record<AssetTab, string> = { DEVICE: 'devices', CREDENTIAL: 'credentials' }

/**
 * v4.11.0 / FR-v4.11-27：页签接入 URL。
 *
 * 侧栏「设备 / 凭据」两项是**同一 path 的两个 query**，`isLeafActive` 严格比较 query，
 * 所以裸路径 `/g/assets`（无 query）会让两个叶子都不亮。这里把无 query / 非法值一律
 * `router.replace` 规范化到 `?tab=devices`，让 URL 恒定带 query。
 */
function tabFromQuery(): AssetTab {
  const raw = route.query.tab
  const value = Array.isArray(raw) ? raw[0] : raw
  return TAB_TO_TYPE[value ?? ''] ?? 'DEVICE'
}

const type = ref<AssetTab>(tabFromQuery())
const keyword = ref('')
const availability = ref('')
const rows = ref<GlobalAssetRow[]>([])
const loading = ref(false)
const failed = ref('')
const canWrite = computed(() => auth.isAdmin)

async function load() {
  loading.value = true
  failed.value = ''
  try {
    rows.value = await globalAssetApi.list({
      type: type.value,
      keyword: keyword.value,
      availability: availability.value,
    })
  } catch {
    failed.value = t('global.loadFailed')
  } finally {
    loading.value = false
  }
}

function switchType(next: AssetTab) {
  type.value = next
  // 页签点击与侧栏选中态必须同步：先改 URL，再由 watch 生效（避免两套状态源）。
  const target = TYPE_TO_TAB[next]
  if (route.query.tab !== target) {
    void router.replace({ query: { ...route.query, tab: target } })
  }
  void load()
}

/** 浏览器前进/后退或侧栏点击：URL 是唯一真源。 */
watch(
  () => route.query.tab,
  () => {
    const next = tabFromQuery()
    if (next !== type.value) {
      type.value = next
      void load()
    }
  },
)

/* ------------------------------------------------------------ 表格列定义 */

const columns = computed<DataTableColumns<GlobalAssetRow>>(() => {
  const cols: DataTableColumns<GlobalAssetRow> = [
    { title: t('global.assetName'), key: 'name', minWidth: 180 },
    { title: t('global.assetDetail'), key: 'detail', minWidth: 200 },
    { title: t('global.assetIp'), key: 'managementIp', width: 160 },
    {
      title: t('global.colStatus'),
      key: 'status',
      width: 120,
      render: (row) =>
        h(
          'span',
          { class: ['badge', row.status] },
          row.status === 'active'
            ? t('global.assetStatusActive')
            : t('global.assetStatusDeprecated'),
        ),
    },
  ]
  if (type.value === 'DEVICE') {
    cols.push({ title: t('global.assetPorts'), key: 'portCount', width: 100 })
    cols.push({
      title: t('global.assetCollectStatus'),
      key: 'collectStatus',
      width: 200,
      render: (row) => collectLabel(row),
    })
  }
  cols.push({ title: t('global.assetRefs'), key: 'referenceCount', width: 100 })
  if (canWrite.value) {
    cols.push({
      title: t('global.colActions'),
      key: 'actions',
      width: 200,
      render: (row) =>
        h('span', { class: 'row-actions' }, [
          type.value === 'DEVICE'
            ? h(
                NButton,
                { text: true, type: 'primary', onClick: () => void openCollect(row) },
                () => t('global.assetCollect'),
              )
            : null,
          h(
            NButton,
            { text: true, type: 'error', onClick: () => void removeAsset(row) },
            () => t('global.assetDelete'),
          ),
        ]),
    })
  }
  return cols
})

// ---------------------------------------------------------------- 导入

const importOpen = ref(false)
const preview = ref<AssetPreview | null>(null)
const result = ref<AssetImportResult | null>(null)
const importing = ref(false)
const importError = ref('')
let pickedFile: File | null = null

function openImport() {
  importOpen.value = true
  preview.value = null
  result.value = null
  importError.value = ''
  pickedFile = null
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  pickedFile = file
  importing.value = true
  importError.value = ''
  result.value = null
  try {
    preview.value = await globalAssetApi.preview(file)
  } catch (error) {
    importError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    importing.value = false
  }
}

async function commitImport() {
  if (!pickedFile) return
  importing.value = true
  try {
    result.value = await globalAssetApi.commit(pickedFile)
    await load()
  } catch (error) {
    importError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    importing.value = false
  }
}

// ---------------------------------------------------------------- 采集

const collectOpen = ref(false)
const collectTarget = ref<GlobalAssetRow | null>(null)
const credentialOptions = ref<GlobalAssetRow[]>([])
const collectCredentialId = ref<number | null>(null)
const collectUsername = ref('')
const collectBusy = ref(false)
const collectError = ref('')
const collectDone = ref('')

async function openCollect(row: GlobalAssetRow) {
  collectTarget.value = row
  collectOpen.value = true
  collectError.value = ''
  collectDone.value = ''
  collectCredentialId.value = null
  collectUsername.value = ''
  try {
    credentialOptions.value = await globalAssetApi.list({ type: 'CREDENTIAL' })
  } catch {
    credentialOptions.value = []
  }
}

async function runCollect() {
  if (!collectTarget.value) return
  collectBusy.value = true
  collectError.value = ''
  try {
    const collected = await globalAssetApi.collect(collectTarget.value.id, {
      credentialId: collectCredentialId.value ?? undefined,
      username: collectUsername.value || undefined,
      confirm: true,
    })
    collectDone.value = t('global.assetCollectDone', {
      status: collected.status,
      nics: collected.nicCount,
      roce: collected.rocePorts,
    })
    await load()
  } catch (error) {
    collectError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    collectBusy.value = false
  }
}

// ---------------------------------------------------------------- 其他

async function exportAssets() {
  const blob = await globalAssetApi.export()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'public-assets.xlsx'
  anchor.click()
  URL.revokeObjectURL(url)
}

async function removeAsset(row: GlobalAssetRow) {
  if (!window.confirm(t('global.assetDeleteConfirm', { name: row.name }))) return
  try {
    await globalAssetApi.remove(row.assetType, row.id)
    await load()
  } catch (error) {
    window.alert(error instanceof Error ? error.message : t('global.loadFailed'))
  }
}

function collectLabel(row: GlobalAssetRow): string {
  if (!row.collectCount) return t('global.assetCollectNever')
  const status = row.collectStatus ?? '-'
  return row.lastCollectedAt ? `${status} · ${row.lastCollectedAt}` : status
}

function shortSha(sha: string): string {
  return sha.length > 16 ? `${sha.slice(0, 12)}…${sha.slice(-4)}` : sha
}

onMounted(() => {
  // 进入即规范化：裸 `/g/assets` 与 `?tab=links` 都改写成 `?tab=devices`，
  // 否则侧栏两个叶子都不亮（FR-v4.11-27 判据）。
  if (route.query.tab !== TYPE_TO_TAB[type.value]) {
    void router.replace({ query: { ...route.query, tab: TYPE_TO_TAB[type.value] } })
  }
  void load()
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('global.assetsTitle') }}</h1>
        <p class="muted">{{ t('global.assetsHint') }}</p>
      </div>
      <div class="page-actions">
        <button v-if="canWrite" class="btn" @click="exportAssets">{{ t('global.export') }}</button>
        <button v-if="canWrite" class="btn btn-primary" @click="openImport">
          {{ t('global.import') }}
        </button>
      </div>
    </header>

    <div class="tabs">
      <button class="tab" :class="{ active: type === 'DEVICE' }" @click="switchType('DEVICE')">
        {{ t('global.assetDevice') }}
      </button>
      <button
        class="tab"
        :class="{ active: type === 'CREDENTIAL' }"
        @click="switchType('CREDENTIAL')"
      >
        {{ t('global.assetCredential') }}
      </button>
    </div>

    <div class="filters">
      <input
        v-model="keyword"
        class="input"
        :placeholder="t('global.searchPlaceholder')"
        @keyup.enter="load"
      />
      <select v-model="availability" class="select" @change="load">
        <option value="">{{ t('global.assetAllStatus') }}</option>
        <option value="active">{{ t('global.assetStatusActive') }}</option>
        <option value="deprecated">{{ t('global.assetStatusDeprecated') }}</option>
      </select>
    </div>

    <p v-if="failed" class="error">{{ failed }}</p>

    <div data-testid="assets-grid">
      <DataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: GlobalAssetRow) => row.id"
        :error="Boolean(failed)"
        :empty-title="t('global.empty')"
        :max-height="560"
      />
    </div>

    <div v-if="importOpen" class="modal-mask" @click.self="importOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t('global.importAssets') }}</h2>
          <button class="link" @click="importOpen = false">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('global.assetImportHint') }}</p>
        <input type="file" accept=".xlsx,.xls" @change="onFilePicked" />
        <p v-if="importing" class="muted">{{ t('global.loading') }}</p>
        <p v-if="importError" class="error">{{ importError }}</p>

        <template v-if="preview">
          <p class="muted mono">
            {{ t('global.assetChecksum') }}：{{ shortSha(preview.sha256) }} ·
            {{ t('global.assetBatch') }} #{{ preview.batchId }}
          </p>
          <p v-if="preview.replayed" class="warning">{{ t('global.assetReplayed') }}</p>
          <div class="diff-cards">
            <span class="diff-card new">{{ t('global.assetDevice') }} {{ preview.devices }}</span>
            <span class="diff-card update">{{ t('global.assetPorts') }} {{ preview.ports }}</span>
            <span class="diff-card unchanged">
              {{ t('global.assetCredential') }} {{ preview.credentials }}
            </span>
          </div>
          <ul v-if="preview.samples.length" class="samples">
            <li v-for="(sample, index) in preview.samples" :key="index">{{ sample }}</li>
          </ul>
          <p v-for="warning in preview.warnings" :key="warning" class="warning">{{ warning }}</p>

          <p v-if="result" class="success">
            {{
              t('global.assetImportDone', {
                devices: result.devices,
                ports: result.ports,
                credentials: result.credentials,
                deprecations: result.deprecations,
                supplemented: result.statusSupplemented,
              })
            }}
          </p>
          <div class="modal-actions">
            <button class="btn" @click="importOpen = false">{{ t('global.close') }}</button>
            <button class="btn btn-primary" :disabled="importing" @click="commitImport">
              {{ t('global.importCommit') }}
            </button>
          </div>
        </template>
      </div>
    </div>

    <div v-if="collectOpen" class="modal-mask" @click.self="collectOpen = false">
      <div class="modal narrow">
        <header class="modal-head">
          <h2>{{ t('global.assetCollectTitle') }}</h2>
          <button class="link" @click="collectOpen = false">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('global.assetCollectHint') }}</p>
        <p class="mono">{{ collectTarget?.name }} · {{ collectTarget?.managementIp }}</p>
        <p v-if="credentialOptions.length === 0" class="warning">
          {{ t('global.assetCollectNoCredential') }}
        </p>
        <template v-else>
          <label class="field">
            <span>{{ t('global.assetCollectCredential') }}</span>
            <select v-model="collectCredentialId" class="select">
              <option :value="null">-</option>
              <option v-for="item in credentialOptions" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>{{ t('global.assetCollectUsername') }}</span>
            <input v-model="collectUsername" class="input" />
          </label>
        </template>
        <p v-if="collectError" class="error">{{ collectError }}</p>
        <p v-if="collectDone" class="success">{{ collectDone }}</p>
        <div class="modal-actions">
          <button class="btn" @click="collectOpen = false">{{ t('global.close') }}</button>
          <button
            class="btn btn-primary"
            :disabled="collectBusy || credentialOptions.length === 0"
            @click="runCollect"
          >
            {{ t('global.assetCollectConfirm') }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.page-actions {
  display: flex;
  gap: var(--space-2);
}

.tabs {
  display: flex;
  gap: var(--space-2);
  margin: var(--space-3) 0;
  border-bottom: 1px solid var(--border);
}

.tab {
  border: 0;
  background: transparent;
  color: var(--text-muted);
  padding: 6px 10px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}

.tab.active {
  color: var(--text);
  border-bottom-color: var(--accent);
}

/* DataTable 的 render 返回值：两个文字按钮并排，间距与旧版 .link 一致。 */
:deep(.row-actions) {
  display: inline-flex;
  gap: 10px;
  align-items: center;
}

.filters {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.input,
.select {
  min-width: 180px;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

/* 表头下第二个文字按钮（采集 / 删除）由 render() 产生，不带 scope id，必须 :deep 锚定。 */
.mono {
  font-family: var(--font-mono, monospace);
}

:deep(.badge) {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 11px;
  border: 1px solid var(--border);
}

:deep(.badge.active) {
  color: var(--ok);
}

:deep(.badge.deprecated) {
  color: var(--fail);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal {
  width: min(760px, 92vw);
  max-height: 86vh;
  overflow: auto;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.modal.narrow {
  width: min(480px, 92vw);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: var(--space-2) 0;
  font-size: 12px;
  color: var(--text-muted);
}

.diff-cards {
  display: flex;
  gap: var(--space-2);
  margin: var(--space-3) 0;
}

.diff-card {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  font-size: 12px;
}

.samples {
  max-height: 180px;
  overflow: auto;
  font-size: 12px;
  color: var(--text-muted);
}

.error {
  color: var(--fail);
}

.warning {
  color: var(--warn);
  font-size: 12px;
}

.success {
  color: var(--ok);
}

.link {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
  padding: 0;
  margin-right: var(--space-2);
}

.link.danger {
  color: var(--fail);
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
</style>
