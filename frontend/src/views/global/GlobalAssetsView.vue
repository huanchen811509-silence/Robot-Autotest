<script setup lang="ts">
/**
 * 公共资产库（v4.7.0 / FR-v4.7-01..09、v4.7.1 / FR-v4.7-11..13）。
 *
 * 资产来自「当前测试环境」分节导入：服务器/交换机/端口/凭据各自成节，按业务键 upsert，
 * 源文件已无的资产软失效；被项目引用的资产删除返回 409。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import {
  globalAssetApi,
  type AssetImportResult,
  type AssetPreview,
  type GlobalAssetRow,
} from '@/api/globalAssets'

const { t } = useI18n()
const auth = useAuthStore()

const type = ref<'DEVICE' | 'CREDENTIAL'>('DEVICE')
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

function switchType(next: 'DEVICE' | 'CREDENTIAL') {
  type.value = next
  void load()
}

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

onMounted(load)
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
        <option value="inactive">{{ t('global.assetStatusInactive') }}</option>
      </select>
    </div>

    <p v-if="failed" class="error">{{ failed }}</p>
    <div v-if="loading" class="muted">{{ t('global.loading') }}</div>

    <table v-else class="asset-table">
      <thead>
        <tr>
          <th>{{ t('global.assetName') }}</th>
          <th>{{ t('global.assetDetail') }}</th>
          <th>{{ t('global.assetIp') }}</th>
          <th>{{ t('global.colStatus') }}</th>
          <th>{{ t('global.assetRefs') }}</th>
          <th v-if="canWrite">{{ t('global.colActions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td>{{ row.name }}</td>
          <td class="muted">{{ row.detail }}</td>
          <td class="mono">{{ row.managementIp }}</td>
          <td>
            <span class="badge" :class="row.status">
              {{
                row.status === 'active'
                  ? t('global.assetStatusActive')
                  : t('global.assetStatusInactive')
              }}
            </span>
          </td>
          <td>{{ row.referenceCount }}</td>
          <td v-if="canWrite">
            <button class="link danger" @click="removeAsset(row)">
              {{ t('global.assetDelete') }}
            </button>
          </td>
        </tr>
        <tr v-if="rows.length === 0">
          <td :colspan="canWrite ? 6 : 5" class="muted empty">{{ t('global.empty') }}</td>
        </tr>
      </tbody>
    </table>

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
          <div class="diff-cards">
            <span class="diff-card new">{{ t('global.assetDevice') }} {{ preview.devices }}</span>
            <span class="diff-card update">{{ t('global.assetPorts') }} {{ preview.ports }}</span>
            <span class="diff-card unchanged">
              {{ t('global.assetCredential') }} {{ preview.credentials }}
            </span>
          </div>
          <ul v-if="preview.samples.length" class="samples">
            <li v-for="sample in preview.samples" :key="sample">{{ sample }}</li>
          </ul>
          <p v-for="warning in preview.warnings" :key="warning" class="warning">{{ warning }}</p>

          <p v-if="result" class="success">
            {{
              t('global.assetImportDone', {
                devices: result.devices,
                ports: result.ports,
                credentials: result.credentials,
                deprecations: result.deprecations,
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
  border-bottom-color: var(--accent, #2563eb);
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

.asset-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.asset-table th,
.asset-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  text-align: left;
}

.asset-table th {
  color: var(--text-muted);
  font-weight: 600;
}

.mono {
  font-family: var(--font-mono, monospace);
}

.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 11px;
  border: 1px solid var(--border);
}

.badge.active {
  color: var(--ok);
}

.badge.inactive {
  color: var(--fail);
}

.empty {
  text-align: center;
  padding: var(--space-4);
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
  color: var(--accent, #2563eb);
  cursor: pointer;
  padding: 0;
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
  background: var(--accent, #2563eb);
  border-color: var(--accent, #2563eb);
  color: #fff;
}
</style>
