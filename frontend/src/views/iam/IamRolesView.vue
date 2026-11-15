<script setup lang="ts">
/**
 * 用户与权限 → 角色管理（v5.9.0 / FR-v5.9-09 / W59-19）。
 *
 * 内置 4 个角色（ADMIN/PM/ENGINEER/VIEWER）**只读**：它们的权限矩阵写在
 * `PermissionMatrix.java` 里，从界面改会与代码判权分叉。所以编辑/删除按钮禁用并带 title 说明，
 * 不做成「点了没反应」。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
// naive-ui 组件按需显式导入：项目没有全局注册，缺 import 时模板静默渲染成空标签。
import { NButton, NInput } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { roleApi, type Role } from '@/api/iam'
import { confirmAction } from '@/composables/useConfirm'

const { t } = useI18n()

const rows = ref<Role[]>([])
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const notice = ref('')

const showForm = ref(false)
const editing = ref<Role | null>(null)
const form = ref({ code: '', name: '', description: '' })

const columns = computed<DataTableColumns<Role>>(() => [
  { title: t('iam.roles.code'), key: 'code', width: 150 },
  { title: t('iam.roles.name'), key: 'name', minWidth: 140 },
  { title: t('iam.roles.description'), key: 'description', minWidth: 240,
    render: (row) => row.description ?? '—' },
  {
    title: t('iam.roles.builtIn'),
    key: 'builtIn',
    width: 120,
    render: (row) => (row.builtIn ? t('iam.roles.readonlyTag') : t('iam.roles.customTag')),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 200,
    render: (row) => h('span', { class: 'row-actions' }, [
      h('button', {
        class: 'btn-link',
        disabled: row.builtIn,
        title: row.builtIn ? t('iam.roles.builtInHint') : undefined,
        onClick: () => startEdit(row),
      }, t('common.edit')),
      h('button', {
        class: 'btn-link danger',
        disabled: row.builtIn,
        title: row.builtIn ? t('iam.roles.builtInHint') : undefined,
        onClick: () => remove(row),
      }, t('common.delete')),
    ]),
  },
])

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    rows.value = await roleApi.list()
  } catch (cause) {
    error.value = localizeError(cause)
  } finally {
    loading.value = false
  }
}

function startCreate(): void {
  editing.value = null
  form.value = { code: '', name: '', description: '' }
  showForm.value = true
}

function startEdit(row: Role): void {
  if (row.builtIn) return
  editing.value = row
  form.value = { code: row.code, name: row.name, description: row.description ?? '' }
  showForm.value = true
}

async function submit(): Promise<void> {
  if (!form.value.code.trim() || !form.value.name.trim()) return
  try {
    const body = {
      code: form.value.code.trim().toUpperCase(),
      name: form.value.name.trim(),
      description: form.value.description.trim() || null,
    }
    if (editing.value) {
      await roleApi.update(editing.value.id, body)
    } else {
      await roleApi.create(body)
    }
    showForm.value = false
    notice.value = t('iam.saved')
    await load()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function remove(row: Role): Promise<void> {
  const ok = await confirmAction(t('iam.roles.deleteConfirm', { name: row.name }), { tone: 'danger' })
  if (!ok) return
  try {
    await roleApi.remove(row.id)
    notice.value = t('iam.deleted')
    await load()
  } catch (cause) {
    // 被用户引用时后端 409 并给出引用数——原样显示，不吞成「删除失败」。
    error.value = localizeError(cause)
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('iam.roles.title') }}</h1>
      <p class="muted">{{ t('iam.roles.subtitle') }}</p>
    </header>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div class="toolbar">
      <NButton type="primary" @click="startCreate">{{ t('common.create') }}</NButton>
      <NButton quaternary @click="load">{{ t('common.refresh') }}</NButton>
    </div>

    <form v-if="showForm" class="inline-form" @submit.prevent="submit">
      <NInput v-model:value="form.code" :disabled="Boolean(editing)" style="width: 150px"
              :placeholder="t('iam.roles.code')" />
      <NInput v-model:value="form.name" style="width: 180px" :placeholder="t('iam.roles.name')" />
      <NInput v-model:value="form.description" style="width: 260px"
              :placeholder="t('iam.roles.description')" />
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
      <NButton quaternary @click="showForm = false">{{ t('common.cancel') }}</NButton>
    </form>

    <DataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: Role) => row.id"
      :error="Boolean(error)"
      :empty-title="t('iam.roles.empty')"
      empty-icon="audit"
    />
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 16px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: var(--text-muted); font-size: 13px; }
.toolbar { display: flex; gap: 8px; align-items: center; }
.inline-form { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.notice { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; margin: 0; }
:deep(.row-actions) { display: flex; gap: 10px; }
:deep(.btn-link) { background: none; border: 0; padding: 0; cursor: pointer; color: var(--link, #2563eb); font-size: 13px; }
:deep(.btn-link[disabled]) { color: var(--text-muted); cursor: not-allowed; }
:deep(.btn-link.danger) { color: var(--danger, #dc2626); }
:deep(.btn-link.danger[disabled]) { color: var(--text-muted); }
</style>
