/**
 * 枚举值的**唯一**展示映射（FR-v0.2-34）。
 *
 * <p>问题：后端返回的是**受控词表**（`draft` / `FINISHED` / `P0` / `schedule`），
 * 界面直接 `{{ c.reviewStatus }}` 就把内部值暴露给用户了。中文界面里出现
 * 裸 `approved`、英文界面里出现裸 `草稿`，都属于"程序不一致"。
 *
 * <p>本文件之前，同一件事在 4 个页面各写了一遍（`OverviewView.stateText`、
 * `RunsView.stateLabel`、`ReportsView.stateLabel`、`PlansView.runStateLabel`），
 * 且大小写处理各不相同（有的 `toLowerCase()` 有的没有）。集中到一处后，
 * 新增一个状态只需要改一个地方。
 *
 * <p>**未知值不吞掉**：语言包里没有对应键时原样返回。宁可让用户看到一个
 * 不认识的英文，也不要显示空白——空白会让"数据没加载"和"状态没翻译"分不清。
 *
 * <p>**`ENUM_GROUPS` 必须是声明式的**：`tools/check_i18n.py` 靠它逐个值核对
 * `enum.<组>.<值>` 在语言包里是否存在。写成 `lookup('reviewStatus', v)` 这种
 * 散落调用时，脚本看不见"这个组该有哪些值"，删错一个键只能等到界面上
 * 冒出裸 `approved` 才发现（本项目真实发生过一次，见 TEST-PLAN §13.3）。
 */
import { i18n } from '@/locales'

/** 六组受控词表。数组同时供下拉框选项使用，避免选项和映射两处漂移。 */
export const REVIEW_STATUSES = ['draft', 'pending', 'approved', 'rejected'] as const
export const RUN_STATUSES = ['queued', 'preparing', 'running', 'finished', 'failed', 'canceled'] as const
export const PRIORITIES = ['P0', 'P1', 'P2', 'P3'] as const
export const TRIGGER_TYPES = ['manual', 'schedule', 'ci'] as const
export const MEMBER_ROLES = ['owner', 'admin', 'pm', 'engineer', 'member', 'viewer'] as const
/** 执行安全级（ADR-0013）：L0 只读 / L1 可逆写 / L2 高危 / LX 未判定 */
export const SAFETY_LEVELS = ['L0', 'L1', 'L2', 'LX'] as const
/**
 * 测试报告状态（v5.20.0 / FR-v5.20-04）：与后端 `ReportState` 同值域。
 * 之前这一列直接把 `REVOKED` 印给用户看。
 */
export const REPORT_STATUSES = ['draft', 'frozen', 'revoked', 'archived'] as const
/** 环境采集状态（v5.20.0 / FR-v5.20-04）：`HostInventorySpec.status` 的三个值。 */
export const COLLECT_STATUSES = ['ok', 'partial', 'failed'] as const

export type ReviewStatus = (typeof REVIEW_STATUSES)[number]
export type RunStatus = (typeof RUN_STATUSES)[number]
export type Priority = (typeof PRIORITIES)[number]
export type TriggerType = (typeof TRIGGER_TYPES)[number]
export type MemberRole = (typeof MEMBER_ROLES)[number]
export type SafetyLevel = (typeof SAFETY_LEVELS)[number]
export type ReportStatus = (typeof REPORT_STATUSES)[number]
export type CollectStatus = (typeof COLLECT_STATUSES)[number]

/**
 * 组名 → 取值集合。守门脚本读这份声明。
 *
 * <p>组名要与语言包 `enum.<组名>.<值小写>` 对齐；改这里等于改契约。
 */
export const ENUM_GROUPS = {
  reviewStatus: REVIEW_STATUSES,
  runStatus: RUN_STATUSES,
  priority: PRIORITIES,
  triggerType: TRIGGER_TYPES,
  memberRole: MEMBER_ROLES,
  safetyLevel: SAFETY_LEVELS,
  reportStatus: REPORT_STATUSES,
  collectStatus: COLLECT_STATUSES,
} as const

export type EnumGroup = keyof typeof ENUM_GROUPS

function lookup(group: EnumGroup, value: string | null | undefined): string {
  if (!value) return '—'
  // 运行状态在库里是小写、在部分 DTO 里是大写，统一按小写查键。
  const key = `enum.${group}.${value.toLowerCase()}`
  const te = i18n.global.te
  return te(key) ? i18n.global.t(key) : value
}

/** 用例评审状态：draft / pending / approved / rejected */
export function reviewStatusLabel(value: string | null | undefined): string {
  return lookup('reviewStatus', value)
}

/** 运行状态：queued / preparing / running / finished / failed / canceled */
export function runStatusLabel(value: string | null | undefined): string {
  return lookup('runStatus', value)
}

/** 优先级：P0～P3。两套语言同值，属于刻意保留的技术标识，不算漏译。 */
export function priorityLabel(value: string | null | undefined): string {
  return lookup('priority', value)
}

/** 触发方式：manual / schedule / ci */
export function triggerTypeLabel(value: string | null | undefined): string {
  return lookup('triggerType', value)
}

/** 项目内成员角色：owner / admin / pm / engineer / member / viewer */
export function memberRoleLabel(value: string | null | undefined): string {
  return lookup('memberRole', value)
}

/** 执行安全级：L0 只读 / L1 可逆写 / L2 高危 / LX 未判定 */
export function safetyLevelLabel(value: string | null | undefined): string {
  return lookup('safetyLevel', value)
}

/** 测试报告状态：draft / frozen / revoked / archived（v5.20.0 / FR-v5.20-04） */
export function reportStatusLabel(value: string | null | undefined): string {
  return lookup('reportStatus', value)
}

/** 环境采集状态：ok / partial / failed（v5.20.0 / FR-v5.20-04） */
export function collectStatusLabel(value: string | null | undefined): string {
  return lookup('collectStatus', value)
}

/**
 * 运行失败原因的人话化（v5.20.0 / FR-v5.20-04）。
 *
 * 后端 `failureReason` 落的是内核原始 token（`pytest_exit_1`、`TIMEOUT`…），
 * 列里直接印等于把日志贴到列表上。这里给出**一句人话**，同时调用方保留原值做悬浮，
 * 方便排障时对日志。
 *
 * 未知 token **不吞掉**：原样返回。宁可看到不认识的英文，也不要看到空白——
 * 空白会让"没采集到原因"和"原因不认识"分不清。
 */
const FAILURE_REASONS: Record<string, string> = {
  pytest_exit_0: 'env.failure.pytestExit0',
  pytest_exit_1: 'env.failure.pytestExit1',
  pytest_exit_2: 'env.failure.pytestExit2',
  pytest_exit_3: 'env.failure.pytestExit3',
  pytest_exit_4: 'env.failure.pytestExit4',
  pytest_exit_5: 'env.failure.pytestExit5',
  TIMEOUT: 'env.failure.timeout',
  CANCELED: 'env.failure.canceled',
  NODE_LOST: 'env.failure.nodeLost',
  SAFETY_LEVEL_BLOCKED: 'env.failure.safetyBlocked',
}

export function failureReasonLabel(value: string | null | undefined): string {
  if (!value) return '—'
  const token = value.trim()
  // 退出码可能带后缀（`pytest_exit_1 (3 failed)`），按前缀匹配，避免一个变体就漏翻
  const exact = FAILURE_REASONS[token]
  if (exact && i18n.global.te(exact)) return i18n.global.t(exact)
  const base = token.split(/[\s(]/)[0]
  const mapped = FAILURE_REASONS[base]
  if (mapped && i18n.global.te(mapped)) return i18n.global.t(mapped)
  return token
}

/** 失败原因是否已知（已知才给悬浮原始 token，未知本来就显示原样） */
export function hasFailureReasonLabel(value: string | null | undefined): boolean {
  if (!value) return false
  const token = value.trim()
  const base = token.split(/[\s(]/)[0]
  return Boolean(FAILURE_REASONS[token] || FAILURE_REASONS[base])
}

/** 执行安全级 → 色档：L2 红、LX 黄（按 L2 对待）、L1 绿、L0 灰。 */
export function safetyLevelTone(value: string | null | undefined): StatusTone {
  switch ((value ?? '').toUpperCase()) {
    case 'L0':
      return 'neutral'
    case 'L1':
      return 'ok'
    case 'L2':
      return 'fail'
    case 'LX':
      return 'warn'
    default:
      return 'neutral'
  }
}

/**
 * 该安全级是否禁止自动执行（ADR-0013）：L2 高危、LX 未判定。
 *
 * 与后端 `SafetyLevel#blocksExecution` 同口径——前端只负责禁用按钮并说明原因，
 * 真正的拦截在 `CaseRunService` / `TestSetRunService`，前端不拦也必须被拒。
 */
export function blocksExecution(value: string | null | undefined): boolean {
  const level = (value ?? '').toUpperCase()
  // 空值 / 未知值一律按 LX 处理：安全级没判就不许跑（ADR-0013），
  // 宁可多拦一次让人补判，也不能让高危用例静默跑上真实 DUT
  return level !== 'L0' && level !== 'L1'
}

/**
 * 状态徽标的语义色档（与 `StatusTag` 的 tone 一一对应）。
 *
 * 定义放在 util 而不是组件里，是因为「状态值 → 色档」属于显示契约，
 * 表格列、概览卡片、徽标都要用同一套；各页各写一个 `x === 'paused' ? 'neutral' : 'ok'`
 * 迟早会漂。`StatusTag` 反向 import 这个类型，不引入环（util 不依赖组件）。
 */
export type StatusTone = 'ok' | 'fail' | 'warn' | 'info' | 'neutral'

/**
 * 运行状态 → 色档。
 *
 * `running` 用 info（进行中不是"成功"也不是"告警"）；`queued/preparing` 用 warn
 * 表示"还没真的跑起来"，避免排队中的任务看起来已经完成。
 */
export function runStatusTone(value: string | null | undefined): StatusTone {
  switch ((value ?? '').toLowerCase()) {
    case 'finished':
      return 'ok'
    case 'failed':
      return 'fail'
    case 'running':
      return 'info'
    case 'queued':
    case 'preparing':
      return 'warn'
    default:
      return 'neutral'
  }
}

/**
 * 用例评审状态 → 色档。
 *
 * `draft` 用 neutral 而不是 warn：草稿是作者自己没提交，不是「需要人处理」；
 * `pending` 才用 warn —— 它表示队列里有人在等评审。这两个如果都标黄，
 * 列表里「等我审的」就淹没在草稿堆里了。
 */
export function reviewStatusTone(value: string | null | undefined): StatusTone {
  switch ((value ?? '').toLowerCase()) {
    case 'approved':
      return 'ok'
    case 'rejected':
      return 'fail'
    case 'pending':
      return 'warn'
    default:
      return 'neutral'
  }
}

/**
 * 用例结论 → 色档（PASS / FAIL / SKIP / UNKNOWN）。
 *
 * **结论值刻意不翻译**：`PASS` / `FAIL` 出现在套件树、对比表、原始日志和测试报告的同一行里，
 * 翻译成「通过」反而与日志对不上；这与 `P0`～`P3` 属于同一类技术标识。
 *
 * `SKIP` 给 neutral 而不是 warn：跳过是「没判定」，不是「需要人处理」。
 * 若标黄，一次大面积跳过的运行会在对比表里看起来像半屏告警。
 */
export function verdictTone(value: string | null | undefined): StatusTone {
  switch ((value ?? '').toUpperCase()) {
    case 'PASS':
      return 'ok'
    case 'FAIL':
      return 'fail'
    case 'SKIP':
      return 'neutral'
    default:
      return 'neutral'
  }
}
