import { beforeEach, describe, expect, it } from 'vitest'
import { ref } from 'vue'
import {
  DRAFT_TTL_MS,
  draftStorageKey,
  isDraftDirty,
  isDraftExpired,
  isSameValue,
  readDraft,
  removeDraft,
  useDraft,
  writeDraft,
} from '@/composables/useDraft'

beforeEach(() => {
  localStorage.clear()
})

describe('draftStorageKey', () => {
  it('按项目 / 表单 / 记录隔离，新建用 new 占位', () => {
    expect(draftStorageKey(1, 'case', null)).toBe('robot-autotest:draft:1:case:new')
    expect(draftStorageKey(1, 'case', 42)).toBe('robot-autotest:draft:1:case:42')
    expect(draftStorageKey(2, 'case', 42)).not.toBe(draftStorageKey(1, 'case', 42))
  })
})

describe('isSameValue / isDraftDirty', () => {
  it('对象与数组按内容深比较，不受键顺序影响', () => {
    expect(isSameValue({ a: 1, b: { c: [1, 2] } }, { b: { c: [1, 2] }, a: 1 })).toBe(true)
    expect(isSameValue({ a: 1 }, { a: '1' })).toBe(false)
    expect(isSameValue([1, 2], [1, 2, 3])).toBe(false)
  })

  it('与初始值相同不算脏', () => {
    const initial = { title: 'a', tags: ['x'] }
    expect(isDraftDirty({ ...initial, tags: ['x'] }, initial)).toBe(false)
    expect(isDraftDirty({ ...initial, title: 'b' }, initial)).toBe(true)
  })
})

describe('writeDraft / readDraft', () => {
  it('写入含时间戳的信封，可原样读出', () => {
    const now = () => 1_700_000_000_000
    writeDraft('k', { title: '草稿' }, localStorage, now)
    const draft = readDraft<{ title: string }>('k', { now: () => now() })
    expect(draft?.value.title).toBe('草稿')
    expect(draft?.savedAt).toBe(now())
  })

  it('超过 7 天视为过期并清除', () => {
    const savedAt = 1_700_000_000_000
    writeDraft('k', { title: 'old' }, localStorage, () => savedAt)
    const draft = readDraft('k', { now: () => savedAt + DRAFT_TTL_MS + 1 })
    expect(draft).toBeNull()
    expect(localStorage.getItem('k')).toBeNull()
  })

  it('恰好等于 TTL 边界时仍可用', () => {
    const savedAt = 1_700_000_000_000
    writeDraft('k', { title: 'edge' }, localStorage, () => savedAt)
    const draft = readDraft('k', { now: () => savedAt + DRAFT_TTL_MS })
    expect(draft?.value).toEqual({ title: 'edge' })
  })

  it('JSON 损坏或结构非法时容错并移除，不抛异常', () => {
    localStorage.setItem('bad', '{not json')
    expect(readDraft('bad')).toBeNull()
    expect(localStorage.getItem('bad')).toBeNull()

    localStorage.setItem('shape', JSON.stringify({ value: 'x' }))
    expect(readDraft('shape')).toBeNull()
    expect(localStorage.getItem('shape')).toBeNull()

    localStorage.setItem('arr', JSON.stringify([]))
    expect(readDraft('arr')).toBeNull()
  })

  it('removeDraft 只清对应键', () => {
    writeDraft('a', 1)
    writeDraft('b', 2)
    removeDraft('a')
    expect(localStorage.getItem('a')).toBeNull()
    expect(localStorage.getItem('b')).not.toBeNull()
  })
})

it('isDraftExpired 对非法时间戳按过期处理', () => {
  expect(isDraftExpired({ savedAt: Number.NaN })).toBe(true)
  expect(isDraftExpired({ savedAt: Date.now() })).toBe(false)
})

describe('useDraft', () => {
  it('上下文变化后自动切换到对应存储键', () => {
    const recordId = ref<number | null>(7)
    const draft = useDraft(() => ({ projectId: 1, formKey: 'case', recordId: recordId.value }))
    draft.save({ title: '记录 7 的草稿' })
    expect(draft.key.value).toBe('robot-autotest:draft:1:case:7')

    recordId.value = 8
    expect(draft.read()).toBeNull()
  })
})
