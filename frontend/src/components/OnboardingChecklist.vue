<script setup lang="ts">
/**
 * 新手引导清单（FR-3.1-08）。
 *
 * 解决什么问题：空项目进概览只有一排 0，不知道下一步该去哪。清单只读取概览里
 * 已有统计判断完成态（不额外造示例数据），未完成项直接给跳转；「不再显示」和
 * 折叠状态按用户 + 项目持久化，换项目不会被别人的进度干扰。
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useAuthStore } from '@/stores/auth'
import type { ProjectOverview } from '@/api/overview'

const props = defineProps<{ data: ProjectOverview }>()

const { t } = useI18n()
const auth = useAuthStore()

const STORAGE_PREFIX = 'robot.onboarding'
const collapsed = ref(false)
const dismissed = ref(false)

const storageKey = computed(
  () => `${STORAGE_PREFIX}.${auth.user?.id ?? 'anon'}.${auth.projectId ?? 0}`,
)

function readState(): void {
  collapsed.value = false
  dismissed.value = false
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (!raw) return
    const parsed = JSON.parse(raw) as { collapsed?: boolean; dismissed?: boolean }
    collapsed.value = Boolean(parsed.collapsed)
    dismissed.value = Boolean(parsed.dismissed)
  } catch {
    // 坏数据当作未设置，不影响主流程
  }
}

function persist(): void {
  try {
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({ collapsed: collapsed.value, dismissed: dismissed.value }),
    )
  } catch {
    // 隐私模式下 localStorage 可能不可写；引导仍可本次会话内使用
  }
}

watch(storageKey, readState, { immediate: true })

const steps = computed(() => [
  {
    key: 'createProject',
    label: t('onboarding.createProject'),
    hint: t('onboarding.createProjectHint'),
    to: '/admin',
    done: auth.projects.length > 0 && auth.projectId != null,
  },
  {
    key: 'importCases',
    label: t('onboarding.importCases'),
    hint: t('onboarding.importCasesHint'),
    to: '/imports',
    done: props.data.cases.total > 0,
  },
  {
    key: 'configureNode',
    label: t('onboarding.configureNode'),
    hint: t('onboarding.configureNodeHint'),
    to: '/nodes',
    done: props.data.assets.devices > 0,
  },
  {
    key: 'firstRun',
    label: t('onboarding.firstRun'),
    hint: t('onboarding.firstRunHint'),
    to: '/runs',
    done: props.data.runs.total > 0,
  },
  {
    key: 'viewReport',
    label: t('onboarding.viewReport'),
    hint: t('onboarding.viewReportHint'),
    to: '/reports',
    done: props.data.runs.finished > 0,
  },
])

const doneCount = computed(() => steps.value.filter((step) => step.done).length)
const allDone = computed(() => doneCount.value === steps.value.length)
const expanded = computed(() => !collapsed.value && !allDone.value)

function toggleCollapsed(): void {
  collapsed.value = !collapsed.value
  persist()
}

function dismiss(): void {
  dismissed.value = true
  persist()
}
</script>

<template>
  <section
    v-if="!dismissed"
    class="card onboarding"
    data-test="onboarding"
    :aria-label="t('onboarding.ariaLabel')"
  >
    <header class="onboarding__head">
      <h2 class="onboarding__title">{{ t('onboarding.title') }}</h2>
      <span class="onboarding__progress muted">
        {{ t('onboarding.progress', { done: doneCount, total: steps.length }) }}
      </span>
      <div class="onboarding__actions">
        <button
          v-if="!allDone"
          type="button"
          class="btn onboarding__toggle"
          :aria-expanded="expanded"
          aria-controls="onboarding-steps"
          @click="toggleCollapsed"
        >
          {{ expanded ? t('onboarding.collapse') : t('onboarding.expand') }}
        </button>
        <button type="button" class="btn onboarding__dismiss" @click="dismiss">
          {{ t('onboarding.dismiss') }}
        </button>
      </div>
    </header>

    <p v-if="allDone" class="onboarding__completed" role="status">
      <AppIcon name="success" :size="16" />
      <span>{{ t('onboarding.completed') }}</span>
    </p>

    <ol v-else-if="expanded" id="onboarding-steps" class="onboarding__steps">
      <li
        v-for="(step, index) in steps"
        :key="step.key"
        class="onboarding__step"
        :class="{ 'onboarding__step--done': step.done }"
        :data-step="step.key"
      >
        <span class="onboarding__index" aria-hidden="true">{{ index + 1 }}</span>
        <div class="onboarding__text">
          <RouterLink v-if="!step.done" :to="step.to" class="onboarding__link">
            {{ step.label }}
          </RouterLink>
          <span v-else class="onboarding__label">{{ step.label }}</span>
          <span class="onboarding__hint muted">{{ step.hint }}</span>
        </div>
        <span v-if="step.done" class="onboarding__done text-ok">
          <AppIcon name="success" :size="16" />
          {{ t('onboarding.done') }}
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.onboarding {
  display: grid;
  gap: var(--space-3);
  max-width: none;
}

.onboarding__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.onboarding__title {
  margin: 0;
  font-size: var(--font-size-md);
}

.onboarding__progress {
  font-size: var(--font-size-xs);
  font-variant-numeric: tabular-nums;
}

.onboarding__actions {
  display: flex;
  gap: var(--space-2);
  margin-left: auto;
}

.onboarding__toggle,
.onboarding__dismiss {
  margin-top: 0;
}

.onboarding__completed {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  color: var(--ok);
  font-size: var(--font-size-sm);
}

.onboarding__steps {
  display: grid;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.onboarding__step {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  min-width: 0;
}

.onboarding__step--done {
  background: var(--ok-soft);
  border-color: var(--ok-border);
}

.onboarding__index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--surface-2);
  font-size: var(--font-size-xs);
  font-variant-numeric: tabular-nums;
}

.onboarding__text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.onboarding__link,
.onboarding__label {
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.onboarding__hint {
  font-size: var(--font-size-xs);
}

.onboarding__done {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  flex: none;
  margin-left: auto;
  font-size: var(--font-size-xs);
}

@media (width <= 480px) {
  .onboarding__step {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .onboarding__actions {
    width: 100%;
    margin-left: 0;
  }
}
</style>
