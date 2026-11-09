<script setup lang="ts">
import { computed, h, reactive, ref, watch } from 'vue'
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
import StatusTag from '@/components/StatusTag.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY } from '@/utils/format'
import { batchApi, type BatchResource } from '@/api/batch'
import SelectionBar from '@/components/SelectionBar.vue'
import { useBatchAction } from '@/composables/useBatchAction'
import { useSelection } from '@/composables/useSelection'
import { confirmAction } from '@/composables/useConfirm'
import type { StatusTone } from '@/utils/statusLabels'

type Tab = 'devices' | 'links' | 'credentials' | 'leases'
const TABS: Tab[] = ['devices', 'links', 'credentials', 'leases']

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

function endpointName(link: Link, side: 'a' | 'b'): string {
  const name = side === 'a' ? link.aDeviceName : link.bDeviceName
  return name || t('assets.link.unconnected')
}

function endpointPort(link: Link, side: 'a' | 'b'): string {
  return (side === 'a' ? link.aPortName : link.bPortName) || ''
}

/**
 * 多跳拓扑：把「一条链路一行」重排成「一条链一行」。
 *
 * 数据模型只有两端链路，但同一台设备会出现在多条链路里（DUT → SW → HOST）。
 * 按链路逐行画，看到的是三张互不相干的图，排障时还得自己在脑子里接起来；
 * 这里用链路两端的设备 id 建邻接表，从任意一条没走过的边出发向两端各走到底，
 * 串成节点与边交替的链。分叉与环都不特殊处理——走到不能再走就收尾，
 * 剩下的边由外层循环另起一条链，从而保证「每条链路恰好渲染一次」。
 */
type TopoNode = { key: string; name: string; ports: string[] }
type TopoChain = { key: string; label: string; nodes: TopoNode[]; edges: Link[] }

function sideKey(link: Link, side: 'a' | 'b'): string {
  const id = side === 'a' ? link.aDeviceId : link.bDeviceId
  // 没登记设备时用「链路 + 侧」做键：否则两条孤儿链路的端点会被并成同一个「未连接」节点
  return id ? `d:${id}` : `u:${link.id}:${side}`
}

function otherKey(link: Link, side: 'a' | 'b'): string {
  return sideKey(link, side === 'a' ? 'b' : 'a')
}

const linkChains = computed<TopoChain[]>(() => {
  const nodes = new Map<string, TopoNode>()
  const adjacency = new Map<string, { link: Link; side: 'a' | 'b' }[]>()

  const ensure = (key: string, name: string): TopoNode => {
    const existing = nodes.get(key)
    if (existing) return existing
    const created: TopoNode = { key, name, ports: [] }
    nodes.set(key, created)
    return created
  }

  for (const link of links.value) {
    for (const side of ['a', 'b'] as const) {
      const key = sideKey(link, side)
      const node = ensure(key, endpointName(link, side))
      const port = endpointPort(link, side)
      if (port && !node.ports.includes(port)) node.ports.push(port)
      const list = adjacency.get(key) ?? []
      list.push({ link, side })
      adjacency.set(key, list)
    }
  }

  const used = new Set<Link>()

  /** 从 startKey 顺着「不是刚走过的那条、且还没用过」的边一直走到头。 */
  const extend = (startKey: string, via: Link): { keys: string[]; edges: Link[] } => {
    const keys: string[] = []
    const edges: Link[] = []
    let cursor = startKey
    let previous: Link = via
    for (;;) {
      const next = (adjacency.get(cursor) ?? []).find(
        (item) => item.link !== previous && !used.has(item.link),
      )
      if (!next) break
      used.add(next.link)
      cursor = otherKey(next.link, next.side)
      previous = next.link
      keys.push(cursor)
      edges.push(next.link)
    }
    return { keys, edges }
  }

  const chains: TopoChain[] = []
  for (const link of links.value) {
    if (used.has(link)) continue
    used.add(link)
    const aKey = sideKey(link, 'a')
    const bKey = sideKey(link, 'b')
    // 先往 A 端外侧走，再往 B 端外侧走；左侧是倒序长出来的，拼回去时要翻一次
    const left = extend(aKey, link)
    const right = extend(bKey, link)
    const keys = [...left.keys.slice().reverse(), aKey, bKey, ...right.keys]
    const edges = [...left.edges.slice().reverse(), link, ...right.edges]
    chains.push({
      key: `chain:${link.id}`,
      // 单跳链的标签就是链路名本身，多跳才用箭头连起来
      label: edges.map((item) => item.name).join(' → '),
      nodes: keys.map((key) => nodes.get(key) ?? { key, name: '', ports: [] }),
      edges,
    })
  }
  return chains
})

function edgeMeta(link: Link): string {
  return (
    [link.protocol, link.speedGbps ? `${link.speedGbps} Gbps` : '']
      .filter(Boolean)
      .join(' · ') || EMPTY
  )
}

/** 链有多长栅格就多宽：节点拉伸，边固定 180px，否则长链会把节点压成一条缝。 */
function chainColumns(chain: TopoChain): string {
  return chain.nodes
    .map((_, index) => (index < chain.edges.length ? 'minmax(0, 1fr) 180px' : 'minmax(0, 1fr)'))
    .join(' ')
}

/**
 * 端口面板独立在表格下方（同 RunsView 的日志面板），不做行内展开。
 *
 * 行内展开要往 NDataTable 里塞一张子表和一张表单，单元格里既没有宽度也没有焦点管理余量；
 * 而「点一行 → 下方出详情」是本项目已统一的交互语言（执行页日志、报告页明细都这样），
 * 同一套心智比省一次滚动更值。
 */
const portsDeviceId = ref<number | null>(null)
const portsDevice = computed(() => devices.value.find((d) => d.id === portsDeviceId.value) ?? null)
const ports = ref<DevicePort[]>([])
const showForm = ref(false)

const deviceForm = reactive({
  name: '',
  deviceType: '',
  model: '',
  vendor: '',
  serialNo: '',
  managementIp: '',
  location: '',
  domainTag: '',
})

const portForm = reactive({ portName: '', portType: '', speedGbps: null as number | null })

const linkForm = reactive({
  name: '',
  linkType: 'ethernet',
  protocol: '',
  speedGbps: null as number | null,
  aDeviceId: null as number | null,
  aPortId: null as number | null,
  bDeviceId: null as number | null,
  bPortId: null as number | null,
  description: '',
  enabled: true,
})
const aPorts = ref<DevicePort[]>([])
const bPorts = ref<DevicePort[]>([])

const credentialForm = reactive({
  name: '',
  credType: 'ssh',
  username: '',
  secret: '',
})

const projectId = computed(() => auth.projectId ?? 0)
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

const deviceColumns = computed<DataTableColumns<Device>>(() => [
  selectionColumn<Device>(),
  {
    title: t('assets.device.name'),
    key: 'name',
    minWidth: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: t('assets.device.type'),
    key: 'deviceType',
    width: 110,
    render: (row) => row.deviceType || EMPTY,
  },
  {
    title: t('assets.device.model'),
    key: 'model',
    width: 130,
    ellipsis: { tooltip: true },
    render: (row) => row.model || EMPTY,
  },
  {
    title: t('assets.device.serialNo'),
    key: 'serialNo',
    width: 140,
    ellipsis: { tooltip: true },
    render: (row) => row.serialNo || EMPTY,
  },
  {
    title: t('assets.device.managementIp'),
    key: 'managementIp',
    width: 150,
    render: (row) => row.managementIp || EMPTY,
  },
  {
    title: t('assets.device.domain'),
    key: 'domainTag',
    width: 110,
    render: (row) => row.domainTag || EMPTY,
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 160,
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          {
            text: true,
            type: 'primary',
            size: 'small',
            onClick: () => void togglePorts(row),
          },
          {
            default: () =>
              portsDeviceId.value === row.id
                ? t('assets.device.hidePorts')
                : t('assets.device.ports'),
          },
        ),
        h(
          NButton,
          {
            text: true,
            type: 'error',
            size: 'small',
            onClick: () => void removeDevice(row),
          },
          { default: () => t('common.delete') },
        ),
      ]),
  },
])

const linkColumns = computed<DataTableColumns<Link>>(() => [
  selectionColumn<Link>(),
  {
    title: t('assets.link.name'),
    key: 'name',
    minWidth: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: t('assets.link.endpointA'),
    key: 'aEndpoint',
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: (row) => [row.aDeviceName, row.aPortName].filter(Boolean).join(' / ') || EMPTY,
  },
  {
    title: t('assets.link.endpointB'),
    key: 'bEndpoint',
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: (row) => [row.bDeviceName, row.bPortName].filter(Boolean).join(' / ') || EMPTY,
  },
  {
    title: t('assets.link.protocol'),
    key: 'protocol',
    width: 110,
    render: (row) => row.protocol || EMPTY,
  },
  {
    title: t('assets.link.speed'),
    key: 'speedGbps',
    width: 120,
    render: (row) => row.speedGbps ?? EMPTY,
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 110,
    render: (row) =>
      h(
        NButton,
        {
          text: true,
          type: 'error',
          size: 'small',
          onClick: () => void removeLink(row),
        },
        { default: () => t('common.delete') },
      ),
  },
])

const credentialColumns = computed<DataTableColumns<Credential>>(() => [
  selectionColumn<Credential>(),
  {
    title: t('assets.credential.name'),
    key: 'name',
    minWidth: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: t('assets.credential.type'),
    key: 'credType',
    width: 110,
    render: (row) => row.credType || EMPTY,
  },
  {
    title: t('assets.credential.username'),
    key: 'username',
    width: 150,
    render: (row) => row.username || EMPTY,
  },
  {
    title: t('assets.credential.configured'),
    key: 'configured',
    width: 120,
    render: (row) =>
      h(StatusTag, {
        label: row.configured ? t('common.yes') : t('common.no'),
        tone: configuredTone(row.configured),
      }),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 110,
    render: (row) =>
      h(
        NButton,
        {
          text: true,
          type: 'error',
          size: 'small',
          onClick: () => void removeCredential(row),
        },
        { default: () => t('common.delete') },
      ),
  },
])

/**
 * 租约页是**只读**的：这里不提供申请/释放入口，唯一动作是管理员强制释放（FR-v0.4-10）。
 *
 * 剩余时间按 `m s` 展示而不是直接打秒数：一个 "3600" 的单元格没人能一眼换算成"还有一小时"。
 * 到点即 `0`，显示为「已到期」——留个 0 会让人以为是「立刻可用」，实际是租约该被回收了。
 */
function remainingText(seconds: number): string {
  if (seconds <= 0) return t('assets.lease.expired')
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return minutes > 0 ? `${minutes}m ${rest}s` : `${rest}s`
}

const releasingKey = ref('')

const leaseColumns = computed<DataTableColumns<LeaseView>>(() => [
  {
    title: t('assets.lease.resource'),
    key: 'resourceKey',
    minWidth: 200,
    ellipsis: { tooltip: true },
  },
  {
    title: t('assets.lease.run'),
    key: 'runId',
    minWidth: 180,
    ellipsis: { tooltip: true },
  },
  {
    title: t('assets.lease.remaining'),
    key: 'remainingSeconds',
    width: 120,
    render: (row) => remainingText(row.remainingSeconds),
  },
  {
    title: t('assets.lease.state'),
    key: 'state',
    width: 110,
    render: (row) =>
      h(StatusTag, {
        label: row.state === 'held' ? t('assets.lease.held') : row.state,
        tone: 'ok',
      }),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 120,
    // 只读视图对普通用户连按钮都不渲染：摆一个点了报 403 的按钮比没有更糟
    render: (row) =>
      auth.isAdmin
        ? h(
            NButton,
            {
              text: true,
              type: 'error',
              size: 'small',
              loading: releasingKey.value === row.resourceKey,
              onClick: () => void forceRelease(row),
            },
            { default: () => t('assets.lease.forceRelease') },
          )
        : EMPTY,
  },
])

const portColumns = computed<DataTableColumns<DevicePort>>(() => [
  { title: t('assets.port.name'), key: 'portName', minWidth: 160 },
  {
    title: t('assets.port.type'),
    key: 'portType',
    width: 130,
    render: (row) => row.portType || EMPTY,
  },
  {
    title: t('assets.port.speed'),
    key: 'speedGbps',
    width: 130,
    render: (row) => row.speedGbps ?? EMPTY,
  },
])

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

async function createDevice(): Promise<void> {
  error.value = null
  message.value = ''
  if (!deviceForm.name.trim()) {
    error.value = { summary: t('assets.requiredName'), detail: null }
    return
  }
  try {
    await deviceApi.create(projectId.value, {
      ...deviceForm,
      name: deviceForm.name.trim(),
      enabled: true,
    })
    message.value = t('common.saved')
    showForm.value = false
    deviceForm.name = ''
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function removeDevice(device: Device): Promise<void> {
  if (!(await confirmAction(t('common.confirmDelete'), { tone: 'danger' }))) return
  try {
    await deviceApi.remove(projectId.value, device.id)
    // 删的正是当前展开的那台设备时，端口面板留着会指向已不存在的对象
    if (portsDeviceId.value === device.id) {
      portsDeviceId.value = null
      ports.value = []
    }
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function togglePorts(device: Device): Promise<void> {
  error.value = null
  if (portsDeviceId.value === device.id) {
    portsDeviceId.value = null
    ports.value = []
    return
  }
  portsDeviceId.value = device.id
  ports.value = []
  try {
    ports.value = await deviceApi.ports(projectId.value, device.id)
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function addPort(): Promise<void> {
  const deviceId = portsDeviceId.value
  if (!deviceId) return
  error.value = null
  if (!portForm.portName.trim()) {
    error.value = { summary: t('assets.requiredPortName'), detail: null }
    return
  }
  try {
    await deviceApi.addPort(projectId.value, deviceId, {
      portName: portForm.portName.trim(),
      portType: portForm.portType,
      speedGbps: portForm.speedGbps,
    })
    portForm.portName = ''
    ports.value = await deviceApi.ports(projectId.value, deviceId)
  } catch (e) {
    error.value = localizeError(e)
  }
}

/**
 * 换设备时必须把已选端口清掉。
 *
 * 旧实现在这里只重载端口列表、不动 `aPortId`：从设备 A 换成设备 B 后，`aPortId` 仍指向
 * A 的端口，提交出去就是一条「A 端设备是 B、A 端端口是 A 的端口」的错链，而界面上
 * 下拉框还显示着上一次的端口名，看不出已经错位。
 */
async function changeLinkDevice(side: 'a' | 'b', deviceId: number | null): Promise<void> {
  if (side === 'a') {
    linkForm.aDeviceId = deviceId
    linkForm.aPortId = null
  } else {
    linkForm.bDeviceId = deviceId
    linkForm.bPortId = null
  }
  if (!deviceId) {
    if (side === 'a') aPorts.value = []
    else bPorts.value = []
    return
  }
  try {
    const list = await deviceApi.ports(projectId.value, deviceId)
    if (side === 'a') aPorts.value = list
    else bPorts.value = list
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function createLink(): Promise<void> {
  error.value = null
  message.value = ''
  if (!linkForm.name.trim()) {
    error.value = { summary: t('assets.requiredName'), detail: null }
    return
  }
  try {
    await linkApi.create(projectId.value, { ...linkForm, name: linkForm.name.trim() })
    message.value = t('common.saved')
    linkForm.name = ''
    showForm.value = false
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function removeLink(link: Link): Promise<void> {
  if (!(await confirmAction(t('common.confirmDelete'), { tone: 'danger' }))) return
  try {
    await linkApi.remove(projectId.value, link.id)
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function createCredential(): Promise<void> {
  error.value = null
  message.value = ''
  if (!credentialForm.name.trim() || !credentialForm.secret) {
    error.value = { summary: t('assets.requiredCredential'), detail: null }
    return
  }
  try {
    await credentialApi.create(projectId.value, {
      name: credentialForm.name.trim(),
      credType: credentialForm.credType,
      username: credentialForm.username,
      secret: credentialForm.secret,
    })
    message.value = t('common.saved')
    credentialForm.name = ''
    credentialForm.username = ''
    credentialForm.secret = ''
    showForm.value = false
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function removeCredential(credential: Credential): Promise<void> {
  if (!(await confirmAction(t('common.confirmDelete'), { tone: 'danger' }))) return
  try {
    await credentialApi.remove(projectId.value, credential.id)
    await load()
  } catch (e) {
    error.value = localizeError(e)
  }
}

/**
 * 强制释放（FR-v0.4-10）。
 *
 * 确认文案里把「占用者 runId」和「资源」都念出来：只说"确定释放吗"的操作者，
 * 十有八九是在资产页顺手点的，并不知道这一下会把谁的运行打成失败。
 * confirm 参数取该租约的 runId —— 与后端二次确认口径一致，前端不另造一套。
 */
async function forceRelease(lease: LeaseView): Promise<void> {
  const ok = await confirmAction(
    t('assets.lease.forceReleaseConfirm', { run: lease.runId, key: lease.resourceKey }),
    { tone: 'danger' },
  )
  if (!ok) return
  error.value = null
  message.value = ''
  releasingKey.value = lease.resourceKey
  try {
    const result = await executionApi.forceReleaseLease(
      projectId.value,
      lease.resourceKey,
      lease.runId,
    )
    message.value = t('assets.lease.forceReleased', {
      run: result.runId,
      state: result.runState,
    })
    await load()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    releasingKey.value = ''
  }
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

    <form v-if="showForm && tab === 'devices'" class="inline-form" @submit.prevent="createDevice">
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
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
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

    <div class="list-fill">
      <DataTable
        v-if="tab === 'devices'"
        :columns="deviceColumns"
        :data="devices"
        :loading="loading"
        :row-key="(row: Device) => row.id"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? undefined : t('overview.noProject')"
        empty-icon="boxes"
        :error="Boolean(error)"
        :empty-illustration="keyword ? 'no-result' : 'no-data'"
        flex-height
      />
    </div>

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
      <form class="inline-form port-form" @submit.prevent="addPort">
        <NInput v-model:value="portForm.portName" style="width: 180px" :placeholder="t('assets.port.name')" />
        <NInput v-model:value="portForm.portType" style="width: 140px" :placeholder="t('assets.port.type')" />
        <NInputNumber
          v-model:value="portForm.speedGbps"
          style="width: 180px"
          :min="1"
          :show-button="false"
          :placeholder="t('assets.port.speed')"
        />
        <NButton attr-type="submit">{{ t('common.add') }}</NButton>
      </form>
    </div>

    <div class="list-fill">
      <DataTable
        v-if="tab === 'links' && !topologyMode"
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

    <div v-if="tab === 'links' && topologyMode" class="topology">
      <p v-if="links.length === 0" class="muted">{{ t('assets.link.topologyEmpty') }}</p>
      <!-- 一条链渲染成一行：节点与边交替，链断了自然折到下一行，多跳才看得出「谁串到谁」 -->
      <div
        v-for="chain in linkChains"
        :key="chain.key"
        class="topo-link"
        :data-link="chain.label"
        :style="{ gridTemplateColumns: chainColumns(chain) }"
      >
        <template v-for="(node, index) in chain.nodes" :key="`${chain.key}:${index}`">
          <div class="topo-node">
            <span class="topo-node-name">{{ node.name }}</span>
            <span v-if="node.ports.length" class="topo-node-port">
              {{ node.ports.join(' / ') }}
            </span>
          </div>
          <div v-if="chain.edges[index]" class="topo-edge">
            <span class="topo-edge-name">{{ chain.edges[index].name }}</span>
            <span class="muted small">{{ edgeMeta(chain.edges[index]) }}</span>
          </div>
        </template>
      </div>
    </div>

    <div class="list-fill">
      <DataTable
        v-if="tab === 'credentials'"
        :columns="credentialColumns"
        :data="credentialRows"
        :loading="loading"
        :row-key="(row: Credential) => row.id"
        :empty-title="t('common.noData')"
        :empty-description="projectId ? undefined : t('overview.noProject')"
        empty-icon="boxes"
        :error="Boolean(error)"
        :empty-illustration="keyword ? 'no-result' : 'no-data'"
        flex-height
      />
    </div>

    <!--
      租约只读（FR-v0.4-09）：搜索框与「新建」在这一页无意义——后端没有关键词参数，
      租约也只能由执行产生，所以工具栏里两个控件都隐掉，只留刷新。
    -->
    <div class="list-fill">
      <DataTable
        v-if="tab === 'leases'"
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

<style scoped>
.asset-tabs {
  margin-bottom: 12px;
}

.port-card {
  margin-top: 16px;
}

.port-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.port-head .card-title {
  margin: 0;
}

.port-empty {
  margin: 0;
  padding: var(--space-4) 0;
}

.port-form {
  margin-top: 12px;
}

/* 操作列多个 text 按钮并排：h() 渲染的节点拿不到 scoped 类，用 :deep 兜 */
:deep(.row-actions) {
  display: flex;
  gap: var(--space-2);
}

/* 拓扑视图：一行一条链 = 节点 / 边 / 节点（边数不定，栅格列由 chainColumns 内联给出） */
.topology {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  /* 多跳链比容器宽时横向滚动，不要把节点压成一条缝 */
  overflow-x: auto;
}

.topo-link {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px minmax(0, 1fr);
  align-items: center;
  gap: var(--space-3);
}

.topo-node {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
}

.topo-node-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topo-node-port {
  font-family: var(--font-mono);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.topo-edge {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-1);
}

.topo-edge::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--border-strong);
  z-index: 0;
}

.topo-edge-name {
  position: relative;
  z-index: 1;
  padding: 0 var(--space-2);
  background: var(--bg);
  font-size: var(--font-size-sm);
}
</style>
