<script setup lang="ts">
/**
 * 用户与权限 → 项目关联（v5.9.0 / FR-v5.9-04、05 / W59-15、17）。
 *
 * 这是**推荐写入口**：选一个项目，再把用户组或单个用户关联进来。
 * 「用户页看到所属组」「组页看到授权项目」「项目页看到成员与组」三处显示的数字
 * 全部来自 `/api/iam/relations`，所以不存在「在 A 页加完、B 页看不到」。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import DataTable from '@/components/DataTable.vue'
import type { DataTableColumns } from 'naive-ui'
// naive-ui 组件按需显式导入：项目没有全局注册，缺 import 时模板静默渲染成空标签。
import { NButton, NSelect } from 'naive-ui'
import { projectApi, userApi, type Project, type UserSummary } from '@/api/iam'
import { groupsApi, type GroupRow } from '@/api/groups'
import { iamRelationApi, type IamRelations, type RelationGroup, type RelationUser } from '@/api/iamRelations'
import { confirmAction } from '@/composables/useConfirm'

const { t } = useI18n()

const projects = ref<Project[]>([])
const groups = ref<GroupRow[]>([])
const users = ref<UserSummary[]>([])
const relation = ref<IamRelations | null>(null)
const selectedId = ref<number | null>(null)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const notice = ref('')

const groupToAdd = ref<number | null>(null)
const groupRole = ref('viewer')
const userToAdd = ref<number | null>(null)
const userRoles = ref<string[]>(['engineer'])

const roleOptions = computed(() =>
  ['viewer', 'engineer', 'pm', 'admin'].map((code) => ({ label: code.toUpperCase(), value: code })))
const projectOptions = computed(() =>
  projects.value.map((project) => ({ label: `${project.code} · ${project.name}`, value: project.id })))
const groupOptions = computed(() =>
  groups.value.map((group) => ({ label: group.name, value: group.id })))
const userOptions = computed(() =>
  users.value.map((user) => ({ label: `${user.displayName} (${user.username})`, value: user.id })))

const projectColumns = computed<DataTableColumns<Project>>(() => [
  { title: t('iam.links.code'), key: 'code', width: 160 },
  { title: t('iam.links.name'), key: 'name', minWidth: 180 },
  { title: t('iam.links.owner'), key: 'ownerId', width: 110,
    render: (row) => (row.ownerId == null ? '—' : `#${row.ownerId}`) },
])

const groupColumns = computed<DataTableColumns<RelationGroup>>(() => [
  { title: t('iam.groups.name'), key: 'name', minWidth: 180 },
  { title: t('iam.links.role'), key: 'roleCode', width: 120 },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 110,
    render: (row) => h('button', { class: 'btn-link danger', onClick: () => revokeGroup(row.id) },
      t('common.remove')),
  },
])

const userColumns = computed<DataTableColumns<RelationUser>>(() => [
  { title: t('iam.users.username'), key: 'username', minWidth: 140 },
  { title: t('iam.users.displayName'), key: 'displayName', minWidth: 140 },
  { title: t('iam.links.source'), key: 'source', width: 120 },
  { title: t('iam.links.role'), key: 'roleCodes', minWidth: 140,
    render: (row) => (row.roleCodes?.length ? row.roleCodes.join(', ') : '—') },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 110,
    render: (row) => h('button', { class: 'btn-link danger', onClick: () => removeUser(row.id) },
      t('common.remove')),
  },
])


async function loadBase(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    projects.value = await projectApi.list()
    groups.value = await groupsApi.groups()
    users.value = (await userApi.page({ page: 1, size: 200 })).items
    if (selectedId.value == null && projects.value.length > 0) selectedId.value = projects.value[0].id
    if (selectedId.value != null) relation.value = await iamRelationApi.of('project', selectedId.value)
  } catch (cause) {
    error.value = localizeError(cause)
  } finally {
    loading.value = false
  }
}

/** 模板表达式里不能写类型注解（vue-tsc 会当成 TS 语法错误），故在 script 里定义。 */
function projectRowProps(row: Project): Record<string, unknown> {
  return { style: 'cursor: pointer', onClick: () => pick(row) }
}

function projectRowClass(row: Project): string {
  return row.id === selectedId.value ? 'row-active' : ''
}

async function pick(project: Project): Promise<void> {
  selectedId.value = project.id
  relation.value = await iamRelationApi.of('project', project.id)
}

async function refresh(): Promise<void> {
  if (selectedId.value == null) return
  relation.value = await iamRelationApi.of('project', selectedId.value)
  projects.value = await projectApi.list()
}

async function grantGroup(): Promise<void> {
  if (selectedId.value == null || groupToAdd.value == null) return
  try {
    await groupsApi.grant(selectedId.value, groupToAdd.value, groupRole.value, 0)
    notice.value = t('iam.links.granted')
    groupToAdd.value = null
    await refresh()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function revokeGroup(groupId: number): Promise<void> {
  if (selectedId.value == null) return
  const ok = await confirmAction(t('iam.links.revokeConfirm'), { tone: 'danger' })
  if (!ok) return
  try {
    await groupsApi.revoke(groupId, selectedId.value)
    await refresh()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function addUser(): Promise<void> {
  if (selectedId.value == null || userToAdd.value == null) return
  try {
    // 幂等：重复添加后端视为成功跳过（FR-v5.9-06），不再 409。
    await projectApi.addMember(selectedId.value, { userId: userToAdd.value, roleCodes: userRoles.value })
    notice.value = t('iam.links.memberAdded')
    userToAdd.value = null
    await refresh()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function removeUser(userId: number): Promise<void> {
  if (selectedId.value == null) return
  const ok = await confirmAction(t('iam.links.removeMemberConfirm'), { tone: 'danger' })
  if (!ok) return
  try {
    await projectApi.removeMember(selectedId.value, userId)
    await refresh()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

onMounted(loadBase)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('iam.links.title') }}</h1>
      <p class="muted">{{ t('iam.links.subtitle') }}</p>
    </header>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div class="toolbar">
      <NSelect v-model:value="selectedId" :options="projectOptions" filterable style="width: 320px"
               :placeholder="t('iam.links.pickProject')" @update:value="refresh" />
      <NButton quaternary @click="loadBase">{{ t('common.refresh') }}</NButton>
    </div>

    <DataTable
      :columns="projectColumns"
      :data="projects"
      :loading="loading"
      :row-key="(row: Project) => row.id"
      :row-props="projectRowProps"
      :row-class-name="projectRowClass"
      :error="Boolean(error)"
      :empty-title="t('iam.links.empty')"
      empty-icon="boxes"
    />

    <section v-if="selectedId != null" class="dock" data-testid="iam-project-links">
      <h2>{{ t('iam.links.detailTitle') }}</h2>

      <div class="grid-2">
        <div>
          <h3>{{ t('iam.links.groups') }}
            <span class="muted">({{ relation?.counts.groups ?? 0 }})</span>
          </h3>
          <div class="bar">
            <NSelect v-model:value="groupToAdd" :options="groupOptions" filterable clearable
                     style="width: 180px" :placeholder="t('iam.links.pickGroup')" />
            <NSelect v-model:value="groupRole" :options="roleOptions" style="width: 120px" />
            <NButton size="small" @click="grantGroup">{{ t('common.add') }}</NButton>
          </div>
          <DataTable
            :columns="groupColumns"
            :data="relation?.groups ?? []"
            :row-key="(row) => row.id"
            :empty-title="t('iam.links.noGroup')"
            max-height="260"
          />
        </div>

        <div>
          <h3>{{ t('iam.links.people') }}
            <span class="muted">({{ relation?.counts.users ?? 0 }})</span>
          </h3>
          <div class="bar">
            <NSelect v-model:value="userToAdd" :options="userOptions" filterable clearable
                     style="width: 200px" :placeholder="t('iam.links.pickUser')" />
            <NSelect v-model:value="userRoles" multiple :options="roleOptions" style="width: 200px" />
            <NButton size="small" @click="addUser">{{ t('common.add') }}</NButton>
          </div>
          <DataTable
            :columns="userColumns"
            :data="relation?.users ?? []"
            :row-key="(row) => row.id"
            :empty-title="t('iam.links.noPeople')"
            max-height="260"
          />
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
.notice { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; margin: 0; }
.dock { border: 1px solid var(--border); border-radius: 8px; padding: 14px 16px; display: grid; gap: 12px; }
.dock h2 { margin: 0; font-size: 16px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.grid-2 h3 { margin: 0 0 8px; font-size: 14px; }
:deep(.btn-link) { background: none; border: 0; padding: 0; cursor: pointer; color: var(--fail); font-size: 13px; }
:deep(.row-active) { background: var(--surface-2, rgba(37, 99, 235, 0.08)); }
</style>
