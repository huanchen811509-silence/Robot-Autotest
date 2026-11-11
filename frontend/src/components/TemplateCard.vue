<script setup lang="ts">
/**
 * 模板卡片（v4.9.2 / OPT-05 / FR-49-17~18、FR-49-22）。
 *
 * 与 `ProjectCard.vue` 共用同一套栅格与视觉 token：这里刻意复用相同的 class 名，
 * 并逐字复制其 `<style scoped>` 声明（scoped 样式互不泄漏），因此样式 token diff = 0。
 * 四类计数取自 `GET /api/global/templates/{id}` 的 items 明细，不只信 `itemCount`。
 * 「以此创建项目」走 `POST /api/projects/from-template`，默认 `mode:'copy'`。
 *
 * 新 i18n 键交付在 docs/evidence/opt-20261111/i18n/frontend-views.json。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TemplateRow } from '@/api/globalTemplates'
import { templateProjectsApi, type TemplateDetailView } from '@/api/templateProjects'

const props = withDefaults(defineProps<{
  template: TemplateRow
  busy?: boolean
}>(), { busy: false })

const { t } = useI18n()
const detail = ref<TemplateDetailView | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    detail.value = await templateProjectsApi.detail(props.template.id)
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
})

const counts = computed(() => ({
  cases: detail.value?.cases.length ?? null,
  caseSets: detail.value?.caseSets.length ?? null,
  scenarios: detail.value?.scenarios.length ?? null,
  assets: detail.value?.assets.length ?? null,
}))

function countText(value: number | null): string {
  return value === null ? t("templateCard.noData") : String(value)
}

const kernelLabel = computed(() => props.template.kernel || t("templateCard.noData"))
const lastUpdated = computed(
  () => detail.value?.template.updatedAt || props.template.createdAt || t("templateCard.noData"),
)

const confirming = ref(false)
const projectName = ref('')
const creating = ref(false)
const result = ref('')

function openConfirm(): void {
  confirming.value = true
  projectName.value = `${props.template.name}-project`
  result.value = ''
}

async function confirmCreate(): Promise<void> {
  if (!projectName.value.trim()) return
  creating.value = true
  result.value = ''
  try {
    const created = await templateProjectsApi.createFromTemplate({
      templateId: props.template.id,
      name: projectName.value.trim(),
      mode: 'copy',
    })
    result.value = t("templateCard.created", { id: created.id })
    confirming.value = false
  } catch (ex) {
    result.value = (ex as { message?: string }).message ?? t("templateCard.createFailed")
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <article
    class="project-card"
    :class="{ 'is-disabled': template.status !== 'active' }"
    data-testid="template-card"
  >
    <header class="project-card__head">
      <div class="project-card__identity">
        <h2 class="project-card__name">{{ template.name }}</h2>
        <span class="project-card__code">{{ template.code }}</span>
      </div>
      <div class="project-card__flags">
        <span v-if="template.builtin" class="badge badge--accent">{{ t("templateCard.builtin") }}</span>
        <span class="badge" :class="template.status === 'active' ? 'badge--ok' : 'badge--muted'">
          {{ template.status }}
        </span>
      </div>
    </header>

    <p v-if="template.description" class="project-card__desc">{{ template.description }}</p>

    <dl class="project-card__metrics">
      <div class="metric">
        <dt class="metric__label">{{ t("templateCard.cases") }}</dt>
        <dd class="metric__value" data-testid="card-cases">{{ countText(counts.cases) }}</dd>
      </div>
      <div class="metric">
        <dt class="metric__label">{{ t("templateCard.caseSets") }}</dt>
        <dd class="metric__value" data-testid="card-case-sets">{{ countText(counts.caseSets) }}</dd>
      </div>
      <div class="metric">
        <dt class="metric__label">{{ t("templateCard.scenarios") }}</dt>
        <dd class="metric__value" data-testid="card-scenarios">{{ countText(counts.scenarios) }}</dd>
      </div>
      <div class="metric">
        <dt class="metric__label">{{ t("templateCard.assets") }}</dt>
        <dd class="metric__value" data-testid="card-assets">{{ countText(counts.assets) }}</dd>
      </div>
    </dl>

    <dl class="project-card__details">
      <div class="detail">
        <dt class="detail__label">{{ t("templateCard.kernel") }}</dt>
        <dd class="detail__value" data-testid="card-kernel">{{ kernelLabel }}</dd>
      </div>
      <div class="detail">
        <dt class="detail__label">{{ t("templateCard.lastUpdated") }}</dt>
        <dd class="detail__value" data-testid="card-last-updated">{{ lastUpdated }}</dd>
      </div>
    </dl>

    <p v-if="loading" class="project-card__hint" data-testid="card-loading">
      {{ t("templateCard.loading") }}
    </p>
    <p v-if="result" class="project-card__result" data-testid="card-result">{{ result }}</p>

    <div v-if="confirming" class="project-card__confirm" data-testid="card-confirm">
      <p class="project-card__hint">{{ t("templateCard.confirmCreate") }}</p>
      <input v-model="projectName" class="project-card__input" data-testid="card-project-name" />
      <div class="project-card__actions">
        <button type="button" class="btn" @click="confirming = false">
          {{ t("templateCard.cancel") }}
        </button>
        <button
          type="button"
          class="btn btn-primary"
          data-testid="card-confirm-create"
          :disabled="creating || !projectName.trim()"
          @click="confirmCreate"
        >{{ t("templateCard.confirm") }}</button>
      </div>
    </div>

    <div class="project-card__actions">
      <button
        type="button"
        class="btn"
        data-testid="card-create-project"
        :disabled="busy"
        @click="openConfirm"
      >{{ t("templateCard.createProject") }}</button>
    </div>
  </article>
</template>

<style scoped>
/* 逐字复用 ProjectCard.vue 的视觉 token 与栅格声明，保证样式 token diff = 0。 */
.project-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  min-width: 0;
}

.project-card.is-current {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.project-card.is-disabled {
  opacity: 0.72;
}

.project-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.project-card__identity {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}

.project-card__name {
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text);
  overflow-wrap: anywhere;
}

.project-card__code {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: var(--font-size-xs);
}

.project-card__flags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: flex-end;
}

.badge {
  padding: 2px var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  white-space: nowrap;
}

.badge--accent {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--panel);
}

.badge--ok {
  color: var(--ok);
  border-color: var(--ok-border);
  background: var(--ok-soft);
}

.badge--muted {
  color: var(--text-muted);
  background: var(--surface-2);
}

.project-card__desc {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  overflow-wrap: anywhere;
}

.project-card__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  margin: 0;
}

.project-card__details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
  margin: 0;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.metric,
.detail {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.metric--wide {
  grid-column: 1 / -1;
}

.metric__label,
.detail__label {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-xs);
}

.metric__value,
.detail__value {
  margin: 0;
  color: var(--text);
  font-size: var(--font-size-base);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.detail__value {
  font-size: var(--font-size-sm);
}

.project-card__actions {
  display: flex;
  gap: var(--space-3);
  margin-top: auto;
}

.project-card__actions .btn {
  margin-top: 0;
}

/* 以下增量样式只使用上面已有的 token，不引入新的视觉变量。 */
.project-card__hint {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}

.project-card__result {
  margin: 0;
  color: var(--ok);
  font-size: var(--font-size-sm);
}

.project-card__confirm {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.project-card__input {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
}

.btn {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}

.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
