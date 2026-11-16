<script setup lang="ts">
import { confirmAction } from '@/composables/useConfirm'
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
import { computed, onMounted, ref, watch } from 'vue'
import AssetWorkspace from '@/components/AssetWorkspace.vue'
import AssetImportBlock from '@/components/AssetImportBlock.vue'
import { isVisible, visibleTabs } from '@/config/assetWorkspaceMatrix'
import { useCollectProfiles } from '@/composables/useCollectProfiles'
import { useGlobalAssetColumns } from '@/config/globalAssetColumns'
import { useAssetCollection } from '@/composables/useAssetCollection'
import CollectHistoryPanel from '@/components/CollectHistoryPanel.vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import {
  globalAssetApi,
  type CreateCredentialPayload,
  type CreateDevicePayload,
  type CredentialDevice,
  type GlobalAssetRow,
} from '@/api/globalAssets'
import DataTable from '@/components/DataTable.vue'
import CredentialPicker from '@/components/CredentialPicker.vue'
import type { CredentialOption, ManualCollectOutcome } from '@/api/credentialOptions'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

type AssetTab = 'DEVICE' | 'CREDENTIAL'
/** W62-08：global 只有 2 个页签，由矩阵决定；页面不得再硬编码第三份清单。 */
const GLOBAL_TABS = visibleTabs('global').map(
  (row) => (row.key === 'tab.devices' ? 'DEVICE' : 'CREDENTIAL') as AssetTab,
)
function tabVisible(next: AssetTab): boolean {
  return GLOBAL_TABS.includes(next)
}

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
    void loadProfiles()
  } catch {
    failed.value = t('global.loadFailed')
  } finally {
    loading.value = false
  }
}

function switchType(next: AssetTab) {
  type.value = next
  selectedAssetIds.value = []
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

/** W63-09：画像按 deviceId 建索引；两页共用同一份加载与取值逻辑。 */
const { byDevice: profilesByDevice, load: loadProfiles } = useCollectProfiles()

const renaming = ref<GlobalAssetRow | null>(null)
const renameValue = ref('')
const renameIp = ref('')
const renameBusy = ref(false)
const renameError = ref('')

/** W62-14：批量删是否可用由差异矩阵唯一决定，不在页面里另写一份 scope 判断。 */
const BATCH_DELETE_ENABLED = isVisible('global', 'action.batchDelete')
const selectedAssetIds = ref<number[]>([])
const batchBusy = ref(false)
const batchMessage = ref('')

function toggleAssetSelection(id: number, checked: boolean): void {
  const next = new Set(selectedAssetIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  selectedAssetIds.value = [...next]
}

async function batchDeleteAssets(): Promise<void> {
  const ids = [...selectedAssetIds.value]
  if (ids.length === 0 || batchBusy.value) return
  if (!(await confirmAction(t('w62.batchDeleteConfirm', { count: ids.length }), {
      tone: 'danger',
    })))
    return
  batchBusy.value = true
  batchMessage.value = ''
  try {
    const result = await globalAssetApi.batchDelete(type.value, ids)
    const conflicts = result.items.filter((item) => !item.deleted && item.reason === 'CONFLICT')
    const projects = [...new Set(conflicts.flatMap((item) => item.projects))]
    batchMessage.value = t('w62.batchDeleteResult', {
      deleted: result.deleted,
      conflicted: result.conflicted,
      projects: projects.length ? projects.join(', ') : t('w62.batchDeleteNoConflict'),
    })
    selectedAssetIds.value = []
    await load()
  } catch (error) {
    batchMessage.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    batchBusy.value = false
  }
}



// v5.18.0 / FR-v5.18-02 / W68-05：采集与「凭据→设备」面板抽成 `useAssetCollection`。
const {
  collectOpen, collectTarget, credentialOptions, collectCredentialId, collectUsername,
  collectBusy, collectError, collectDone, setAsDefault, historyRows,
  canCollect, collectDisabledReason, openCollect, directCollect, runCollect,
  bindOpen, bindTarget, bindDevices, bindLoading, bindBusy, bindError, batchResult,
  openDevices, collectAll,
} = useAssetCollection({ t, profilesByDevice, load, loadProfiles })

// v5.18.0 / FR-v5.18-02 / W68-05：两组列定义抽到 `config/globalAssetColumns.ts`。
const { columns, bindColumns } = useGlobalAssetColumns({
  t,
  type,
  canWrite,
  batchDeleteEnabled: BATCH_DELETE_ENABLED,
  selectedAssetIds,
  profilesByDevice,
  toggleAssetSelection,
  collectLabel,
  canCollect,
  collectDisabledReason,
  planeLabel,
  channelLabel,
  openCollect,
  directCollect,
  openDevices,
  openRename,
  removeAsset,
})

// ---------------------------------------------------------------- 导入
//
// v5.18.0 / FR-v5.18-01：导入从「两步弹窗」升级为五步向导，整块搬到
// `AssetImportBlock`（含批次记录入口）。页面这边只剩一个开关——
// 向导自己管预检/提交，页面不再持有 file / preview / result 三份临时状态。

const importOpen = ref(false)
/** `?view=imports` → 直接展开批次面板（判据 7：结果页能跳过去查批次）。 */
const isImportView = computed(() => route.query.view === 'imports')

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

/**
 * W57-41：既有凭据下拉（GlobalAssetRow[]）与凭证选择器的候选（CredentialOption）字段不同。
 * 新建设备无 assetId（传 0），选择器用 fallbackOptions 展示既有凭据；采集弹层有真实
 * assetId，会走后端 options 端点拿四级排序结果，fallback 只作接口失败时的兜底。
 */
function toCredentialOptions(items: GlobalAssetRow[]): CredentialOption[] {
  return items.map((row) => ({
    credentialId: row.id,
    name: row.name,
    credType: '',
    username: '',
    tier: 3,
    lastResult: '',
    lastVerifiedAt: '',
    boundDevices: 0,
    configured: true,
  }))
}
const deviceCredentialFallback = computed(() => toCredentialOptions(credentialPicker.value))
const collectCredentialFallback = computed(() => toCredentialOptions(credentialOptions.value))
function onManualCollected(outcome: ManualCollectOutcome): void {
  // 响应不含任何材料位；一次性凭证未写入凭据库，提示要说清「不是新增了一条凭据」。
  collectDone.value = t('w57.globalAssets.collectDone')
  // oneShot=false 说明用户显式勾了「另存为凭据」，凭据库多了一条，下拉要刷新；
  // oneShot=true 时库里没有新增，重拉纯属浪费一次往返。
  if (!outcome.oneShot) void loadCredentialPicker()
}

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

/**
 * 改名（v5.9.0 / W59-20）：这是台账里 `/g/assets` 缺的那个 U。
 *
 * 只开放显示名 —— 型号/序列号/管理 IP 由采集与导入回写，手工改会在下次采集被覆盖。
 * 保存后重拉列表而不是就地改 `row.name`：列表里还有排序与搜索命中的可能，
 * 以服务端返回为准才不会出现「改完看起来没变」。
 */
function openRename(row: GlobalAssetRow): void {
  renaming.value = row
  renameValue.value = row.name
  renameIp.value = row.managementIp ?? ''
  renameError.value = ''
}

async function submitRename(): Promise<void> {
  const row = renaming.value
  if (!row || renameBusy.value) return
  renameError.value = ''
  renameBusy.value = true
  try {
    const ip = renameIp.value.trim()
    // 没动管理 IP 就保持 5.9 的三参调用（不碰事实列），改了才把 IP 透传给 W62-12 的新 PUT
    if (row.assetType === 'DEVICE' && ip !== (row.managementIp ?? '')) {
      await globalAssetApi.rename(row.assetType, row.id, renameValue.value, ip)
    } else {
      await globalAssetApi.rename(row.assetType, row.id, renameValue.value)
    }
    renaming.value = null
    await load()
  } catch (error) {
    renameError.value = error instanceof Error ? error.message : t('global.loadFailed')
  } finally {
    renameBusy.value = false
  }
}

async function removeAsset(row: GlobalAssetRow): Promise<void> {
  // W63-16：删除前把既有 referenceCount / collectCount 接到影响面，不再让用户盲删
  const impact = row.assetType === 'CREDENTIAL'
    ? t('w63.deleteImpact', { devices: row.referenceCount, uses: row.collectCount ?? 0 })
    : t('global.assetDeleteConfirm', { name: row.name })
  if (!(await confirmAction(impact, { tone: 'danger' })))
    return
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
      </div>
      <AssetImportBlock
        v-model:open="importOpen"
        :can-write="canWrite"
        :open-batches="isImportView"
        @committed="load"
      />
    </header>

    <div class="tabs">
      <button v-if="tabVisible('DEVICE')" class="tab" :class="{ active: type === 'DEVICE' }" @click="switchType('DEVICE')">
        {{ t('global.assetDevice') }}
      </button>
      <button
        v-if="tabVisible('CREDENTIAL')"
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

    <div v-if="canWrite && BATCH_DELETE_ENABLED" class="selection-bar" data-testid="global-batch-bar">
      <span data-testid="global-batch-count">
        {{ t('w62.assetSelectedCount', { count: selectedAssetIds.length }) }}
      </span>
      <button
        class="btn"
        :disabled="selectedAssetIds.length === 0 || batchBusy"
        data-testid="global-batch-delete"
        @click="batchDeleteAssets"
      >
        {{ t('w62.batchDelete') }}
      </button>
    </div>
    <p v-if="batchMessage" class="muted" data-testid="global-batch-result">{{ batchMessage }}</p>

    <!-- W62-09：与项目侧共用同一个 AssetWorkspace；矩阵过滤掉 project 独有列 -->
    <AssetWorkspace
      scope="global"
      :columns="columns"
      :rows="rows"
      :row-key="(row: GlobalAssetRow) => row.id"
      :loading="loading"
      :error="Boolean(failed)"
      :empty-title="t('global.empty')"
      :max-height="560"
    />


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
          <CredentialPicker
            v-model="collectCredentialId"
            :asset-id="collectTarget?.id ?? 0"
            :fallback-options="collectCredentialFallback"
            :label="t('w57.globalAssets.labelCollect')"
            @submitted="onManualCollected"
          />
          <label class="field">
            <span>{{ t('global.assetCollectUsername') }}</span>
            <input v-model="collectUsername" class="input" />
          </label>
          <label class="field">
            <span>{{ t('w63.setAsDefault') }}</span>
            <input v-model="setAsDefault" type="checkbox" data-testid="set-as-default" />
          </label>
        </template>
        <p class="muted">{{ t('w63.autoBindHint') }}</p>
        <CollectHistoryPanel
          :rows="historyRows"
          :bound-credential-name="profilesByDevice.get(collectTarget?.id ?? 0)?.boundCredentialMasked ?? null"
        />
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

        <!-- 加载中画骨架、无设备画空态，都交给壳：弹窗里再写一遍 loading / 空文案
             会和主表出现两套说法（UX-05 / tools/check_tables.py 的同一理由）。 -->
        <div class="bind-table" data-testid="credential-device-grid">
          <DataTable
            :columns="bindColumns"
            :data="bindDevices"
            :loading="bindLoading"
            :row-key="(row: CredentialDevice) => `${row.deviceId}-${row.plane}`"
            :max-height="260"
            :empty-title="t('global.assetCredentialNoDevice')"
            empty-icon="link"
          />
        </div>

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
          <CredentialPicker
            :model-value="deviceForm.managementCredentialId ?? null"
            @update:model-value="deviceForm.managementCredentialId = $event"
            :asset-id="0"
            :fallback-options="deviceCredentialFallback"
            :label="t('w57.globalAssets.labelManage')"
          />
          <CredentialPicker
            :model-value="deviceForm.bmcCredentialId ?? null"
            @update:model-value="deviceForm.bmcCredentialId = $event"
            :asset-id="0"
            :fallback-options="deviceCredentialFallback"
            :label="t('w57.globalAssets.labelBmc')"
          />
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

    <!-- W59-20：公共资产改名（只改显示名） -->
    <div v-if="renaming" class="modal-mask" @click.self="renaming = null">
      <div class="modal narrow">
        <header class="modal-head">
          <h2>{{ t('global.assetRenameTitle') }}</h2>
          <button class="link" @click="renaming = null">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('global.assetRenameHint') }}</p>
        <label class="field">
          <span>{{ t('global.assetFieldName') }} *</span>
          <input v-model="renameValue" class="input" data-testid="asset-rename-input" />
        </label>
        <label v-if="renaming.assetType === 'DEVICE'" class="field">
          <span>{{ t('global.assetIp') }}</span>
          <input v-model="renameIp" class="input" data-testid="asset-rename-ip" />
        </label>
        <p v-if="renameError" class="error">{{ renameError }}</p>
        <div class="modal-actions">
          <button class="btn" @click="renaming = null">{{ t('global.close') }}</button>
          <button class="btn btn-primary" :disabled="renameBusy" data-testid="asset-rename-submit"
                  @click="submitRename">
            {{ t('global.assetRenameSubmit') }}
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

<style scoped src="../../styles/global-assets-view.css"></style>
