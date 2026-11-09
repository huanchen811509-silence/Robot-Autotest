<script setup lang="ts">
/**
 * 编辑器宿主页（EDT-05 / EDT-09）。
 *
 * - 进入即问权限：`canWrite=false` 时以 `readonly` 挂载（界面禁用 + 写回闸门双保险）。
 * - `/viewer/:projectId` 与 `/editor/:projectId` 共用本页，前者强制只读，可独立路由访问。
 * - 保存走 `/api/editor/save`：带 `baseVersion` 乐观锁，内容未变不增版（FR-v0.3-19/20）。
 * - 版本历史（列表 / 回滚 / 两版 diff）来自版本快照，回滚生成新版本而非删历史（FR-v0.3-21/22）。
 * - 语法校验由服务端做（RF `--dryrun`）；服务端缺 RF 时返回 `syntaxChecked=false`，如实提示。
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { NButton } from 'naive-ui'
import '@robot-autotest/editor-css'
import {
  editorKeywordApi,
  editorPermissionApi,
  editorSaveApi,
  type DiffLine,
} from '@/api/editor'
import { fileApi, type RobotFileVersion } from '@/api/files'
import { ApiError } from '@/api/http'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { formatDateTime, EMPTY } from '@/utils/format'
import ErrorLineView from '@/components/ErrorLine.vue'

/**
 * 编辑器包按需加载（FR-3.2-06 路由块预算）：静态 import 会把 452 KB 的
 * `editor.js` 整个打进 EditorView 路由块（实测 gzip 247 KiB > 150 KiB）。
 * 改成异步组件后，路由块只剩页面壳；编辑器本身体积进入懒加载共享分包，
 * 由 `check_bundle_budget.py` 的 160 KiB 预算单独把关。
 *
 * <p>适配器注入是进程级单例：在 loader 里配置，保证组件渲染前已完成；
 * 编辑器的 `import()` 结果被 ESM 缓存，后续 `withEditor()` 复用同一模块。
 */
const RobotEditor = defineAsyncComponent(async () => {
  const editor = await import('@robot-autotest/editor')
  editor.configureEditorAdapter({
    searchKeywords: async (query: string, scopeId?: number) => {
      const keyword = query.trim()
      const rows = await editorKeywordApi.search({
        projectId: scopeId,
        q: keyword.length > 0 ? keyword : '*',
        limit: 50,
      })
      return {
        results: rows.map((row) => ({
          name: row.name,
          library: row.library,
          doc: row.doc ?? '',
          args: row.args,
        })),
      }
    },
  })
  return editor.RobotEditor
})

async function withEditor() {
  return await import('@robot-autotest/editor')
}

const route = useRoute()
const { t } = useI18n()
const projectId = computed(() => Number(route.params.projectId))
const filePath = computed(() => String(route.query.path ?? ''))
const forcedReadonly = computed(() => route.name === 'viewer')

const content = ref('')
const savedContent = ref('')
const currentVersion = ref(0)
const canWrite = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref<ErrorLine | null>(null)
const notice = ref<string | null>(null)
const noticeLevel = ref<'info' | 'warn' | 'error'>('info')

const versions = ref<RobotFileVersion[]>([])
const showVersions = ref(false)
const diffLines = ref<DiffLine[] | null>(null)
const diffTitle = ref('')

const readonly = computed(() => forcedReadonly.value || !canWrite.value)
const dirty = computed(() => content.value !== savedContent.value)
const roleLabel = computed(() => (canWrite.value ? t('editor.roleEditable') : t('editor.roleReadonly')))

function say(text: string, level: 'info' | 'warn' | 'error' = 'info'): void {
  notice.value = text
  noticeLevel.value = level
}

async function open(): Promise<void> {
  const pid = projectId.value
  const path = filePath.value
  if (!Number.isInteger(pid) || pid <= 0 || path.length === 0) {
    error.value = { summary: t('editor.missingRoute'), detail: null }
    return
  }
  loading.value = true
  error.value = null
  notice.value = null
  try {
    const permissions = await editorPermissionApi.get(pid)
    canWrite.value = permissions.canWrite
    const editor = await withEditor()
    editor.setEditorScope({ scopeId: pid, filePath: path })
    await reloadFile()
    if (canWrite.value) await loadVersions()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

async function reloadFile(): Promise<void> {
  const file = await fileApi.read(projectId.value, filePath.value)
  content.value = file.content
  savedContent.value = file.content
  currentVersion.value = file.currentVersion
}

async function loadVersions(): Promise<void> {
  try {
    versions.value = await fileApi.versions(projectId.value, filePath.value)
  } catch {
    versions.value = []
  }
}

async function save(): Promise<void> {
  if (readonly.value || saving.value) return
  saving.value = true
  notice.value = null
  try {
    const result = await editorSaveApi.save({
      projectId: projectId.value,
      path: filePath.value,
      content: content.value,
      baseVersion: currentVersion.value,
    })
    currentVersion.value = result.version
    savedContent.value = content.value
    const key = result.changed
      ? 'editor.savedAs'
      : result.syntaxChecked
        ? 'editor.unchanged'
        : 'editor.unchangedUnchecked'
    say(t(key, { version: result.version }), result.syntaxChecked ? 'info' : 'warn')
    await loadVersions()
  } catch (e) {
    // 409：他人已改，先刷新到最新，再让用户对比（不自动合并）
    if (e instanceof ApiError && e.codeName === 'CONFLICT') {
      await reloadFile()
      await loadVersions()
      say(t('editor.conflictRefreshed', { version: currentVersion.value }), 'warn')
    } else {
      error.value = localizeError(e)
    }
  } finally {
    saving.value = false
  }
}

async function rollback(versionNo: number): Promise<void> {
  if (readonly.value) return
  error.value = null
  try {
    const result = await editorSaveApi.rollback({
      projectId: projectId.value,
      path: filePath.value,
      targetVersion: versionNo,
    })
    currentVersion.value = result.version
    await reloadFile()
    await loadVersions()
    say(t('editor.rolledBack', { from: versionNo, to: result.version }), 'info')
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function showDiff(fromVersion: number): Promise<void> {
  error.value = null
  try {
    diffLines.value = await editorSaveApi.diff({
      projectId: projectId.value,
      path: filePath.value,
      from: fromVersion,
      to: currentVersion.value,
    })
    diffTitle.value = `v${fromVersion} → v${currentVersion.value}`
  } catch (e) {
    error.value = localizeError(e)
  }
}

watch([projectId, filePath], () => { void open() }, { immediate: true })
</script>

<template>
  <div class="editor-host">
    <header class="editor-host__bar">
      <h2 class="editor-host__title">
        {{ forcedReadonly ? t('editor.titleViewer') : t('editor.titleEditor') }}
      </h2>
      <span class="editor-host__path">{{ filePath }}</span>
      <span v-if="currentVersion > 0" class="editor-host__version">v{{ currentVersion }}</span>
      <span class="editor-host__role" :data-readonly="readonly">{{ roleLabel }}</span>
      <NButton
        v-if="!readonly"
        type="primary"
        size="small"
        data-testid="editor-save"
        :disabled="!dirty || saving"
        :loading="saving"
        @click="save"
      >
        {{ saving ? t('editor.saving') : t('common.save') }}
      </NButton>
      <NButton
        v-if="canWrite"
        size="small"
        data-testid="editor-versions"
        @click="showVersions = !showVersions"
      >
        {{ t('editor.versionsCount', { count: versions.length }) }}
      </NButton>
    </header>

    <p v-if="notice" class="editor-host__notice" :data-level="noticeLevel">{{ notice }}</p>
    <ErrorLineView v-if="error" :error="error" />
    <p v-else-if="loading" class="editor-host__loading">{{ t('common.loading') }}</p>
    <RobotEditor
      v-else
      class="editor-host__body"
      :content="content"
      :readonly="readonly"
      @update:content="content = $event"
    />

    <aside v-if="showVersions && canWrite" class="editor-host__versions" data-testid="version-list">
      <h3>{{ t('editor.versions') }}</h3>
      <p v-if="versions.length === 0" class="editor-host__versions-empty">
        {{ t('editor.noVersions') }}
      </p>
      <ul v-else>
        <li v-for="item in versions" :key="item.id" class="version-row">
          <span class="version-row__no">v{{ item.versionNo }}</span>
          <span class="version-row__time">{{ formatDateTime(item.createdAt) }}</span>
          <span class="version-row__comment">{{ item.comment || EMPTY }}</span>
          <NButton
            v-if="item.versionNo !== currentVersion"
            size="tiny"
            @click="showDiff(item.versionNo)"
          >
            {{ t('editor.diffCurrent') }}
          </NButton>
          <NButton
            v-if="item.versionNo !== currentVersion"
            size="tiny"
            @click="rollback(item.versionNo)"
          >
            {{ t('editor.rollback') }}
          </NButton>
        </li>
      </ul>
    </aside>

    <div v-if="diffLines" class="editor-host__modal" data-testid="diff-modal">
      <div class="editor-host__modal-body">
        <header class="editor-host__modal-bar">
          <h3>{{ t('editor.diffTitle', { title: diffTitle }) }}</h3>
          <NButton size="small" @click="diffLines = null">{{ t('common.close') }}</NButton>
        </header>
        <pre class="diff">
<span
  v-for="(line, index) in diffLines"
  :key="index"
  class="diff__line"
  :data-op="line.op"
>{{ line.op === 'ADD' ? '+' : line.op === 'REMOVE' ? '-' : ' ' }} {{ line.text }}
</span></pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-host {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  position: relative;
}

.editor-host__bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--border);
}

.editor-host__title {
  margin: 0;
  font-size: 1rem;
}

.editor-host__path {
  color: var(--text-muted);
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
}

.editor-host__version {
  padding: 0.1rem 0.45rem;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 0.8rem;
}

.editor-host__role {
  margin-left: auto;
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  background: var(--ok-soft);
  color: var(--ok);
  font-size: 0.8rem;
}

.editor-host__role[data-readonly='true'] {
  background: var(--warn-soft);
  color: var(--warn);
}

/*
 * `.editor-host__btn` / `--primary` / `--small` 已于 UX-04b 第七批退场：
 * 五个按钮全换 `NButton`（保存 primary + loading、版本开关与 diff 弹窗关闭 small、
 * 版本行的两个操作 tiny）。原生那份既没有 hover / focus-visible，也用 `rem` 而不是
 * 项目 token —— 换成库按钮后三态由 `naiveTheme.ts` 的派生主题承担。
 */
.editor-host__body {
  flex: 1;
  min-height: 0;
}

.editor-host__notice,
.editor-host__error,
.editor-host__loading {
  margin: 0;
  padding: 0.5rem 1rem;
  font-size: 0.85rem;
}

.editor-host__notice[data-level='info'] {
  color: var(--ok);
  background: var(--ok-soft);
}

.editor-host__notice[data-level='warn'] {
  color: var(--warn);
  background: var(--warn-soft);
}

.editor-host__notice[data-level='error'],
.editor-host__error {
  color: var(--fail);
}

.editor-host__versions {
  position: absolute;
  top: 3rem;
  right: 1rem;
  width: 26rem;
  max-height: 60%;
  overflow: auto;
  padding: 0.75rem;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: var(--panel);
  box-shadow: var(--shadow);
  font-size: 0.8rem;
}

.editor-host__versions h3 {
  margin: 0 0 0.5rem;
  font-size: 0.9rem;
}

.editor-host__versions ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0;
  border-bottom: 1px dashed var(--border);
}

.version-row__no {
  min-width: 2.5rem;
  font-family: ui-monospace, monospace;
  color: var(--accent);
}

.version-row__time {
  color: var(--text-muted);
  white-space: nowrap;
}

.version-row__comment {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-host__modal {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--overlay);
}

.editor-host__modal-body {
  display: flex;
  flex-direction: column;
  width: min(72rem, 92%);
  max-height: 84%;
  border-radius: 10px;
  background: var(--panel);
  overflow: hidden;
}

.editor-host__modal-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--border);
}

.editor-host__modal-bar h3 {
  margin: 0;
  font-size: 0.9rem;
}

.diff {
  margin: 0;
  padding: 0.5rem 0.75rem;
  overflow: auto;
  font-family: ui-monospace, monospace;
  font-size: 0.8rem;
  white-space: pre-wrap;
}

.diff__line {
  display: block;
}

.diff__line[data-op='ADD'] {
  background: var(--ok-soft);
  color: var(--ok);
}

.diff__line[data-op='REMOVE'] {
  background: var(--fail-soft);
  color: var(--fail);
}

.editor-host__versions-empty {
  margin: 0;
  color: var(--text-muted);
}
</style>
