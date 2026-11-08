<script setup lang="ts">
/**
 * AI 助手设置（v2.5 / FR-AI-10..13）。
 *
 * 一个助手一张卡，四件事必须同时能看见：
 *   ① 装没装（只代表可执行文件在不在，不代表能推理）；
 *   ② 配置会不会真的生效（`injectable=false` 的卡片要显式说「仅记录」）；
 *   ③ 端点 / 模型 / 密钥三个值，密钥只显示掩码，留空即不改；
 *   ④ 「测试连接」的原始读数（耗时、退出码、注入了哪些变量名），
 *      让人能判断是配置问题还是环境问题，而不是只看到一句失败。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import {
  aiAssistantsApi,
  type AiAssistantItem,
  type AiAssistantTestResult,
} from '@/api/aiAssistants'

const { t } = useI18n()

const items = ref<AiAssistantItem[]>([])
const loading = ref(false)
const error = ref('')

type Draft = { baseUrl: string; model: string; apiKey: string }
const drafts = reactive<Record<string, Draft>>({})
const saving = reactive<Record<string, boolean>>({})
const testing = reactive<Record<string, boolean>>({})
const notices = reactive<Record<string, string>>({})
const results = reactive<Record<string, AiAssistantTestResult | null>>({})

const installedCount = computed(() => items.value.filter((item) => item.installed).length)

function draftOf(item: AiAssistantItem): Draft {
  if (!drafts[item.key]) {
    drafts[item.key] = { baseUrl: item.baseUrl, model: item.model, apiKey: '' }
  }
  return drafts[item.key]!
}

function syncDraft(item: AiAssistantItem): void {
  drafts[item.key] = { baseUrl: item.baseUrl, model: item.model, apiKey: '' }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    items.value = await aiAssistantsApi.list()
    for (const item of items.value) syncDraft(item)
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function replaceItem(next: AiAssistantItem): void {
  const index = items.value.findIndex((item) => item.key === next.key)
  if (index >= 0) items.value.splice(index, 1, next)
  syncDraft(next)
}

async function save(item: AiAssistantItem): Promise<void> {
  const draft = draftOf(item)
  saving[item.key] = true
  notices[item.key] = ''
  try {
    const next = await aiAssistantsApi.save(item.key, {
      baseUrl: draft.baseUrl,
      model: draft.model,
      // 留空即不改：空字符串会让后端走「保留原密文」分支
      apiKey: draft.apiKey.trim() || undefined,
      enabled: item.enabled,
    })
    replaceItem(next)
    notices[item.key] = t('aiSettings.saved')
  } catch (e) {
    notices[item.key] = e instanceof Error ? e.message : String(e)
  } finally {
    saving[item.key] = false
  }
}

async function clearKey(item: AiAssistantItem): Promise<void> {
  saving[item.key] = true
  notices[item.key] = ''
  try {
    const next = await aiAssistantsApi.save(item.key, {
      baseUrl: draftOf(item).baseUrl,
      model: draftOf(item).model,
      clearKey: true,
      enabled: item.enabled,
    })
    replaceItem(next)
    notices[item.key] = t('aiSettings.keyCleared')
  } catch (e) {
    notices[item.key] = e instanceof Error ? e.message : String(e)
  } finally {
    saving[item.key] = false
  }
}

async function test(item: AiAssistantItem): Promise<void> {
  testing[item.key] = true
  results[item.key] = null
  try {
    results[item.key] = await aiAssistantsApi.test(item.key)
  } catch (e) {
    results[item.key] = {
      ok: false,
      reason: e instanceof Error ? e.message : String(e),
      elapsedMs: 0,
      exitCode: -1,
      stdout: '',
      stderr: '',
    }
  } finally {
    testing[item.key] = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('aiSettings.title') }}</h1>
      <p class="muted">{{ t('aiSettings.subtitle') }}</p>
      <div class="head-actions">
        <span class="chip">{{ t('aiSettings.installedCount', {
          installed: installedCount, total: items.length }) }}</span>
        <button class="btn btn-ghost" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="16" />
          <span>{{ t('aiSettings.refresh') }}</span>
        </button>
      </div>
    </header>

    <p v-if="error" class="notice notice-error">{{ error }}</p>

    <section v-for="item in items" :key="item.key" class="panel" :data-cli="item.key">
      <div class="panel-head">
        <div class="title-row">
          <h2>{{ item.name }}</h2>
          <code class="cli-key">{{ item.key }}</code>
          <span class="chip" :class="item.installed ? 'ok' : 'off'">
            {{ item.installed ? t('aiSettings.installed') : t('aiSettings.notInstalled') }}
          </span>
          <span class="chip" :class="item.injectable ? 'ok' : 'warn'">
            {{ item.injectable ? t('aiSettings.injectable') : t('aiSettings.recordOnly') }}
          </span>
          <span v-if="item.hasKey" class="chip ok">{{ t('aiSettings.keyConfigured') }}</span>
        </div>
        <p class="muted small">
          <span class="cmd">{{ item.command }}</span>
          <span class="dot">·</span>
          {{ item.hint }}
        </p>
      </div>

      <div class="grid">
        <label class="field">
          <span class="label">{{ t('aiSettings.baseUrl') }}</span>
          <input
            v-model="draftOf(item).baseUrl"
            class="input"
            type="text"
            :disabled="!item.baseUrlEnv"
            :placeholder="item.baseUrlEnv
              ? t('aiSettings.baseUrlPlaceholder', { env: item.baseUrlEnv })
              : t('aiSettings.notInjectable')"
          />
        </label>

        <label class="field">
          <span class="label">{{ t('aiSettings.model') }}</span>
          <input
            v-model="draftOf(item).model"
            class="input"
            type="text"
            list="ai-models"
            :disabled="!item.modelFlag"
            :placeholder="item.modelFlag
              ? t('aiSettings.modelPlaceholder', { flag: item.modelFlag })
              : t('aiSettings.notInjectable')"
          />
        </label>

        <label class="field">
          <span class="label">{{ t('aiSettings.apiKey') }}</span>
          <input
            v-model="draftOf(item).apiKey"
            class="input"
            type="password"
            autocomplete="new-password"
            :disabled="!item.credentialEnv"
            :placeholder="item.hasKey
              ? t('aiSettings.keyKeep', { mask: item.keyMasked })
              : t('aiSettings.keyPlaceholder', { env: item.credentialEnv ?? '' })"
          />
          <span v-if="item.keyUnreadable" class="warn-text">{{ t('aiSettings.keyUnreadable') }}</span>
        </label>
      </div>

      <datalist id="ai-models">
        <option v-for="model in item.models" :key="model" :value="model" />
      </datalist>

      <div class="actions">
        <button class="btn" type="button" :disabled="saving[item.key]" @click="save(item)">
          {{ saving[item.key] ? t('aiSettings.saving') : t('aiSettings.save') }}
        </button>
        <button
          v-if="item.hasKey && item.credentialEnv"
          class="btn btn-ghost"
          type="button"
          :disabled="saving[item.key]"
          @click="clearKey(item)"
        >
          {{ t('aiSettings.clearKey') }}
        </button>
        <button class="btn btn-ghost" type="button" :disabled="testing[item.key]" @click="test(item)">
          <AppIcon name="sparkles" :size="16" />
          <span>{{ testing[item.key] ? t('aiSettings.testing') : t('aiSettings.test') }}</span>
        </button>
        <span v-if="notices[item.key]" class="muted small">{{ notices[item.key] }}</span>
      </div>

      <div v-if="results[item.key]" class="result" :class="{ ok: results[item.key]!.ok }">
        <p class="result-head">
          {{ results[item.key]!.ok ? t('aiSettings.testOk') : t('aiSettings.testFail') }}
          <span class="muted small">
            {{ t('aiSettings.testMeta', {
              ms: results[item.key]!.elapsedMs,
              code: results[item.key]!.exitCode,
            }) }}
          </span>
        </p>
        <p v-if="results[item.key]!.appliedEnv?.length" class="muted small">
          {{ t('aiSettings.appliedEnv', { names: results[item.key]!.appliedEnv!.join(', ') }) }}
        </p>
        <pre v-if="results[item.key]!.stdout" class="snippet">{{ results[item.key]!.stdout }}</pre>
        <pre v-if="results[item.key]!.stderr" class="snippet err">{{ results[item.key]!.stderr }}</pre>
        <p v-if="!results[item.key]!.ok" class="muted small">{{ results[item.key]!.reason }}</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { padding: var(--space-5) var(--space-5); display: grid; gap: var(--space-4); max-width: 999px; }
.page-head h1 { margin: 0 0 var(--space-1); font-size: var(--font-size-lg); }
.muted { color: var(--text-muted); font-size: var(--font-size-sm); }
.small { font-size: var(--font-size-sm); }
.head-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); margin-top: var(--space-3); }
.panel { border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); display: grid; gap: var(--space-3); }
.panel-head { display: grid; gap: var(--space-1); }
.title-row { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); }
.title-row h2 { margin: 0; font-size: var(--font-size-md); }
.cli-key { font-family: var(--font-mono, monospace); font-size: var(--font-size-sm); color: var(--text-muted); }
.cmd { font-family: var(--font-mono, monospace); }
.dot { margin: 0 var(--space-1); }
.chip { padding: 2px var(--space-2); border-radius: 999px; font-size: var(--font-size-sm); border: 1px solid var(--border); color: var(--text-muted); }
.chip.ok { border-color: var(--ok-border); color: var(--text); }
.chip.off { border-color: var(--fail-border); color: var(--text-muted); }
.chip.warn { border-color: var(--warn-border); }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--space-3); }
.field { display: grid; gap: var(--space-1); font-size: var(--font-size-sm); }
.label { color: var(--text-muted); }
.input { height: 28px; padding: 0 var(--space-2); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--panel); color: var(--text); font: inherit; }
.input:disabled { opacity: 0.6; }
.warn-text { color: var(--text-muted); }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); }
.result { border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-3); display: grid; gap: var(--space-2); }
.result.ok { border-color: var(--ok-border); }
.result-head { margin: 0; font-weight: 600; display: flex; align-items: baseline; gap: var(--space-2); flex-wrap: wrap; }
.snippet { margin: 0; padding: var(--space-2); background: var(--panel); border-radius: var(--radius-md); font-family: var(--font-mono, monospace); font-size: var(--font-size-sm); white-space: pre-wrap; word-break: break-word; max-height: 180px; overflow: auto; }
.snippet.err { border: 1px solid var(--fail-border); }
.notice { padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); font-size: var(--font-size-sm); }
.notice-error { border: 1px solid var(--fail-border); }
</style>
