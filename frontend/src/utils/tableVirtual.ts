/**
 * 长列表窗口化判定（FR-3.2-06）。
 *
 * <p>naive-ui 的虚拟滚动要求有固定高度的滚动容器；`max-height: null`（整页滚动）
 * 时开启会把所有行都算进视口，等于没开，因此显式禁用。
 *
 * <p>默认阈值 200 行：分页大小上限 100，常规页面不会触发；只有「一次加载大量行」
 * 的页面（报告明细、审计导出）才进入窗口化，避免改变既有小表的交互语义。
 */
export const VIRTUAL_ROW_THRESHOLD = 200

/**
 * @param dataLength 当前行数
 * @param maxHeight  表格最大高度；null 表示不内滚
 * @param requested  调用方显式要求：false 关闭、true 强制、不传按行数自动
 */
export function shouldVirtualize(
  dataLength: number,
  maxHeight: string | number | null | undefined,
  requested: boolean | undefined,
): boolean {
  if (maxHeight === null) return false
  if (requested !== undefined) return requested
  return dataLength >= VIRTUAL_ROW_THRESHOLD
}
