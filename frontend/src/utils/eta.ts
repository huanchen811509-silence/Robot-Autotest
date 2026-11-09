/**
 * ETA / 耗时格式化（v3.1.0「顺手」）。
 *
 * 三档口径：< 60 秒按秒、< 1 小时按分秒、更长按小时分。
 * 不四舍五入到小时：剩余 59 分钟写成「59分」比「约 1 小时」更可判断。
 */
export interface EtaLabels {
  second: string
  minute: string
  hour: string
}

/** 非法 / 未知 ETA 返回破折号，避免界面出现 NaN 或负数。 */
export function formatEta(seconds: number | null | undefined, labels: EtaLabels): string {
  if (seconds === null || seconds === undefined) return '—'
  if (!Number.isFinite(seconds) || seconds < 0) return '—'
  const total = Math.floor(seconds)
  if (total < 60) return `${total}${labels.second}`
  if (total < 3600) {
    const minutes = Math.floor(total / 60)
    const remainder = total % 60
    return `${minutes}${labels.minute}${remainder}${labels.second}`
  }
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  return `${hours}${labels.hour}${minutes}${labels.minute}`
}

/** 倒计时按钮统一用 mm:ss（不足 1 分钟仍显示 00:xx），避免 5 分钟内句子长度跳动。 */
export function formatCountdown(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '00:00'
  const total = Math.floor(seconds)
  const minutes = Math.floor(total / 60)
  const remainder = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
}
