<script setup lang="ts">
/**
 * W3 / KRN-04..05 + v5.1.1 / W51-10：内核能力矩阵 + pytest 工程健康检查。
 *
 * 「/kernels 转正」要回答的四件事，全部只读渲染，不在这里做副作用：
 *   能不能跑（解释器版本 + 契约探测）、脚本在哪（仓库可达性）、接上多少（绑定覆盖率）、
 *   上次为什么失败（原因码）。探测本身由后端 24h 缓存，页面刷新不重复起 Python 进程；
 *   只有点「重新探测」才带 refresh=true 穿透缓存。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { kernelsApi, type KernelCapabilities, type KernelSelection } from '@/api/kernels'
import { pytestProjectApi, type PytestHealth } from '@/api/pytestProject'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)
const caps = ref<KernelCapabilities | null>(null)
const selection = ref<KernelSelection | null>(null)
const health = ref<PytestHealth | null>(null)
const failed = ref(false)
const healthFailed = ref(false)
const probing = ref(false)

async function loadHealth(refresh = false): Promise<void> {
  if (!projectId.value) {
    return
  }
  probing.value = true
  healthFailed.value = false
  try {
    health.value = await pytestProjectApi.health(projectId.value, refresh)
  } catch {
    healthFailed.value = true
    health.value = null
  } finally {
    probing.value = false
  }
}

async function load(): Promise<void> {
  failed.value = false
  try {
    caps.value = await kernelsApi.capabilities()
    selection.value = await kernelsApi.select(projectId.value)
  } catch {
    failed.value = true
  }
  await loadHealth(false)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('kernels.title') }}</h1>
      <p class="muted">{{ t('kernels.subtitle') }}</p>
    </header>
    <p v-if="failed" class="notice">{{ t('kernels.failed') }}</p>
    <div v-if="caps" class="facts">
      <p v-for="item in ['robot', 'pabot', 'pytest', 'python3']" :key="item" class="notice">
        {{ item }}: {{ caps[item as 'robot'] ? t('kernels.available') : t('kernels.unavailable') }}
      </p>
      <p class="notice">{{ t('kernels.mixed') }}: {{ caps.sameRunMixedKernels }}</p>
    </div>
    <div v-if="selection" class="facts">
      <p class="notice">{{ t('kernels.selection') }}: {{ selection.kernel }} / {{ selection.source }}</p>
      <p class="notice">{{ t('kernels.overridden') }}: {{ selection.overridden }}</p>
    </div>

    <section class="block" data-test="pytest-health">
      <header class="block-head">
        <h2>{{ t('kernels.projectHealth') }}</h2>
        <button
          type="button"
          class="reprobe"
          data-test="reprobe"
          :disabled="probing"
          @click="loadHealth(true)"
        >
          {{ t('kernels.reprobe') }}
        </button>
      </header>
      <p v-if="healthFailed" class="notice warn" data-test="health-failed">
        {{ t('kernels.healthFailed') }}
      </p>
      <template v-else-if="health">
        <p class="notice" :class="health.healthy ? 'ok' : 'warn'" data-test="health-state">
          {{ health.healthy ? t('kernels.healthy') : t('kernels.unhealthy') }}
        </p>
        <dl class="kv">
          <dt>{{ t('kernels.pythonBin') }}</dt>
          <dd>{{ health.pythonBin ?? '—' }}</dd>

          <dt>{{ t('kernels.pythonVersion') }}</dt>
          <dd>
            {{ health.pythonVersion ?? '—' }}
            <span class="muted">/ {{ t('kernels.minPython') }} {{ health.minPythonVersion }}</span>
            <span
              v-if="health.pythonVersionProblem"
              class="tag bad"
              data-test="version-too-old"
            >{{ t('kernels.versionTooOld') }}</span>
            <span v-else-if="health.pythonVersion" class="tag good" data-test="version-ok">
              {{ t('kernels.versionOk') }}
            </span>
          </dd>

          <dt>{{ t('kernels.pytestVersion') }}</dt>
          <dd>{{ health.pytestVersion ?? '—' }}</dd>

          <dt>{{ t('kernels.contract') }}</dt>
          <dd>
            <span v-if="health.contractMissing.length === 0" class="tag good">
              {{ t('kernels.contractOk') }}
            </span>
            <span v-else class="tag bad" data-test="contract-missing">
              {{ t('kernels.contractMissing') }}: {{ health.contractMissing.join(', ') }}
            </span>
          </dd>

          <dt>{{ t('kernels.repoReachable') }}</dt>
          <dd>
            <span v-if="health.repoReachable" class="tag good">{{ health.repoPath }}</span>
            <span v-else class="tag bad" data-test="repo-bad">
              {{ health.repoConfigured ? t('kernels.repoUnreachable') : t('kernels.repoMissing') }}
            </span>
          </dd>

          <dt>{{ t('kernels.coverage') }}</dt>
          <dd data-test="coverage">
            {{ health.bound }} / {{ health.totalCases }} = {{ health.coveragePercent }}%
          </dd>

          <dt>{{ t('kernels.modeDefault') }}</dt>
          <dd>{{ health.modeDefault }}</dd>

          <dt v-if="health.contractFailure">{{ t('kernels.lastFailure') }}</dt>
          <dd v-if="health.contractFailure" data-test="last-failure">
            {{ health.contractFailure }}
          </dd>
        </dl>
        <ul v-if="health.hints.length" class="hints" data-test="hints">
          <li v-for="hint in health.hints" :key="hint">{{ hint }}</li>
        </ul>
      </template>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: var(--text-muted); font-size: 13px; margin: 0; }
.facts { display: grid; gap: 8px; }
.notice { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; margin: 0; }
.notice.ok { border-color: var(--ok, #2e7d32); }
.notice.warn { border-color: var(--warn, #b26a00); }
.block { display: grid; gap: 12px; border: 1px solid var(--border); border-radius: 8px; padding: 16px; }
.block-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.block-head h2 { margin: 0; font-size: 16px; }
.reprobe { font-size: 13px; padding: 4px 12px; border: 1px solid var(--border); border-radius: 6px; background: transparent; cursor: pointer; }
.reprobe:disabled { opacity: 0.5; cursor: default; }
.kv { display: grid; grid-template-columns: 180px 1fr; gap: 6px 16px; margin: 0; font-size: 13px; }
.kv dt { color: var(--text-muted); }
.kv dd { margin: 0; }
.tag { display: inline-block; margin-left: 8px; padding: 1px 8px; border-radius: 10px; font-size: 12px; border: 1px solid var(--border); }
.tag.good { border-color: var(--ok, #2e7d32); }
.tag.bad { border-color: var(--fail, #c62828); }
.hints { margin: 0; padding-left: 18px; font-size: 13px; color: var(--text-muted); }
</style>
