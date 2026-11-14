/**
 * W57-41/42 · 凭证选择器单测。
 *
 * 数据源 `@/api/credentialOptions` 全量 mock（不打网络）；naive-ui 的
 * NInput / NCheckbox / NSelect 换成同契约轻量桩（真实的 NSelect 是 teleport +
 * 虚拟列表，happy-dom 下不可控）。测四件与后端契约直接相关的事：
 * 四级排序 + 失败沉底、自动选中口径、敏感字段清理、手工提交 payload。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App, type Ref } from 'vue'
import { i18n } from '@/locales'
import CredentialPicker from './CredentialPicker.vue'
import { credentialOptionsApi, type CredentialOption } from '@/api/credentialOptions'

vi.mock('@/api/credentialOptions', () => ({
  credentialOptionsApi: {
    options: vi.fn(),
    collectManual: vi.fn(),
  },
}))

vi.mock('naive-ui', async () => {
  const { defineComponent, h: hh } = await import('vue')
  return {
    NInput: defineComponent({
      name: 'NInputStub',
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup(props, { emit, attrs }) {
        return () =>
          hh('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          })
      },
    }),
    NCheckbox: defineComponent({
      name: 'NCheckboxStub',
      props: { checked: { type: Boolean, default: false } },
      emits: ['update:checked'],
      setup(props, { emit, attrs }) {
        return () =>
          hh('input', {
            type: 'checkbox',
            ...attrs,
            checked: props.checked,
            onChange: (event: Event) =>
              emit('update:checked', (event.target as HTMLInputElement).checked),
          })
      },
    }),
    NSelect: defineComponent({
      name: 'NSelectStub',
      setup(_props, { attrs }) {
        return () => hh('select', { ...attrs })
      },
    }),
  }
})

function opt(partial: Partial<CredentialOption> = {}): CredentialOption {
  return {
    credentialId: 1,
    name: '凭据',
    credType: 'ssh',
    username: 'root',
    tier: 2,
    lastResult: '',
    lastVerifiedAt: '',
    boundDevices: 0,
    configured: true,
    ...partial,
  }
}

function makeSpies() {
  return {
    modelValue: vi.fn(),
    submitted: vi.fn(),
    failed: vi.fn(),
  }
}
type Spies = ReturnType<typeof makeSpies>

function handlers(s: Spies): Record<string, unknown> {
  return {
    'onUpdate:modelValue': s.modelValue,
    onSubmitted: s.submitted,
    onFailed: s.failed,
  }
}

let app: App | null = null
let host: HTMLElement | null = null

async function settle(): Promise<void> {
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

async function mountPicker(
  props: Record<string, unknown> = {},
  s: Spies = makeSpies(),
  pickerRef?: Ref<{ resetSensitive: () => void } | null>,
): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp({
    render: () =>
      h(CredentialPicker as never, {
        assetId: 5,
        modelValue: null,
        ...props,
        ...handlers(s),
        ...(pickerRef ? { ref: pickerRef } : {}),
      } as never),
  })
  app.use(i18n)
  app.mount(root)
  await settle()
  return root
}

function fill(root: HTMLElement, testid: string, value: string): void {
  const el = root.querySelector<HTMLInputElement>(`[data-testid="${testid}"]`)
  if (!el) throw new Error(`missing input ${testid}`)
  el.value = value
  el.dispatchEvent(new Event('input'))
}

async function toManual(root: HTMLElement): Promise<void> {
  root.querySelector<HTMLElement>('[data-testid="credential-mode-manual"]')!.click()
  await nextTick()
}

beforeEach(() => {
  vi.mocked(credentialOptionsApi.options).mockResolvedValue({ items: [], total: 0, truncated: false })
  vi.mocked(credentialOptionsApi.collectManual).mockResolvedValue({
    assetId: 5,
    oneShot: true,
    mode: 'manual',
    status: 'OK',
    channel: 'ssh',
  })
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  vi.clearAllMocks()
})

describe('CredentialPicker 四级排序与失败沉底', () => {
  it('成功项按 tier 升序、失败项一律沉底并标原因', async () => {
    vi.mocked(credentialOptionsApi.options).mockResolvedValue({
      items: [
        opt({ credentialId: 3, name: '其它', tier: 3, lastVerifiedAt: '2026-01-03' }),
        opt({ credentialId: 1, name: '本设备', tier: 0, lastResult: 'SUCCESS', lastVerifiedAt: '2026-01-02' }),
        opt({ credentialId: 4, name: '失败项', tier: 1, lastResult: 'AUTH_FAILED', lastVerifiedAt: '2026-01-04' }),
        opt({ credentialId: 2, name: '已配置', tier: 2 }),
      ],
      total: 4,
      truncated: false,
    })
    const root = await mountPicker()
    const ids = [...root.querySelectorAll<HTMLElement>('[data-testid^="credential-option-"]')].map(
      (el) => el.getAttribute('data-testid'),
    )
    expect(ids).toEqual([
      'credential-option-1',
      'credential-option-2',
      'credential-option-3',
      'credential-option-4',
    ])
    expect(root.querySelector('.credential-picker__option.is-failed')?.textContent).toContain(
      '最近验证失败',
    )
  })

  it('tier 徽标渲染四级标签', async () => {
    vi.mocked(credentialOptionsApi.options).mockResolvedValue({
      items: [opt({ tier: 0 }), opt({ credentialId: 2, tier: 1 }), opt({ credentialId: 3, tier: 2 })],
      total: 3,
      truncated: false,
    })
    const root = await mountPicker()
    const labels = [...root.querySelectorAll('.credential-picker__tier')].map(
      (el) => el.textContent?.trim(),
    )
    expect(labels).toContain('本设备成功')
    expect(labels).toContain('同型号/同域成功')
    expect(labels).toContain('已配置')
  })
})

describe('CredentialPicker 默认选中口径', () => {
  it('默认选中「本设备已验证成功」且带验证时间，并标出时间', async () => {
    vi.mocked(credentialOptionsApi.options).mockResolvedValue({
      items: [
        opt({ credentialId: 9, tier: 0, lastResult: 'SUCCESS', lastVerifiedAt: '2026-05-01 10:00' }),
        opt({ credentialId: 8, tier: 1, lastResult: 'SUCCESS', lastVerifiedAt: '2026-04-01' }),
      ],
      total: 2,
      truncated: false,
    })
    const s = makeSpies()
    const root = await mountPicker({}, s)
    expect(s.modelValue).toHaveBeenCalledWith(9)
    expect(root.querySelector('[data-testid="credential-auto"]')?.textContent).toContain(
      '2026-05-01 10:00',
    )
  })

  it('无验证记录（lastVerifiedAt 为空）不自动选', async () => {
    vi.mocked(credentialOptionsApi.options).mockResolvedValue({
      items: [opt({ credentialId: 9, tier: 0, lastResult: 'SUCCESS', lastVerifiedAt: '' })],
      total: 1,
      truncated: false,
    })
    const s = makeSpies()
    const root = await mountPicker({}, s)
    expect(s.modelValue).not.toHaveBeenCalled()
    expect(root.querySelector('[data-testid="credential-auto"]')).toBeNull()
  })

  it('宿主已给 modelValue 时不自动选，不覆盖用户选择', async () => {
    vi.mocked(credentialOptionsApi.options).mockResolvedValue({
      items: [opt({ tier: 0, lastResult: 'SUCCESS', lastVerifiedAt: '2026-01-01' })],
      total: 1,
      truncated: false,
    })
    const s = makeSpies()
    await mountPicker({ modelValue: 42 }, s)
    expect(s.modelValue).not.toHaveBeenCalled()
  })
})

describe('CredentialPicker 数据源与搜索降级', () => {
  it('assetId=0 用 fallbackOptions，且不打 options 接口', async () => {
    const root = await mountPicker({
      assetId: 0,
      fallbackOptions: [opt({ credentialId: 7, name: '回退凭据' })],
    })
    expect(credentialOptionsApi.options).not.toHaveBeenCalled()
    expect(root.textContent).toContain('回退凭据')
  })

  it('options 接口失败降级为空列表 + 可读说明，不拖垮表单', async () => {
    vi.mocked(credentialOptionsApi.options).mockRejectedValue(new Error('接口 500'))
    const root = await mountPicker()
    expect(root.querySelector('[data-testid="credential-error"]')?.textContent).toContain('接口 500')
    expect(root.querySelector('[data-testid="credential-options"]')).toBeNull()
  })

  it('搜索关键字即时过滤候选（本地反馈）', async () => {
    vi.mocked(credentialOptionsApi.options).mockResolvedValue({
      items: [
        opt({ credentialId: 1, name: 'RDMA 凭据', username: 'admin' }),
        opt({ credentialId: 2, name: 'BMC 凭据', username: 'root' }),
      ],
      total: 2,
      truncated: false,
    })
    const root = await mountPicker()
    const search = root.querySelector<HTMLInputElement>('[data-testid="credential-search"]')!
    search.value = 'bmc'
    search.dispatchEvent(new Event('input'))
    await nextTick()
    expect(root.querySelectorAll('[data-testid^="credential-option-"]')).toHaveLength(1)
    expect(root.textContent).toContain('BMC 凭据')
  })
})

describe('CredentialPicker 手工输入与敏感字段清理', () => {
  it('切换模式后立即清空口令与私钥', async () => {
    const root = await mountPicker()
    await toManual(root)
    fill(root, 'credential-manual-password', 'secret')
    await nextTick()
    expect(
      root.querySelector<HTMLInputElement>('[data-testid="credential-manual-password"]')!.value,
    ).toBe('secret')

    root.querySelector<HTMLElement>('[data-testid="credential-mode-list"]')!.click()
    await nextTick()
    await toManual(root)
    expect(
      root.querySelector<HTMLInputElement>('[data-testid="credential-manual-password"]')!.value,
    ).toBe('')
  })

  it('手工提交 payload 带 confirm、端口折入 connectOptions，提交后清空敏感字段', async () => {
    const s = makeSpies()
    const root = await mountPicker({}, s)
    await toManual(root)
    fill(root, 'credential-manual-username', 'root')
    fill(root, 'credential-manual-password', 'pw')
    fill(root, 'credential-manual-options', 'timeout=30')
    fill(root, 'credential-manual-port', '2222')
    await nextTick()
    root.querySelector<HTMLElement>('[data-testid="credential-manual-submit"]')!.click()
    await settle()

    expect(credentialOptionsApi.collectManual).toHaveBeenCalledWith(
      5,
      expect.objectContaining({
        username: 'root',
        password: 'pw',
        credType: 'ssh',
        connectOptions: 'timeout=30; port=2222',
        confirm: true,
        saveAsCredential: false,
      }),
    )
    expect(s.submitted).toHaveBeenCalledTimes(1)
    expect(
      root.querySelector<HTMLInputElement>('[data-testid="credential-manual-password"]')!.value,
    ).toBe('')
  })

  it('缺少设备 ID 时提交被拒并 emit failed，且不发请求', async () => {
    const s = makeSpies()
    const root = await mountPicker({ assetId: 0, fallbackOptions: [] }, s)
    await toManual(root)
    fill(root, 'credential-manual-username', 'root')
    fill(root, 'credential-manual-password', 'pw')
    await nextTick()
    root.querySelector<HTMLElement>('[data-testid="credential-manual-submit"]')!.click()
    await settle()
    expect(credentialOptionsApi.collectManual).not.toHaveBeenCalled()
    expect(s.failed).toHaveBeenCalledWith(expect.stringContaining('缺少设备 ID'))
    expect(
      root.querySelector<HTMLInputElement>('[data-testid="credential-manual-password"]')!.value,
    ).toBe('')
  })

  it('缺口令与私钥时 emit failed，不发请求', async () => {
    const s = makeSpies()
    const root = await mountPicker({}, s)
    await toManual(root)
    fill(root, 'credential-manual-username', 'root')
    await nextTick()
    root.querySelector<HTMLElement>('[data-testid="credential-manual-submit"]')!.click()
    await settle()
    expect(s.failed).toHaveBeenCalledWith('请填写口令或私钥')
    expect(credentialOptionsApi.collectManual).not.toHaveBeenCalled()
  })

  it('defineExpose 的 resetSensitive 供宿主关闭弹层时清空敏感字段', async () => {
    const pickerRef = ref<{ resetSensitive: () => void } | null>(null)
    const root = await mountPicker({}, makeSpies(), pickerRef)
    await toManual(root)
    fill(root, 'credential-manual-password', 'secret')
    await nextTick()
    expect(
      root.querySelector<HTMLInputElement>('[data-testid="credential-manual-password"]')!.value,
    ).toBe('secret')
    pickerRef.value?.resetSensitive()
    await nextTick()
    expect(
      root.querySelector<HTMLInputElement>('[data-testid="credential-manual-password"]')!.value,
    ).toBe('')
  })
})
