<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ReportCaseNode, ReportSuiteNode } from '@/api/reports'
import StatusTag from '@/components/StatusTag.vue'
import { verdictTone } from '@/utils/statusLabels'
import { copyText } from '@/composables/useClipboard'
import KeywordSteps from './KeywordSteps.vue'

/**
 * 套件树节点（RPT-02）：
 * - 根套件默认展开，子级默认折叠，点标题展开/收起；
 * - 用例行按 PASS/FAIL/SKIP 着色；
 * - 点用例展开关键字步骤树，再点「查看日志」交给父级按需拉取。
 *
 * <p>UX-04b 第六批两处改动：
 *
 * <p>① **状态徽标 `badge verdict-*` → `StatusTag`**（图标 + 文字）。树里一屏可能出现
 * 十几条 FAIL，只靠红色区分时，六预设里 crimson 与 fail 同色相基本读不出差别。
 *
 * <p>② **整行按钮补齐 hover / focus-visible / transition**。它原来是个裸 `<button>`，
 * 三态一个都没有；而 `tools/check_states.py` 只扫 `styles/*.css`，读不到 scoped style，
 * 所以守门一直是绿的 —— 键盘用户走一遍这棵树，焦点停在哪一行完全看不出来。
 * 本批把守门扩到 `.vue` 的 `<style>` 块并把 `.tree-toggle` 登记进 REQUIRED。
 *
 * <p>**为什么不用 `NButton`**：库的 `--n-height` 最小档是 28px（medium 34px），
 * 而这里的行高是 21px。一个套件里几十条用例，换成库按钮会让整棵树凭空高出一倍；
 * 而这行本质是**展开/收起控件**（disclosure），不是设计系统意义上的"按钮"。
 * 保留原生元素 + 显式声明三态，比为了"统一"付出密度代价更合适。
 *
 * <p>v3.9.0 追加「下发命令」行（FR-v3.9-03）：在用例名下方单列一行，与「查看日志」联动。
 * 三种状态分别显示，绝不共用空白：
 * - `null` → 「未采集（v3.9.0 之前的报告）」——历史数据，说明白比装作没有好；
 * - `'NONE'` → 「本用例未下发外部命令」——PRD 判据 ④ 指定文案；
 * - 其余 → 命令原文，超长折叠为一行，可展开全文 / 复制 / 定位日志。
 *
 * 命令留在树里而不是另开抽屉：现场看报告的动作是「这条用例挂了，它到底跑了什么」，
 * 让命令和结论同屏比再点一次更省事；全文仍可展开，密度只多一行。
 */
const props = defineProps<{
  node: ReportSuiteNode
  depth: number
}>()

const emit = defineEmits<{ (event: 'open-logs', caseKey: string): void }>()

const { t } = useI18n()
const open = ref(props.depth === 0)
const expanded = ref<Set<string>>(new Set())
/** 已展开全文的命令行（按 caseKey）。 */
const cmdExpanded = ref<Set<string>>(new Set())
/** 刚复制成功的 caseKey，用于 1.5s 内的「已复制」反馈。 */
const copied = ref('')
/** 复制失败提示（现场 http 内网地址 + 权限被拒时才会走到）。 */
const copyFailed = ref('')

/** 超过这个长度的命令默认折叠成一行：一行放不下，展开才有意义。 */
const FOLD_THRESHOLD = 72

function toggleSuite(): void {
  open.value = !open.value
}

function toggleCase(caseKey: string): void {
  const next = new Set(expanded.value)
  if (next.has(caseKey)) {
    next.delete(caseKey)
  } else {
    next.add(caseKey)
  }
  expanded.value = next
}

/**
 * 三态归一的命令展示模型：hasCommand=false 时 text 是解释性文案。
 *
 * v5.6.0 / FR-v5.6-21：`NONE` 不再直接显示（用户看不懂），一律显示「未采集（原因）」。
 * 原因按 `commandSource` 区分：来源也是 NONE 说明用例库压根没记命令行（最常见，
 * 59 条真实执行里 pytest 侧原本全是这一种）；有来源但文本为空说明提取器没命中，
 * 那是平台侧问题，需要单独排查。
 */
function commandOf(test: ReportCaseNode): {
  hasCommand: boolean
  text: string
  foldable: boolean
} {
  const raw = test.commandText
  if (raw === null || raw === undefined) {
    return { hasCommand: false, text: t('reports.command.notCollected'), foldable: false }
  }
  const trimmed = raw.trim()
  if (!trimmed) {
    return { hasCommand: false, text: t('reports.command.notCollected'), foldable: false }
  }
  if (trimmed.toUpperCase() === 'NONE') {
    const source = (test.commandSource ?? '').toUpperCase()
    const reason =
      !source || source === 'NONE'
        ? t('reports.command.reasonNoCommand')
        : t('reports.command.reasonSourceMismatch')
    return {
      hasCommand: false,
      text: t('reports.command.notCollectedReason', { reason }),
      foldable: false,
    }
  }
  return { hasCommand: true, text: trimmed, foldable: trimmed.length > FOLD_THRESHOLD }
}

function isCmdExpanded(caseKey: string): boolean {
  return cmdExpanded.value.has(caseKey)
}

function toggleCmd(caseKey: string): void {
  const next = new Set(cmdExpanded.value)
  if (next.has(caseKey)) {
    next.delete(caseKey)
  } else {
    next.add(caseKey)
  }
  cmdExpanded.value = next
}

async function copyCommand(test: ReportCaseNode): Promise<void> {
  const model = commandOf(test)
  if (!model.hasCommand) return
  const ok = await copyText(model.text)
  if (!ok) {
    copyFailed.value = t('reports.command.copyFailed')
    return
  }
  copyFailed.value = ''
  copied.value = test.caseKey
  window.setTimeout(() => {
    if (copied.value === test.caseKey) copied.value = ''
  }, 1500)
}

/** 来源标签：认不出来的来源不硬猜，原样显示后端给的值。 */
function sourceLabel(source: string | null): string {
  switch ((source ?? '').toUpperCase()) {
    case 'RF_KEYWORD':
      return t('reports.command.sourceRf')
    case 'PYTEST_MARK':
      return t('reports.command.sourceMark')
    // 后端常量是 STDOUT_EXTRACT；STDOUT 是早期别名，一并认下
    case 'STDOUT_EXTRACT':
    case 'STDOUT':
      return t('reports.command.sourceStdout')
    case 'COMMAND_INDEX':
      return t('reports.command.sourceCommandIndex')
    case 'NONE':
      return t('reports.command.sourceNone')
    default:
      return source ?? ''
  }
}
</script>

<template>
  <div class="suite" :class="{ nested: depth > 0 }">
    <div class="suite-head">
      <button class="tree-toggle" type="button" :data-suite-name="node.name" @click="toggleSuite">
        <span class="caret">{{ open ? '▾' : '▸' }}</span>
        <span class="suite-name">{{ node.name }}</span>
        <StatusTag :label="node.status" :tone="verdictTone(node.status)" />
      </button>
    </div>

    <div v-if="open" class="suite-body">
      <div v-for="test in node.cases" :key="test.caseKey" class="case-block">
        <div class="case-head">
          <button
            class="tree-toggle"
            type="button"
            :data-case-key="test.caseKey"
            @click="toggleCase(test.caseKey)"
          >
            <span class="caret">{{ expanded.has(test.caseKey) ? '▾' : '▸' }}</span>
            <span class="case-name">{{ test.name }}</span>
            <StatusTag :label="test.verdict" :tone="verdictTone(test.verdict)" />
            <!-- v5.6.0 / FR-v5.6-24：抖动是「通过但不稳定」，必须和干净通过区分开。
                 只标 FLAKY 不写轮次，人看不出到底重跑了几次。 -->
            <span
              v-if="test.flaky"
              class="case-flaky"
              data-test="case-flaky"
              :title="t('reports.flaky.hint', { rounds: test.retryCount })"
            >
              {{ t('reports.flaky.label') }} · {{ test.retryCount }}
            </span>
            <span class="muted">{{ test.durationMs }} ms</span>
          </button>
          <button class="link-btn" type="button" @click="emit('open-logs', test.caseKey)">
            {{ t('reports.viewLog') }}
          </button>
        </div>

        <div class="case-cmd" :data-command="commandOf(test).hasCommand ? 'present' : 'absent'">
          <span class="cmd-label">{{ t('reports.command.label') }}</span>
          <span v-if="commandOf(test).hasCommand" class="cmd-source">
            {{ sourceLabel(test.commandSource) }}
          </span>
          <code
            v-if="commandOf(test).hasCommand"
            class="cmd-text"
            :class="{ 'is-expanded': isCmdExpanded(test.caseKey) }"
          >{{ commandOf(test).text }}</code>
          <span v-else class="cmd-none">{{ commandOf(test).text }}</span>
          <span
            v-if="test.commandExitCode !== null && test.commandExitCode !== undefined"
            class="cmd-exit"
          >
            {{ t('reports.command.exitCode', { code: test.commandExitCode }) }}
          </span>
          <button
            v-if="commandOf(test).foldable"
            class="link-btn cmd-action"
            type="button"
            :data-action="isCmdExpanded(test.caseKey) ? 'collapse' : 'expand'"
            @click="toggleCmd(test.caseKey)"
          >
            {{
              isCmdExpanded(test.caseKey)
                ? t('reports.command.collapse')
                : t('reports.command.expand')
            }}
          </button>
          <button
            v-if="commandOf(test).hasCommand"
            class="link-btn cmd-action"
            type="button"
            data-action="copy"
            :aria-label="t('reports.command.copy')"
            @click="copyCommand(test)"
          >
            {{ copied === test.caseKey ? t('reports.command.copied') : t('reports.command.copy') }}
          </button>
          <button
            v-if="commandOf(test).hasCommand"
            class="link-btn cmd-action"
            type="button"
            data-action="locate-log"
            @click="emit('open-logs', test.caseKey)"
          >
            {{ t('reports.command.locateLog') }}
          </button>
        </div>

        <p v-if="test.failureMsg" class="text-fail case-failure">{{ test.failureMsg }}</p>

        <div v-if="expanded.has(test.caseKey)" class="steps">
          <KeywordSteps v-if="test.keywords.length" :nodes="test.keywords" />
          <p v-else class="muted">{{ t('reports.noSteps') }}</p>
        </div>
      </div>

      <SuiteTree
        v-for="child in node.suites"
        :key="child.name"
        :node="child"
        :depth="depth + 1"
        @open-logs="(caseKey: string) => emit('open-logs', caseKey)"
      />
    </div>

    <p v-if="copyFailed" class="cmd-copy-error" role="status">{{ copyFailed }}</p>
  </div>
</template>

<style scoped>
.suite.nested {
  margin-left: 18px;
  border-left: 1px dashed var(--border-strong);
  padding-left: 10px;
}

.suite-head,
.case-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.case-head {
  justify-content: space-between;
}

/*
 * 展开/收起整行。密度由这里的 21px 行高定死，别再换成库按钮（见 script 注释）。
 * transition 是守门硬要求：状态切换不能硬跳。
 */
.tree-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  padding: 2px 4px;
  text-align: left;
  transition:
    color 0.15s ease,
    background-color 0.15s ease;
}

.tree-toggle:hover {
  background: var(--surface-2);
}

.tree-toggle:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}

.caret {
  color: var(--text-muted);
  width: 12px;
}

.suite-name {
  font-weight: 600;
}

.suite-body {
  padding-left: 12px;
}

.case-block {
  padding: 2px 0 4px;
}

.case-name {
  font-size: 13px;
}

/* v5.6.0 / FR-v5.6-24：抖动徽标用 warn 色相——它是「需要注意」而不是「失败」 */
.case-flaky {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.case-failure {
  margin: 2px 0 6px 20px;
  white-space: pre-wrap;
  word-break: break-word;
}

.steps {
  margin: 4px 0 8px 20px;
  padding: 8px 10px;
  background: var(--surface-2);
  border-radius: 6px;
}

/*
 * ── 下发命令行（FR-v3.9-03）─────────────────────────────────────────
 * 单行排布：标签 / 来源 / 命令 / 退出码 / 操作。命令默认一行截断，
 * 展开后允许换行并按任意字符断行（命令里常没有空格可断）。
 */
.case-cmd {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  margin: 2px 0 4px 20px;
  font-size: 12px;
  line-height: 18px;
}

.cmd-label {
  color: var(--text-muted);
  flex: none;
}

.cmd-source {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 11px;
}

.cmd-text {
  flex: 1 1 320px;
  min-width: 0;
  display: block;
  padding: 1px 6px;
  background: var(--surface-2);
  border-radius: 4px;
  color: var(--text);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cmd-text.is-expanded {
  white-space: pre-wrap;
  word-break: break-all;
}

.cmd-none {
  flex: 1 1 auto;
  color: var(--text-muted);
  font-style: italic;
}

.cmd-exit {
  flex: none;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.cmd-action {
  flex: none;
}

.cmd-copy-error {
  margin: 2px 0 0 20px;
  font-size: 12px;
  color: var(--fail);
}
</style>
