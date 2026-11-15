/** 采集画像一次加载、按 deviceId 建索引；两个 scope 共用，失败降级为空而不是让整页崩。 */
import { computed, ref } from 'vue'
import { collectProfileApi, type CollectProfile } from '@/api/collectProfiles'

export function useCollectProfiles() {
  const profiles = ref<CollectProfile[]>([])
  const loading = ref(false)
  const byDevice = computed(() => new Map<number, CollectProfile>(
    profiles.value.map((profile) => [profile.deviceId, profile]),
  ))

  async function load(projectId?: number): Promise<void> {
    loading.value = true
    try {
      profiles.value = await collectProfileApi.list(projectId)
    } catch {
      // 画像只是列表增强列；它挂了不能让资产列表整页不可用
      profiles.value = []
    } finally {
      loading.value = false
    }
  }

  return { profiles, loading, byDevice, load }
}
