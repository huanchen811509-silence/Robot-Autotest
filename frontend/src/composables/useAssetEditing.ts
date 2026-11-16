/**
 * 资产编辑状态机 —— v5.18.0 / FR-v5.18-02 / W68-04 从 `AssetsView.vue` 抽出。
 *
 * <p>这一组是「表单状态 + 增删改」：设备、端口、链路、凭据四套表单，加上引用/解除引用
 * 平台资产。它们的共同点是**都在页面里各存一份 ref，再各写一个提交函数**，
 * 与列表取数（load / 分页）完全无关。抽出来后页面只剩取数与编排。
 *
 * <p>依赖从 `deps` 进来：数据源（devices 等）用于回填默认值，`load` 用于写后刷新，
 * `error` / `message` 是页面级的反馈行。这样不复制任何页面状态。
 */
import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue'
import {
  credentialApi,
  deviceApi,
  linkApi,
  shareApi,
  type Credential,
  type Device,
  type DevicePort,
  type Link,
  type PlatformAssetType,
} from '@/api/asset'
import { executionApi, type LeaseView } from '@/api/execution'
import { confirmAction } from '@/composables/useConfirm'
import { localizeError, type ErrorLine } from '@/utils/errors'

export type AssetTab = 'devices' | 'links' | 'credentials' | 'leases'

export interface AssetEditingDeps {
  t: (key: string, params?: Record<string, unknown>) => string
  projectId: ComputedRef<number>
  tab: Ref<AssetTab>
  devices: Ref<Device[]>
  load: () => Promise<void>
  error: Ref<ErrorLine | null>
  message: Ref<string>
}

export function useAssetEditing(deps: AssetEditingDeps) {
  const { t, projectId, tab, devices, load, error, message } = deps

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
  /** W62-12 / W62-13：同一张行内表单承载「新增」与「编辑」，不另开弹窗。 */
  const editingDeviceId = ref<number | null>(null)
  const editingPortId = ref<number | null>(null)
  const portDeleteError = ref('')

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

  const portForm = reactive({
    portName: '',
    portType: '',
    speedGbps: null as number | null,
    // v4.3.0 / FR-v4.3-03：网卡归属可留空（空 = 「未归属」，归一到一个网卡分组里）。
    nicName: '',
    nicIndex: null as number | null,
  })

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

  /**
   * 平台资产引用（v4.3.0 / FR-v4.3-08，P1）。
   *
   * 资产页只能看到「本项目私有 + 已引用平台共享」，因此「引用」入口必须允许输入资产 ID；
   * 「解除引用」只在行上出现——判定依据是 `projectId !== 当前项目`（接口自带该字段），
   * 不给自己的私有资产摆一个点了必然 404/403 的按钮。
   */
  const showShareForm = ref(false)
  const shareForm = reactive({ assetId: null as number | null })

  /** 租约页「强制释放」的进行中标记：按 resourceKey 定位，避免整表 loading。 */
  const releasingKey = ref('')

  const shareAssetType = computed<PlatformAssetType>(() => {
    if (tab.value === 'devices') return 'DEVICE'
    if (tab.value === 'links') return 'LINK'
    return 'CREDENTIAL'
  })

  /**
   * 引用平台资产（P1）：成功后关表单并重载；失败（含 403）只把错误写进错误行，
   * **表单与已填 ID 一律保留**，用户不用重新输入就能换一个 ID 再试（PRD FR-v4.3-08 R2）。
   */
  async function referencePlatformAsset(): Promise<void> {
    error.value = null
    message.value = ''
    if (!shareForm.assetId) {
      error.value = { summary: t('assets.requiredAssetId'), detail: null }
      return
    }
    try {
      await shareApi.reference(projectId.value, shareAssetType.value, shareForm.assetId)
      message.value = t('assets.share.referenceOk')
      shareForm.assetId = null
      showShareForm.value = false
      await load()
    } catch (e) {
      error.value = localizeError(e)
    }
  }

  /** 解除引用（P1）：只删关系不删资产，因此文案强调「本项目不再看到」。 */
  async function unreferenceAsset(type: PlatformAssetType, id: number): Promise<void> {
    if (!(await confirmAction(t('assets.share.unreferenceConfirm'), { tone: 'danger' }))) return
    error.value = null
    message.value = ''
    try {
      await shareApi.unreference(projectId.value, type, id)
      message.value = t('assets.share.unreferenceOk')
      await load()
    } catch (e) {
      error.value = localizeError(e)
    }
  }

  function openDeviceEdit(row: Device): void {
    showForm.value = true
    editingDeviceId.value = row.id
    deviceForm.name = row.name ?? ''
    deviceForm.deviceType = row.deviceType ?? ''
    deviceForm.model = row.model ?? ''
    deviceForm.vendor = row.vendor ?? ''
    deviceForm.serialNo = row.serialNo ?? ''
    deviceForm.managementIp = row.managementIp ?? ''
    deviceForm.location = row.location ?? ''
    deviceForm.domainTag = row.domainTag ?? ''
  }

  function cancelDeviceEdit(): void {
    editingDeviceId.value = null
    deviceForm.name = ''
    deviceForm.deviceType = ''
    deviceForm.model = ''
    deviceForm.vendor = ''
    deviceForm.serialNo = ''
    deviceForm.managementIp = ''
    deviceForm.location = ''
    deviceForm.domainTag = ''
  }

  async function createDevice(): Promise<void> {
    error.value = null
    message.value = ''
    if (!deviceForm.name.trim()) {
      error.value = { summary: t('assets.requiredName'), detail: null }
      return
    }
    try {
      const command = { ...deviceForm, name: deviceForm.name.trim(), enabled: true }
      if (editingDeviceId.value !== null) {
        await deviceApi.update(projectId.value, editingDeviceId.value, command)
      } else {
        await deviceApi.create(projectId.value, command)
      }
      message.value = t('common.saved')
      showForm.value = false
      cancelDeviceEdit()
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

  function openPortEdit(row: DevicePort): void {
    editingPortId.value = row.id
    portForm.portName = row.portName ?? ''
    portForm.portType = row.portType ?? ''
    portForm.speedGbps = row.speedGbps ?? null
    portForm.nicName = row.nicName ?? ''
    portForm.nicIndex = row.nicIndex ?? null
    portDeleteError.value = ''
  }

  function cancelPortEdit(): void {
    editingPortId.value = null
    portForm.portName = ''
    portForm.portType = ''
    portForm.speedGbps = null
    portForm.nicName = ''
    portForm.nicIndex = null
  }

  async function deletePort(row: DevicePort): Promise<void> {
    if (!(await confirmAction(t('w62.deletePortConfirm', { name: row.portName }), {
        tone: 'danger',
      })))
      return
    portDeleteError.value = ''
    try {
      await deviceApi.deletePort(projectId.value, row.id)
      if (editingPortId.value === row.id) cancelPortEdit()
      ports.value = await deviceApi.ports(projectId.value, portsDeviceId.value!)
    } catch (e) {
      // 409 文案由后端点名引用链路；前端只负责把它显示出来，不改成模糊的「删除失败」
      portDeleteError.value = localizeError(e).summary
    }
  }

  async function submitPort(): Promise<void> {
    const deviceId = portsDeviceId.value
    if (!deviceId) return
    error.value = null
    if (!portForm.portName.trim()) {
      error.value = { summary: t('assets.requiredPortName'), detail: null }
      return
    }
    try {
      const command = {
        portName: portForm.portName.trim(),
        portType: portForm.portType,
        speedGbps: portForm.speedGbps,
        nicName: portForm.nicName.trim() || null,
        nicIndex: portForm.nicIndex,
      }
      if (editingPortId.value !== null) {
        await deviceApi.updatePort(projectId.value, editingPortId.value, command)
      } else {
        await deviceApi.addPort(projectId.value, deviceId, command)
      }
      cancelPortEdit()
      portDeleteError.value = ''
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

  return {
    portsDeviceId, portsDevice, ports, showForm,
    editingDeviceId, editingPortId, portDeleteError,
    deviceForm, portForm, linkForm, credentialForm, aPorts, bPorts,
    showShareForm, shareForm, shareAssetType, releasingKey,
    referencePlatformAsset, unreferenceAsset, openDeviceEdit, cancelDeviceEdit, createDevice,
    removeDevice, togglePorts, openPortEdit, cancelPortEdit, deletePort, submitPort,
    changeLinkDevice, createLink, removeLink, createCredential, removeCredential, forceRelease,
  }
}
