/**
 * 表单草稿（v3.1.0「顺手」第二批）。
 *
 * 存储键按 projectId + formKey + recordId 隔离：同一表单的新建与编辑互不覆盖，
 * 切换项目也不会把 A 项目的草稿带到 B 项目。
 *
 * 纯函数（draftStorageKey / isDraftExpired / isSameValue 等）单独导出，测试优先覆盖
 * 这些分支；`useDraft` 只负责把当前上下文绑到 localStorage。
 */
import { computed, type MaybeRefOrGetter, toValue } from 'vue'

export const DRAFT_TTL_MS = 7 * 24 * 60 * 60 * 1000
export const DRAFT_PREFIX = 'robot-autotest:draft'

export interface DraftEnvelope<T> {
  value: T
  savedAt: number
}

export interface DraftContext {
  projectId: number | string | null | undefined
  formKey: string
  recordId: number | string | null | undefined
}

export interface UseDraftOptions {
  ttlMs?: number
  now?: () => number
  storage?: Storage
}

export function draftStorageKey(
  projectId: number | string | null | undefined,
  formKey: string,
  recordId: number | string | null | undefined,
): string {
  const project = projectId ?? 'none'
  const record = recordId ?? 'new'
  return `${DRAFT_PREFIX}:${project}:${formKey}:${record}`
}

/** 深比较：表单是纯 JSON 结构，递归处理对象/数组，避免 JSON.stringify 受键顺序影响。 */
export function isSameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  if (typeof a !== typeof b) return false
  if (a === null || b === null) return false
  if (typeof a !== 'object') return false

  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b)) return false
    if (a.length !== b.length) return false
    return a.every((item, index) => isSameValue(item, b[index]))
  }

  const left = a as Record<string, unknown>
  const right = b as Record<string, unknown>
  const leftKeys = Object.keys(left)
  const rightKeys = Object.keys(right)
  if (leftKeys.length !== rightKeys.length) return false
  return leftKeys.every((key) => isSameValue(left[key], right[key]))
}

/** 与初始值相同视为不脏：避免打开表单没改一个字也提示「已保存草稿」。 */
export function isDraftDirty<T>(current: T, initial: T): boolean {
  return !isSameValue(current, initial)
}

export function isDraftExpired(
  draft: Pick<DraftEnvelope<unknown>, 'savedAt'>,
  ttlMs: number = DRAFT_TTL_MS,
  now: number = Date.now(),
): boolean {
  if (!Number.isFinite(draft.savedAt)) return true
  return now - draft.savedAt > ttlMs
}

function resolveStorage(storage?: Storage): Storage | null {
  if (storage) return storage
  try {
    return localStorage
  } catch {
    return null
  }
}

export function readDraft<T>(
  key: string,
  options: Pick<UseDraftOptions, 'ttlMs' | 'now' | 'storage'> = {},
): DraftEnvelope<T> | null {
  const storage = resolveStorage(options.storage)
  if (!storage) return null
  const raw = storage.getItem(key)
  if (!raw) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    // JSON 损坏（手动改 localStorage / 旧版本格式）直接移除，避免每次打开都报错
    storage.removeItem(key)
    return null
  }

  if (!parsed || typeof parsed !== 'object') {
    storage.removeItem(key)
    return null
  }
  const envelope = parsed as Partial<DraftEnvelope<T>>
  if (typeof envelope.savedAt !== 'number' || !('value' in envelope)) {
    storage.removeItem(key)
    return null
  }
  if (isDraftExpired(envelope as DraftEnvelope<T>, options.ttlMs, options.now?.())) {
    storage.removeItem(key)
    return null
  }
  return envelope as DraftEnvelope<T>
}

export function writeDraft<T>(
  key: string,
  value: T,
  storage?: Storage,
  now: () => number = Date.now,
): void {
  const target = resolveStorage(storage)
  if (!target) return
  const envelope: DraftEnvelope<T> = { value, savedAt: now() }
  try {
    target.setItem(key, JSON.stringify(envelope))
  } catch {
    // 配额满 / 隐私模式：草稿不是核心链路，静默失败即可
  }
}

export function removeDraft(key: string, storage?: Storage): void {
  const target = resolveStorage(storage)
  target?.removeItem(key)
}

export function useDraft<T>(context: MaybeRefOrGetter<DraftContext>, options: UseDraftOptions = {}) {
  const key = computed(() => {
    const ctx = toValue(context)
    return draftStorageKey(ctx.projectId, ctx.formKey, ctx.recordId)
  })

  function save(value: T): void {
    writeDraft(key.value, value, options.storage, options.now)
  }

  function read(): DraftEnvelope<T> | null {
    return readDraft<T>(key.value, options)
  }

  function clear(): void {
    removeDraft(key.value, options.storage)
  }

  return { key, save, read, clear }
}
