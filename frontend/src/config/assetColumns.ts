/**
 * 资产页（项目范围）的表格列定义 —— v5.18.0 / FR-v5.18-02 / W68-04 从 `AssetsView.vue` 抽出。
 *
 * <p>抽出的理由不是「文件太长」本身：这一页 1600 行里有 370 行是列定义，列定义又与
 * 页面状态机（表单 ref、编辑态）零耦合——它只读 `t` 与几个取值函数，产出
 * `DataTableColumns`。混在一起时，改一列宽度要滚过 400 行表单逻辑，改一次表单又要
 * 跳过 370 行列定义，两边都在打断对方的阅读。
 *
 * <p>依赖全部从 `ctx` 进来（而不是 import 页面里的东西），这样这份列定义可以被
 * 全局资产页复用，也可以单独测。
 */
import { computed, h, type ComputedRef, type Ref } from 'vue'
import { NButton, type DataTableColumns } from 'naive-ui'
import type { Credential, Device, DevicePort, Link, PlatformAssetType } from '@/api/asset'
import type { LeaseView } from '@/api/execution'
import StatusTag from '@/components/StatusTag.vue'
import { EMPTY } from '@/utils/format'
import type { StatusTone } from '@/utils/statusLabels'
import { blockedReasonLabelKey, credentialLabelKey, stateLabelKey } from '@/config/collectProfileColumns'
import type { useCollectProfiles } from '@/composables/useCollectProfiles'

/** 采集画像索引：与 `useCollectProfiles().byDevice` 同类型，不另立一份结构。 */
export type ProfilesByDevice = ReturnType<typeof useCollectProfiles>['byDevice']

/** 列定义需要的外部能力：取值函数 + 行操作回调 + 文案。 */
export interface AssetColumnsCtx {
  t: (key: string, params?: Record<string, unknown>) => string
  selectionColumn: <T extends { id: number }>() => DataTableColumns<T>[number]
  isPlatformAsset: (row: { projectId: number }) => boolean
  configuredTone: (value: boolean) => StatusTone
  /** 采集画像按设备 id 索引（W63-09 三列同源）。 */
  profilesByDevice: ProfilesByDevice
  /** 当前只读用户能否写：列上的编辑/删除按钮按它决定渲染。 */
  isAdmin: ComputedRef<boolean>
  /** 端口面板当前展开的设备（列上的「端口」按钮文案随它变）。 */
  portsDeviceId: Ref<number | null>
  /** 租约行强制释放的进行中标记（按 resourceKey 定位）。 */
  releasingKey: Ref<string>
  openDeviceEdit: (row: Device) => void
  removeDevice: (row: Device) => void
  togglePorts: (row: Device) => void
  openPortEdit: (row: DevicePort) => void
  deletePort: (row: DevicePort) => void
  removeLink: (row: Link) => void
  removeCredential: (row: Credential) => void
  forceRelease: (row: LeaseView) => void
  unreferenceAsset: (type: PlatformAssetType, id: number) => void
}

export function useAssetColumns(ctx: AssetColumnsCtx) {
  const {
    t, selectionColumn, isPlatformAsset, configuredTone, profilesByDevice,
    isAdmin, portsDeviceId, releasingKey,
    openDeviceEdit, removeDevice, togglePorts, openPortEdit, deletePort,
    removeLink, removeCredential, forceRelease, unreferenceAsset,
  } = ctx

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
      // W62-11：项目资产页必须能一眼分辨「自己私有」与「平台共享且已授权」。
      // 列名与差异矩阵的 col.source 对齐，不允许页面自己再写一套 scope 判断。
      title: t('w62.sourceColumn'),
      key: 'source',
      width: 160,
      render: (row) => (isPlatformAsset(row) ? t('w62.sourcePlatform') : t('w62.sourceProject')),
    },
    // W63-09：三列与全局侧同一套画像字段，项目侧不另写一份判定
    {
      title: t('w63.colCredential'),
      key: 'credential',
      width: 160,
      render: (row) => {
        const profile = profilesByDevice.value.get(row.id)
        const key = credentialLabelKey(profile)
        return h('span', {
          class: key === 'w63.unbound' ? 'muted' : '',
          'data-testid': `profile-credential-${row.id}`,
        }, key === 'w63.unbound' ? t(key)
          : t(key, { name: profile?.boundCredentialMasked ?? '-' }))
      },
    },
    {
      title: t('w63.colCollect'),
      key: 'collectProfile',
      width: 150,
      render: (row) => {
        const profile = profilesByDevice.value.get(row.id)
        return h('span', { 'data-testid': `profile-collect-${row.id}` },
          profile?.collectCount
            ? t('w63.collectCount', { count: profile.collectCount })
            : t('w63.noCollect'))
      },
    },
    {
      title: t('w63.colState'),
      key: 'profileState',
      width: 170,
      render: (row) => {
        const profile = profilesByDevice.value.get(row.id)
        const reason = profile?.blockedReason
          ? blockedReasonLabelKey(profile.blockedReason) : ''
        return h('span', { 'data-testid': `profile-state-${row.id}` },
          t(stateLabelKey(profile)) + (reason ? ` (${t(reason)})` : ''))
      },
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
      width: 190,
      render: (row) => {
        // v4.3.0 / FR-v4.3-08 R1：平台共享资产只给「解除引用」，不给「删除」——
        // 删的是别人的资产本体，入口不该出现在本项目的行上。
        const platform = isPlatformAsset(row)
        return h('div', { class: 'row-actions' }, [
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
              size: 'small',
              disabled: platform,
              'data-testid': `device-edit-${row.id}`,
              onClick: () => openDeviceEdit(row),
            },
            { default: () => t('w62.editDevice') },
          ),
          platform
            ? h(
                NButton,
                {
                  text: true,
                  type: 'warning',
                  size: 'small',
                  onClick: () => void unreferenceAsset('DEVICE', row.id),
                },
                { default: () => t('assets.share.unreference') },
              )
            : h(
                NButton,
                {
                  text: true,
                  type: 'error',
                  size: 'small',
                  onClick: () => void removeDevice(row),
                },
                { default: () => t('common.delete') },
              ),
        ])
      },
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
      width: 130,
      render: (row) =>
        h(
          NButton,
          isPlatformAsset(row)
            ? {
                text: true,
                type: 'warning',
                size: 'small',
                onClick: () => void unreferenceAsset('LINK', row.id),
              }
            : {
                text: true,
                type: 'error',
                size: 'small',
                onClick: () => void removeLink(row),
              },
          { default: () => (isPlatformAsset(row) ? t('assets.share.unreference') : t('common.delete')) },
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
      width: 130,
      render: (row) =>
        h(
          NButton,
          isPlatformAsset(row)
            ? {
                text: true,
                type: 'warning',
                size: 'small',
                onClick: () => void unreferenceAsset('CREDENTIAL', row.id),
              }
            : {
                text: true,
                type: 'error',
                size: 'small',
                onClick: () => void removeCredential(row),
              },
          { default: () => (isPlatformAsset(row) ? t('assets.share.unreference') : t('common.delete')) },
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
        isAdmin.value
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
    {
      title: t('common.actions'),
      key: 'actions',
      width: 160,
      render: (row) =>
        h('span', { class: 'row-actions' }, [
          h(
            NButton,
            {
              text: true,
              size: 'small',
              'data-testid': `port-edit-${row.id}`,
              onClick: () => openPortEdit(row),
            },
            { default: () => t('w62.editPort') },
          ),
          h(
            NButton,
            {
              text: true,
              type: 'error',
              size: 'small',
              'data-testid': `port-delete-${row.id}`,
              onClick: () => void deletePort(row),
            },
            { default: () => t('w62.deletePort') },
          ),
        ]),
    },
  ])

  return { deviceColumns, linkColumns, credentialColumns, leaseColumns, portColumns }
}
