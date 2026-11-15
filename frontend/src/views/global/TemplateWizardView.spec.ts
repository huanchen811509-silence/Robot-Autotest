/**
 * TemplateWizardView 视图级回归（v5.10.0 / FR-v5.10-05、FR-v5.10-06、W60-09/10）。
 *
 * <p>盯四件事：
 * - 五步可前进 / 可回退；
 * - 空模板不勾确认不能提交（避免列表里冒出一堆打开就没内容的模板）；
 * - 编码前缀被别的模板占用 → 复核步硬拒并点名；
 * - 提交时明细按 itemType 落到 items（CASE / CASE_SET / SCENARIO / DEVICE）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import { projectApi } from '@/api/iam'
import { templateApi } from '@/api/globalTemplates'
import { globalAssetApi } from '@/api/globalAssets'
import TemplateWizardView from '@/views/global/TemplateWizardView.vue'

vi.mock('@/api/iam', () => ({
  projectApi: { nextCode: vi.fn() },
  userApi: { page: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn(), renew: vi.fn(), logout: vi.fn() },
}))

vi.mock('@/api/globalTemplates', () => ({
  templateApi: {
    list: vi.fn(),
    create: vi.fn(),
    prefixes: vi.fn(),
    get: vi.fn(),
    saveAsTemplate: vi.fn(),
    applyContent: vi.fn(),
  },
}))

vi.mock('@/api/globalAssets', () => ({
  globalAssetApi: { list: vi.fn() },
}))

const routerState = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, path: '/g/templates/new' }),
  useRouter: () => ({ push: routerState.push }),
}))

const picker = vi.hoisted(() => ({
  payload: {
    templateKeys: [] as string[],
    setIds: [] as number[],
    sets: [] as { id: number; name: string }[],
    caseIds: [] as number[],
    assetIds: [] as number[],
  },
}))
vi.mock('@/components/WizardContentPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'WizardContentPickerStub',
      props: { modelValue: { type: Object, default: undefined } },
      emits: ['update:modelValue'],
      setup(_props, { emit }) {
        return () =>
          h('div', { 'data-testid': 'content-picker' }, [
            h('button', {
              type: 'button',
              'data-testid': 'picker-emit',
              onClick: () => emit('update:modelValue', { ...picker.payload }),
            }, 'emit'),
          ])
      },
    }),
  }
})

let app: App | null = null
let host: HTMLElement | null = null

async function tick(): Promise<void> {
  await nextTick()
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(TemplateWizardView)
  app.use(i18n)
  app.mount(root)
  await tick()
  return root
}

function click(root: HTMLElement, selector: string): void {
  root.querySelector<HTMLButtonElement>(selector)?.click()
}

function fill(root: HTMLElement, selector: string, value: string): void {
  const el = root.querySelector<HTMLInputElement>(selector)
  if (!el) return
  el.value = value
  el.dispatchEvent(new Event('input'))
}

function disableBtn(root: HTMLElement, selector: string): boolean {
  return root.querySelector<HTMLButtonElement>(selector)?.disabled ?? false
}

/** 走到 review：逐步点「下一步」直到出现创建按钮。 */
async function toReview(root: HTMLElement): Promise<void> {
  fill(root, '[data-testid="template-name"]', '冒烟模板')
  await tick()
  for (let i = 0; i < 6 && !root.querySelector('[data-testid="template-create"]'); i += 1) {
    click(root, '[data-testid="template-next"]')
    await tick()
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(projectApi.nextCode).mockResolvedValue({ code: 'PRJ-RF-202611-001' } as never)
  vi.mocked(templateApi.list).mockResolvedValue([] as never)
  vi.mocked(templateApi.prefixes).mockResolvedValue([
    { code: 'PRJ', meaning: '默认项目前缀', builtin: 1, used: 0 },
    { code: 'API', meaning: '接口自动化', builtin: 0, used: 1 },
  ] as never)
  vi.mocked(templateApi.create).mockResolvedValue({ id: 88, name: '冒烟模板' } as never)
  vi.mocked(globalAssetApi.list).mockResolvedValue([] as never)
  picker.payload = {
    templateKeys: [], setIds: [], sets: [], caseIds: [], assetIds: [],
  }
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('v5.10.0 模板向导五步', () => {
  it('五步可达：basics → kernel → content → assets → review', async () => {
    const root = await mountView()
    expect(root.querySelector('[data-testid="template-name"]')).not.toBeNull()

    fill(root, '[data-testid="template-name"]', '冒烟模板')
    await tick()
    click(root, '[data-testid="template-next"]')
    await tick()
    expect(root.querySelector('[data-testid="template-kernel-pytest"]')).not.toBeNull()

    click(root, '[data-testid="template-next"]')
    await tick()
    expect(root.querySelector('[data-testid="content-picker"]')).not.toBeNull()

    click(root, '[data-testid="template-next"]')
    await tick()
    expect(root.textContent).toContain('预置资产')

    click(root, '[data-testid="template-next"]')
    await tick()
    expect(root.querySelector('[data-testid="template-create"]')).not.toBeNull()
  })

  it('可回退：从 kernel 退回 basics 保留已填名称', async () => {
    const root = await mountView()
    fill(root, '[data-testid="template-name"]', '回归模板')
    await tick()
    click(root, '[data-testid="template-next"]')
    await tick()
    click(root, '[data-testid="template-back-step"]')
    await tick()

    const input = root.querySelector<HTMLInputElement>('[data-testid="template-name"]')
    expect(input?.value).toBe('回归模板')
  })

  it('空模板未勾确认 → 创建按钮不可用（FR-v5.10-05 判据 4）', async () => {
    const root = await mountView()
    await toReview(root)

    expect(root.querySelector('[data-testid="template-empty-confirm"]')).not.toBeNull()
    expect(disableBtn(root, '[data-testid="template-create"]')).toBe(true)

    const box = root.querySelector<HTMLInputElement>('[data-testid="template-empty-confirm"]')!
    box.checked = true
    box.dispatchEvent(new Event('change'))
    await tick()
    expect(disableBtn(root, '[data-testid="template-create"]')).toBe(false)
  })

  it('编码前缀被占用 → 复核步点名且禁止提交（FR-v5.10-05 判据 3）', async () => {
    vi.mocked(templateApi.list).mockResolvedValue([
      { id: 1, name: '接口自动化模板', code: 'PRJ-RF-202610-001' },
    ] as never)
    const root = await mountView()
    await toReview(root)

    const conflict = root.querySelector('[data-testid="template-prefix-conflict"]')?.textContent ?? ''
    expect(conflict).toContain('PRJ')
    expect(conflict).toContain('接口自动化模板')
    expect(disableBtn(root, '[data-testid="template-create"]')).toBe(true)
  })

  it('提交：明细按 itemType 落 items（SCENARIO / CASE / CASE_SET）,编号用服务端序列值', async () => {
    picker.payload = {
      templateKeys: [],
      setIds: [5],
      sets: [{ id: 5, name: '冒烟集' }],
      caseIds: [11],
      assetIds: [],
    }
    const root = await mountView()
    fill(root, '[data-testid="template-name"]', '冒烟模板')
    await tick()
    // kernel 步加一个场景标签
    click(root, '[data-testid="template-next"]')
    await tick()
    fill(root, '[data-testid="template-scenario-input"]', '以太网')
    await tick()
    click(root, '[data-testid="template-scenario-add"]')
    await tick()
    let emitted = false
    for (let i = 0; i < 6 && !root.querySelector('[data-testid="template-create"]'); i += 1) {
      if (!emitted && root.querySelector('[data-testid="picker-emit"]')) {
        click(root, '[data-testid="picker-emit"]')
        await tick()
        emitted = true
      }
      click(root, '[data-testid="template-next"]')
      await tick()
    }
    const box = root.querySelector<HTMLInputElement>('[data-testid="template-empty-confirm"]')
    // 有内容时不该出现空模板确认
    expect(box).toBeNull()
    click(root, '[data-testid="template-create"]')
    await tick()

    expect(templateApi.create).toHaveBeenCalledTimes(1)
    const body = vi.mocked(templateApi.create).mock.calls[0][0] as Record<string, unknown>
    expect(body.code).toBe('PRJ-RF-202611-001')
    expect(body.items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ itemType: 'CASE', refId: 11 }),
        expect.objectContaining({ itemType: 'CASE_SET', refId: 5 }),
        expect.objectContaining({ itemType: 'SCENARIO', name: '以太网' }),
      ]),
    )
  })

  it('返回模板列表跳 /g/templates', async () => {
    const root = await mountView()
    click(root, '[data-testid="template-wizard-back"]')
    await tick()
    expect(routerState.push).toHaveBeenCalledWith('/g/templates')
  })
})
