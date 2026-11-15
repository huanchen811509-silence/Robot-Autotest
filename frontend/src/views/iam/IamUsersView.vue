<script setup lang="ts">
/**
 * 用户与权限 → 用户（v5.9.0 / FR-v5.9-04、05 / W59-15、17）。
 *
 * 三件事必须同时成立，否则这一页就是「又一个用户表格」：
 *   1. 列表本身可增删改查（T1~T4 通则）；
 *   2. 选中一行 → **在当前列表下方**展开详情（R6：不在页面最底部打开）；
 *   3. 详情里的「所属组 / 参与项目」读 `/api/iam/relations`，与组页、项目关联页同源。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DataTableColumns } from 'naive-ui'
// naive-ui 组件按需显式导入：项目没有全局注册，缺 import 时模板静默渲染成空标签。
import { NButton, NInput, NSelect } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'
import { roleApi, userApi, type Role, type UserSummary } from '@/api/iam'
import { iamRelationApi, type IamRelations } from '@/api/iamRelations'
import { confirmAction } from '@/composables/useConfirm'

const { t } = useI18n()

const rows = ref<UserSummary[]>([])
const roles = ref<Role[]>([])
const loading = ref(false)
const error = ref<ErrorLine | null>(null)
const notice = ref('')
const keyword = ref('')

const selected = ref<UserSummary | null>(null)
const relation = ref<IamRelations | null>(null)
const relationLoading = ref(false)

const showForm = ref(false)
const editing = ref<UserSummary | null>(null)
const form = ref({ username: '', displayName: '', email: '', password: '', roleCodes: [] as string[] })

const roleOptions = computed(() => roles.value.map((role) => ({ label: role.name, value: role.code })))

const columns = computed<DataTableColumns<UserSummary>>(() => [
  { title: t('iam.users.username'), key: 'username', minWidth: 140 },
  { title: t('iam.users.displayName'), key: 'displayName', minWidth: 140 },
  { title: t('iam.users.email'), key: 'email', minWidth: 180, render: (row) => row.email ?? '—' },
  {
    title: t('iam.users.globalRoles'),
    key: 'roles',
    minWidth: 140,
    render: (row) => (row.roles.length ? row.roles.join(', ') : '—'),
  },
  {
    title: t('iam.users.enabled'),
    key: 'enabled',
    width: 100,
    render: (row) => (row.enabled ? t('common.yes') : t('common.no')),
  },
  {
    title: t('common.actions'),
    key: 'actions',
    width: 190,
    render: (row) => h('span', { class: 'row-actions' }, [
      h('button', { class: 'btn-link', onClick: withStop(() => openDetail(row)) }, t('common.detail')),
      h('button', { class: 'btn-link', onClick: withStop(() => startEdit(row)) }, t('common.edit')),
      h('button', { class: 'btn-link danger', onClick: withStop(() => remove(row)) }, t('common.delete')),
    ]),
  },
])


async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const page = await userApi.page({ keyword: keyword.value || undefined, page: 1, size: 100 })
    rows.value = page.items
    if (roles.value.length === 0) roles.value = await roleApi.list()
    if (selected.value) {
      const fresh = rows.value.find((row) => row.id === selected.value?.id) ?? null
      selected.value = fresh
      if (fresh) await openDetail(fresh)
    }
  } catch (cause) {
    error.value = localizeError(cause)
  } finally {
    loading.value = false
  }
}

async function openDetail(row: UserSummary): Promise<void> {
  selected.value = row
  relationLoading.value = true
  try {
    relation.value = await iamRelationApi.of('user', row.id)
  } catch (cause) {
    error.value = localizeError(cause)
    relation.value = null
  } finally {
    relationLoading.value = false
  }
}

/** 行内按钮必须阻断冒泡：否则点「删除」会先打开详情，再弹确认框。 */
function withStop(action: () => void) {
  return (event: MouseEvent) => {
    event.stopPropagation()
    action()
  }
}

/** DataTable 没有 row-click 事件，用 rowProps 注入点击；行内按钮自己 stopPropagation。 */
function rowProps(row: UserSummary): Record<string, unknown> {
  return { style: 'cursor: pointer', onClick: () => openDetail(row) }
}

function startCreate(): void {
  editing.value = null
  form.value = { username: '', displayName: '', email: '', password: '', roleCodes: [] }
  showForm.value = true
}

function startEdit(row: UserSummary): void {
  editing.value = row
  form.value = {
    username: row.username,
    displayName: row.displayName,
    email: row.email ?? '',
    password: '',
    roleCodes: [...row.roles],
  }
  showForm.value = true
}

function roleIdsOf(codes: string[]): number[] {
  return roles.value.filter((role) => codes.includes(role.code)).map((role) => role.id)
}

async function submit(): Promise<void> {
  notice.value = ''
  try {
    if (editing.value) {
      await userApi.update(editing.value.id, {
        displayName: form.value.displayName,
        email: form.value.email || undefined,
        enabled: editing.value.enabled,
        roleIds: roleIdsOf(form.value.roleCodes),
      })
    } else {
      await userApi.create({
        username: form.value.username,
        displayName: form.value.displayName,
        email: form.value.email || undefined,
        password: form.value.password,
        roleIds: roleIdsOf(form.value.roleCodes),
      })
    }
    showForm.value = false
    notice.value = t('iam.saved')
    await load()
  } catch (cause) {
    error.value = localizeError(cause)
  }
}

async function remove(row: UserSummary): Promise<void> {
  const ok = await confirmAction(t('iam.users.deleteConfirm', { name: row.username }), { tone: 'danger' })
  if (!ok) return
  try {
    await userApi.remove(row.id)
    if (selected.value?.id === row.id) {
      selected.value = null
      relation.value = null
    }
    notice.value = t('iam.deleted')
    await load()
  } catch (cause) {
    // T4：删除被引用时后端给 409 并点名引用方，这里原样呈现，不吞成「操作失败」。
    error.value = localizeError(cause)
  }
}

onMounted(load)

/** 仅测试用：锁死「界面数字 = 关系聚合数字」与删除确认路径。 */
defineExpose({ openDetail, load, remove })
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('iam.users.title') }}</h1>
      <p class="muted">{{ t('iam.users.subtitle') }}</p>
    </header>

    <ErrorLineView v-if="error" :error="error" />
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div class="toolbar">
      <NInput
        v-model:value="keyword"
        clearable
        style="width: 220px"
        :placeholder="t('common.searchPlaceholder')"
        @keyup.enter="load"
      />
      <NButton @click="load">{{ t('common.search') }}</NButton>
      <NButton type="primary" @click="startCreate">{{ t('common.create') }}</NButton>
    </div>

    <form v-if="showForm" class="inline-form" @submit.prevent="submit">
      <NInput v-model:value="form.username" :disabled="Boolean(editing)" style="width: 140px"
              :placeholder="t('iam.users.username')" />
      <NInput v-model:value="form.displayName" style="width: 140px" :placeholder="t('iam.users.displayName')" />
      <NInput v-model:value="form.email" style="width: 200px" :placeholder="t('iam.users.email')" />
      <NInput v-if="!editing" v-model:value="form.password" type="password" show-password-on="click"
              style="width: 200px" :placeholder="t('iam.users.password')" autocomplete="new-password" />
      <NSelect v-model:value="form.roleCodes" multiple :options="roleOptions" style="min-width: 220px"
               :placeholder="t('iam.users.globalRoles')" />
      <NButton type="primary" attr-type="submit">{{ t('common.save') }}</NButton>
      <NButton quaternary @click="showForm = false">{{ t('common.cancel') }}</NButton>
    </form>

    <DataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: UserSummary) => row.id"
      :error="Boolean(error)"
      :empty-title="t('iam.users.empty')"
      empty-icon="users"
      :row-props="rowProps"
    />

    <!-- R6：详情紧跟列表下方，不在页面最底部 -->
    <section v-if="selected" class="dock" data-testid="iam-user-detail">
      <div class="dock-head">
        <h2>{{ selected.displayName }} <span class="muted">({{ selected.username }})</span></h2>
        <NButton quaternary @click="selected = null; relation = null">{{ t('common.close') }}</NButton>
      </div>

      <p class="muted">{{ t('iam.users.detailHint') }}</p>

      <div class="grid-2">
        <div>
          <h3>{{ t('iam.users.groups') }}</h3>
          <p class="count">
            {{ t('iam.users.countGroups', { n: relation?.counts.groups ?? 0 }) }}
          </p>
          <ul class="plain-list">
            <li v-for="group in relation?.groups ?? []" :key="group.id">{{ group.name }}</li>
            <li v-if="!relationLoading && (relation?.groups.length ?? 0) === 0" class="muted">
              {{ t('iam.users.noGroup') }}
            </li>
          </ul>
        </div>
        <div>
          <h3>{{ t('iam.users.projects') }}</h3>
          <p class="count">
            {{ t('iam.users.countProjects', { n: relation?.counts.projects ?? 0 }) }}
          </p>
          <ul class="plain-list">
            <li v-for="project in relation?.projects ?? []" :key="project.id">
              {{ project.code }} · {{ project.name }}
              <span class="tag">{{ project.source }}</span>
              <span v-if="project.roleCodes?.length" class="muted"> [{{ project.roleCodes.join(',') }}]</span>
            </li>
            <li v-if="!relationLoading && (relation?.projects.length ?? 0) === 0" class="muted">
              {{ t('iam.users.noProject') }}
            </li>
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
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.inline-form { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.notice { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; margin: 0; }
.dock { border: 1px solid var(--border); border-radius: 8px; padding: 14px 16px; display: grid; gap: 10px; }
.dock-head { display: flex; justify-content: space-between; align-items: center; }
.dock-head h2 { margin: 0; font-size: 16px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1.4fr; gap: 20px; }
.grid-2 h3 { margin: 0 0 6px; font-size: 14px; }
.count { margin: 0 0 6px; font-size: 13px; color: var(--text-muted); }
.plain-list { margin: 0; padding-left: 18px; display: grid; gap: 4px; font-size: 13px; }
.tag { font-size: 11px; border: 1px solid var(--border); border-radius: 4px; padding: 0 4px; margin-left: 6px; }
:deep(.row-actions) { display: flex; gap: 10px; }
:deep(.btn-link) { background: none; border: 0; padding: 0; cursor: pointer; color: var(--accent); font-size: 13px; }
:deep(.btn-link.danger) { color: var(--fail); }
</style>
