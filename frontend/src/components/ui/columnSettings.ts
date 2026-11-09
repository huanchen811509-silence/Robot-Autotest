/**
 * 列设置的纯逻辑（v3.1.0「顺手」）。
 *
 * <p>「显示哪些列」与「列的顺序」在界面上是两件事，但存到 payload 里只有一个
 * `columns: string[]`（可见 key 的顺序）。`reconcileColumns` 负责把旧视图/本机偏好
 * 与新版本新增的列合并：未知 key 丢弃、缺失的新列按默认顺序追加并保持隐藏。
 */

export interface ColumnOption {
  key: string
  label: string
  visible: boolean
  /** 操作列/展开列这类必须存在的列，不允许被隐藏 */
  alwaysVisible?: boolean
}

/** 上移/下移一格；越界时原样返回，避免调用处再写边界判断。 */
export function moveColumn<T extends ColumnOption>(list: T[], index: number, delta: number): T[] {
  const target = index + delta
  if (index < 0 || index >= list.length || target < 0 || target >= list.length) {
    return list
  }
  const next = [...list]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item)
  return next
}

export function visibleKeys(list: ColumnOption[]): string[] {
  return list.filter((column) => column.visible).map((column) => column.key)
}

/**
 * 用保存的可见 key 列表还原完整列设置。
 * `savedKeys == null`（从未配置）时全部可见；空数组表示用户主动全部隐藏。
 */
export function reconcileColumns(
  defaults: ColumnOption[],
  savedKeys: string[] | null | undefined,
): ColumnOption[] {
  if (savedKeys === null || savedKeys === undefined) {
    return defaults.map((column) => ({ ...column, visible: true }))
  }

  const known = new Set(defaults.map((column) => column.key))
  const requested = savedKeys.filter((key, index) => known.has(key) && savedKeys.indexOf(key) === index)
  const requestedSet = new Set(requested)
  const orderedKeys = [
    ...requested,
    ...defaults.map((column) => column.key).filter((key) => !requestedSet.has(key)),
  ]

  const byKey = new Map(defaults.map((column) => [column.key, column]))
  return orderedKeys.flatMap((key) => {
    const column = byKey.get(key)
    if (!column) return []
    return [{
      ...column,
      visible: column.alwaysVisible === true || requestedSet.has(key),
    }]
  })
}
