import { describe, expect, it } from 'vitest'
import { confirmAction, useConfirmHost } from './useConfirm'

/** 替换 nativeConfirm 后最怕两件事：Promise 永远 pending、两个弹窗叠在一起。 */
describe('useConfirm', () => {
  const { current, settle } = useConfirmHost()

  it('确认 → true；取消 → false，且弹窗关闭', async () => {
    const yes = confirmAction('confirm?')
    expect(current.value?.message).toBe('confirm?')
    expect(current.value?.tone).toBe('default')
    settle(true)
    expect(await yes).toBe(true)
    expect(current.value).toBeNull()

    const no = confirmAction('cancel?', { tone: 'danger' })
    expect(current.value?.tone).toBe('danger')
    settle(false)
    expect(await no).toBe(false)
    expect(current.value).toBeNull()
  })

  it('新请求到达时把上一个按 false 结算（不留 pending）', async () => {
    const first = confirmAction('first')
    const second = confirmAction('second')
    expect(await first).toBe(false)
    expect(current.value?.message).toBe('second')
    settle(true)
    expect(await second).toBe(true)
  })

  it('settle 在没有请求时是安全的空操作', () => {
    settle(false)
    expect(current.value).toBeNull()
  })
})
