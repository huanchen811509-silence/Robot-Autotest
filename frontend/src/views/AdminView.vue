<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  NButton,
  NCheckbox,
  NInput,
  NPagination,
  NSelect,
  type DataTableColumns,
} from 'naive-ui'
import {
  roleApi,
  userApi,
  projectApi,
  type Project,
  type ProjectMember,
  type Role,
  type UserSummary,
} from '@/api/iam'
import { useAuthStore } from '@/stores/auth'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import StatusTag from '@/components/StatusTag.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { EMPTY } from '@/utils/format'
import { batchApi } from '@/api/batch'
import SelectionBar from '@/components/SelectionBar.vue'
import { useBatchAction } from '@/composables/useBatchAction'
import { useSelection } from '@/composables/useSelection'
import { confirmAction } from '@/composables/useConfirm'
import type { StatusTone } from '@/utils/statusLabels'

const { t } = useI18n()
const auth = useAuthStore()

const users = ref<UserSummary[]>([])
const roles = ref<Role[]>([])
const keyword = ref('')
const page = ref(1)
const size = 10
const total = ref(0)
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const message = ref('')
const showUserForm = ref(false)
const showProjectForm = ref(false)

const userForm = reactive({
  username: '',
  displayName: '',
  email: '',
  password: '',
  roleId: null as number | null,
})

const projectForm = reactive({ code: '', name: '', description: '', visibility: 'private', testKernel: 'robot' })

/** 项目内角色只有这四个值（与后端 RoleCode 同值）；与全局角色是两套口径。 */
const PROJECT_ROLES = ['admin', 'pm', 'engineer', 'viewer']

const memberProjectId = ref<number | null>(null)
const members = ref<ProjectMember[]>([])
const memberLoading = ref(false)
const candidates = ref<UserSummary[]>([])
const memberForm = reactive({ userId: null as number | null, roleCode: 'viewer' })

const selectedProject = computed(
  () => auth.projects.find((p) => p.id === memberProjectId.value) ?? null,
)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))

const roleOptions = computed(() =>
  roles.value.map((role) => ({ label: `${role.code} · ${role.name}`, value: role.id })))

const projectRoleOptions = computed(() =>
  PROJECT_ROLES.map((code) => ({ label: roleLabel(code), value: code })))

const projectOptions = computed(() =>
  auth.projects.map((project) => ({ label: `${project.code} · ${project.name}`, value: project.id })))

/** 测试内核选项：robot（Robot Framework，默认）/ pytest。 */
const kernelOptions = computed(() => [
  { label: t('workbench.kernelRobot'), value: 'robot' },
  { label: t('workbench.kernelPytest'), value: 'pytest' },
])

/**
 * 候选人下拉保留「已停用」标注：把停用用户混进来又不标，是管理员最常见的误操作来源。
 * NSelect 不支持在 label 里放多段样式，所以直接在文案里拼。
 */
const candidateOptions = computed(() =>
  candidates.value.map((user) => ({
    label: user.enabled
      ? `${user.username} · ${user.displayName ?? ''}`.trim()
      : `${user.username} · ${user.displayName ?? ''} (${t('admin.disabledUser')})`.trim(),
    value: user.id,
  })))

// ---- UX-02b 用户批量：与资产页复用同一选择模型 / 执行器 / 批量条 ----
const pageUserIds = computed<number[]>(() => users.value.map((u) => u.id))
const userSelection = useSelection(pageUserIds)
const {
  count: userSelectedCount,
  crossPageCount: userCrossPage,
  pageAllSelected: userPageAll,
  pageIndeterminate: userPageIndeterminate,
  isSelected: userIsSelected,
  toggle: userToggle,
  togglePage: userTogglePage,
  clear: clearUserSelection,
} = userSelection

type UserBatchAction = 'delete' | 'setEnabled'
const pendingUserAction = ref<{ action: UserBatchAction; enabled?: boolean }>({
  action: 'setEnabled', enabled: false,
})

const userBatch = useBatchAction({
  run: (ids) => batchApi.run(auth.projectId ?? 0, {
    resource: 'USER',
    action: pendingUserAction.value.action,
    ids,
    enabled: pendingUserAction.value.enabled,
  }),
  reload: loadUsers,
  onError: (e) => { error.value = localizeError(e) },
})

async function runUserBatch(action: UserBatchAction, enabled?: boolean): Promise<void> {
  const confirmMessage = action === 'delete'
    ? t('batch.confirmDeleteUsers')
    : enabled
      ? t('batch.confirmEnableUsers')
      : t('batch.confirmDisableUsers')
  // 删除是破坏性操作用 danger；启用/停用是状态变更，用默认语气
  const confirmOptions = action === 'delete' ? { tone: 'danger' as const } : undefined
  if (!(await confirmAction(confirmMessage, confirmOptions))) return
  error.value = null
  pendingUserAction.value = { action, enabled }
  await userBatch.execute([...userSelection.selectedIds.value], userSelection)
}

/** 「启用 / 停用」是布尔事实：启用是 ok，停用只是一条中性事实，不是告警。 */
function enabledTone(value: boolean): StatusTone {
  return value ? 'ok' : 'neutral'
}

/**
 * 勾选列自渲染 `NCheckbox`（不用内置 `type: 'selection'`：内置列只认
 * `checked-row-keys`，数据刷新会丢掉不在当前页的键，与跨页保留契约冲突）。
 */
function userSelectionColumn(): DataTableColumns<UserSummary>[number] {
  return {
    key: 'selection',
    width: 36,
    align: 'center',
    title: () =>
      h(NCheckbox, {
        checked: userPageAll.value,
        indeterminate: userPageIndeterminate.value,
        'aria-label': t('cases.batch.barLabel'),
        onUpdateChecked: () => userTogglePage(),
      }),
    render: (row) =>
      h(NCheckbox, {
        checked: userIsSelected(row.id),
        'aria-label': `${t('cases.batch.barLabel')}: ${row.username}`,
        onUpdateChecked: () => userToggle(row.id),
      }),
  }
}

const userColumns = computed<DataTableColumns<UserSummary>>(() => [
  userSelectionColumn(),
  { title: t('admin.username'), key: 'username', minWidth: 140, ellipsis: { tooltip: true } },
  {
    title: t('admin.displayName'),
    key: 'displayName',
    minWidth: 140,
    render: (row) => row.displayName || EMPTY,
  },
  {
    title: t('admin.email'),
    key: 'email',
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: (row) => row.email || EMPTY,
  },
  {
    title: t('admin.roles'),
    key: 'roles',
    minWidth: 150,
    render: (row) => row.roles.join(', ') || EMPTY,
  },
  {
    title: t('admin.enabled'),
    key: 'enabled',
    width: 110,
    render: (row) =>
      h(StatusTag, {
        label: row.enabled ? t('common.yes') : t('common.no'),
        tone: enabledTone(row.enabled),
      }),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 110,
    render: (row) =>
      h(
        NButton,
        {
          text: true,
          type: row.enabled ? 'error' : 'primary',
          size: 'small',
          onClick: () => void toggleUser(row),
        },
        { default: () => (row.enabled ? t('admin.disable') : t('admin.enable')) },
      ),
  },
])

const projectColumns = computed<DataTableColumns<Project>>(() => [
  { title: t('admin.projectCode'), key: 'code', width: 160, ellipsis: { tooltip: true } },
  { title: t('admin.projectName'), key: 'name', minWidth: 180, ellipsis: { tooltip: true } },
  {
    title: t('admin.description'),
    key: 'description',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: (row) => row.description || EMPTY,
  },
  {
    title: t('admin.enabled'),
    key: 'enabled',
    width: 110,
    render: (row) =>
      h(StatusTag, {
        label: row.enabled ? t('common.yes') : t('common.no'),
        tone: enabledTone(row.enabled),
      }),
  },
])

const memberColumns = computed<DataTableColumns<ProjectMember>>(() => [
  { title: t('admin.username'), key: 'username', minWidth: 140, ellipsis: { tooltip: true } },
  {
    title: t('admin.displayName'),
    key: 'displayName',
    minWidth: 140,
    render: (row) => row.displayName || EMPTY,
  },
  {
    title: t('admin.memberRole'),
    key: 'roleCode',
    width: 200,
    // 所有者不允许改角色（后端也会拒），所以直接显示只读文本，不给一个会失败的下拉
    render: (row) =>
      isOwner(row)
        ? h('span', { class: 'muted' }, `${roleLabel(row.roleCode)} · ${t('admin.owner')}`)
        : h(NSelect, {
          value: row.roleCode,
          options: projectRoleOptions.value,
          size: 'small',
          'aria-label': t('admin.memberRole'),
          'onUpdate:value': (value: string) => void changeMemberRole(row, value),
        }),
  },
  {
    title: t('admin.memberStatus'),
    key: 'enabled',
    width: 130,
    render: (row) =>
      row.enabled
        ? h(StatusTag, { label: t('common.yes'), tone: 'ok' as StatusTone })
        : h('span', { class: 'muted' }, t('admin.disabledUser')),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 130,
    render: (row) =>
      isOwner(row)
        ? h('span', { class: 'muted' }, t('admin.ownerLocked'))
        : h(
          NButton,
          {
            text: true,
            type: 'error',
            size: 'small',
            onClick: () => void removeMember(row),
          },
          { default: () => t('admin.removeMember') },
        ),
  },
])

const roleColumns = computed<DataTableColumns<Role>>(() => [
  { title: t('admin.roleCode'), key: 'code', width: 160, ellipsis: { tooltip: true } },
  { title: t('admin.roleName'), key: 'name', minWidth: 160, ellipsis: { tooltip: true } },
  {
    title: t('admin.description'),
    key: 'description',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render: (row) => row.description || EMPTY,
  },
  {
    title: t('admin.builtIn'),
    key: 'builtIn',
    width: 110,
    render: (row) =>
      h(StatusTag, {
        label: row.builtIn ? t('common.yes') : t('common.no'),
        tone: enabledTone(row.builtIn),
      }),
  },
])

async function loadUsers(): Promise<void> {
  loading.value = true
  try {
    const result = await userApi.page({ keyword: keyword.value, page: page.value, size })
    users.value = result.items
    total.value = result.total
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    roles.value = await roleApi.list()
  } catch (e) {
    error.value = localizeError(e)
  }
  await loadUsers()
  if (auth.projects.length === 0) {
    try {
      await auth.loadProjects()
    } catch (e) {
      error.value = localizeError(e)
    }
  }
  try {
    candidates.value = (await userApi.page({ page: 1, size: 100 })).items
  } catch (e) {
    error.value = localizeError(e)
  }
  if (memberProjectId.value === null && auth.projects.length > 0) {
    memberProjectId.value = auth.projects[0].id
    await loadMembers()
  }
})

function turnPage(next: number): void {
  if (next < 1 || next > pageCount.value) return
  page.value = next
  void loadUsers()
}

async function createUser(): Promise<void> {
  error.value = null
  message.value = ''
  if (!userForm.username.trim() || !userForm.displayName.trim() || !userForm.password) {
    error.value = { summary: t('admin.requiredUser'), detail: null }
    return
  }
  try {
    await userApi.create({
      username: userForm.username.trim(),
      displayName: userForm.displayName.trim(),
      email: userForm.email,
      password: userForm.password,
      roleIds: userForm.roleId ? [userForm.roleId] : [],
    })
    message.value = t('common.saved')
    userForm.username = ''
    userForm.displayName = ''
    userForm.email = ''
    userForm.password = ''
    showUserForm.value = false
    await loadUsers()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function toggleUser(user: UserSummary): Promise<void> {
  try {
    const roleIds = roles.value
      .filter((r) => user.roles.includes(r.code))
      .map((r) => r.id)
    await userApi.update(user.id, {
      displayName: user.displayName,
      email: user.email ?? '',
      enabled: !user.enabled,
      roleIds,
    })
    await loadUsers()
  } catch (e) {
    error.value = localizeError(e)
  }
}

async function createProject(): Promise<void> {
  error.value = null
  message.value = ''
  if (!projectForm.code.trim() || !projectForm.name.trim()) {
    error.value = { summary: t('admin.requiredProject'), detail: null }
    return
  }
  try {
    await projectApi.create({
      code: projectForm.code.trim(),
      name: projectForm.name.trim(),
      description: projectForm.description,
      visibility: projectForm.visibility,
      testKernel: projectForm.testKernel,
    })
    await auth.loadProjects()
    message.value = t('common.saved')
    projectForm.code = ''
    projectForm.name = ''
    projectForm.description = ''
    projectForm.testKernel = 'robot'
    showProjectForm.value = false
  } catch (e) {
    error.value = localizeError(e)
  }
}

function roleLabel(code: string): string {
  return t(`admin.projectRole.${code}`)
}

function isOwner(member: ProjectMember): boolean {
  return selectedProject.value?.ownerId === member.userId
}

async function loadMembers(): Promise<void> {
  if (memberProjectId.value === null) {
    members.value = []
    return
  }
  memberLoading.value = true
  error.value = null
  try {
    members.value = await projectApi.members(memberProjectId.value)
  } catch (e) {
    error.value = localizeError(e)
    members.value = []
  } finally {
    memberLoading.value = false
  }
}

/** 换项目下拉直接打后端：`memberProjectId` 变了就必须重拉成员，不能只改显示值。 */
async function changeMemberProject(value: number | null): Promise<void> {
  memberProjectId.value = value
  await loadMembers()
}

async function addMember(): Promise<void> {
  error.value = null
  message.value = ''
  if (memberProjectId.value === null || memberForm.userId === null) {
    error.value = { summary: t('admin.memberRequired'), detail: null }
    return
  }
  try {
    await projectApi.addMember(memberProjectId.value, {
      userId: memberForm.userId,
      roleCode: memberForm.roleCode,
    })
    message.value = t('common.saved')
    memberForm.userId = null
    await loadMembers()
  } catch (e) {
    error.value = localizeError(e)
  }
}

/** 改角色失败时仍重载列表：把下拉框还原成服务端的真实值，不留下「看起来改了」的假象。 */
async function changeMemberRole(member: ProjectMember, roleCode: string): Promise<void> {
  error.value = null
  message.value = ''
  try {
    await projectApi.updateMember(member.projectId, member.userId, roleCode)
    message.value = t('common.saved')
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    await loadMembers()
  }
}

async function removeMember(member: ProjectMember): Promise<void> {
  error.value = null
  message.value = ''
  try {
    await projectApi.removeMember(member.projectId, member.userId)
    message.value = t('common.saved')
    await loadMembers()
  } catch (e) {
    error.value = localizeError(e)
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">{{ t('menu.adminUsers') }}</h1>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="message" class="text-ok">{{ message }}</p>

    <section class="card wide-card">
      <div class="section-head">
        <h2 class="card-title">{{ t('admin.users') }}</h2>
        <div class="toolbar">
          <NInput
            v-model:value="keyword"
            clearable
            style="width: 180px"
            :placeholder="t('common.searchPlaceholder')"
            :aria-label="t('common.searchPlaceholder')"
            @keyup.enter="loadUsers"
          />
          <NButton :type="showUserForm ? 'default' : 'primary'" @click="showUserForm = !showUserForm">
            {{ showUserForm ? t('common.cancel') : t('common.create') }}
          </NButton>
        </div>
      </div>

      <form v-if="showUserForm" class="inline-form" @submit.prevent="createUser">
        <NInput v-model:value="userForm.username" style="width: 140px" :placeholder="t('admin.username')" />
        <NInput v-model:value="userForm.displayName" style="width: 140px" :placeholder="t('admin.displayName')" />
        <NInput v-model:value="userForm.email" style="width: 180px" :placeholder="t('admin.email')" />
        <NInput
          v-model:value="userForm.password"
          type="password"
          show-password-on="click"
          style="width: 180px"
          :placeholder="t('admin.password')"
          autocomplete="new-password"
        />
        <NSelect
          v-model:value="userForm.roleId"
          :options="roleOptions"
          clearable
          style="width: 180px"
          :placeholder="t('admin.noRole')"
          :aria-label="t('admin.roles')"
        />
        <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
      </form>
      <p class="muted">{{ t('admin.passwordHint') }}</p>

      <SelectionBar
        v-if="userSelectedCount > 0"
        :count="userSelectedCount"
        :cross-page="userCrossPage"
        :result="userBatch.result.value"
        :loading="userBatch.loading.value"
        @clear="clearUserSelection(); userBatch.clear()"
      >
        <NButton quaternary :disabled="userBatch.loading.value" @click="runUserBatch('setEnabled', false)">
          {{ t('batch.disableUsers') }}
        </NButton>
        <NButton quaternary :disabled="userBatch.loading.value" @click="runUserBatch('setEnabled', true)">
          {{ t('batch.enableUsers') }}
        </NButton>
        <NButton quaternary type="error" :disabled="userBatch.loading.value" @click="runUserBatch('delete')">
          {{ t('batch.deleteUsers') }}
        </NButton>
      </SelectionBar>

      <DataTable
        :columns="userColumns"
        :data="users"
        :loading="loading"
        :row-key="(row: UserSummary) => row.id"
        :empty-title="t('common.noData')"
        empty-icon="users"
        :error="Boolean(error)"
        :empty-illustration="keyword ? 'no-result' : 'no-data'"
      />

      <NPagination
        v-if="total > 0"
        class="pager"
        :page="page"
        :page-count="pageCount"
        :page-slot="7"
        @update:page="turnPage"
      />
    </section>

    <section class="card wide-card">
      <div class="section-head">
        <h2 class="card-title">{{ t('admin.projects') }}</h2>
        <NButton :type="showProjectForm ? 'default' : 'primary'" @click="showProjectForm = !showProjectForm">
          {{ showProjectForm ? t('common.cancel') : t('common.create') }}
        </NButton>
      </div>

      <form v-if="showProjectForm" class="inline-form" @submit.prevent="createProject">
        <NInput v-model:value="projectForm.code" style="width: 140px" :placeholder="t('admin.projectCode')" />
        <NInput v-model:value="projectForm.name" style="width: 180px" :placeholder="t('admin.projectName')" />
        <NInput v-model:value="projectForm.description" style="width: 220px" :placeholder="t('admin.description')" />
        <NSelect
          v-model:value="projectForm.testKernel"
          style="width: 180px"
          :options="kernelOptions"
          :aria-label="t('admin.projectKernel')"
        />
        <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
      </form>

      <DataTable
        :columns="projectColumns"
        :data="auth.projects"
        :row-key="(row: Project) => row.id"
        :empty-title="t('common.noData')"
        :empty-description="t('project.emptyHint')"
        empty-icon="boxes"
        :error="Boolean(error)"
      />
    </section>

    <section class="card wide-card">
      <div class="section-head">
        <h2 class="card-title">{{ t('admin.projectMembers') }}</h2>
        <NSelect
          :value="memberProjectId"
          :options="projectOptions"
          style="width: 220px"
          :placeholder="t('admin.selectProject')"
          :aria-label="t('admin.selectProject')"
          @update:value="(value: number | null) => void changeMemberProject(value)"
        />
      </div>

      <template v-if="auth.projects.length > 0">
        <form class="inline-form" @submit.prevent="addMember">
          <NSelect
            v-model:value="memberForm.userId"
            :options="candidateOptions"
            filterable
            style="width: 220px"
            :placeholder="t('admin.selectUser')"
            :aria-label="t('admin.selectUser')"
          />
          <NSelect
            v-model:value="memberForm.roleCode"
            :options="projectRoleOptions"
            style="width: 140px"
            :aria-label="t('admin.memberRole')"
          />
          <NButton type="primary" attr-type="submit">{{ t('admin.addMember') }}</NButton>
        </form>
        <p class="muted">{{ t('admin.ownerHint') }}</p>

        <DataTable
          :columns="memberColumns"
          :data="members"
          :loading="memberLoading"
          :row-key="(row: ProjectMember) => row.userId"
          :empty-title="t('admin.noMembers')"
          empty-icon="users"
          :error="Boolean(error)"
        />
      </template>
      <p v-else class="muted">{{ t('project.emptyHint') }}</p>
    </section>

    <section class="card wide-card">
      <h2 class="card-title">{{ t('admin.roles') }}</h2>
      <DataTable
        :columns="roleColumns"
        :data="roles"
        :row-key="(row: Role) => row.id"
        :empty-title="t('common.noData')"
        empty-icon="audit"
        :error="Boolean(error)"
      />
    </section>
  </div>
</template>

<style scoped>
.wide-card {
  margin-bottom: var(--space-4);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.section-head .card-title {
  margin-bottom: 0;
}
</style>
