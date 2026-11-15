<script setup lang="ts">
/**
 * 用例集三栏工作区（v5.12 / W62-02 / FR-v5.12-01）。
 *
 * 左右两页（项目用例集 / 平台用例集库）共用**同一个布局壳**：
 * - 中栏 320px 放集合列表（搜索、新建、分页、平台库入口由各页 slot 自带）；
 * - 右工作区放集合头 + 成员表 + 成员 DetailDock；
 * - 未选中集合时右栏不是空白，而是 D-01 的自解释空态。
 *
 * 壳只管「形态」，不碰数据、不碰 URL、不碰接口：URL 由页面统一写一次，
 * 与 5.11 `useDetailDock` 的分工一致。
 */
import { useI18n } from 'vue-i18n'

withDefaults(defineProps<{
  /** 是否已选中集合。false 时右栏显示空态，且不挂载 workspace slot。 */
  active: boolean
}>(), { active: false })

const { t } = useI18n()
</script>

<template>
  <div class="case-set-workspace" data-testid="case-set-workspace">
    <aside class="set-nav" data-testid="set-nav" :aria-label="t('w62.navTitle')">
      <slot name="nav" />
    </aside>
    <section class="set-main" data-testid="set-main" :aria-label="t('w62.workspace')">
      <div v-if="!active" class="case-set-empty" data-testid="case-set-empty">
        <p class="case-set-empty-title">{{ t('w62.empty') }}</p>
        <p class="muted">{{ t('w62.emptyHint') }}</p>
      </div>
      <slot v-else name="workspace" />
    </section>
  </div>
</template>

<style scoped>
.case-set-workspace {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  width: 100%;
}
.set-nav {
  min-width: 0;
  max-height: calc(100vh - 210px);
  overflow: auto;
  padding-right: 4px;
}
.set-main { min-width: 0; }
.case-set-empty {
  box-sizing: border-box;
  min-height: 220px;
  padding: 48px 24px;
  border: 1px dashed var(--n-border-color, #d9d9d9);
  border-radius: 8px;
  text-align: center;
}
.case-set-empty-title { margin: 0 0 8px; font-size: 16px; font-weight: 600; }
.muted { margin: 0; opacity: 0.7; font-size: 13px; }
@media (max-width: 1100px) {
  .case-set-workspace { grid-template-columns: minmax(0, 1fr); }
  .set-nav { max-height: 320px; }
}
</style>
