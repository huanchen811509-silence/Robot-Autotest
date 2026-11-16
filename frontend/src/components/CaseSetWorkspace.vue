<script setup lang="ts">
/**
 * 用例集三栏工作区（v5.12 / W62-02 / FR-v5.12-01；v5.19 / W69-05 加窄屏抽屉）。
 *
 * 左右两页（项目用例集 / 平台用例集库）共用**同一个布局壳**：
 * - 中栏 320px 放集合列表（搜索、新建、分页、平台库入口由各页 slot 自带）；
 * - 右工作区放集合头 + 成员表 + 成员 DetailDock；
 * - 未选中集合时右栏不是空白，而是 D-01 的自解释空态。
 *
 * 壳只管「形态」，不碰数据、不碰 URL、不碰接口：URL 由页面统一写一次，
 * 与 5.11 `useDetailDock` 的分工一致。
 *
 * **W69-05 窄屏形态**：视口 ≤ 1400px 时两栏并排会把集合表的操作列挤出可视区
 * （320px 中栏里塞 900+px 的表），因此该档位改成「列表铺满 + 右栏抽屉」：
 * - 抽屉由「已选中集合」驱动，关闭抽屉 = 收起当前集合（emit close，页面清空 activeSet）；
 * - 遮罩点击与抽屉头「收起」按钮走同一条路径，避免两套关闭语义；
 * - 宽屏（> 1400px）行为完全不变，回归由既有 spec + 三档视口探针守。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(defineProps<{
  /** 是否已选中集合。false 时右栏显示空态，且不挂载 workspace slot。 */
  active: boolean
  /** 窄屏断点（px）。默认 1400：恰好把常见 1366 笔记本与 1440 显示器分开。 */
  narrowAt?: number
}>(), { active: false, narrowAt: 1400 })

const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()

const narrow = ref(false)
let mq: MediaQueryList | null = null

function sync(e: MediaQueryList | MediaQueryListEvent): void {
  narrow.value = e.matches
}

onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  mq = window.matchMedia(`(max-width: ${props.narrowAt}px)`)
  sync(mq)
  // Safari < 14 只有 addListener，这里按能力退化，不额外引依赖
  if (typeof mq.addEventListener === 'function') mq.addEventListener('change', sync)
  else mq.addListener(sync)
})

onBeforeUnmount(() => {
  if (!mq) return
  if (typeof mq.removeEventListener === 'function') mq.removeEventListener('change', sync)
  else mq.removeListener(sync)
})

/** 抽屉是否展开：只有「窄屏 + 已选中」才展开，宽屏恒为 false。 */
const drawerOpen = computed(() => narrow.value && props.active)
</script>

<template>
  <div
    class="case-set-shell"
    :class="{ 'is-narrow': narrow, 'is-open': drawerOpen }"
    :data-narrow="narrow ? 'true' : 'false'"
    :data-drawer="drawerOpen ? 'open' : 'closed'"
  >
    <div class="case-set-workspace" data-testid="case-set-workspace">
      <aside class="set-nav" data-testid="set-nav" :aria-label="t('w62.navTitle')">
        <slot name="nav" />
      </aside>
      <section
        class="set-main"
        data-testid="set-main"
        :aria-label="t('w62.workspace')"
        :role="drawerOpen ? 'dialog' : undefined"
        :aria-modal="drawerOpen ? 'true' : undefined"
      >
        <header v-if="drawerOpen" class="drawer-head" data-testid="case-set-drawer-head">
          <span class="drawer-title">{{ t('w62.workspace') }}</span>
          <button
            type="button"
            class="link"
            data-testid="case-set-drawer-close"
            @click="emit('close')"
          >
            {{ t('common.close') }}
          </button>
        </header>
        <div v-if="!active" class="case-set-empty" data-testid="case-set-empty">
          <p class="case-set-empty-title">{{ t('w62.empty') }}</p>
          <p class="muted">{{ t('w62.emptyHint') }}</p>
        </div>
        <slot v-else name="workspace" />
      </section>
    </div>
    <!-- 遮罩放在 grid 之外：grid 的 first/last child 必须仍是 aside/section（既有 spec 断言） -->
    <div
      v-if="drawerOpen"
      class="drawer-mask"
      data-testid="case-set-drawer-mask"
      @click="emit('close')"
    />
  </div>
</template>

<style scoped>
.case-set-shell { width: 100%; }
.case-set-workspace {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  width: 100%;
}
.set-nav {
  min-width: 0;
  max-height: calc(100vh - 200px);
  overflow: auto;
  padding-right: 4px;
}
.set-main { min-width: 0; }
.case-set-empty {
  box-sizing: border-box;
  min-height: 220px;
  padding: 44px 24px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  text-align: center;
}
.case-set-empty-title { margin: 0 0 8px; font-size: 16px; font-weight: 600; }
.muted { margin: 0; opacity: 0.7; font-size: 13px; }

/* ---- 窄屏抽屉形态（W69-05） ---- */
.case-set-shell.is-narrow .case-set-workspace {
  grid-template-columns: minmax(0, 1fr);
}
.case-set-shell.is-narrow .set-nav {
  max-height: none;
  overflow: visible;
  padding-right: 0;
}
.case-set-shell.is-narrow.is-open .set-main {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1201;
  box-sizing: border-box;
  /* 1080px 是成员表 7 列 + 行内三按钮能挺住不换行的大致下限；比这窄就会挤成
     一列一变行（1366 视口下实测 760px 就是这样）。留 94vw 兜住更窄的窗口。 */
  width: min(1080px, 94vw);
  padding: var(--space-4);
  overflow: auto;
  background: var(--panel);
  border-left: 1px solid var(--border-strong);
  box-shadow: -12px 0 32px rgb(0 0 0 / 32%);
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  padding-bottom: var(--space-2);
  border-bottom: 1px dashed var(--border);
}
.drawer-title { font-size: var(--font-size-md); color: var(--text); }
.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: rgb(0 0 0 / 44%);
}
.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--accent);
  cursor: pointer;
  font-size: var(--font-size-sm);
}
</style>
