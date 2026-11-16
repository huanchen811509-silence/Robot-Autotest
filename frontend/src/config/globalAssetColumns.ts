/**
 * 全局资产页的列定义 —— v5.18.0 / FR-v5.18-02 / W68-05 从 `GlobalAssetsView.vue` 抽出。
 *
 * <p>与项目侧同一处理：这一页 1290 行里近 170 行是「列长什么样」，与取数、采集、
 * 表单零耦合，却夹在中间——改列宽要滚过采集逻辑，改采集又要跳过列定义。
 *
 * <p>依赖全部经 `ctx` 注入，所以这两份列定义（资产表 / 凭据设备表）可以被别的页面
 * 直接复用，也可以单独渲染断言。
 */
import { computed, h, type ComputedRef, type Ref } from 'vue'
import { NButton, type DataTableColumns } from 'naive-ui'
import type { CredentialDevice, GlobalAssetRow } from '@/api/globalAssets'

import { blockedReasonLabelKey, credentialLabelKey, stateLabelKey } from '@/config/collectProfileColumns'
import type { useCollectProfiles } from '@/composables/useCollectProfiles'

/** 与 `useCollectProfiles().byDevice` 同类型。 */
export type ProfilesByDevice = ReturnType<typeof useCollectProfiles>['byDevice']

/** 资产页签（与页面同一套取值）。 */
export type AssetTab = 'DEVICE' | 'PORT' | 'CREDENTIAL' | 'LINK'

export interface GlobalAssetColumnsCtx {
  t: (key: string, params?: Record<string, unknown>) => string
  /** 资源类型（设备 / 端口 / 凭据 / 链路）：决定「端口数」「采集状态」这类列出不出。 */
  type: Ref<AssetTab>
  /** 只读用户不渲染批量勾选列。 */
  canWrite: ComputedRef<boolean>
  /** 批量删除是否在本页启用（差异矩阵 `action.batchDelete`）。 */
  batchDeleteEnabled: boolean
  /** 跨页保留的已选 id。 */
  selectedAssetIds: Ref<number[]>
  /** 采集画像索引：三列（可采/原因/最近状态）同源。 */
  profilesByDevice: ProfilesByDevice
  toggleAssetSelection: (id: number, checked: boolean) => void
  collectLabel: (row: GlobalAssetRow) => string
  canCollect: (row: GlobalAssetRow) => boolean
  collectDisabledReason: (row: GlobalAssetRow) => string
  planeLabel: (plane: string) => string
  channelLabel: (channel: string) => string
  openCollect: (row: GlobalAssetRow) => void
  directCollect: (row: GlobalAssetRow) => void
  openDevices: (row: GlobalAssetRow) => void
  openRename: (row: GlobalAssetRow) => void
  removeAsset: (row: GlobalAssetRow) => void
}

export function useGlobalAssetColumns(ctx: GlobalAssetColumnsCtx) {
  const {
    t, type, canWrite, batchDeleteEnabled, selectedAssetIds, profilesByDevice,
    toggleAssetSelection, collectLabel, canCollect, collectDisabledReason,
    planeLabel, channelLabel, openCollect, directCollect, openDevices, openRename, removeAsset,
  } = ctx

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
      // W63-09：三列都是共享列（不在差异矩阵里），两 scope 都保留
      cols.push({
        title: t('w63.colCredential'), key: 'credential', width: 160,
        render: (row) => {
          const profile = profilesByDevice.value.get(row.id)
          const key = credentialLabelKey(profile)
          return h('span', {
            class: key === 'w63.unbound' ? 'muted' : '',
            'data-testid': `profile-credential-${row.id}`,
          }, key === 'w63.unbound' ? t(key)
            : t(key, { name: profile?.boundCredentialMasked ?? '-' }))
        },
      })
      cols.push({
        title: t('w63.colCollect'), key: 'collectProfile', width: 150,
        render: (row) => {
          const profile = profilesByDevice.value.get(row.id)
          return h('span', { 'data-testid': `profile-collect-${row.id}` },
            profile?.collectCount
              ? t('w63.collectCount', { count: profile.collectCount })
              : t('w63.noCollect'))
        },
      })
      cols.push({
        title: t('w63.colState'), key: 'profileState', width: 170,
        render: (row) => {
          const profile = profilesByDevice.value.get(row.id)
          const reason = profile?.blockedReason
            ? blockedReasonLabelKey(profile.blockedReason) : ''
          return h('span', { 'data-testid': `profile-state-${row.id}` },
            t(stateLabelKey(profile)) + (reason ? ` (${t(reason)})` : ''))
        },
      })
    }
    cols.push({ title: t('global.assetRefs'), key: 'referenceCount', width: 100 })
    if (canWrite.value && batchDeleteEnabled) {
      cols.push({
        title: '',
        key: 'selection',
        width: 46,
        render: (row) =>
          h('input', {
            type: 'checkbox',
            checked: selectedAssetIds.value.includes(row.id),
            'data-testid': `asset-check-${row.id}`,
            onChange: (event: Event) =>
              toggleAssetSelection(row.id, (event.target as HTMLInputElement).checked),
          }),
      })
    }
    if (canWrite.value) {
      cols.push({
        title: t('global.colActions'),
        key: 'actions',
        width: 220,
        render: (row) =>
          h('span', { class: 'row-actions' }, [
            type.value === 'DEVICE'
              ? h('span', { class: 'row-actions' }, [
                  h(NButton, {
                    text: true,
                    type: 'primary',
                    disabled: !canCollect(row),
                    title: collectDisabledReason(row),
                    'data-testid': `direct-collect-${row.id}`,
                    onClick: () => void directCollect(row),
                  }, () => t('w63.directCollect')),
                  h(NButton, {
                    text: true,
                    'data-testid': `specify-collect-${row.id}`,
                    onClick: () => void openCollect(row),
                  }, () => t('w63.specifyCollect')),
                ])
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
              {
                text: true,
                type: 'primary',
                'data-testid': `rename-${row.id}`,
                onClick: () => openRename(row),
              },
              () => t('global.assetRenameAction'),
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
   * 凭据 → 关联设备面板的列（FR-v4.12-15）。
   *
   * 这张表原来手写原生 `<table>`，被 `tools/check_tables.py` 拦下 —— 它和多行设备列表
   * 是同一类东西（数据网格），不是「定义表」那种 th/td 两列的展示块。手写的代价是
   * 空态、横滚、暗色下表头各有一套，与主表走两套规范；改成壳之后列只剩「有几列、渲染什么」。
   */
  const bindColumns = computed<DataTableColumns<CredentialDevice>>(() => [
    { title: t('global.assetName'), key: 'name', minWidth: 160 },
    {
      title: t('global.assetPlane'),
      key: 'plane',
      width: 100,
      render: (row) => planeLabel(row.plane),
    },
    {
      title: t('global.assetPlaneAddress'),
      key: 'address',
      width: 170,
      render: (row) => h('span', { class: 'mono' }, row.address || '-'),
    },
    {
      title: t('global.assetChannel'),
      key: 'channel',
      width: 140,
      render: (row) => channelLabel(row.channel),
    },
    {
      title: t('global.assetCollectStatus'),
      key: 'lastStatus',
      minWidth: 200,
      // 没采过的面显示「未采集」而不是空白：空白会被读成「加载失败」
      render: (row) =>
        h('span', {}, [
          row.lastStatus || t('global.assetCollectNever'),
          ...(row.lastCollectedAt ? [h('span', { class: 'muted' }, ` · ${row.lastCollectedAt}`)] : []),
        ]),
    },
  ])

  /**
   * 能不能点「采集」（W63-14）：判定与服务端 collect() 的 400 条件一一对应，
   * 即「有管理 IP 且有凭证」。只绑 BMC、没管理 IP 的设备必须禁用并点名原因。
   */

  return { columns, bindColumns }
}
