<script setup lang="ts">
/**
 * 创建项目向导（FR-v4.1-04 / v5.10.0）：模板 → 基本信息 → 运行设置 → 成员 → 用户组 →
 * 内容与资产 → 确认。
 *
 * 四条约束：
 * 1. 模板只做默认值，不写库、不预置用例；创建后仍可在项目管理里改。
 * 2. 无 `user:write` 能力时自动跳过成员与用户组两步，不把越权入口摆出来。
 * 3. **前六步只在前端持有状态，第七步一次提交**（v5.10.0 / FR-v5.10-10 方案 A）：
 *    走完不提交 → 库里零新增；提交失败 → 各步状态保留可重试。
 * 4. 成员添加失败不回滚项目本身：项目已创建是事实，失败项逐条列出（后端 composite
 *    也按同一口径把这些失败放进 warnings，而不是整体 500）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { projectApi, userApi, type UserSummary } from '@/api/iam'
import { groupsApi, type GroupRow } from '@/api/groups'
import { credentialOptionsApi, type CredentialOption } from '@/api/credentialOptions'
import { projectCardsApi } from '@/api/projectCards'
import { templateApi } from '@/api/globalTemplates'
import { capabilitiesForRole, roleFromCodes } from '@/config/navigation'
import { PROJECT_TEMPLATES } from '@/config/projectTemplates'
import { engineCode, nextProjectCode, yearMonth } from '@/config/projectCode'
import ErrorLineView from '@/components/ErrorLine.vue'
import WizardContentPicker from '@/components/WizardContentPicker.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

/** W57-12：全局向导（`/g/projects/new` 或 `?from=global`）创建成功后回 `/g/projects`。 */
const isGlobal = computed(
  () => route.query.from === 'global' || (typeof route.path === 'string' && route.path.startsWith('/g/')),
)

const ALL_STEPS = [
  'template',
  'basics',
  'runtime',
  'members',
  'groups',
  'content',
  'review',
] as const
type StepKey = (typeof ALL_STEPS)[number]

const step = ref(0)
const selectedKey = ref('')
const busy = ref(false)
const message = ref('')
const error = ref<ErrorLine | null>(null)
const created = ref<number | null>(null)
const warnings = ref<string[]>([])

const form = reactive({
  code: '',
  name: '',
  description: '',
  testKernel: 'robot',
  visibility: 'private',
  /** 负责人（FR-v5.10-04）：必填，默认当前用户；写 project.owner_id 且同事务补成员行。 */
  ownerId: null as number | null,
  /** 演示项目标记（FR-v5.10-04）：默认关，只写 project.is_demo，不改权限。 */
  demo: false,
})

/**
 * 用户组授权（v5.10.0 / FR-v5.10-02）：组 + 该组在本项目的角色。
 *
 * <p>这里不发明新的授权语义，提交后由后端走 `GroupService.grant`（与
 * `POST /groups/grant` 同一个方法、同一张 `project_group_role` 表）。
 * 步内显示「该组已有 N 名成员」——操作人得知道自己实际授给了多少人。
 */
const groupRows = ref<GroupRow[]>([])
const groupsLoading = ref(false)
const groupPicks = ref<{ groupId: number; roleCode: string; name: string; memberCount: number }[]>([])

/**
 * 资产凭证内联绑定（v5.10.0 / FR-v5.10-03）：每台选中设备两个地址面各一个下拉。
 *
 * <p>候选来自 `GET /api/global/assets/{id}/credentials/options` —— 与资产页「凭据选择器」
 * 同一个端点、同一套排序（本设备成功 > 同型号成功 > 已配置 > 其它），不另造匹配逻辑。
 * 空串表示不绑定（null=不动），与后端 `bindDeviceCredentials` 的哨兵语义一致。
 */
const credOptions = ref<Record<number, CredentialOption[]>>({})
const credPicks = ref<Record<number, { mgmt: string; bmc: string }>>({})

const memberRoles = ref<string[]>(['engineer'])
const memberUserId = ref<number | null>(null)
const picked = ref<{ userId: number; roleCodes: string[]; username: string }[]>([])
const users = ref<UserSummary[]>([])
const usersLoading = ref(false)

/**
 * 内容步（W57-12/13）：四类来源（模板 / 用例集 / 用例 / 测试资产）交给 WizardContentPicker。
 * 选择状态放在向导顶层 ref —— content 步被 v-if 销毁再回来（返回上一步）也不丢选择。
 */
interface ContentSelection {
  templateKeys: string[]
  setIds: number[]
  sets: { id: number; name: string }[]
  caseIds: number[]
  assetIds: number[]
}
const contentSelection = ref<ContentSelection>({
  templateKeys: [],
  setIds: [],
  sets: [],
  caseIds: [],
  assetIds: [],
})
/** 兼容既有引用：review / 存为模板 / 创建提交都读这几份派生视图。 */
const selectedCaseIds = computed(() => contentSelection.value.caseIds)
const selectedAssetIds = computed(() => contentSelection.value.assetIds)
const selectedSetIds = computed(() => contentSelection.value.setIds)
/** 提交时后端报「集合已删」的并发冲突，交给内容步指名显示、供用户移除。 */
const contentConflicts = ref<{ id: number; name: string }[]>([])

/**
 * 项目编码自动生成（v4.4 需求 1）。
 *
 * <p>建议值 = 模板前缀 + 三位序号；用户改过就不再用建议值覆盖。
 * 序号来源是本账号可见项目的编码集合——私有项目不可见时会漏号，
 * 因此建议值只是默认值，重复仍由后端 409 拒绝并原样提示。
 */
const projectCodes = ref<string[]>([])
const autoCode = ref('')
let codesLoaded = false

async function ensureCodes(): Promise<void> {
  if (codesLoaded) return
  codesLoaded = true
  try {
    projectCodes.value = (await projectCardsApi.list()).map((card) => card.code)
  } catch {
    // 取不到列表不影响创建：退化成 001 起，重复时后端会拒绝
    projectCodes.value = []
  }
}

/** 编码建议优先问服务端（FR-v4.4-07）：私有项目不可见也不会撞号。 */
async function regenerateCode(): Promise<void> {
  const tpl = selected.value
  if (!tpl) return
  try {
    const result = await projectApi.nextCode(tpl.codePrefix, form.testKernel || tpl.testKernel)
    form.code = result.code
  } catch {
    // 服务端不可用时退化为本地推算；重复仍由创建接口 409 拒绝
    form.code = nextProjectCode(
      tpl.codePrefix,
      form.testKernel || tpl.testKernel,
      projectCodes.value,
      form.code,
    )
  }
  autoCode.value = form.code
}

/** 编码示例跟着「前缀 + 引擎 + 当前年月」走，用户在输入框里能看到目标格式。 */
const codeSample = computed(() => {
  const tpl = selected.value
  if (!tpl) return ''
  return `${tpl.codePrefix}-${engineCode(form.testKernel || tpl.testKernel)}-${yearMonth()}-001`
})

const capabilities = computed(() => capabilitiesForRole(roleFromCodes(auth.user?.roles)))
const canManageMembers = computed(() => capabilities.value.includes('user:write'))
const steps = computed<StepKey[]>(() =>
  canManageMembers.value
    ? ALL_STEPS.slice()
    : ALL_STEPS.filter((key) => key !== 'members' && key !== 'groups'),
)
const current = computed<StepKey>(() => steps.value[Math.min(step.value, steps.value.length - 1)])
const selected = computed(() => PROJECT_TEMPLATES.find((tpl) => tpl.key === selectedKey.value))

const codePattern = /^[A-Za-z0-9._-]{2,40}$/

/** 复核步显示负责人姓名（而不是 id）：id 对人没有意义。 */
const ownerName = computed(() => {
  if (!form.ownerId) return t('projectWizard.ownerPlaceholder')
  const hit = users.value.find((user) => user.id === form.ownerId)
  if (hit) return hit.displayName || hit.username
  if (form.ownerId === auth.user?.id) return auth.user?.displayName || auth.user?.username || ''
  return String(form.ownerId)
})
const availableUsers = computed(() =>
  users.value.filter((user) => !picked.value.some((member) => member.userId === user.id)),
)

function selectTemplate(key: string): void {
  selectedKey.value = key
  const tpl = PROJECT_TEMPLATES.find((item) => item.key === key)
  if (!tpl) return
  form.testKernel = tpl.testKernel
  form.visibility = tpl.visibility
}

function stepProblem(): string | null {
  if (current.value === 'template') return selectedKey.value ? null : t('projectWizard.noTemplate')
  if (current.value === 'basics') {
    if (!form.code.trim()) return t('projectWizard.codeRequired')
    if (!codePattern.test(form.code.trim())) return t('projectWizard.codeInvalid')
    if (!form.name.trim()) return t('projectWizard.nameRequired')
  }
  // 负责人必填：前端先拦一道，服务端 `create(... ownerId ...)` 也会 404/400 兜住。
  if (current.value === 'runtime' && !form.ownerId) return t('projectWizard.ownerRequired')
  return null
}

function goNext(): void {
  message.value = ''
  const problem = stepProblem()
  if (problem) {
    message.value = problem
    return
  }
  if (step.value < steps.value.length - 1) step.value += 1
}

function goBack(): void {
  message.value = ''
  if (step.value > 0) step.value -= 1
}

async function loadUsers(): Promise<void> {
  if (users.value.length || usersLoading.value) return
  usersLoading.value = true
  try {
    const page = await userApi.page({ page: 1, size: 100 })
    users.value = page.items.filter((user) => user.enabled)
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    usersLoading.value = false
  }
}

// 资产是在内容步里才被选中的（进入该步时还是空的），所以除了进入步骤时拉一次，
// 选择集变化也要补拉——否则用户勾完设备看不到凭证下拉。
watch(selectedAssetIds, () => {
  if (current.value === "content") void loadCredentialOptions()
})

watch(current, async (key) => {
  if (key === 'basics') {
    await ensureCodes()
    // 只在「没填过」或「还是上一次的自动建议」时覆盖，避免抹掉用户手写的编码
    if (!form.code.trim() || form.code === autoCode.value) void regenerateCode()
  }
  if (key === 'members') void loadUsers()
  if (key === 'groups') void loadGroups()
  if (key === 'content') void loadCredentialOptions()
})

// 改内核（robot ↔ pytest）会改编码里的引擎段：只有编码还是自动建议时才跟着换
watch(
  () => form.testKernel,
  () => {
    if (form.code === autoCode.value) void regenerateCode()
  },
)

onMounted(() => {
  void loadUsers()
  // 负责人默认当前用户（FR-v5.10-04）：用户不必为了建项目先去下拉里找自己一次。
  form.ownerId = auth.user?.id ?? null
})

async function loadGroups(): Promise<void> {
  if (groupRows.value.length || groupsLoading.value) return
  groupsLoading.value = true
  try {
    groupRows.value = await groupsApi.groups()
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    groupsLoading.value = false
  }
}

function groupPicked(groupId: number): boolean {
  return groupPicks.value.some((item) => item.groupId === groupId)
}

function toggleGroup(group: GroupRow): void {
  if (groupPicked(group.id)) {
    groupPicks.value = groupPicks.value.filter((item) => item.groupId !== group.id)
    return
  }
  groupPicks.value.push({
    groupId: group.id,
    roleCode: 'viewer',
    name: group.name,
    memberCount: group.memberCount ?? 0,
  })
}

function setGroupRole(groupId: number, roleCode: string): void {
  const hit = groupPicks.value.find((item) => item.groupId === groupId)
  if (hit) hit.roleCode = roleCode
}

/** 内容步里选中的设备 → 逐个拉凭据候选（懒加载，已拉过不重复请求）。 */
async function loadCredentialOptions(): Promise<void> {
  for (const assetId of selectedAssetIds.value) {
    if (credOptions.value[assetId]) continue
    try {
      const page = await credentialOptionsApi.options(assetId)
      credOptions.value = { ...credOptions.value, [assetId]: page.items }
      if (!credPicks.value[assetId]) {
        credPicks.value = { ...credPicks.value, [assetId]: { mgmt: '', bmc: '' } }
      }
    } catch {
      // 单台设备拉不到候选不影响其它设备：留空即「不绑定」
      credOptions.value = { ...credOptions.value, [assetId]: [] }
    }
  }
}

function setCredential(assetId: number, plane: 'mgmt' | 'bmc', value: string): void {
  const current = credPicks.value[assetId] ?? { mgmt: '', bmc: '' }
  credPicks.value = { ...credPicks.value, [assetId]: { ...current, [plane]: value } }
}

function addMember(): void {
  const user = users.value.find((item) => item.id === memberUserId.value)
  if (!user) {
    message.value = t('projectWizard.memberUserRequired')
    return
  }
  if (memberRoles.value.length === 0) {
    message.value = t('projectWizard.memberRoleRequired')
    return
  }
  if (picked.value.some((member) => member.userId === user.id)) {
    message.value = t('projectWizard.memberDuplicate')
    return
  }
  picked.value.push({
    userId: user.id,
    roleCodes: [...memberRoles.value],
    username: user.displayName || user.username,
  })
  memberUserId.value = null
  memberRoles.value = ['engineer']
  message.value = ''
}

function removeMember(userId: number): void {
  picked.value = picked.value.filter((member) => member.userId !== userId)
}

async function createProject(): Promise<void> {
  message.value = ''
  error.value = null
  busy.value = true
  warnings.value = []
  contentConflicts.value = []
  try {
    // 一次提交（FR-v5.10-10 方案 A）：项目 / 成员 / 组授权 / 内容 / 资产 / 凭证在服务端
    // 一个事务里写完。任何一环失败前端都不会看到「项目建了但成员没进」这种半成品。
    const result = await projectApi.composite({
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      visibility: form.visibility,
      testKernel: form.testKernel,
      ownerId: form.ownerId,
      demo: form.demo,
      members: picked.value.map((member) => ({
        userId: member.userId,
        roleCodes: member.roleCodes,
      })),
      groups: groupPicks.value.map((item) => ({
        groupId: item.groupId,
        roleCode: item.roleCode,
      })),
      // 凭证：空串 = 不绑定（后端把 null 当「不动」、0 当「清空」，这里只表达两个状态）
      assets: selectedAssetIds.value.map((assetId) => {
        const pick = credPicks.value[assetId]
        return {
          assetType: 'DEVICE',
          assetId,
          managementCredentialId: pick && pick.mgmt ? Number(pick.mgmt) : null,
          bmcCredentialId: pick && pick.bmc ? Number(pick.bmc) : null,
        }
      }),
      caseIds: [...new Set(selectedCaseIds.value)],
      caseSetIds: [...new Set(selectedSetIds.value)],
      setName: t('projectWizard.contentSetName'),
    })
    warnings.value = result.warnings
    await auth.loadProjects()
    auth.setProject(result.projectId)
    created.value = result.projectId
    message.value = t('projectWizard.created')
  } catch (e) {
    const reason = localizeError(e).summary
    // 集合已删的并发错误要「指名」：按名称 / id 从已选集合里定位，交内容步显示移除入口。
    const missing = contentSelection.value.sets.filter(
      (set) => reason.includes(set.name) || reason.includes(String(set.id)),
    )
    if (missing.length > 0) {
      contentConflicts.value = missing
      warnings.value.push(
        t('w57.projectWizard.contentConflict', {
          names: missing.map((set) => set.name).join('、'),
        }),
      )
    }
    error.value = localizeError(e)
  } finally {
    busy.value = false
  }
}

function openWorkspace(): void {
  void router.push('/workspace')
}

function openWorkbench(): void {
  // W57-12：全局向导创建成功后回全局项目列表，而非项目模式工作台。
  void router.push(isGlobal.value ? '/g/projects' : '/workbench')
}

// ---------------------------------------------------------------- 存为模板（FR-49-25）
// 项目创建成功后提供入口：确认弹层列出将收纳的用例 / 用例集 / 资产数与 reference 语义，
// 再调既有 templateApi.saveAsTemplate；模板以引用收纳，不复制用例正文。
const saveTemplateOpen = ref(false)
const saveTemplateName = ref('')
const saveTemplateBusy = ref(false)
const saveTemplateResult = ref('')
const saveTemplateError = ref('')
/** 内容步会把选中的用例装进 1 个用例集；没选用例则 0 个。 */
const caseSetCount = computed(() => (selectedCaseIds.value.length ? 1 : 0))

function openSaveTemplate(): void {
  saveTemplateOpen.value = true
  saveTemplateName.value = `${form.name || form.code}-template`
  saveTemplateResult.value = ''
  saveTemplateError.value = ''
}

async function confirmSaveTemplate(): Promise<void> {
  if (!created.value || !saveTemplateName.value.trim()) return
  saveTemplateBusy.value = true
  saveTemplateError.value = ''
  try {
    const row = await templateApi.saveAsTemplate(created.value, saveTemplateName.value.trim())
    saveTemplateResult.value = t("saveTemplate.done", { name: row.name })
    saveTemplateOpen.value = false
  } catch (e) {
    saveTemplateError.value = localizeError(e).summary
  } finally {
    saveTemplateBusy.value = false
  }
}
</script>

<template>
  <div class="project-wizard">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ t('projectWizard.title') }}</h1>
        <p class="page-subtitle">{{ t('projectWizard.subtitle') }}</p>
      </div>
      <button type="button" class="btn" data-testid="wizard-back" @click="openWorkbench">
        {{ isGlobal ? t('w57.projectWizard.backToList') : t('projectWizard.toWorkbench') }}
      </button>
    </div>

    <div v-if="created" class="card wizard-result" role="status">
      <h2 class="card-title">{{ t('projectWizard.created') }}</h2>
      <p class="wizard-hint">{{ t('projectWizard.createdHint') }}</p>
      <div v-if="warnings.length" class="wizard-warnings" role="status">
        <p v-for="line in warnings" :key="line">{{ line }}</p>
      </div>
      <div class="toolbar">
        <button type="button" class="btn btn-primary" @click="openWorkspace">
          {{ t('projectWizard.openWorkspace') }}
        </button>
        <button type="button" class="btn" data-testid="wizard-back-created" @click="openWorkbench">
          {{ isGlobal ? t('w57.projectWizard.backToList') : t('projectWizard.backToWorkbench') }}
        </button>
        <button type="button" class="btn" data-testid="open-save-template" @click="openSaveTemplate">
          {{ t("saveTemplate.open") }}
        </button>
      </div>
      <p v-if="saveTemplateResult" class="wizard-hint" data-testid="save-template-result">
        {{ saveTemplateResult }}
      </p>
    </div>

    <template v-else>
      <nav class="wizard-steps" :aria-label="t('projectWizard.stepNav')">
        <ol>
          <li
            v-for="(key, index) in steps"
            :key="key"
            :aria-current="index === step ? 'step' : undefined"
            :class="{ done: index < step, active: index === step }"
          >
            <span class="wizard-step__index" aria-hidden="true">{{ index + 1 }}</span>
            <span>{{ t(`projectWizard.steps.${key}`) }}</span>
          </li>
        </ol>
        <span class="wizard-progress">{{ t('projectWizard.stepOf', { step: step + 1, total: steps.length }) }}</span>
      </nav>

      <ErrorLineView v-if="error" :error="error" />
      <p v-if="message" class="wizard-message" role="alert">{{ message }}</p>

      <section v-if="current === 'template'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.templateTitle') }}</h2>
        <p class="wizard-hint">{{ t('projectWizard.templateHint') }}</p>
        <div class="template-grid">
          <button
            v-for="tpl in PROJECT_TEMPLATES"
            :key="tpl.key"
            type="button"
            class="template-card"
            :class="{ selected: selectedKey === tpl.key }"
            :aria-pressed="selectedKey === tpl.key"
            @click="selectTemplate(tpl.key)"
          >
            <span class="template-card__name">{{ t(tpl.nameKey) }}</span>
            <span class="template-card__code">{{ tpl.code }}</span>
            <span class="template-card__desc">{{ t(tpl.descKey) }}</span>
            <span class="template-card__meta">
              {{ t(`projectWizard.kernels.${tpl.testKernel}`) }} ·
              {{ t(`projectWizard.visibilities.${tpl.visibility}`) }}
            </span>
          </button>
        </div>
      </section>

      <section v-else-if="current === 'basics'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.basicsTitle') }}</h2>
        <form class="wizard-form" @submit.prevent="goNext">
          <div class="wizard-field">
            <label for="wizard-code-input">{{ t('projectWizard.code') }}</label>
            <span class="wizard-field__row">
              <input
                id="wizard-code-input"
                v-model="form.code"
                class="input wide"
                type="text"
                autocomplete="off"
                spellcheck="false"
                data-testid="wizard-code"
                :placeholder="codeSample || t('projectWizard.codePlaceholder')"
              />
              <button
                type="button"
                class="btn"
                data-testid="wizard-code-suggest"
                @click="regenerateCode"
              >
                {{ t('projectWizard.codeSuggest') }}
              </button>
            </span>
            <span class="wizard-hint">{{ t('projectWizard.codeHint') }}</span>
          </div>
          <label class="wizard-field">
            <span>{{ t('projectWizard.name') }}</span>
            <input v-model="form.name" class="input wide" type="text" autocomplete="off" data-testid="wizard-name" />
          </label>
          <label class="wizard-field">
            <span>{{ t('projectWizard.description') }}</span>
            <textarea v-model="form.description" class="input wide" rows="3" />
          </label>
        </form>
      </section>

      <section v-else-if="current === 'runtime'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.runtimeTitle') }}</h2>
        <fieldset class="wizard-group">
          <legend>{{ t('projectWizard.kernelLabel') }}</legend>
          <label class="wizard-radio">
            <input v-model="form.testKernel" type="radio" value="robot" />
            <span>{{ t('projectWizard.kernels.robot') }}</span>
          </label>
          <label class="wizard-radio">
            <input v-model="form.testKernel" type="radio" value="pytest" />
            <span>{{ t('projectWizard.kernels.pytest') }}</span>
          </label>
        </fieldset>
        <fieldset class="wizard-group">
          <legend>{{ t('projectWizard.visibilityLabel') }}</legend>
          <label class="wizard-radio">
            <input v-model="form.visibility" type="radio" value="private" />
            <span>{{ t('projectWizard.visibilities.private') }}</span>
          </label>
          <label class="wizard-radio">
            <input v-model="form.visibility" type="radio" value="project" />
            <span>{{ t('projectWizard.visibilities.project') }}</span>
          </label>
          <label class="wizard-radio">
            <input v-model="form.visibility" type="radio" value="public" />
            <span>{{ t('projectWizard.visibilities.public') }}</span>
          </label>
        </fieldset>
        <label class="wizard-field">
          <span>{{ t('projectWizard.owner') }}</span>
          <select v-model="form.ownerId" class="input wide" data-testid="wizard-owner">
            <option :value="null">{{ t('projectWizard.ownerPlaceholder') }}</option>
            <option v-for="user in users" :key="user.id" :value="user.id">
              {{ user.displayName || user.username }}
            </option>
          </select>
          <span class="wizard-hint">{{ t('projectWizard.ownerHint') }}</span>
        </label>
        <label class="wizard-check">
          <input v-model="form.demo" type="checkbox" data-testid="wizard-demo" />
          <span>{{ t('projectWizard.demo') }}</span>
        </label>
        <p class="wizard-hint">{{ t('projectWizard.demoHint') }}</p>
        <p class="wizard-hint">{{ t('projectWizard.runtimeHint') }}</p>
      </section>

      <section v-else-if="current === 'members'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.membersTitle') }}</h2>
        <p class="wizard-hint">{{ t('projectWizard.membersHint') }}</p>
        <div class="wizard-member-form">
          <label class="wizard-field">
            <span>{{ t('projectWizard.memberUser') }}</span>
            <select v-model="memberUserId" class="input wide" :disabled="usersLoading">
              <option :value="null">{{ t('projectWizard.memberUserPlaceholder') }}</option>
              <option v-for="user in availableUsers" :key="user.id" :value="user.id">
                {{ user.displayName || user.username }}
              </option>
            </select>
          </label>
          <div class="wizard-field">
            <span>{{ t('projectWizard.memberRole') }}</span>
            <div class="wizard-role-checks" role="group" :aria-label="t('projectWizard.memberRole')">
              <label v-for="role in ['admin', 'pm', 'engineer', 'viewer']" :key="role">
                <input v-model="memberRoles" type="checkbox" :value="role" />
                <span>{{ t(`admin.projectRole.${role}`) }}</span>
              </label>
            </div>
          </div>
          <button type="button" class="btn" @click="addMember">{{ t('projectWizard.addMember') }}</button>
        </div>
        <p v-if="usersLoading" class="wizard-hint">{{ t('common.loading') }}</p>
        <p v-else-if="availableUsers.length === 0" class="wizard-hint">{{ t('projectWizard.noUsers') }}</p>
        <ul v-if="picked.length" class="wizard-members">
          <li v-for="member in picked" :key="member.userId">
            <span>{{ member.username }}</span>
            <span class="wizard-member-role">
              <span v-for="role in member.roleCodes" :key="role" class="role-chip">
                {{ t(`admin.projectRole.${role}`) }}
              </span>
            </span>
            <button
              type="button"
              class="btn btn-ghost"
              :aria-label="t('projectWizard.removeMember', { name: member.username })"
              @click="removeMember(member.userId)"
            >
              {{ t('common.remove') }}
            </button>
          </li>
        </ul>
      </section>

      <section v-else-if="current === 'groups'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.groupsTitle') }}</h2>
        <p class="wizard-hint">{{ t('projectWizard.groupsHint') }}</p>
        <p v-if="groupsLoading" class="wizard-hint">{{ t('common.loading') }}</p>
        <p v-else-if="groupRows.length === 0" class="wizard-hint">
          {{ t('projectWizard.noGroups') }}
        </p>
        <ul v-else class="wizard-groups">
          <li v-for="group in groupRows" :key="group.id">
            <label class="wizard-check">
              <input
                type="checkbox"
                :checked="groupPicked(group.id)"
                :data-testid="`wizard-group-${group.id}`"
                @change="toggleGroup(group)"
              />
              <span>{{ group.name }}</span>
            </label>
            <span class="wizard-hint">
              {{ t('projectWizard.groupMembers', { count: group.memberCount ?? 0 }) }}
            </span>
            <select
              v-if="groupPicked(group.id)"
              class="input"
              :value="groupPicks.find((item) => item.groupId === group.id)?.roleCode"
              :aria-label="t('projectWizard.groupRole')"
              @change="setGroupRole(group.id, ($event.target as HTMLSelectElement).value)"
            >
              <option v-for="role in ['admin', 'pm', 'engineer', 'viewer']" :key="role" :value="role">
                {{ t(`admin.projectRole.${role}`) }}
              </option>
            </select>
          </li>
        </ul>
      </section>

      <section v-else-if="current === 'content'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.contentTitle') }}</h2>
        <p class="wizard-hint">{{ t('projectWizard.contentHint') }}</p>
        <!-- W57-12/13：四类来源由选择器自管理，选择状态经 v-model 持久在向导顶层 -->
        <WizardContentPicker v-model="contentSelection" :conflicts="contentConflicts" />
        <!-- v5.10.0 / FR-v5.10-03：每台选中设备两个地址面各一个凭证下拉，可留空。
             候选来自资产页同一端点（本设备成功 > 同型号成功 > 已配置 > 其它），不另造匹配。 -->
        <div v-if="selectedAssetIds.length" class="wizard-credentials">
          <h3 class="card-title">{{ t('projectWizard.credentialsTitle') }}</h3>
          <p class="wizard-hint">{{ t('projectWizard.credentialsHint') }}</p>
          <div v-for="assetId in selectedAssetIds" :key="assetId" class="wizard-credential-row">
            <span class="wizard-credential-row__id">
              {{ t('projectWizard.assetLabel', { id: assetId }) }}
            </span>
            <label class="wizard-field">
              <span>{{ t('projectWizard.mgmtCredential') }}</span>
              <select
                class="input"
                :value="credPicks[assetId]?.mgmt ?? ''"
                :data-testid="`wizard-cred-mgmt-${assetId}`"
                @change="setCredential(assetId, 'mgmt', ($event.target as HTMLSelectElement).value)"
              >
                <option value="">{{ t('projectWizard.credentialNone') }}</option>
                <option v-for="option in credOptions[assetId] ?? []" :key="option.credentialId" :value="String(option.credentialId)">
                  {{ option.name }}
                </option>
              </select>
            </label>
            <label class="wizard-field">
              <span>{{ t('projectWizard.bmcCredential') }}</span>
              <select
                class="input"
                :value="credPicks[assetId]?.bmc ?? ''"
                :data-testid="`wizard-cred-bmc-${assetId}`"
                @change="setCredential(assetId, 'bmc', ($event.target as HTMLSelectElement).value)"
              >
                <option value="">{{ t('projectWizard.credentialNone') }}</option>
                <option v-for="option in credOptions[assetId] ?? []" :key="option.credentialId" :value="String(option.credentialId)">
                  {{ option.name }}
                </option>
              </select>
            </label>
          </div>
        </div>
      </section>

      <section v-else class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.reviewTitle') }}</h2>
        <dl class="wizard-review">
          <div><dt>{{ t('projectWizard.code') }}</dt><dd>{{ form.code }}</dd></div>
          <div><dt>{{ t('projectWizard.name') }}</dt><dd>{{ form.name }}</dd></div>
          <div><dt>{{ t('projectWizard.description') }}</dt><dd>{{ form.description || '—' }}</dd></div>
          <div><dt>{{ t('projectWizard.kernelLabel') }}</dt><dd>{{ t(`projectWizard.kernels.${form.testKernel}`) }}</dd></div>
          <div><dt>{{ t('projectWizard.visibilityLabel') }}</dt><dd>{{ t(`projectWizard.visibilities.${form.visibility}`) }}</dd></div>
          <div><dt>{{ t('projectWizard.owner') }}</dt><dd>{{ ownerName }}</dd></div>
          <div>
            <dt>{{ t('projectWizard.demo') }}</dt>
            <dd>{{ form.demo ? t('common.yes') : t('common.no') }}</dd>
          </div>
          <div>
            <dt>{{ t('projectWizard.membersTitle') }}</dt>
            <dd>
              <template v-if="picked.length">
                {{ picked.map((member) => `${member.username}(${member.roleCodes.map((role) => t(`admin.projectRole.${role}`)).join('/')})`).join('、') }}
              </template>
              <template v-else>{{ t('projectWizard.noMembers') }}</template>
            </dd>
          </div>
          <div>
            <dt>{{ t('projectWizard.groupsTitle') }}</dt>
            <dd>
              <template v-if="groupPicks.length">
                {{ groupPicks.map((item) => `${item.name}(${t(`admin.projectRole.${item.roleCode}`)})`).join('、') }}
              </template>
              <template v-else>{{ t('projectWizard.noGroupsPicked') }}</template>
            </dd>
          </div>
          <div>
            <dt>{{ t('projectWizard.contentTitle') }}</dt>
            <dd>
              {{ t('projectWizard.contentSummary', {
                cases: selectedCaseIds.length,
                assets: selectedAssetIds.length,
              }) }}
              <span class="wizard-hint">
                {{ t('w57.projectWizard.setSummary', { sets: selectedSetIds.length }) }}
              </span>
            </dd>
          </div>
        </dl>
        <div class="toolbar">
          <button type="button" class="btn btn-primary" :disabled="busy" data-testid="wizard-create" @click="createProject">
            {{ busy ? t('projectWizard.creating') : t('projectWizard.create') }}
          </button>
        </div>
      </section>

      <div v-if="current !== 'review'" class="wizard-footer">
        <button v-if="step > 0" type="button" class="btn" data-testid="wizard-back-step" @click="goBack">
          {{ t('projectWizard.back') }}
        </button>
        <button type="button" class="btn btn-primary" data-testid="wizard-next" @click="goNext">
          {{ t('projectWizard.next') }}
        </button>
      </div>
    </template>

    <div v-if="saveTemplateOpen" class="modal-mask" data-testid="save-template-modal" @click.self="saveTemplateOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t("saveTemplate.title") }}</h2>
          <button type="button" class="link" @click="saveTemplateOpen = false">
            {{ t('common.close') }}
          </button>
        </header>
        <p class="wizard-hint">{{ t("saveTemplate.referenceHint") }}</p>
        <dl class="wizard-review">
          <div><dt>{{ t("saveTemplate.cases") }}</dt><dd>{{ selectedCaseIds.length }}</dd></div>
          <div><dt>{{ t("saveTemplate.caseSets") }}</dt><dd>{{ caseSetCount }}</dd></div>
          <div><dt>{{ t("saveTemplate.assets") }}</dt><dd>{{ selectedAssetIds.length }}</dd></div>
        </dl>
        <label class="wizard-field">
          <span>{{ t("saveTemplate.name") }}</span>
          <input v-model="saveTemplateName" class="input wide" data-testid="save-template-name" />
        </label>
        <p v-if="saveTemplateError" class="wizard-message" data-testid="save-template-error">
          {{ saveTemplateError }}
        </p>
        <div class="toolbar">
          <button type="button" class="btn" @click="saveTemplateOpen = false">
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="btn btn-primary"
            data-testid="save-template-confirm"
            :disabled="saveTemplateBusy"
            @click="confirmSaveTemplate"
          >{{ t("saveTemplate.confirm") }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.project-wizard {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.wizard-steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
}

.wizard-steps ol {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.wizard-steps li {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.wizard-steps li.active {
  color: var(--text);
  font-weight: 600;
}

.wizard-steps li.done {
  color: var(--accent);
}

.wizard-step__index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--border);
  border-radius: 50%;
  font-size: var(--font-size-xs);
}

.wizard-steps li.active .wizard-step__index {
  color: var(--accent-contrast);
  background: var(--accent);
  border-color: var(--accent);
}

.wizard-progress {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.wizard-card {
  padding: var(--space-4);
}

.wizard-hint {
  margin: var(--space-2) 0 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.wizard-message {
  margin: 0;
  color: var(--fail);
}

.wizard-form,
.wizard-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin: var(--space-3) 0 0;
  border: 0;
}

.wizard-group {
  padding: 0;
}

.wizard-group legend {
  padding: 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.wizard-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--font-size-sm);
}

/* 编码输入 + 「重新生成」并排：按钮附在字段内，避免用户跑到别处找命名规则。 */
.wizard-field__row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.wizard-radio {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-size-sm);
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.template-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  text-align: left;
  color: var(--text);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  font: inherit;
  cursor: pointer;
}

.template-card.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent) inset;
}

.template-card__name {
  font-weight: 600;
}

/* 模板内部编号：等宽、弱化，方便对号但别抢模板名的注意力。 */
.template-card__code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  letter-spacing: 0.02em;
}

.template-card__desc,
.template-card__meta {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.wizard-member-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.wizard-members {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: var(--space-3) 0 0;
  padding: 0;
  list-style: none;
}

.wizard-members li {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
}

.wizard-member-role {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.role-chip {
  display: inline-flex;
  align-items: center;
  padding: 0 var(--space-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text);
  font-size: var(--font-size-xs);
}

.wizard-role-checks {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.wizard-role-checks label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--text);
  font-size: var(--font-size-sm);
}

.content-group {
  margin-top: var(--space-3);
}

.content-group h3 {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}

.content-picker {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-1);
  max-height: 220px;
  overflow: auto;
  padding: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.content-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-size-sm);
  padding: 2px 4px;
}

.content-item__title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-item__meta {
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.wizard-review {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
  margin: var(--space-3) 0 0;
}

.wizard-review dt {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.wizard-review dd {
  margin: 0;
}

.wizard-footer {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
}

.wizard-result {
  padding: var(--space-4);
}

.wizard-warnings {
  margin-top: var(--space-3);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal {
  width: min(520px, 92vw);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.link {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
  padding: 0;
}
</style>
