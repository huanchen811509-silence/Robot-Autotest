<script lang="ts">
/**
 * 空态插图（UX-05；v5.20.0 / FR-v5.20-01 加第四态）。
 *
 * 四种状态在语义上必须分开，否则用户读不出「该做什么」：
 *
 * - `no-data`：这里本来就还没有东西 → 引导去创建；
 * - `no-result`：有数据但被筛选/搜索滤空了 → 引导改条件或清空；
 * - `error`：拿不到数据 → 引导重试，**不能**写成「暂无数据」（那是在说「一切正常，就是空的」）；
 * - `forbidden`：接口明确拒绝（403）→ 引导去找人开权限，**不能**引导重试
 *   （重试一万次也还是 403，那是最消耗信任的一种"提示"）。
 *
 * 为什么是几何线稿而不是插画图：本项目没有插画师，位图/复杂 SVG 在暗色下要另做一套，
 * 且体积会突破图标预算。几何形只用 3~5 个图元、颜色全走 token，亮暗两套自动成立。
 *
 * 类型单独放在普通 `<script>` 块里：`<script setup>` 中不允许 `export`，
 * 而 `EmptyState` 与 `DataTable` 都要 import 这个联合类型。
 */
export type IllustrationName = 'no-data' | 'no-result' | 'error' | 'forbidden'
</script>

<script setup lang="ts">
withDefaults(defineProps<{ name?: IllustrationName; width?: number }>(), {
  name: 'no-data',
  width: 96,
})
</script>

<template>
  <svg
    class="empty-illustration"
    :class="`empty-illustration--${name}`"
    :style="{ width: `${width}px` }"
    viewBox="0 0 96 72"
    role="presentation"
    aria-hidden="true"
  >
    <!-- 没有数据：一只空托盘 + 两缕上浮的虚线（"还没往里放东西"） -->
    <template v-if="name === 'no-data'">
      <path class="empty-illustration__plate" d="M20 34h56l-8 26H28z" />
      <path class="empty-illustration__line" d="M20 34h56" />
      <path class="empty-illustration__dash" d="M36 24h24M42 15h12" />
    </template>

    <!-- 搜不到：放大镜压在一个空列表上（"条件把结果滤光了"） -->
    <template v-else-if="name === 'no-result'">
      <path class="empty-illustration__line" d="M18 22h40M18 34h28M18 46h22" />
      <circle class="empty-illustration__lens" cx="62" cy="38" r="16" />
      <path class="empty-illustration__line" d="M73.5 49.5L84 60" />
      <path class="empty-illustration__accent" d="M56 38h12" />
    </template>

    <!-- 出错：一个断开的方框（"链路断了，不是没有"） -->
    <template v-else-if="name === 'error'">
      <rect class="empty-illustration__plate" x="24" y="18" width="48" height="36" rx="6" />
      <path class="empty-illustration__line" d="M36 36h8M52 36h8" />
      <path class="empty-illustration__bolt" d="M47 29l-5 9h6l-2 7 7-10h-6z" />
    </template>

    <!-- 无权限：一把锁扣在文档上（"内容存在，但你没钥匙"） -->
    <template v-else>
      <rect class="empty-illustration__plate" x="24" y="14" width="48" height="44" rx="6" />
      <path class="empty-illustration__line" d="M34 24h14M34 32h10" />
      <rect class="empty-illustration__plate" x="48" y="38" width="18" height="14" rx="3" />
      <path class="empty-illustration__line" d="M52 38v-4a5 5 0 0 1 10 0v4" />
      <path class="empty-illustration__accent" d="M57 45v2" />
    </template>
  </svg>
</template>

<style scoped>
.empty-illustration {
  display: block;
  height: auto;
  margin: 0 auto;
}

.empty-illustration__plate {
  fill: var(--surface-2);
  stroke: var(--border-strong);
  stroke-width: 1.5;
  stroke-linejoin: round;
}

.empty-illustration__line {
  stroke: var(--border-strong);
  stroke-width: 1.5;
  stroke-linecap: round;
  fill: none;
}

.empty-illustration__dash {
  stroke: var(--border-strong);
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-dasharray: 4 5;
  opacity: 0.7;
}

.empty-illustration__lens {
  fill: var(--surface-2);
  stroke: var(--border-strong);
  stroke-width: 1.5;
}

/* 每张图只有一处强调色，用来指向「可操作的那一步」 */
.empty-illustration__accent {
  stroke: var(--text-muted);
  stroke-width: 2;
  stroke-linecap: round;
  fill: none;
}

/* 闪电是面，不是线：填充版本在 96px 宽下才看得出是「断」而不是一条折线 */
.empty-illustration__bolt {
  fill: var(--text-muted);
  stroke: none;
}
</style>
