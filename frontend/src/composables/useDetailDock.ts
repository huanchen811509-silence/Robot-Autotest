/**
 * 列表行详情的选中态（v5.11.0 / W61-01 / FR-v5.11-01）。
 *
 * 只做「哪一行被选中」这一件事，不碰 URL。原因：
 * - URL 里同时装着「分类 + 筛选 + 详情」三态（FR-02 判据 6），三者必须由页面统一写一次，
 *   组件各自 `router.replace` 会互相覆盖成「最后一个赢」。
 * - 纯状态在单测里不需要装 Router，断言更直接。
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue'

export interface DetailDockState {
  /** 当前选中行 id（字符串形式，便于与 URL query 对齐）；空串表示收起。 */
  selected: Ref<string>
  /** 是否有详情在展示。 */
  isOpen: ComputedRef<boolean>
  /** 点同一行收起，点另一行替换（永不会出现第二个详情区）。 */
  toggle: (id: string | number) => void
  /** 直接指定选中项（URL 恢复 / 程序化打开）；传空则收起。 */
  set: (id: string | number | null | undefined) => void
  /** 收起。 */
  close: () => void
}

export function useDetailDock(
  options: { initial?: string | number | null } = {},
): DetailDockState {
  const selected = ref<string>(options.initial == null ? '' : String(options.initial))
  const isOpen = computed(() => selected.value !== '')

  function set(id: string | number | null | undefined): void {
    selected.value = id == null || id === '' ? '' : String(id)
  }

  function toggle(id: string | number): void {
    const next = String(id)
    selected.value = selected.value === next ? '' : next
  }

  function close(): void {
    selected.value = ''
  }

  return { selected, isOpen, toggle, set, close }
}
