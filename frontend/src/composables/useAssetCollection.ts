/**
 * 资产采集状态机 —— v5.18.0 / FR-v5.18-02 / W68-05 从 `GlobalAssetsView.vue` 抽出。
 *
 * <p>管三件事：① 「能不能采」的判定（与服务端 collect() 的 400 条件一一对应：
 * 有管理 IP 且有凭证）；② 单个采集与弹窗采集的执行与反馈；③ 凭据 → 设备面板的
 * 批量采集。三者共用一套 loading / error / done 状态，放在页面里会和列表取数耦合。
 */
import { ref } from 'vue'
import { collectProfileApi, type CollectHistoryRow } from '@/api/collectProfiles'
import {
  globalAssetApi,
  type BatchCollectResult,
  type CredentialDevice,
  type GlobalAssetRow,
} from '@/api/globalAssets'
import {
  canCollect as profileCanCollect,
  collectBlockLabelKey,
  collectBlockReason,
} from '@/config/collectProfileColumns'
import type { ProfilesByDevice } from '@/config/globalAssetColumns'

export interface AssetCollectionDeps {
  t: (key: string, params?: Record<string, unknown>) => string
  profilesByDevice: ProfilesByDevice
  /** 列表刷新（采集会改设备状态列）。 */
  load: () => Promise<void>
  /** 画像刷新（自动绑定会改凭证绑定）。 */
  loadProfiles: () => Promise<void>
}

export function useAssetCollection(deps: AssetCollectionDeps) {
  const { t, profilesByDevice, load, loadProfiles } = deps

  function collectRowOf(row: GlobalAssetRow) {
    const profile = profilesByDevice.value.get(row.id)
    return {
      managementIp: profile?.managementIp ?? row.managementIp,
      managementCredentialId: profile?.managementCredentialId ?? row.managementCredentialId,
      bmcCredentialId: profile?.bmcCredentialId ?? row.bmcCredentialId,
    }
  }

  function canCollect(row: GlobalAssetRow): boolean {
    return profileCanCollect(collectRowOf(row))
  }

  function collectDisabledReason(row: GlobalAssetRow): string {
    const reason = collectBlockReason(collectRowOf(row))
    return reason === 'ok' ? t('w63.autoBindHint') : t(collectBlockLabelKey(reason))
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
  const setAsDefault = ref(false)
  const historyRows = ref<CollectHistoryRow[]>([])

  async function loadHistory(deviceId: number): Promise<void> {
    try {
      historyRows.value = await collectProfileApi.history(deviceId)
    } catch {
      historyRows.value = []
    }
  }

  async function openCollect(row: GlobalAssetRow) {
    collectTarget.value = row
    collectOpen.value = true
    collectError.value = ''
    collectDone.value = ''
    collectCredentialId.value = null
    collectUsername.value = ''
    setAsDefault.value = false
    void loadHistory(row.id)
    try {
      credentialOptions.value = await globalAssetApi.list({ type: 'CREDENTIAL' })
    } catch {
      credentialOptions.value = []
    }
  }

  /** 主入口：不传 credentialId，由后端回退链读绑定；成功后再刷画像（自动填空会改状态）。 */
  async function directCollect(row: GlobalAssetRow): Promise<void> {
    if (!canCollect(row)) return
    collectBusy.value = true
    collectError.value = ''
    try {
      const collected = await globalAssetApi.collect(row.id, { confirm: true })
      collectDone.value = t('global.assetCollectDone', {
        status: collected.status, nics: collected.nicCount, roce: collected.rocePorts,
      })
      await load()
      await loadProfiles()
    } catch (error) {
      collectError.value = error instanceof Error ? error.message : t('global.loadFailed')
    } finally {
      collectBusy.value = false
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
      if (setAsDefault.value && collectCredentialId.value && collectTarget.value) {
        await collectProfileApi.bindGlobal(collectTarget.value.id, {
          managementCredentialId: collectCredentialId.value,
        })
        await loadProfiles()
      }
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

  return {
    collectOpen, collectTarget, credentialOptions, collectCredentialId, collectUsername,
    collectBusy, collectError, collectDone, setAsDefault, historyRows,
    collectRowOf, canCollect, collectDisabledReason, loadHistory, openCollect, directCollect, runCollect,
    bindOpen, bindTarget, bindDevices, bindLoading, bindBusy, bindError, batchResult,
    openDevices, collectAll,
  }
}
