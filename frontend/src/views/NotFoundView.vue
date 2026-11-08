<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { NButton } from 'naive-ui'

const { t } = useI18n()
</script>

<template>
  <div class="page">
    <h1 class="page-title">{{ t('notFound.title') }}</h1>
    <!--
      `custom` + `navigate`：路由跳转仍由 RouterLink 负责（保留它的事务语义与
      `prefetch` 行为），视觉与三态交给 `NButton`。直接把 `class="btn"` 挂在 `<a>` 上
      会得到一个"长得像按钮的链接"，键盘 Enter 能跳但拿不到按钮的 loading/disabled 状态位。
    -->
    <RouterLink v-slot="{ navigate }" to="/workbench" custom>
      <NButton type="primary" @click="navigate">{{ t('notFound.back') }}</NButton>
    </RouterLink>
  </div>
</template>
