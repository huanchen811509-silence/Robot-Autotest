<script setup lang="ts">
/**
 * 版本迭代页（NAV-03 / FR-v2.0-12）。
 * 数据源是仓库根 `CHANGELOG.md`（构建时同步到 public），不另写一份手写历史。
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseChangelog, type ChangelogRelease } from '@/config/changelog'

const { t } = useI18n()
const releases = ref<ChangelogRelease[]>([])
const error = ref('')

// CHANGELOG.md 的小节标题用 <!-- added --> 这类锚点标记，故此处只需要语言无关的
// key；中文/英文显示文案一律由 t() 从语言包取，代码里不写死任何自然语言。
const SECTION_LABELS: Record<string, string> = {
  added: 'changelog.added',
  changed: 'changelog.changed',
  fixed: 'changelog.fixed',
  breaking: 'changelog.breaking',
}

function label(key: string): string {
  const i18nKey = SECTION_LABELS[key]
  return i18nKey ? t(i18nKey) : key
}

onMounted(async () => {
  try {
    const response = await fetch('/CHANGELOG.md')
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    releases.value = parseChangelog(await response.text())
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>{{ t('changelog.title') }}</h1>
      <p class="muted">{{ t('changelog.subtitle') }}</p>
      <!-- v3.11.0-delivery：版本迭代页要能回答「这一版的交付包去哪拿、怎么装」。 -->
      <p class="muted small delivery-hint">{{ t('changelog.deliveryHint') }}</p>
    </header>
    <p v-if="error" class="notice">{{ t('changelog.loadFailed') }}：{{ error }}</p>
    <p v-else-if="releases.length === 0" class="muted">{{ t('changelog.empty') }}</p>

    <article
      v-for="release in releases"
      :key="release.title"
      class="release"
      :class="{ current: release.current }"
    >
      <header>
        <h2>{{ release.title }}</h2>
        <span v-if="release.current" class="tag">{{ t('changelog.current') }}</span>
      </header>
      <section v-for="section in release.sections" :key="section.key" class="block">
        <h3>{{ label(section.key) }}</h3>
        <ul>
          <li v-for="item in section.items" :key="item">{{ item }}</li>
        </ul>
      </section>
    </article>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 999px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.release { border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 10px; padding: 18px; }
.release.current { border-color: var(--ok-border); }
.release header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.release h2 { margin: 0; font-size: 16px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--ok-border); }
.block { margin-top: 12px; }
.block h3 { margin: 0 0 6px; font-size: 13px; opacity: .75; }
.block ul { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.8; }
.notice { padding: 8px 12px; border-radius: 6px; font-size: 13px; border: 1px solid var(--fail-border); }
</style>
