<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import markSvg from '@/assets/brand/logo-mark.svg?raw'

/**
 * 品牌标记（v4.5.0 / FR-v4.5-11、FR-v4.5-17）。
 *
 * 唯一来源是 `assets/brand/logo-mark.svg`（由 `logo/白泽Logo_白底黑线矢量版.svg`
 * 移除满幅白底、路径改为 currentColor 得到）；组件只用 `?raw` 内联一次，
 * 页面不得复制 SVG。颜色走 `--logo-fg`：亮色近黑、暗色香槟金，不跟随强调色。
 */
const props = withDefaults(
  defineProps<{
    size?: number
    withWordmark?: boolean
  }>(),
  { size: 28, withWordmark: false },
)

const { t } = useI18n()

const boxStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }))
</script>

<template>
  <span class="app-logo">
    <!-- 本地静态资产，非用户输入；v-html 只为让 currentColor 生效 -->
    <span
      class="app-logo__mark"
      :style="boxStyle"
      role="img"
      :aria-label="t('app.title')"
      v-html="markSvg"
    />
    <span v-if="withWordmark" class="app-logo__word">{{ t('app.title') }}</span>
  </span>
</template>

<style scoped>
.app-logo {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--logo-fg);
}

.app-logo__mark {
  flex: none;
  display: block;
}

.app-logo__mark :deep(svg) {
  display: block;
  width: 100%;
  height: 100%;
  color: inherit;
}

.app-logo__word {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (prefers-reduced-motion: no-preference) {
  .app-logo {
    transition: color 150ms ease;
  }
}
</style>
