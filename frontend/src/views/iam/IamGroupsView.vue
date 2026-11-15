<script setup lang="ts">
/**
 * 用户与权限 → 用户组（v5.9.0 / FR-v5.9-04、05、06 / W59-15、17）。
 *
 * 「已授权项目 N 个」这个数字**不能**自己 count：它读 `/api/iam/relations?dimension=group`，
 * 与用户页、项目关联页同源。点开后的列表条数必须等于这个数字（判据 3）。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
// naive-ui 组件按需显式导入：项目没有全局注册，缺 import 时模板静默渲染成空标签。
import { NButton, NInput, NSelect } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { groupsApi, type GroupRow } from '@/api/groups'
import { projectApi, userApi, type Project, type UserSummary } from '@/api/iam'
import { iamRelationApi, type IamRelations } from '@/api/iamRelations'
import { confirmAction } from '@/composables/useConfirm'

const { t } = useI18n()

const rows = ref<GroupRow[]>([])
const users = ref<UserSummary[]>([])
const projects = ref<Project[]>([])
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const notice = ref('')

const selected = ref<GroupRow | null>(null)
const relation = ref<IamRelations | null>(null)

const showForm = ref(false)
const editing = ref<GroupRow | null>(null)
const form = ref({ name: '', description: '' })
const memberToAdd = ref<number | null>(null)
const projectToGrant = ref<number | null>(null)
const grantRole = ref('viewer')

const userOptions = computed(() =>
  users.value.map((user) => ({ label: `${user.displayName} (${user.username})`, value: user.id })))
const projectOptions = computed(() =>
  projects.value.map((project) => ({ label: `${project.code} · ${project.name}`, value: project.id })))
const roleOptions = computed(() =>
  ['viewer', 'engineer', 'pm', 'admin'].map((code) => ({ label: code.toUpperCase(), value: code })))

const columns = computed<DataTableColumns<GroupRow>>(() => [
  { title: t('iam.groups.name'), key: 'name', minWidth: 160 },
  { title: t('iam.groups.description'), key: 'description', minWidth: 220,
    render: (row) => row.description ?? '—' },
  { title: t('iam.groups.members'), key: 'memberCount', width: 110 },
  { title: t('iam.groups.projects'), key: 'projectCount', width: 130 },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 200,
    render: (row) => h('span', { class: 'row-actions' }, [
      h('button', { class: 'btn-link', onClick: withStop(() => openDetail(row)) }, t('common.detail')),
      h('button', { class: 'btn-link', onClick: withStop(() => startEdit(row)) }, t('common.edit')),
      h('button', { class: 'btn-link danger', onClick: withStop(() => remove(row)) }, t('common.delete')),
    ]),
  },
])

function withStop(action: () => void) {
  return (event: MouseEvent) => {
    event.stopPropagation()
    action()
  }
}

function rowProps(row: GroupRow): Record<string, unknown> {
  return { style: 'cursor: pointer', onClick: () => openDetail(row) }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    rows.value = await groupsApi.groups()
    if (users.value.length === 0) users.value = (await userApi.page({ page: 1, size: 200 })).items
    if (projects.value.length === 0) projects.value = await projectApi.list()
    if (selected.value) {
      const fresh = rows.value.find((row) => row.id === selected.value?.id) ?? null
      selected.value = fresh
      relation.value = fresh ? await iamRelationApi.of('group', fresh.id) : null
    }
  } catch (cause) {
    error.value = localizeError(cause)
  } finally {
    loading.value = false
  }
}

async function openDetail(row: GroupRow): Promise<void> {
  selected.value = row
  try {
    relation.value = await iamRelationApi.of('group', row.id)
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

function startCreate(): void {
  editing.value = null
  form.value = { name: '', description: '' }
  showForm.value = true
}

function startEdit(row: GroupRow): void {
  editing.value = row
  form.value = { name: row.name, description: row.description ?? '' }
  showForm.value = true
}

async function submit(): Promise<void> {
  if (!form.value.name.trim()) return
  try {
    if (editing.value) {
      await groupsApi.rename(editing.value.id, {
        name: form.value.name.trim(),
        description: form.value.description.trim(),
      })
    } else {
      await groupsApi.create(form.value.name.trim(), form.value.description.trim())
    }
    showForm.value = false
    notice.value = t('iam.saved')
    await load()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function remove(row: GroupRow): Promise<void> {
  const ok = await confirmAction(t('iam.groups.deleteConfirm', { name: row.name }), { tone: 'danger' })
  if (!ok) return
  try {
    await groupsApi.remove(row.id)
    if (selected.value?.id === row.id) {
      selected.value = null
      relation.value = null
    }
    notice.value = t('iam.deleted')
    await load()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function addMember(): Promise<void> {
  if (!selected.value || !memberToAdd.value) return
  try {
    const result = await groupsApi.addMember(selected.value.id, memberToAdd.value)
    // FR-v5.9-06：重复添加是 200，不是 409，这里据此提示「已存在」而不是报错。
    notice.value = result.added
      ? t('iam.groups.memberAdded')
      : t('iam.groups.memberSkipped')
    memberToAdd.value = null
    await load()
    await openDetail(selected.value)
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function removeMember(userId: number): Promise<void> {
  if (!selected.value) return
  const ok = await confirmAction(t('iam.groups.removeMemberConfirm'))
  if (!ok) return
  try {
    await groupsApi.removeMember(selected.value.id, userId)
    await load()
    await openDetail(selected.value)
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function grant(): Promise<void> {
  if (!selected.value || !projectToGrant.value) return
  try {
    // 组的授权写入用既有端点（幂等 ON DUPLICATE KEY）；userId 只用于返回有效角色，传 0 即可。
    await groupsApi.grant(projectToGrant.value, selected.value.id, grantRole.value, 0)
    notice.value = t('iam.groups.granted')
    projectToGrant.value = null
    await load()
    await openDetail(selected.value)
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function revoke(projectId: number): Promise<void> {
  if (!selected.value) return
  const ok = await confirmAction(t('iam.groups.revokeConfirm'), { tone: 'danger' })
  if (!ok) return
  try {
    await groupsApi.revoke(selected.value.id, projectId)
    await load()
    await openDetail(selected.value)
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

onMounted(load)

/** 仅测试用：锁死「界面数字 = 关系聚合数字」与删除确认路径。 */
defineExpose({ openDetail, load, addMember, grant, remove })
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('iam.groups.title') }}</h1>
      <p class="muted">{{ t('iam.groups.subtitle') }}</p>
    </header>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div class="toolbar">
      <NButton type="primary" @click="startCreate">{{ t('common.create') }}</NButton>
      <NButton quaternary @click="load">{{ t('common.refresh') }}</NButton>
    </div>

    <form v-if="showForm" class="inline-form" @submit.prevent="submit">
      <NInput v-model:value="form.name" style="width: 180px" :placeholder="t('iam.groups.name')" />
      <NInput v-model:value="form.description" style="width: 260px"
              :placeholder="t('iam.groups.description')" />
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
      <NButton quaternary @click="showForm = false">{{ t('common.cancel') }}</NButton>
    </form>

    <DataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: GroupRow) => row.id"
      :error="Boolean(error)"
      :empty-title="t('iam.groups.empty')"
      empty-icon="users"
      :row-props="rowProps"
    />

    <section v-if="selected" class="dock" data-testid="iam-group-detail">
      <div class="dock-head">
        <h2>{{ selected.name }}</h2>
        <NButton quaternary @click="selected = null; relation = null">{{ t('common.close') }}</NButton>
      </div>

      <div class="grid-2">
        <div>
          <h3>{{ t('iam.groups.members') }}
            <span class="muted">({{ relation?.counts.users ?? 0 }})</span>
          </h3>
          <div class="bar">
            <NSelect v-model:value="memberToAdd" :options="userOptions" filterable clearable
                     style="width: 220px" :placeholder="t('iam.groups.pickUser')" />
            <NButton size="small" @click="addMember">{{ t('common.add') }}</NButton>
          </div>
          <ul class="plain-list">
            <li v-for="member in relation?.users ?? []" :key="member.id">
              {{ member.displayName }} · {{ member.username }}
              <button class="btn-link danger" @click="removeMember(member.id)">
                {{ t('common.remove') }}
              </button>
            </li>
            <li v-if="(relation?.users.length ?? 0) === 0" class="muted">{{ t('iam.groups.noMember') }}</li>
          </ul>
        </div>

        <div>
          <h3>{{ t('iam.groups.projects') }}
            <span class="muted">({{ relation?.counts.projects ?? 0 }})</span>
          </h3>
          <div class="bar">
            <NSelect v-model:value="projectToGrant" :options="projectOptions" filterable clearable
                     style="width: 220px" :placeholder="t('iam.groups.pickProject')" />
            <NSelect v-model:value="grantRole" :options="roleOptions" style="width: 130px" />
            <NButton size="small" @click="grant">{{ t('iam.groups.grant') }}</NButton>
          </div>
          <ul class="plain-list">
            <li v-for="project in relation?.projects ?? []" :key="project.id">
              {{ project.code }} · {{ project.name }}
              <span class="tag">{{ project.roleCode }}</span>
              <button class="btn-link danger" @click="revoke(project.id)">{{ t('common.remove') }}</button>
            </li>
            <li v-if="(relation?.projects.length ?? 0) === 0" class="muted">{{ t('iam.groups.noProject') }}</li>
          </ul>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 16px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: var(--text-muted); font-size: 13px; }
.toolbar, .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.inline-form { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.notice { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; margin: 0; }
.dock { border: 1px solid var(--border); border-radius: 8px; padding: 14px 16px; display: grid; gap: 10px; }
.dock-head { display: flex; justify-content: space-between; align-items: center; }
.dock-head h2 { margin: 0; font-size: 16px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.grid-2 h3 { margin: 0 0 8px; font-size: 14px; }
.plain-list { margin: 8px 0 0; padding-left: 18px; display: grid; gap: 6px; font-size: 13px; }
.tag { font-size: 11px; border: 1px solid var(--border); border-radius: 4px; padding: 0 4px; margin: 0 6px; }
:deep(.row-actions) { display: flex; gap: 10px; }
.btn-link { background: none; border: 0; padding: 0; cursor: pointer; color: var(--link, #2563eb); font-size: 13px; }
.btn-link.danger { color: var(--danger, #dc2626); }
</style>
