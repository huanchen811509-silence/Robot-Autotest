<script setup lang="ts">
/**
 * 创建项目向导（FR-v4.1-04）：模板 → 基本信息 → 运行设置 → 成员 → 确认。
 *
 * 三条约束：
 * 1. 模板只做默认值，不写库、不预置用例；创建后仍可在项目管理里改。
 * 2. 无 `user:write` 能力时自动跳过成员步骤，不把越权入口摆出来。
 * 3. 成员添加失败不回滚项目本身：项目已创建是事实，失败项逐条列出。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { projectApi, userApi, type UserSummary } from '@/api/iam'
import { projectCardsApi } from '@/api/projectCards'
import { globalCaseApi, type GlobalCaseRow } from '@/api/globalCases'
import { globalAssetApi, type GlobalAssetRow } from '@/api/globalAssets'
import { templateApi } from '@/api/globalTemplates'
import { capabilitiesForRole, roleFromCodes } from '@/config/navigation'
import { PROJECT_TEMPLATES } from '@/config/projectTemplates'
import { engineCode, nextProjectCode, yearMonth } from '@/config/projectCode'
import ErrorLineView from '@/components/ErrorLine.vue'
import { localizeError, type ErrorLine } from '@/utils/errors'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const ALL_STEPS = ['template', 'basics', 'runtime', 'members', 'content', 'review'] as const
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
})

const memberRoles = ref<string[]>(['engineer'])
const memberUserId = ref<number | null>(null)
const picked = ref<{ userId: number; roleCodes: string[]; username: string }[]>([])
const users = ref<UserSummary[]>([])
const usersLoading = ref(false)

// 内容步（FR-v4.8-05）：从公共库挑选用例与资产，默认引用；用例复制进项目用例集。
const contentCases = ref<GlobalCaseRow[]>([])
const contentAssets = ref<GlobalAssetRow[]>([])
const selectedCaseIds = ref<number[]>([])
const selectedAssetIds = ref<number[]>([])
const contentLoading = ref(false)
const contentAvailable = ref(true)
const contentHint = ref('')
let contentLoaded = false

async function loadContent(): Promise<void> {
  if (contentLoaded) return
  contentLoaded = true
  contentLoading.value = true
  try {
    const [cases, assets] = await Promise.all([
      globalCaseApi.page({ page: 1, size: 100, lifecycleStatus: 'active' }),
      globalAssetApi.list({ type: 'DEVICE', availability: 'active' }),
    ])
    contentCases.value = cases.items
    contentAssets.value = assets.slice(0, 100)
  } catch {
    contentAvailable.value = false
    contentHint.value = t('projectWizard.contentUnavailable')
  } finally {
    contentLoading.value = false
  }
}

function toggleCase(id: number): void {
  selectedCaseIds.value = selectedCaseIds.value.includes(id)
    ? selectedCaseIds.value.filter((item) => item !== id)
    : [...selectedCaseIds.value, id]
}

function toggleAsset(id: number): void {
  selectedAssetIds.value = selectedAssetIds.value.includes(id)
    ? selectedAssetIds.value.filter((item) => item !== id)
    : [...selectedAssetIds.value, id]
}

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
  canManageMembers.value ? ALL_STEPS.slice() : ALL_STEPS.filter((key) => key !== 'members'),
)
const current = computed<StepKey>(() => steps.value[Math.min(step.value, steps.value.length - 1)])
const selected = computed(() => PROJECT_TEMPLATES.find((tpl) => tpl.key === selectedKey.value))

const codePattern = /^[A-Za-z0-9._-]{2,40}$/
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

watch(current, async (key) => {
  if (key === 'basics') {
    await ensureCodes()
    // 只在「没填过」或「还是上一次的自动建议」时覆盖，避免抹掉用户手写的编码
    if (!form.code.trim() || form.code === autoCode.value) void regenerateCode()
  }
  if (key === 'members') void loadUsers()
  if (key === 'content') void loadContent()
})

// 改内核（robot ↔ pytest）会改编码里的引擎段：只有编码还是自动建议时才跟着换
watch(
  () => form.testKernel,
  () => {
    if (form.code === autoCode.value) void regenerateCode()
  },
)

onMounted(() => void loadUsers())

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
  try {
    const project = await projectApi.create({
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      visibility: form.visibility,
      testKernel: form.testKernel,
    })
    // 批量成员（FR-v4.4-03）：一次请求，逐人失败原因回显，不回滚项目本身
    if (picked.value.length) {
      try {
        const result = await projectApi.addMembers(
          project.id,
          picked.value.map((member) => ({ userId: member.userId, roleCodes: member.roleCodes })),
        )
        for (const failure of result.failures) {
          const member = picked.value.find((item) => item.userId === failure.userId)
          warnings.value.push(
            t('projectWizard.memberFailed', {
              name: member?.username ?? failure.userId,
              reason: failure.reason,
            }),
          )
        }
      } catch (e) {
        const line = localizeError(e)
        warnings.value.push(t('projectWizard.memberBatchFailed', { reason: line.summary }))
      }
    }
    // 内容步（FR-v4.8-05）：用例复制进项目用例集，资产按引用登记；失败不回滚项目
    if (selectedCaseIds.value.length || selectedAssetIds.value.length) {
      try {
        await templateApi.applyContent(project.id, {
          caseIds: selectedCaseIds.value,
          assets: selectedAssetIds.value.map((assetId) => ({ assetType: 'DEVICE', assetId })),
          setName: t('projectWizard.contentSetName'),
        })
      } catch (e) {
        warnings.value.push(
          t('projectWizard.contentFailed', { reason: localizeError(e).summary }),
        )
      }
    }
    await auth.loadProjects()
    auth.setProject(project.id)
    created.value = project.id
    message.value = t('projectWizard.created')
  } catch (e) {
    error.value = localizeError(e)
  } finally {
    busy.value = false
  }
}

function openWorkspace(): void {
  void router.push('/workspace')
}

function openWorkbench(): void {
  void router.push('/workbench')
}
</script>

<template>
  <div class="project-wizard">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ t('projectWizard.title') }}</h1>
        <p class="page-subtitle">{{ t('projectWizard.subtitle') }}</p>
      </div>
      <router-link class="btn" to="/workbench">{{ t('projectWizard.toWorkbench') }}</router-link>
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
        <button type="button" class="btn" @click="openWorkbench">
          {{ t('projectWizard.backToWorkbench') }}
        </button>
      </div>
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
            <input v-model="form.name" class="input wide" type="text" autocomplete="off" />
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

      <section v-else-if="current === 'content'" class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.contentTitle') }}</h2>
        <p class="wizard-hint">{{ t('projectWizard.contentHint') }}</p>
        <p v-if="contentHint" class="wizard-hint">{{ contentHint }}</p>
        <p v-if="contentLoading" class="wizard-hint">{{ t('common.loading') }}</p>
        <template v-else-if="contentAvailable">
          <div class="content-group">
            <h3>{{ t('projectWizard.contentCases', { count: selectedCaseIds.length }) }}</h3>
            <div class="content-picker">
              <label v-for="item in contentCases" :key="item.id" class="content-item">
                <input
                  type="checkbox"
                  :checked="selectedCaseIds.includes(item.id)"
                  @change="toggleCase(item.id)"
                />
                <span class="content-item__title">{{ item.title }}</span>
                <span class="content-item__meta">{{ item.categoryL1 || '—' }}</span>
              </label>
              <p v-if="contentCases.length === 0" class="wizard-hint">
                {{ t('global.empty') }}
              </p>
            </div>
          </div>
          <div class="content-group">
            <h3>{{ t('projectWizard.contentAssets', { count: selectedAssetIds.length }) }}</h3>
            <div class="content-picker">
              <label v-for="item in contentAssets" :key="item.id" class="content-item">
                <input
                  type="checkbox"
                  :checked="selectedAssetIds.includes(item.id)"
                  @change="toggleAsset(item.id)"
                />
                <span class="content-item__title">{{ item.name }}</span>
                <span class="content-item__meta">{{ item.managementIp || '—' }}</span>
              </label>
              <p v-if="contentAssets.length === 0" class="wizard-hint">
                {{ t('global.empty') }}
              </p>
            </div>
          </div>
        </template>
      </section>

      <section v-else class="card wizard-card">
        <h2 class="card-title">{{ t('projectWizard.reviewTitle') }}</h2>
        <dl class="wizard-review">
          <div><dt>{{ t('projectWizard.code') }}</dt><dd>{{ form.code }}</dd></div>
          <div><dt>{{ t('projectWizard.name') }}</dt><dd>{{ form.name }}</dd></div>
          <div><dt>{{ t('projectWizard.description') }}</dt><dd>{{ form.description || '—' }}</dd></div>
          <div><dt>{{ t('projectWizard.kernelLabel') }}</dt><dd>{{ t(`projectWizard.kernels.${form.testKernel}`) }}</dd></div>
          <div><dt>{{ t('projectWizard.visibilityLabel') }}</dt><dd>{{ t(`projectWizard.visibilities.${form.visibility}`) }}</dd></div>
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
            <dt>{{ t('projectWizard.contentTitle') }}</dt>
            <dd>
              {{ t('projectWizard.contentSummary', {
                cases: selectedCaseIds.length,
                assets: selectedAssetIds.length,
              }) }}
            </dd>
          </div>
        </dl>
        <div class="toolbar">
          <button type="button" class="btn btn-primary" :disabled="busy" @click="createProject">
            {{ busy ? t('projectWizard.creating') : t('projectWizard.create') }}
          </button>
        </div>
      </section>

      <div v-if="current !== 'review'" class="wizard-footer">
        <button v-if="step > 0" type="button" class="btn" @click="goBack">
          {{ t('projectWizard.back') }}
        </button>
        <button type="button" class="btn btn-primary" @click="goNext">
          {{ t('projectWizard.next') }}
        </button>
      </div>
    </template>
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
</style>
