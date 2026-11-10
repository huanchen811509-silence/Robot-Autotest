<script setup lang="ts">
/**
 * 版本迭代页（NAV-03 / FR-v2.0-12）。
 * 数据源是仓库根 `CHANGELOG.md`（构建时同步到 public），不另写一份手写历史。
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CHANGELOG_SECTION_LABELS,
  parseChangelog,
  renderInlineMarkdown,
  type ChangelogRelease,
} from '@/config/changelog'

const { t } = useI18n()
const releases = ref<ChangelogRelease[]>([])
const error = ref('')

// 小节锚点 → i18n key 的映射放在 config 里，与「关于」页共用一份，避免两处文案分叉。
function label(key: string): string {
  const i18nKey = CHANGELOG_SECTION_LABELS[key]
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
      <header class="release-head">
        <h2 v-html="renderInlineMarkdown(release.title)"></h2>
        <span v-if="release.current" class="tag">{{ t('changelog.current') }}</span>
      </header>
      <section v-for="section in release.sections" :key="section.key" class="block">
        <h3 class="block-head">
          <span class="block-title">{{ label(section.key) }}</span>
          <span class="count">{{ section.items.length }}</span>
        </h3>
        <ul class="items">
          <li v-for="(item, index) in section.items" :key="index" class="item">
            <span class="bullet" aria-hidden="true"></span>
            <span class="text" v-html="renderInlineMarkdown(item)"></span>
          </li>
        </ul>
      </section>
    </article>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; display: grid; gap: 18px; max-width: 1080px; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; }
.muted { color: color-mix(in srgb, currentColor 60%, transparent); font-size: 13px; }
.small { font-size: 12px; margin-top: 4px; }
.release {
  border: 1px solid color-mix(in srgb, currentColor 16%, transparent);
  border-radius: 12px;
  padding: 20px 22px;
  background: color-mix(in srgb, currentColor 2%, transparent);
}
.release.current {
  border-color: var(--ok-border);
  background: color-mix(in srgb, var(--ok-border) 7%, transparent);
}
.release-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.release h2 { margin: 0; font-size: 15px; font-family: var(--font-mono, ui-monospace, monospace); letter-spacing: .2px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--ok-border); }
.block { margin-top: 16px; }
.release-head + .block { margin-top: 0; }
.block-head { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; font-size: 12px; font-weight: 600; }
.block-head::before {
  content: '';
  width: 3px; height: 12px; border-radius: 2px;
  background: color-mix(in srgb, currentColor 45%, transparent);
}
.block-title { letter-spacing: .04em; }
.count {
  font-size: 11px; font-weight: 400; padding: 1px 6px; border-radius: 999px;
  border: 1px solid color-mix(in srgb, currentColor 20%, transparent);
  color: color-mix(in srgb, currentColor 70%, transparent);
  font-family: var(--font-mono, ui-monospace, monospace);
}
.items { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; font-size: 13px; line-height: 1.75; }
.item { display: grid; grid-template-columns: 6px 1fr; gap: 10px; align-items: start; }
.bullet { width: 6px; height: 6px; margin-top: 8px; border-radius: 50%; background: color-mix(in srgb, currentColor 35%, transparent); }
.text :deep(code) {
  padding: 1px 6px; border-radius: 4px;
  background: color-mix(in srgb, currentColor 9%, transparent);
  font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px;
}
.text :deep(strong) { font-weight: 600; }
.text :deep(a) { color: inherit; text-decoration: underline; }
.notice { padding: 8px 12px; border-radius: 6px; font-size: 13px; border: 1px solid var(--fail-border); }
@media (max-width: 640px) {
  .page { padding: 16px; }
  .release { padding: 16px; }
}
</style>
