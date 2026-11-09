/**
 * FR-v3.9-04 / ADR-39-05 · 列表页「每页条数」统一策略。
 *
 * 现状问题是两重限制叠加：页大小硬编码 10 + 表格 `max-height: calc(100vh - 340px)`，
 * 1440×900 实测 `/cases` 分页条下方空 337px（37% 视口）、`/runs` 231px（26%）。
 * 只放开其中一项都没用：条数放开但容器不撑高，空白照旧；容器撑高但条数还是 10，
 * 用户仍要频繁翻页。
 *
 * 这里只负责「条数」这一件事：
 * ① 档位固定 10 / 20 / 50 / 100 —— 不限自由输入。自由输入会造出 7、13 这种页，
 *    翻页语义变模糊，且后端分页没有收益；
 * ② 首次进入按视口估算（向上取档），大屏默认就多显示，小屏不硬塞；
 * ③ 用户手动选过就记住（localStorage），不再被视口估算覆盖 —— 记忆优先于猜测。
 *
 * 容器撑高由 `.page.is-list` + DataTable 的 `flex-height` 负责，两件事分开改、
 * 分开回滚，避免又变成"两处都改一点、都不彻底"。
 */

import { computed, ref, type WritableComputedRef } from 'vue'

/** 固定档位。顺序即下拉顺序，末项是上限。 */
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100] as const

export type PageSize = (typeof PAGE_SIZE_OPTIONS)[number]

const STORAGE_KEY = 'robot.list.pageSize'

/** 表格行高估算：舒适 44 / 紧凑 36，与 theme.css 的 `--table-row-h` 同源，取宽松值。 */
const ROW_HEIGHT = 44

/**
 * 表格以外的固定占位：顶栏 56 + 页头与工具条 ≈ 150 + 分页条 56 + 上下留白 ≈ 40。
 * 宁可估大一点（估大 → 档位取小），也不要估小导致一进页面就出现整页滚动。
 */
const CHROME_RESERVE = 302

/** 按视口高度估算合适的每页条数：向上取到最近的档位。 */
export function estimatePageSize(viewportHeight = window.innerHeight): PageSize {
  const rows = Math.floor((viewportHeight - CHROME_RESERVE) / ROW_HEIGHT)
  const fit = PAGE_SIZE_OPTIONS.find((option) => option >= rows)
  // rows 超过最大档位时 fit 为 undefined，落到最大档
  return fit ?? PAGE_SIZE_OPTIONS[PAGE_SIZE_OPTIONS.length - 1]
}

/** 读记忆值；非法值（旧版本残留、被改坏）一律当没记住。 */
export function readRememberedPageSize(): PageSize | null {
  try {
    const raw = Number(window.localStorage.getItem(STORAGE_KEY))
    return (PAGE_SIZE_OPTIONS as readonly number[]).includes(raw) ? (raw as PageSize) : null
  } catch {
    // 隐私模式 / 存储被禁用：不记忆，退回视口估算
    return null
  }
}

function persist(next: PageSize): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, String(next))
  } catch {
    // 存不进去不影响本次会话
  }
}

/** 单个视图内的每页条数：可读写，写入即持久化。 */
export function useListPageSize(): {
  size: WritableComputedRef<PageSize>
  /** naive-ui 的 `page-sizes` 要求可变数组，这里给副本而不是把常量暴露出去 */
  pageSizes: number[]
} {
  const remembered = readRememberedPageSize()
  const current = ref<PageSize>(remembered ?? estimatePageSize())

  const size = computed<PageSize>({
    get: () => current.value,
    set: (next) => {
      // 只接受档位内的值：外部（视图恢复、URL 回填）传进来的脏值不能污染状态
      if (!(PAGE_SIZE_OPTIONS as readonly number[]).includes(next)) return
      current.value = next
      persist(next)
    },
  })

  return { size, pageSizes: [...PAGE_SIZE_OPTIONS] }
}
