<script setup lang="ts">
/**
 * 公共资产库（v4.7.0 / FR-v4.7-01..09、v4.7.1 / FR-v4.7-11..13、v4.9.3 采集与状态、v4.12.0 凭据绑定）。
 *
 * 资产来自「当前测试环境」分节导入：服务器/交换机/端口各自成节，按业务键 upsert，
 * 源文件已无的资产软失效（deprecated）；被项目引用的资产删除返回 409。
 * 导入按整份工作簿的 SHA-256 幂等；采集记录挂到设备上，可对单台设备显式补采。
 *
 * v4.12.0 补两条「入口」：凭据行可列举它绑定的设备并按凭据整批采集；设备和凭据都能手工录入。
 * 三条通道（ssh / network / bmc）由后端按凭据类型分派，页面只根据后端给的 `channel` 显示文案，
 * **不在这里重复一份类型映射** —— 两处映射迟早分叉，分了叉用户看到的就是「有按钮但采不到」。
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
  type BatchCollectResult,
  type CreateCredentialPayload,
  type CreateDevicePayload,
  type CredentialDevice,
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
      width: 220,
      render: (row) =>
        h('span', { class: 'row-actions' }, [
          type.value === 'DEVICE'
            ? canCollect(row)
              ? h(
                  NButton,
                  { text: true, type: 'primary', onClick: () => void openCollect(row) },
                  () => t('global.assetCollect'),
                )
              : h(
                  'span',
                  {
                    class: 'muted action-hint',
                    title: t('global.assetCollectUnbound'),
                  },
                  t('global.assetCollectUnbound'),
                )
            : h(
                NButton,
                {
                  text: true,
                  type: 'primary',
                  onClick: () => void openDevices(row),
                },
                () => t('global.assetCredentialDevices'),
              ),
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

/**
 * 能不能点「采集」（FR-v4.12-10）。
 *
 * 判据是**两个地址面至少绑了一条凭据**：没绑凭据的远程采集必然 400，
 * 与其让用户点开弹窗才发现，不如把入口换成一句原因。
 * 纯带外的交换机（只有 bmc 绑定）仍然可以采 —— 它走 BMC 通道，不是「不能采」。
 */
function canCollect(row: GlobalAssetRow): boolean {
  return Boolean(row.managementCredentialId || row.bmcCredentialId)
}

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

// ------------------------------------ 凭据 → 设备面板（FR-v4.12-11/12/15）

const bindOpen = ref(false)
const bindTarget = ref<GlobalAssetRow | null>(null)
const bindDevices = ref<CredentialDevice[]>([])
const bindLoading = ref(false)
const bindBusy = ref(false)
const bindError = ref('')
const batchResult = ref<BatchCollectResult | null>(null)

async function openDevices(row: GlobalAssetRow) {
  bindTarget.value = row
  bindOpen.value = true
  bindDevices.value = []
  batchResult.value = null
  bindError.value = ''
  bindLoading.value = true
  try {
    bindDevices.value = await globalAssetApi.credentialDevices(row.id)
  } catch (error) {
    bindError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    bindLoading.value = false
  }
}

async function collectAll() {
  if (!bindTarget.value) return
  bindBusy.value = true
  bindError.value = ''
  batchResult.value = null
  try {
    batchResult.value = await globalAssetApi.collectByCredential(bindTarget.value.id, true)
    // 采完立刻刷两处：设备列表的「采集状态」列，和面板里的最近状态。只刷一处就会自相矛盾。
    await load()
    bindDevices.value = await globalAssetApi.credentialDevices(bindTarget.value.id)
  } catch (error) {
    bindError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    bindBusy.value = false
  }
}

/** 三层文案表放在组件里而不是 i18n 嵌套里：拼错的通道名会显示原文而不是「undefined」。 */
const CHANNEL_LABEL_KEY: Record<string, string> = {
  ssh: 'global.assetChannelSsh',
  network: 'global.assetChannelNetwork',
  bmc: 'global.assetChannelBmc',
}

function planeLabel(plane: string): string {
  return plane === 'bmc' ? t('global.assetPlaneBmc') : t('global.assetPlaneMgmt')
}

function channelLabel(channel: string): string {
  const key = CHANNEL_LABEL_KEY[channel]
  return key ? t(key) : channel
}

// ---------------------------------------------- 手工录入（FR-v4.12-13/14）

const deviceFormOpen = ref(false)
const deviceFormBusy = ref(false)
const deviceFormError = ref('')
const deviceFormDone = ref('')
const deviceForm = ref<CreateDevicePayload>(emptyDeviceForm())
const credentialPicker = ref<GlobalAssetRow[]>([])

function emptyDeviceForm(): CreateDevicePayload {
  return {
    name: '',
    deviceType: '',
    managementIp: '',
    managementIpAlt: '',
    bmcAddress: '',
    bmcAddressAlt: '',
    managementCredentialId: null,
    bmcCredentialId: null,
    model: '',
    vendor: '',
    serialNo: '',
    location: '',
    domainTag: '',
  }
}

function emptyCredentialForm(): CreateCredentialPayload {
  return { name: '', credType: 'ssh', username: '', secret: '', connectOptions: '' }
}

async function openDeviceForm() {
  deviceFormOpen.value = true
  deviceForm.value = emptyDeviceForm()
  deviceFormError.value = ''
  deviceFormDone.value = ''
  await loadCredentialPicker()
}

async function loadCredentialPicker() {
  try {
    credentialPicker.value = await globalAssetApi.list({ type: 'CREDENTIAL' })
  } catch {
    credentialPicker.value = []
  }
}

/**
 * 提交前本地先挡两道：与后端同口径（名称非空、至少一个地址）。
 *
 * <p>不是为了替代后端校验，而是为了**少一次往返**：这两个错最容易犯，本地提示更快。
 * 真正的把关仍在后端（同名 409、地址全空 400），本地提示不通过就不发请求。
 */
async function submitDeviceForm() {
  deviceFormError.value = ''
  deviceFormDone.value = ''
  const form = deviceForm.value
  if (!form.name || !form.name.trim()) {
    deviceFormError.value = t('global.assetNameRequired')
    return
  }
  const hasAddress = [form.managementIp, form.managementIpAlt, form.bmcAddress, form.bmcAddressAlt]
    .some((value) => Boolean(value && value.trim()))
  if (!hasAddress) {
    deviceFormError.value = t('global.assetAddressRequired')
    return
  }
  deviceFormBusy.value = true
  try {
    const created = await globalAssetApi.createDevice(form)
    deviceFormDone.value = t('global.assetCreateDone', { name: created.name })
    deviceForm.value = emptyDeviceForm()
    await load()
  } catch (error) {
    deviceFormError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    deviceFormBusy.value = false
  }
}

const credentialFormOpen = ref(false)
const credentialFormBusy = ref(false)
const credentialFormError = ref('')
const credentialFormDone = ref('')
const credentialForm = ref<CreateCredentialPayload>(emptyCredentialForm())

function openCredentialForm() {
  credentialFormOpen.value = true
  credentialForm.value = emptyCredentialForm()
  credentialFormError.value = ''
  credentialFormDone.value = ''
}

async function submitCredentialForm() {
  credentialFormError.value = ''
  credentialFormDone.value = ''
  const form = credentialForm.value
  if (!form.name || !form.name.trim()) {
    credentialFormError.value = t('global.assetNameRequired')
    return
  }
  if (!form.secret || !form.secret.trim()) {
    credentialFormError.value = t('global.assetSecretRequired')
    return
  }
  credentialFormBusy.value = true
  try {
    const created = await globalAssetApi.createCredential(form)
    credentialFormDone.value = t('global.assetCreateDone', { name: created.name })
    // 口令不留在页面状态里：提交后立刻清空，截屏 / 录屏都不会带走它
    credentialForm.value = emptyCredentialForm()
    await load()
  } catch (error) {
    credentialFormError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    credentialFormBusy.value = false
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
        <button v-if="canWrite && type === 'DEVICE'" class="btn" @click="openDeviceForm">
          {{ t('global.assetDeviceCreate') }}
        </button>
        <button v-if="canWrite && type === 'CREDENTIAL'" class="btn" @click="openCredentialForm">
          {{ t('global.assetCredentialCreate') }}
        </button>
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

    <!-- FR-v4.12-15：凭据 → 关联设备面板 + 全部采集 -->
    <div v-if="bindOpen" class="modal-mask" @click.self="bindOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t('global.assetCredentialDevicesTitle') }}</h2>
          <button class="link" @click="bindOpen = false">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('global.assetCredentialDevicesHint') }}</p>
        <p class="mono">{{ bindTarget?.name }} · {{ bindTarget?.detail }}</p>

        <p v-if="bindLoading" class="muted">{{ t('global.loading') }}</p>
        <p v-else-if="bindDevices.length === 0" class="warning">
          {{ t('global.assetCredentialNoDevice') }}
        </p>
        <table v-else class="bind-table">
          <thead>
            <tr>
              <th>{{ t('global.assetName') }}</th>
              <th>{{ t('global.assetPlane') }}</th>
              <th>{{ t('global.assetPlaneAddress') }}</th>
              <th>{{ t('global.assetChannel') }}</th>
              <th>{{ t('global.assetCollectStatus') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="device in bindDevices" :key="`${device.deviceId}-${device.plane}`">
              <td>{{ device.name }}</td>
              <td>{{ planeLabel(device.plane) }}</td>
              <td class="mono">{{ device.address || '-' }}</td>
              <td>{{ channelLabel(device.channel) }}</td>
              <td>
                {{ device.lastStatus || t('global.assetCollectNever') }}
                <span v-if="device.lastCollectedAt" class="muted">
                  · {{ device.lastCollectedAt }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>

        <template v-if="batchResult">
          <p class="success" data-testid="batch-summary">
            {{
              t('global.assetCollectAllDone', {
                total: batchResult.total,
                ok: batchResult.ok,
                failed: batchResult.failed,
              })
            }}
          </p>
          <ul v-if="batchResult.failed > 0" class="samples">
            <li v-for="item in batchResult.items.filter((i) => i.status !== 'OK' && i.status !== 'PARTIAL')"
                :key="`${item.deviceId}-${item.plane}`">
              {{ item.name }} · {{ planeLabel(item.plane) }} · {{ item.status }} ·
              {{ item.reason || '-' }}
            </li>
          </ul>
        </template>

        <p v-if="bindError" class="error">{{ bindError }}</p>

        <div class="modal-actions">
          <button class="btn" @click="bindOpen = false">{{ t('global.close') }}</button>
          <button
            class="btn btn-primary"
            :disabled="bindBusy || bindDevices.length === 0"
            @click="collectAll"
          >
            {{ bindBusy ? t('global.loading') : t('global.assetCollectAll') }}
          </button>
        </div>
      </div>
    </div>

    <!-- FR-v4.12-13：手工录入设备 -->
    <div v-if="deviceFormOpen" class="modal-mask" @click.self="deviceFormOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t('global.assetDeviceCreateTitle') }}</h2>
          <button class="link" @click="deviceFormOpen = false">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('global.assetDeviceCreateHint') }}</p>
        <div class="form-grid">
          <label class="field">
            <span>{{ t('global.assetFieldName') }} *</span>
            <input v-model="deviceForm.name" class="input" data-testid="device-name" />
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldDeviceType') }}</span>
            <select v-model="deviceForm.deviceType" class="select" data-testid="device-type">
              <option value="">-</option>
              <option value="gpu">gpu</option>
              <option value="server">server</option>
              <option value="storage">storage</option>
              <option value="switch">switch</option>
            </select>
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldManagementIp') }}</span>
            <input v-model="deviceForm.managementIp" class="input" data-testid="device-mgmt-ip" />
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldManagementIpAlt') }}</span>
            <input v-model="deviceForm.managementIpAlt" class="input" />
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldBmcAddress') }}</span>
            <input v-model="deviceForm.bmcAddress" class="input" data-testid="device-bmc" />
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldBmcAddressAlt') }}</span>
            <input v-model="deviceForm.bmcAddressAlt" class="input" />
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldManagementCredential') }}</span>
            <select v-model="deviceForm.managementCredentialId" class="select">
              <option :value="null">-</option>
              <option v-for="item in credentialPicker" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>{{ t('global.assetFieldBmcCredential') }}</span>
            <select v-model="deviceForm.bmcCredentialId" class="select">
              <option :value="null">-</option>
              <option v-for="item in credentialPicker" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
        </div>
        <p v-if="deviceFormError" class="error">{{ deviceFormError }}</p>
        <p v-if="deviceFormDone" class="success">{{ deviceFormDone }}</p>
        <div class="modal-actions">
          <button class="btn" @click="deviceFormOpen = false">{{ t('global.close') }}</button>
          <button class="btn btn-primary" :disabled="deviceFormBusy" @click="submitDeviceForm">
            {{ t('global.assetCreateSubmit') }}
          </button>
        </div>
      </div>
    </div>

    <!-- FR-v4.12-14：手工录入凭据 -->
    <div v-if="credentialFormOpen" class="modal-mask" @click.self="credentialFormOpen = false">
      <div class="modal narrow">
        <header class="modal-head">
          <h2>{{ t('global.assetCredentialCreateTitle') }}</h2>
          <button class="link" @click="credentialFormOpen = false">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('global.assetCredentialCreateHint') }}</p>
        <label class="field">
          <span>{{ t('global.assetFieldName') }} *</span>
          <input v-model="credentialForm.name" class="input" data-testid="credential-name" />
        </label>
        <label class="field">
          <span>{{ t('global.assetFieldCredType') }}</span>
          <select v-model="credentialForm.credType" class="select" data-testid="credential-type">
            <option value="ssh">ssh</option>
            <option value="network">network</option>
            <option value="bmc">bmc</option>
            <option value="snmp">snmp</option>
            <option value="token">token</option>
          </select>
        </label>
        <label class="field">
          <span>{{ t('global.assetFieldUsername') }}</span>
          <input v-model="credentialForm.username" class="input" data-testid="credential-username" />
        </label>
        <label class="field">
          <span>{{ t('global.assetFieldSecret') }} *</span>
          <input
            v-model="credentialForm.secret"
            class="input"
            type="password"
            data-testid="credential-secret"
          />
        </label>
        <label class="field">
          <span>{{ t('global.assetFieldConnectOptions') }}</span>
          <input
            v-model="credentialForm.connectOptions"
            class="input"
            placeholder="diffie-hellman-group14-sha1"
            data-testid="credential-options"
          />
        </label>
        <p v-if="credentialFormError" class="error">{{ credentialFormError }}</p>
        <p v-if="credentialFormDone" class="success">{{ credentialFormDone }}</p>
        <div class="modal-actions">
          <button class="btn" @click="credentialFormOpen = false">{{ t('global.close') }}</button>
          <button class="btn btn-primary" :disabled="credentialFormBusy" @click="submitCredentialForm">
            {{ t('global.assetCreateSubmit') }}
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

/* 未绑定凭据时的占位：不是按钮，但要让「为什么点不了」看得见（FR-v4.12-10）。 */
:deep(.action-hint) {
  font-size: 12px;
}

.bind-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin: var(--space-3) 0;
}

.bind-table th,
.bind-table td {
  text-align: left;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
}

.bind-table th {
  color: var(--text-muted);
  font-weight: 500;
}

/* 录入表单：两列铺满，字段多但没有层级关系，用 grid 比逐行堆更快扫。 */
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--space-3);
}

.form-grid .input,
.form-grid .select {
  min-width: 0;
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
