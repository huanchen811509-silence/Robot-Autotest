<script setup lang="ts">
/**
 * 用户管理（仅当前用户和关联项目）—— v4.11.0 / FR-v4.11-30。
 *
 * 这是**只读**页，回答两个问题：「我是谁」「我加入了哪些项目」。
 *
 * 三条边界：
 * 1. **不是** `/admin`。后者是系统级账号 CRUD（能力 `user:write`，可停用、改角色），
 *    与这里既不同语义也不同越权面（`00-RESEARCH.md` B6）。本版不复用它。
 * 2. 没有新增后端端点：身份取 `auth` store，关联项目取 `/api/projects/cards` 后按
 *    `own || joined` 过滤——不用 `myRole` 判，因为全局 admin 无成员关系时该值也是 `admin`。
 * 3. 页面上没有任何写操作控件（无新增 / 停用 / 改角色），只有一个「打开项目」的链接。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { projectCardsApi, type ProjectCard } from '@/api/projectCards'

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const cards = ref<ProjectCard[]>([])
const loading = ref(false)
const failed = ref(false)

const user = computed(() => auth.user)

/** 只保留「我创建或我加入」的项目；`own`/`joined` 缺省按 false 处理。 */
const linked = computed(() => cards.value.filter((card) => card.own === true || card.joined === true))

const roleText = computed(() => {
  const roles = user.value?.roles ?? []
  return roles.length > 0 ? roles.join(' · ') : '—'
})

function roleLabel(role: string | null): string {
  if (!role) return '—'
  const key = `memberRole.${role}`
  const text = t(key)
  // vue-i18n 缺键时回退成 key 本身，此时直接显示原始角色码
  return text === key ? role : text
}

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    cards.value = await projectCardsApi.list('7d', auth.projectId)
  } catch {
    failed.value = true
    cards.value = []
  } finally {
    loading.value = false
  }
}

/** 打开关联项目：先把它设为当前项目，再进项目看板（项目域页面都按当前项目取数）。 */
function openProject(card: ProjectCard): void {
  auth.setProject(card.id)
  void router.push('/overview')
}

onMounted(() => void load())
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h1>{{ t('settingsUsers.title') }}</h1>
        <p class="muted">{{ t('settingsUsers.hint') }}</p>
      </div>
    </header>

    <section class="card">
      <h2>{{ t('settingsUsers.currentUser') }}</h2>
      <dl class="kv">
        <div>
          <dt>{{ t('settingsUsers.username') }}</dt>
          <dd class="mono" data-testid="su-username">{{ user?.username ?? '—' }}</dd>
        </div>
        <div>
          <dt>{{ t('settingsUsers.displayName') }}</dt>
          <dd data-testid="su-display-name">{{ user?.displayName ?? '—' }}</dd>
        </div>
        <div>
          <dt>{{ t('settingsUsers.roles') }}</dt>
          <dd data-testid="su-roles">{{ roleText }}</dd>
        </div>
        <div>
          <dt>{{ t('settingsUsers.projectCount') }}</dt>
          <dd data-testid="su-project-count">{{ linked.length }}</dd>
        </div>
      </dl>
    </section>

    <section class="card">
      <h2>{{ t('settingsUsers.linkedProjects') }}（{{ linked.length }}）</h2>
      <p v-if="failed" class="error">{{ t('global.loadFailed') }}</p>
      <p v-else-if="loading" class="muted">{{ t('global.loading') }}</p>
      <ul v-else-if="linked.length > 0" class="linked" data-testid="su-linked-projects">
        <li v-for="card in linked" :key="card.id" class="linked-row">
          <RouterLink to="/overview" class="linked-name" @click="openProject(card)">
            {{ card.name }}
          </RouterLink>
          <span class="mono muted">{{ card.code }}</span>
          <span class="badge">{{ roleLabel(card.myRole) }}</span>
        </li>
      </ul>
      <p v-else class="muted" data-testid="su-linked-empty">
        {{ t('settingsUsers.noProjects') }}
      </p>
    </section>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.card {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.card h2 {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-md);
}

.kv {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
  margin: 0;
}

.kv div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kv dt {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.kv dd {
  margin: 0;
}

.linked {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.linked-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 8px 4px;
  border-bottom: 1px solid var(--border);
}

.linked-row:last-child {
  border-bottom: 0;
}

.linked-name {
  color: var(--accent);
  text-decoration: none;
  min-width: 200px;
}

.linked-name:hover {
  text-decoration: underline;
}

.mono {
  font-family: var(--font-mono, monospace);
}

.badge {
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  font-size: 11px;
  color: var(--text-muted);
}

.error {
  color: var(--fail);
}
</style>
