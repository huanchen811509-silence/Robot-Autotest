<script setup lang="ts">
/**
 * 运维 · 升级与演练（v5.16.0 / FR-v5.16-09~10）。
 *
 * 边界：**界面不执行真实升级**。「执行升级」按钮禁用并给 `title` 说明原因；
 * 能做的只有只读预检与生成可复制命令，真升级必须到 CLI。
 * 安装 / 拆卸同样只给说明，不提供端点 —— 拆卸不删数据库，恢复生产库一律走 CLI。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { NButton, NInput, NTag } from 'naive-ui'
import ExecutionPrinciplesView from '@/views/maintenance/ExecutionPrinciplesView.vue'
import { useAuthStore } from '@/stores/auth'
import { opsConsole } from '@/api/opsV516'
import type { PrecheckView } from '@/api/opsV516'

const { t } = useI18n()
const auth = useAuthStore()
const projectId = computed(() => auth.projectId ?? 0)

const precheck = ref<PrecheckView | null>(null)
const packagePath = ref('')
const notice = ref('')
const loading = ref(false)

const generated = computed(
  () => `bash scripts/ops/update.sh --apply --package ${packagePath.value || '<package.tar.gz>'}`,
)

async function load() {
  if (!projectId.value) return
  loading.value = true
  notice.value = ''
  try {
    precheck.value = await opsConsole.precheck(projectId.value, packagePath.value)
  } catch {
    notice.value = t('opsV516.common.loadFailed')
  } finally {
    loading.value = false
  }
}

async function copyCommand() {
  try {
    await navigator.clipboard.writeText(generated.value)
    notice.value = t('opsV516.common.copyDone')
  } catch {
    notice.value = generated.value
  }
}

onMounted(load)
</script>

<template>
  <section class="ops-page">
    <header class="ops-head">
      <h2>{{ t('opsV516.upgrade.title') }}</h2>
      <NButton size="small" :loading="loading" @click="load">{{ t('opsV516.upgrade.precheck') }}</NButton>
    </header>
    <p class="ops-desc">{{ t('opsV516.upgrade.desc') }}</p>
    <p v-if="notice" class="ops-notice">{{ notice }}</p>

    <div class="ops-row">
      <NInput v-model:value="packagePath" :placeholder="t('opsV516.upgrade.packagePlaceholder')"
              style="max-width: 320px" />
    </div>

    <div v-if="precheck" class="ops-card">
      <h3>
        {{ t('opsV516.upgrade.precheckResult') }}
        <NTag size="small" :type="precheck.passed ? 'success' : 'error'">
          {{ precheck.passed ? t('opsV516.upgrade.passed') : t('opsV516.upgrade.blocked') }}
        </NTag>
      </h3>
      <p v-if="!precheck.issues.length" class="ops-muted">{{ t('opsV516.upgrade.noIssue') }}</p>
      <ul v-else class="ops-plan">
        <li v-for="issue in precheck.issues" :key="issue.kind + issue.detail">
          [{{ issue.level }}] {{ issue.kind }}：{{ issue.detail }}
        </li>
      </ul>
      <p class="ops-muted">{{ precheck.note }}</p>
    </div>

    <h3 class="ops-sub">{{ t('opsV516.upgrade.command') }}</h3>
    <div class="ops-row">
      <code class="ops-code">{{ generated }}</code>
      <NButton size="tiny" @click="copyCommand">{{ t('opsV516.common.copy') }}</NButton>
    </div>
    <div class="ops-row">
      <NButton size="small" disabled :title="t('opsV516.upgrade.disabledReason')">
        {{ t('opsV516.upgrade.execute') }}
      </NButton>
      <span class="ops-muted">{{ t('opsV516.upgrade.disabledReason') }}</span>
    </div>

    <h3 class="ops-sub">{{ t('opsV516.upgrade.boundary') }}</h3>
    <ul class="ops-plan">
      <li>{{ t('opsV516.upgrade.noteUpgrade') }}</li>
      <li>{{ t('opsV516.upgrade.noteUninstall') }}</li>
      <li>{{ t('opsV516.upgrade.noteRestore') }}</li>
    </ul>

    <h3 class="ops-sub">{{ t('opsV516.upgrade.principles') }}</h3>
    <ExecutionPrinciplesView />
  </section>
</template>

<style scoped>
.ops-page { display: flex; flex-direction: column; gap: 12px; }
.ops-head { display: flex; align-items: center; justify-content: space-between; }
.ops-desc { color: var(--text-muted); margin: 0; }
.ops-sub { margin: 12px 0 0; }
.ops-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ops-card { border: 1px solid var(--border-strong); border-radius: var(--radius-sm); padding: 12px; background: var(--panel); }
.ops-card h3 { margin: 0 0 8px; font-size: 13px; }
.ops-muted { color: var(--text-muted); font-size: 12px; }
.ops-notice { color: var(--accent); margin: 0; }
.ops-plan { margin: 0; padding-left: 18px; font-size: 12px; color: var(--text-muted); }
.ops-code { background: var(--surface-2); padding: 4px 8px; border-radius: var(--radius-sm); font-size: 12px; }
</style>
