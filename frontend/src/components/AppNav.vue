<script setup lang="ts">
/**
 * 侧栏导航渲染（v2.3 / FR-UI-07、29）。
 *
 * 抽出来的理由和顶栏一样：这几件事各自都有硬判据，留在外壳里只能靠人眼——
 *   - 叶子与分组形态必须可区分（叶子没有展开箭头、没有 `aria-expanded`）；
 *   - 默认**只展开当前激活组**，没有激活组时展开第一组；
 *   - 合并组内「运行态 / 报告态」之间只画 1 条细分隔线。
 *
 * 展开态落 `localStorage['robot.nav.expanded']`，且**用户手型优先于默认策略**：
 * 手动折叠过的组，切到它的子页也不会被强行展开（v1.0 的 watch 会强行展开，已撤掉）。
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import {
  activeGroupKey,
  isGroup,
  isNodeActive,
  isSectionBreak,
  leafTarget,
  type NavGroup,
  type NavLeaf,
  type NavNode,
} from '@/config/navigation'

const props = defineProps<{ nodes: NavNode[] }>()
const { t } = useI18n()
const route = useRoute()

const NAV_EXPANDED_KEY = 'robot.nav.expanded'
const expanded = ref<Record<string, boolean>>(loadExpanded())

function loadExpanded(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(NAV_EXPANDED_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : {}
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, boolean>) : {}
  } catch {
    return {}
  }
}

function isExpanded(group: NavGroup): boolean {
  const saved = expanded.value[group.key]
  if (saved !== undefined) return saved
  const active = activeGroupKey(route)
  if (active) return group.key === active
  return group.key === props.nodes[0]?.key
}

function toggleGroup(group: NavGroup): void {
  expanded.value = { ...expanded.value, [group.key]: !isExpanded(group) }
  try {
    localStorage.setItem(NAV_EXPANDED_KEY, JSON.stringify(expanded.value))
  } catch {
    // 隐私模式写不进：不影响本次会话的展开/收起
  }
}

function childrenOf(node: NavNode): NavLeaf[] {
  return isGroup(node) ? node.children : []
}

function isActive(node: NavNode): boolean {
  return isNodeActive(node, route)
}

/** 当前激活组：用于给分组头加 `has-active`（不改变展开态）。 */
const activeKey = computed(() => activeGroupKey(route))
</script>

<template>
  <nav class="app-nav" :aria-label="t('menu.label')">
    <template v-for="node in props.nodes" :key="node.key">
      <RouterLink
        v-if="!isGroup(node)"
        :to="leafTarget(node)"
        class="app-nav-item"
        :class="{ active: isActive(node) }"
        :title="t(node.labelKey)"
        :aria-current="isActive(node) ? 'page' : undefined"
      >
        <AppIcon :name="node.icon" :size="16" />
        <span>{{ t(node.labelKey) }}</span>
      </RouterLink>

      <div v-else class="app-nav-group">
        <button
          type="button"
          class="app-nav-item app-nav-group-head"
          :class="{ 'has-active': node.key === activeKey || isActive(node) }"
          :aria-expanded="isExpanded(node)"
          :title="t(node.labelKey)"
          @click="toggleGroup(node)"
        >
          <AppIcon :name="node.icon" :size="16" />
          <span>{{ t(node.labelKey) }}</span>
          <AppIcon
            class="app-nav-chevron"
            :name="isExpanded(node) ? 'chevronDown' : 'chevronRight'"
            :size="16"
          />
        </button>
        <div v-show="isExpanded(node)" class="app-nav-children" :data-group="node.key">
          <template v-for="(child, index) in childrenOf(node)" :key="child.key">
            <!-- v2.3 / FR-UI-29：合并组内「运行态 / 报告态」之间只画 1 条细分隔线，
                 不引三级层级。分隔线是纯装饰，不参与选中与能力过滤。 -->
            <span
              v-if="isSectionBreak(childrenOf(node), index)"
              class="app-nav-sep"
              role="presentation"
              aria-hidden="true"
            />
            <RouterLink
              :to="leafTarget(child)"
              class="app-nav-item app-nav-sub"
              :class="{ active: isActive(child) }"
              :title="t(child.labelKey)"
              :aria-current="isActive(child) ? 'page' : undefined"
            >
              <AppIcon :name="child.icon" :size="16" />
              <span>{{ t(child.labelKey) }}</span>
            </RouterLink>
          </template>
        </div>
      </div>
    </template>
  </nav>
</template>
