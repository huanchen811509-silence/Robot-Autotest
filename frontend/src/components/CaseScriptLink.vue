<script setup lang="ts">
/**
 * 用例 → 脚本的展示件（v5.11.0 / W61-12 / FR-v5.11-05）。
 *
 * 三条硬约束：
 * 1. **文件名与路径都由后端给**（{@code scriptName} / {@code scriptPath}），前端不切路径——
 *    否则列表、详情、用例集三处会出现同一条用例三个字符串。
 * 2. **未绑定不隐藏**：显示灰标签「未绑定脚本」比留空单元格诚实，用户才知道这是「还没有」。
 * 3. **RF 不猜路径**：{@code NOT_APPLICABLE} 只给「不适用」+ 原因，绝不做模糊匹配。
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    scriptPath?: string | null
    scriptName?: string | null
    /** BOUND_VERIFIED / BOUND_UNVERIFIED / UNBOUND / NOT_APPLICABLE */
    bindingState?: string | null
    /** 除首选绑定外还有几个内核的脚本。 */
    otherScriptCount?: number
    /** 原始值是否为绝对路径（后端已截断，这里只提示）。 */
    absoluteSource?: boolean
    /** 表格里用紧凑版（默认）；详情里用完整版（显示完整路径）。 */
    full?: boolean
  }>(),
  { scriptPath: null, scriptName: null, bindingState: null, otherScriptCount: 0, absoluteSource: false, full: false },
)

const { t } = useI18n()
const copied = ref(false)
const copyFailed = ref(false)

const bound = computed(
  () => props.bindingState === 'BOUND_VERIFIED' || props.bindingState === 'BOUND_UNVERIFIED',
)
const verified = computed(() => props.bindingState === 'BOUND_VERIFIED')
const notApplicable = computed(() => props.bindingState === 'NOT_APPLICABLE')

const tooltip = computed(() => {
  if (!bound.value) return notApplicable.value ? t('w61.script.notApplicableWhy') : ''
  const parts = [props.scriptPath ?? '']
  if (props.absoluteSource) parts.push(t('w61.script.absoluteNote'))
  if (props.otherScriptCount > 0) {
    parts.push(t('w61.script.more', { count: props.otherScriptCount }))
  }
  return parts.filter(Boolean).join(' · ')
})

async function copyPath(): Promise<void> {
  const text = props.scriptPath
  if (!text) return
  copyFailed.value = false
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
  } catch {
    // 非安全上下文 / 老浏览器：退回到选中 + execCommand，失败就明说「复制失败」
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    document.body.appendChild(area)
    area.select()
    try {
      copied.value = document.execCommand('copy')
    } catch {
      copied.value = false
    }
    document.body.removeChild(area)
    copyFailed.value = !copied.value
  }
  window.setTimeout(() => {
    copied.value = false
    copyFailed.value = false
  }, 1500)
}
</script>

<template>
  <span class="case-script" data-testid="case-script-link">
    <template v-if="bound">
      <button
        type="button"
        class="script-copy"
        :title="tooltip"
        data-testid="case-script-copy"
        @click.stop="copyPath"
      >
        <code class="script-name" data-testid="case-script-name">{{ scriptName }}</code>
        <span v-if="full" class="script-path" data-testid="case-script-path">{{ scriptPath }}</span>
      </button>
      <span
        class="script-state"
        :class="{ ok: verified }"
        :title="verified ? t('w61.script.verified') : t('w61.script.unverifiedWhy')"
        data-testid="case-script-state"
      >{{ verified ? t('w61.script.verified') : t('w61.script.unverified') }}</span>
      <span
        v-if="otherScriptCount > 0"
        class="script-more"
        :title="tooltip"
        data-testid="case-script-more"
      >+{{ otherScriptCount }}</span>
      <span v-if="copied" class="script-tip" data-testid="case-script-copied">
        {{ t('w61.script.copied') }}
      </span>
      <span v-else-if="copyFailed" class="script-tip fail" data-testid="case-script-copy-failed">
        {{ t('w61.script.copyFailed') }}
      </span>
    </template>
    <span
      v-else-if="notApplicable"
      class="script-tag"
      :title="t('w61.script.notApplicableWhy')"
      data-testid="case-script-na"
    >{{ t('w61.script.notApplicable') }}</span>
    <span v-else class="script-tag" data-testid="case-script-unbound">
      {{ t('w61.script.unbound') }}
    </span>
  </span>
</template>

<style scoped>
.case-script {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.script-copy {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-2);
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  color: var(--text);
  min-width: 0;
}
.script-name {
  font-family: var(--font-mono);
  font-size: var(--font-size-sm);
  overflow-wrap: anywhere;
}
.script-path {
  color: var(--text-muted);
  font-size: var(--font-size-xs);
  font-family: var(--font-mono);
  overflow-wrap: anywhere;
}
.script-state {
  flex: none;
  padding: 1px var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}
.script-state.ok {
  color: var(--ok);
  border-color: var(--ok);
}
.script-more {
  flex: none;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}
.script-tag {
  padding: 1px var(--space-2);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
}
.script-tip {
  font-size: var(--font-size-xs);
  color: var(--ok);
}
.script-tip.fail {
  color: var(--fail);
}
</style>
