<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NCheckbox,
  NInput,
  NInputNumber,
  NPagination,
  NSelect,
  NTabPane,
  NTabs,
  type DataTableColumns,
} from 'naive-ui'
import {
  credentialApi,
  deviceApi,
  linkApi,
  type Credential,
  type Device,
  type DevicePort,
  type Link,
} from '@/api/asset'
import { executionApi, type LeaseView } from '@/api/execution'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import { useListPageSize, type PageSize } from '@/composables/useListPageSize'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import AssetWorkspace from '@/components/AssetWorkspace.vue'
import AssetTopologyBlock from '@/components/AssetTopologyBlock.vue'
import { visibleTabs } from '@/config/assetWorkspaceMatrix'
import { useCollectProfiles } from '@/composables/useCollectProfiles'
import { batchApi, type BatchResource } from '@/api/batch'
import SelectionBar from '@/components/SelectionBar.vue'
import { useBatchAction } from '@/composables/useBatchAction'
import { useSelection } from '@/composables/useSelection'
import { confirmAction } from '@/composables/useConfirm'
import type { StatusTone } from '@/utils/statusLabels'
import { useAssetColumns } from '@/config/assetColumns'
import { useAssetEditing } from '@/composables/useAssetEditing'

type Tab = 'devices' | 'links' | 'credentials' | 'leases'
/** W62-08：页签清单由差异矩阵唯一决定（project 4 个），页面不再另写一份。 */
const TABS: Tab[] = visibleTabs('project').map(
  (row) => row.key.replace(/^tab\./, '') as Tab,
)
/** 设备与凭证表共用一份 rowKey（工作台只有一份表格实现）。 */
function assetRowKey(row: Device | Credential): number {
  return row.id
}

const { t } = useI18n()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/**
 * 页签与 URL 的 `?tab=` 双向同步（两级菜单的资产子项即锚在这三个值上）。
 *
 * 不做同步会出两个问题：① 从菜单点「凭据」进入只能停在默认的设备页；
 * ② 三个子项共用一个 path，菜单无法判断该点亮哪一个，结果一个都不亮。
 * 直接访问 `/assets` 时补默认值，保证「恰好一个选中」这个不变量始终成立。
 */
function tabFromQuery(): Tab {
  const raw = route.query.tab
  const value = Array.isArray(raw) ? raw[0] : raw
  return TABS.includes(value as Tab) ? (value as Tab) : 'devices'
}

const tab = ref<Tab>(tabFromQuery())
const keyword = ref('')
const page = ref(1)
// FR-v3.9-04：档位化 + 记忆 + 视口估算
const { size, pageSizes } = useListPageSize()
const total = ref(0)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')

const devices = ref<Device[]>([])
const deviceOptions = ref<Device[]>([])
const links = ref<Link[]>([])
const credentials = ref<Credential[]>([])
const leases = ref<LeaseView[]>([])
const domains = ref<string[]>([])
const domainFilter = ref('')

/**
 * 链路页签的「拓扑视图」（AST-02）：表格回答「有几条链路」，拓扑回答「谁连谁」。
 * 后台还没有图数据库，这里就用链路自带的 A/B 端做 DUT-LINK-DUT 链式渲染，
 * 一个设备出现多次是有意的——按链路读比按设备聚合更贴近排障时的问法。
 */
const topologyMode = ref(false)

function toggleTopology(): void {
  topologyMode.value = !topologyMode.value
  // 表格里的勾选在视图切换后已不可见，留着会让批量条指向看不见的行
  if (topologyMode.value) clearSelection()
}



const projectId = computed(() => auth.projectId ?? 0)

function isPlatformAsset(row: { projectId: number }): boolean {
  return row.projectId !== projectId.value
}

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

/**
 * 凭据在后端没有分页也没有关键词参数（`GET /credentials` 只按项目全量返回），
 * 所以关键词必须在前端过滤 —— 否则在凭据页签里输入搜索词是个**静默无效**的控件：
 * 有输入框、有回车、列表纹丝不动，用户会以为系统坏了。
 */
const credentialRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return credentials.value
  return credentials.value.filter((item) =>
    [item.name, item.credType, item.username].some((value) =>
      (value ?? '').toLowerCase().includes(kw)))
})

// v5.18.0 / FR-v5.18-02 / W68-04：编辑状态机抽成 `useAssetEditing`（表单 ref + 增删改）。
// 这里只做装配与解构，模板里的名字一个没改——抽取不该顺手改动模板契约。
const {
  portsDeviceId, portsDevice, ports, showForm,
  editingDeviceId, editingPortId, portDeleteError,
  deviceForm, portForm, linkForm, credentialForm, aPorts, bPorts,
  showShareForm, shareForm, releasingKey,
  referencePlatformAsset, unreferenceAsset, openDeviceEdit, cancelDeviceEdit, createDevice,
  removeDevice, togglePorts, openPortEdit, cancelPortEdit, deletePort, submitPort,
  changeLinkDevice, createLink, removeLink, createCredential, removeCredential, forceRelease,
} = useAssetEditing({ t, projectId, tab, devices, load, error, message })

const isAdmin = computed(() => auth.isAdmin)

// ---- UX-02b 通用批量：三个 tab 共用一套选择模型与执行器（复用而非各写一套）----
const pageAssetIds = computed<number[]>(() => {
  if (tab.value === 'devices') return devices.value.map((d) => d.id)
  if (tab.value === 'links') return links.value.map((l) => l.id)
  if (tab.value === 'credentials') return credentialRows.value.map((c) => c.id)
  // 租约没有资产 id，也不支持批量：返回空让选择模型自然禁用整列勾选框
  return []
})
const assetSelection = useSelection(pageAssetIds)
const {
  count: selectedCount,
  crossPageCount,
  pageAllSelected,
  pageIndeterminate,
  isSelected,
  toggle,
  togglePage,
  clear: clearSelection,
} = assetSelection

const batchResource = computed<BatchResource>(() =>
  tab.value === 'devices' ? 'DEVICE' : tab.value === 'links' ? 'LINK' : 'CREDENTIAL')

const assetBatch = useBatchAction({
  run: (ids) => batchApi.run(projectId.value, {
    resource: batchResource.value,
    action: 'delete',
    ids,
  }),
  reload: load,
  onError: (e) => { error.value = localizeError(e) },
})

async function batchDeleteAssets(): Promise<void> {
  if (!(await confirmAction(t('batch.confirmDeleteAssets'), { tone: 'danger' }))) return
  error.value = null
  await assetBatch.execute([...assetSelection.selectedIds.value], assetSelection)
}

const domainOptions = computed(() => [
  { label: t('common.allDomains'), value: '' },
  ...domains.value.map((item) => ({ label: item, value: item })),
])

const deviceSelectOptions = computed(() =>
  deviceOptions.value.map((item) => ({ label: item.name, value: item.id })))

const aPortOptions = computed(() =>
  aPorts.value.map((item) => ({ label: item.portName, value: item.id })))
const bPortOptions = computed(() =>
  bPorts.value.map((item) => ({ label: item.portName, value: item.id })))

/** 「已配置」是布尔事实，不是流程状态：配好了是 ok，没配只是 neutral，不是 warn。 */
function configuredTone(value: boolean): StatusTone {
  return value ? 'ok' : 'neutral'
}

/**
 * 勾选列一律自渲染 `NCheckbox`，不用 NDataTable 内置的 `type: 'selection'`：
 * 内置列只认 `checked-row-keys`，`data` 一变就把不在当前页的键丢掉，与
 * `useSelection` 的**跨页保留**契约（FR-v0.2-35）静默冲突 —— 用户翻页后前面勾的全没，
 * 而且不报错。表头半选态照旧从 `pageIndeterminate` 派生。
 */
function selectionColumn<T extends { id: number }>(): DataTableColumns<T>[number] {
  return {
    key: 'selection',
    width: 36,
    // 勾选框在单元格里默认左贴边，与表头框对不齐；居中后上下两排才在一条竖线上
    align: 'center',
    title: () =>
      h(NCheckbox, {
        checked: pageAllSelected.value,
        indeterminate: pageIndeterminate.value,
        'aria-label': t('cases.batch.barLabel'),
        onUpdateChecked: () => togglePage(),
      }),
    render: (row) =>
      h(NCheckbox, {
        checked: isSelected(row.id),
        'aria-label': `${t('cases.batch.barLabel')}: ${row.id}`,
        onUpdateChecked: () => toggle(row.id),
      }),
  }
}

/** W63-09：项目侧与全局侧共用同一份画像加载逻辑，三列值来自同一接口。 */
const { byDevice: profilesByDevice, load: loadProfiles } = useCollectProfiles()

// v5.18.0 / FR-v5.18-02 / W68-04：五组列定义抽成 `config/assetColumns.ts`。
const { deviceColumns, linkColumns, credentialColumns, leaseColumns, portColumns } =
  useAssetColumns({
    t,
    selectionColumn,
    isPlatformAsset,
    configuredTone,
    profilesByDevice,
    isAdmin,
    portsDeviceId,
    releasingKey,
    openDeviceEdit,
    removeDevice,
    togglePorts,
    openPortEdit,
    deletePort,
    removeLink,
    removeCredential,
    forceRelease,
    unreferenceAsset,
  })

async function load(): Promise<void> {
  error.value = null
  message.value = ''
  if (!projectId.value) {
    devices.value = []
    links.value = []
    credentials.value = []
    leases.value = []
    return
  }
  loading.value = true
  try {
    if (tab.value === 'devices') {
      const [result, domainList, options] = await Promise.all([
        deviceApi.page(projectId.value, {
          keyword: keyword.value,
          domain: domainFilter.value,
          page: page.value,
          size: size.value,
        }),
        deviceApi.domains(projectId.value),
        deviceApi.page(projectId.value, { page: 1, size: 200 }),
      ])
      devices.value = result.items
      total.value = result.total
      domains.value = domainList
      deviceOptions.value = options.items
    } else if (tab.value === 'links') {
      const [result, options] = await Promise.all([
        linkApi.page(projectId.value, { keyword: keyword.value, page: page.value, size: size.value }),
        deviceApi.page(projectId.value, { page: 1, size: 200 }),
      ])
      links.value = result.items
      total.value = result.total
      deviceOptions.value = options.items
    } else if (tab.value === 'credentials') {
      credentials.value = await credentialApi.list(projectId.value)
      total.value = credentials.value.length
    } else {
      leases.value = await executionApi.listLeases(projectId.value)
      total.value = leases.value.length
    }
    void loadProfiles(projectId.value)
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

// immediate 同 CasesView：projectId 初值即目标值时 watch 不会触发，页面会永远空着
watch([projectId, tab], () => {
  page.value = 1
  clearSelection()
  assetBatch.clear()
  portsDeviceId.value = null
  ports.value = []
  showForm.value = false
  showShareForm.value = false
  shareForm.assetId = null
  void load()
}, { immediate: true })

watch([keyword, domainFilter], () => {
  page.value = 1
  // 凭据是全量拉回后本地过滤，关键词变化不必再打后端
  if (tab.value === 'credentials') return
  void load()
})

// 页签 → URL（用 replace，避免每次切页签都往浏览器历史里塞一条）
watch(tab, (next) => {
  if (tabFromQuery() === next) return
  void router.replace({ query: { ...route.query, tab: next } })
})

// URL → 页签（菜单子项、浏览器前进后退、外部深链都走这条）
watch(
  () => route.query.tab,
  () => {
    const next = tabFromQuery()
    if (next !== tab.value) tab.value = next
  },
)

// 首次进入若是裸 `/assets`，把默认页签写回 URL，菜单的「设备」子项才会点亮
if (route.query.tab !== tab.value) {
  void router.replace({ query: { ...route.query, tab: tab.value } })
}

/** 页大小切换（FR-v3.9-04）：改档位即回到第 1 页重新取数。 */
function changePageSize(next: number): void {
  size.value = next as PageSize
  page.value = 1
  void load()
}

function turnPage(next: number): void {
  if (next < 1 || next > pageCount.value) return
  page.value = next
  // 选择跨页保留（FR-v0.2-35），翻页不清空
  void load()
}


</script>

<template>
  <div class="page is-list">
    <div class="page-head">
      <h1 class="page-title">{{ t('menu.assets') }}</h1>
    </div>

    <!--
      页签只用 NTabs 的表头，内容仍在下面按 `tab` 分支：三张表各有自己的工具栏、
      面板与分页，塞进 NTabPane 会把同一段工具栏复制三份。空 pane 不占高度。

      标签必须走 `#tab` 插槽：本版 naive-ui 从 pane 的 `children.tab` 取标题，
      只给 `:tab` prop 会渲染出三个空白标签（页签在、字没了，且没有任何报错）。
    -->
    <NTabs v-model:value="tab" type="line" class="asset-tabs">
      <NTabPane v-for="item in TABS" :key="item" :name="item">
        <template #tab>{{ t(`assets.tabs.${item}`) }}</template>
      </NTabPane>
    </NTabs>

    <div class="toolbar">
      <NInput
        v-if="tab !== 'leases'"
        v-model:value="keyword"
        clearable
        style="width: 200px"
        :placeholder="t('common.searchPlaceholder')"
        :aria-label="t('common.searchPlaceholder')"
        @keyup.enter="load"
      />
      <NSelect
        v-if="tab === 'devices'"
        v-model:value="domainFilter"
        :options="domainOptions"
        style="width: 180px"
        :aria-label="t('assets.device.domain')"
      />
      <NButton
        v-if="tab !== 'leases'"
        :type="showForm ? 'default' : 'primary'"
        @click="showForm = !showForm"
      >
        {{ showForm ? t('common.cancel') : t('common.create') }}
      </NButton>
      <NButton quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
      <NButton
        v-if="tab === 'links'"
        :type="topologyMode ? 'primary' : 'default'"
        @click="toggleTopology"
      >
        {{ topologyMode ? t('assets.link.tableView') : t('assets.link.topologyView') }}
      </NButton>
      <!-- v4.3.0 / FR-v4.3-08（P1）：引用平台共享资产。租约页签不涉及资产关系，不显示。 -->
      <NButton
        v-if="tab !== 'leases'"
        quaternary
        data-testid="assets-share-toggle"
        @click="showShareForm = !showShareForm"
      >
        {{ showShareForm ? t('common.cancel') : t('assets.share.reference') }}
      </NButton>
    </div>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <SelectionBar
      v-if="selectedCount > 0"
      :count="selectedCount"
      :cross-page="crossPageCount"
      :result="assetBatch.result.value"
      :loading="assetBatch.loading.value"
      @clear="clearSelection(); assetBatch.clear()"
    >
      <NButton
        quaternary
        type="error"
        :disabled="assetBatch.loading.value"
        @click="batchDeleteAssets"
      >
        {{ t('batch.deleteAssets') }}
      </NButton>
    </SelectionBar>

    <!--
      v4.3.0 / FR-v4.3-08 R2：引用失败（含 403）只写错误行，**表单与已填 ID 保持**，
      不 flash、不清空——用户能直接改 ID 重试。
    -->
    <form
      v-if="showShareForm && tab !== 'leases'"
      class="inline-form"
      data-testid="assets-share-form"
      @submit.prevent="referencePlatformAsset"
    >
      <span class="muted">{{ t('assets.share.hint') }}</span>
      <NInputNumber
        v-model:value="shareForm.assetId"
        style="width: 180px"
        :min="1"
        :show-button="false"
        :placeholder="t('assets.share.assetId')"
      />
      <NButton type="primary" attr-type="submit">{{ t('assets.share.reference') }}</NButton>
    </form>

    <form
      v-if="showForm && tab === 'devices'"
      class="inline-form"
      data-testid="device-form"
      @submit.prevent="createDevice"
    >
      <NInput v-model:value="deviceForm.name" style="width: 180px" :placeholder="t('assets.device.name')" />
      <NInput v-model:value="deviceForm.deviceType" style="width: 120px" :placeholder="t('assets.device.type')" />
      <NInput v-model:value="deviceForm.model" style="width: 140px" :placeholder="t('assets.device.model')" />
      <NInput v-model:value="deviceForm.vendor" style="width: 140px" :placeholder="t('assets.device.vendor')" />
      <NInput v-model:value="deviceForm.serialNo" style="width: 140px" :placeholder="t('assets.device.serialNo')" />
      <NInput
        v-model:value="deviceForm.managementIp"
        style="width: 180px"
        :placeholder="t('assets.device.managementIp')"
      />
      <NInput v-model:value="deviceForm.location" style="width: 140px" :placeholder="t('assets.device.location')" />
      <NInput v-model:value="deviceForm.domainTag" style="width: 140px" :placeholder="t('assets.device.domain')" />
      <NButton type="primary" attr-type="submit">
        {{ editingDeviceId ? t('w62.saveDevice') : t('common.save') }}
      </NButton>
      <NButton v-if="editingDeviceId" @click="cancelDeviceEdit">{{ t('w62.cancelEdit') }}</NButton>
    </form>

    <form v-if="showForm && tab === 'links'" class="inline-form" @submit.prevent="createLink">
      <NInput v-model:value="linkForm.name" style="width: 180px" :placeholder="t('assets.link.name')" />
      <NInput v-model:value="linkForm.protocol" style="width: 120px" :placeholder="t('assets.link.protocol')" />
      <NInputNumber
        v-model:value="linkForm.speedGbps"
        style="width: 140px"
        :min="1"
        :show-button="false"
        :placeholder="t('assets.link.speed')"
      />
      <NSelect
        :value="linkForm.aDeviceId"
        :options="deviceSelectOptions"
        clearable
        style="width: 180px"
        :placeholder="t('assets.link.aDevice')"
        :aria-label="t('assets.link.aDevice')"
        @update:value="(value: number | null) => void changeLinkDevice('a', value)"
      />
      <NSelect
        v-model:value="linkForm.aPortId"
        :options="aPortOptions"
        clearable
        style="width: 140px"
        :placeholder="t('assets.link.aPort')"
        :aria-label="t('assets.link.aPort')"
      />
      <NSelect
        :value="linkForm.bDeviceId"
        :options="deviceSelectOptions"
        clearable
        style="width: 180px"
        :placeholder="t('assets.link.bDevice')"
        :aria-label="t('assets.link.bDevice')"
        @update:value="(value: number | null) => void changeLinkDevice('b', value)"
      />
      <NSelect
        v-model:value="linkForm.bPortId"
        :options="bPortOptions"
        clearable
        style="width: 140px"
        :placeholder="t('assets.link.bPort')"
        :aria-label="t('assets.link.bPort')"
      />
      <NInput v-model:value="linkForm.description" style="width: 180px" :placeholder="t('assets.link.description')" />
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
    </form>

    <form
      v-if="showForm && tab === 'credentials'"
      class="inline-form"
      @submit.prevent="createCredential"
    >
      <NInput v-model:value="credentialForm.name" style="width: 180px" :placeholder="t('assets.credential.name')" />
      <NInput v-model:value="credentialForm.credType" style="width: 120px" :placeholder="t('assets.credential.type')" />
      <NInput v-model:value="credentialForm.username" style="width: 140px" :placeholder="t('assets.credential.username')" />
      <NInput
        v-model:value="credentialForm.secret"
        type="password"
        show-password-on="click"
        style="width: 180px"
        :placeholder="t('assets.credential.secret')"
        autocomplete="new-password"
      />
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
      <span class="muted">{{ t('assets.credential.noEcho') }}</span>
    </form>

    <!-- W62-09：设备表与凭证表都改用同一个 AssetWorkspace（唯一一份表格实现） -->
    <AssetWorkspace
      v-if="tab === 'devices'"
      scope="project"
      :columns="deviceColumns"
      :rows="devices"
      :row-key="assetRowKey"
      :loading="loading"
      :error="Boolean(error)"
      :empty-title="t('common.noData')"
      :empty-description="projectId ? undefined : t('overview.noProject')"
      empty-icon="boxes"
      :empty-illustration="keyword ? 'no-result' : 'no-data'"
      flex-height
    />

    <div v-if="tab === 'devices' && portsDevice" class="card port-card">
      <div class="port-head">
        <h2 class="card-title">{{ t('assets.device.ports') }} · {{ portsDevice.name }}</h2>
        <NButton text @click="togglePorts(portsDevice)">
          {{ t('assets.device.hidePorts') }}
        </NButton>
      </div>
      <DataTable
        :columns="portColumns"
        :data="ports"
        :row-key="(row: DevicePort) => row.id"
        :error="Boolean(error)"
        :empty-illustration="keyword ? 'no-result' : 'no-data'"
      >
        <template #empty>
          <p class="muted port-empty">{{ t('common.noData') }}</p>
        </template>
      </DataTable>
      <p v-if="portDeleteError" class="error" data-testid="port-delete-error">{{ portDeleteError }}</p>
    <form class="inline-form port-form" @submit.prevent="submitPort">
        <NInput v-model:value="portForm.portName" style="width: 180px" :placeholder="t('assets.port.name')" />
        <NInput v-model:value="portForm.portType" style="width: 140px" :placeholder="t('assets.port.type')" />
        <NInputNumber
          v-model:value="portForm.speedGbps"
          style="width: 180px"
          :min="1"
          :show-button="false"
          :placeholder="t('assets.port.speed')"
        />
        <NInput
          v-model:value="portForm.nicName"
          style="width: 180px"
          :placeholder="t('assets.port.nicName')"
        />
        <NInputNumber
          v-model:value="portForm.nicIndex"
          style="width: 180px"
          :min="0"
          :show-button="false"
          :placeholder="t('assets.port.nicIndex')"
        />
        <NButton attr-type="submit">
          {{ editingPortId ? t('w62.savePort') : t('common.add') }}
        </NButton>
        <NButton v-if="editingPortId" @click="cancelPortEdit">{{ t('w62.cancelEdit') }}</NButton>
      </form>
    </div>

    <!-- v5.21.0 / FR-v5.21-04：外壳必须跟着页签走。
         这两个 .list-fill 原来无条件渲染，各带 240px 最小高度——在「设备」页签下
         白占 480px，把设备表压到 132px（4 行只露出 2 行）。表格 v-if 掉了，
         容器却还在，是「表格塌陷」的另一半原因。 -->
    <div v-if="tab === 'links' && !topologyMode" class="list-fill">
      <DataTable
        :columns="linkColumns"
        :data="links"
        :loading="loading"
        :row-key="(row: Link) => row.id"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? undefined : t('overview.noProject')"
        empty-icon="boxes"
        :error="Boolean(error)"
        :empty-illustration="keyword ? 'no-result' : 'no-data'"
        flex-height
      />
    </div>

        <AssetTopologyBlock v-if="tab === 'links' && topologyMode" :links="links" />

    <AssetWorkspace
      v-if="tab === 'credentials'"
      scope="project"
      :columns="credentialColumns"
      :rows="credentialRows"
      :row-key="assetRowKey"
      :loading="loading"
      :error="Boolean(error)"
      :empty-title="t('common.noData')"
      :empty-description="projectId ? undefined : t('overview.noProject')"
      empty-icon="boxes"
      :empty-illustration="keyword ? 'no-result' : 'no-data'"
      flex-height
    />

    <!--
      租约只读（FR-v0.4-09）：搜索框与「新建」在这一页无意义——后端没有关键词参数，
      租约也只能由执行产生，所以工具栏里两个控件都隐掉，只留刷新。
    -->
    <div v-if="tab === 'leases'" class="list-fill">
      <DataTable
        :columns="leaseColumns"
        :data="leases"
        :loading="loading"
        :row-key="(row: LeaseView) => row.resourceKey"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? t('assets.lease.emptyHint') : t('overview.noProject')"
        empty-icon="boxes"
        :error="Boolean(error)"
        flex-height
      />
    </div>

    <NPagination
      v-if="tab !== 'credentials' && tab !== 'leases' && total > 0"
      class="pager"
      :page="page"
      :page-count="pageCount"
      :page-slot="7"
      :page-size="size"
      :page-sizes="pageSizes"
      show-size-picker
      @update:page-size="changePageSize"
      @update:page="turnPage"
    />
  </div>
</template>

<style scoped src="../styles/assets-view.css"></style>
