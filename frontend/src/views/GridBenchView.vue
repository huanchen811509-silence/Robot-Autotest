<script setup lang="ts">
/**
 * EW32-06 长列表窗口化实测页（仅 DEV 路由，生产构建不包含）。
 *
 * <p>挂载 10,000 行后连续滚动 3 秒，用 rAF 统计真实帧率，并统计 DOM 中实际渲染的
 * `tr` 数量。达标条件：fps ≥ 50 且 rendered 远小于 total（证明窗口化生效，而不是
 * 靠一次性渲染全部行「跑得快」）。
 *
 * <p>结果写入 `[data-testid=grid-bench-result]`，供浏览器自动化读取，避免在测试
 * 脚本里重复实现测量逻辑。
 */
import { nextTick, onMounted, ref } from 'vue'
import type { DataTableColumns } from 'naive-ui'
import DataTable from '@/components/DataTable.vue'

interface BenchRow {
  id: number
  name: string
  status: string
}

// 生产产物里出现这个字符串即视为泄漏
const MARKER = '__GRID_BENCH_DEV_ONLY__'

const rows = ref<BenchRow[]>([])
const result = ref('pending')
const running = ref(false)

const columns: DataTableColumns<BenchRow> = [
  { title: 'ID', key: 'id', width: 100 },
  { title: 'Name', key: 'name' },
  { title: 'Status', key: 'status', width: 120 },
]

async function runBench(): Promise<void> {
  if (running.value) return
  running.value = true
  result.value = 'running'
  rows.value = Array.from({ length: 10_000 }, (_, index) => ({
    id: index + 1,
    name: `CASE-${String(index + 1).padStart(5, '0')}`,
    status: index % 7 === 0 ? 'fail' : 'pass',
  }))
  await nextTick()

  const scroller = document.querySelector<HTMLElement>(
    '[data-testid=grid-bench] .n-data-table-base-table-body',
  )
  const startedAt = performance.now()
  let frames = 0
  let rafId = 0

  const step = (): void => {
    frames += 1
    if (scroller) {
      scroller.scrollTop = Math.min(scroller.scrollTop + 24, scroller.scrollHeight)
    }
    if (performance.now() - startedAt < 3000) {
      rafId = requestAnimationFrame(step)
    } else {
      const elapsed = performance.now() - startedAt
      const fps = (frames * 1000) / elapsed
      const rendered = scroller ? scroller.querySelectorAll('tr').length : 0
      result.value = `fps=${fps.toFixed(1)};frames=${frames};rendered=${rendered};total=10000`
      running.value = false
    }
  }
  rafId = requestAnimationFrame(step)

  // 组件卸载时停止，避免测试页关闭后继续 rAF
  window.addEventListener('beforeunload', () => cancelAnimationFrame(rafId), { once: true })
}

onMounted(runBench)
</script>

<template>
  <main class="bench" :data-dev-marker="MARKER">
    <h1>Long list virtualization bench (DEV only)</h1>
    <p class="hint">
      Auto-runs on mount: 10,000 rows, continuous scroll for 3 seconds. Pass when
      fps ≥ 50 and rendered rows stay bounded.
    </p>
    <p class="result" data-testid="grid-bench-result">{{ result }}</p>
    <div data-testid="grid-bench">
      <DataTable :columns="columns" :data="rows" :max-height="480" />
    </div>
  </main>
</template>

<style scoped>
.bench {
  padding: var(--space-5);
}
.result {
  font-family: var(--font-mono, monospace);
  font-size: var(--font-size-lg);
}
.hint {
  color: var(--text-muted);
}
</style>
