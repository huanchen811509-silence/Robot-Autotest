/**
 * 导入五步向导（v5.18.0 / FR-v5.18-01 / W68-01 首次建立）。
 *
 * 这一组的价值在于：五步向导看着只是「把两步拆成五步」，但三处最容易悄悄坏的地方
 * 都不在界面上——
 * ① **取消要零写入**：多一个「下一步」按钮就多一条可能的提交路径；
 * ② **改列必须真的传给后端**：前端改了 select 却不带 mapping，界面看着改了、库里还是旧列；
 * ③ **预览与提交要逐数一致**：预览说新增 2、提交实际新增 3，是最难事后发现的一类错。
 *
 * 所以断言全部落在「调了什么、带没带参数、数字对不对」上，而不是「页面有没有这几个字」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import ImportWizard from '@/components/ImportWizard.vue'

const api = vi.hoisted(() => ({
  assetPreview: vi.fn(),
  assetCommit: vi.fn(),
  casePreview: vi.fn(),
  caseCommit: vi.fn(),
}))

vi.mock('@/api/globalAssets', () => ({
  globalAssetApi: {
    preview: (...args: unknown[]) => api.assetPreview(...args),
    commit: (...args: unknown[]) => api.assetCommit(...args),
  },
}))
vi.mock('@/api/globalCases', () => ({
  globalCaseApi: {
    previewImport: (...args: unknown[]) => api.casePreview(...args),
    commitImport: (...args: unknown[]) => api.caseCommit(...args),
  },
}))

let app: App | null = null
let host: HTMLElement | null = null
let closed = 0
let openedBatches = 0

const ASSET_PREVIEW = {
  batchId: 77,
  sha256: 'deadbeef',
  devices: 3,
  ports: 4,
  credentials: 1,
  total: 8,
  samples: [],
  warnings: [],
  sheetsScanned: 1,
  replayed: false,
  toCreate: 2,
  toUpdate: 1,
  conflicts: 0,
  headers: ['设备名称', '端口'],
  sampleRows: [
    ['sw1', 'p1'],
    ['sw2', 'p2'],
    ['sw3', 'p3'],
  ],
}

async function mountWizard(kind: 'asset' | 'case' = 'asset', force = false): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  closed = 0
  openedBatches = 0
  const Wrapper = defineComponent({
    setup() {
      return () =>
        h(ImportWizard, {
          kind,
          force,
          onClose: () => {
            closed += 1
          },
          onOpenBatches: () => {
            openedBatches += 1
          },
        })
    },
  })
  app = createApp(Wrapper)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return host
}

function attachFile(input: HTMLInputElement, name = 'assets.xlsx'): void {
  const file = new File(['x'], name, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  input.dispatchEvent(new Event('change'))
}

function activeStep(root: HTMLElement): string | null {
  const active = root.querySelector('[data-active="true"]')
  return active?.getAttribute('data-testid') ?? null
}

function click(root: HTMLElement, testId: string): void {
  root.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.click()
}

function selectField(root: HTMLElement, index: number, value: string): void {
  const select = root.querySelector<HTMLSelectElement>(`[data-testid="iw-map-${index}"]`)
  if (!select) return
  select.value = value
  select.dispatchEvent(new Event('change'))
}

beforeEach(() => {
  api.assetPreview.mockReset().mockResolvedValue(ASSET_PREVIEW)
  api.assetCommit.mockReset().mockResolvedValue({
    batchId: 77,
    sha256: 'deadbeef',
    devices: 2,
    ports: 4,
    credentials: 1,
    deprecations: 0,
    statusSupplemented: 0,
    warnings: [],
    replayed: false,
  })
  api.casePreview.mockReset().mockResolvedValue({
    batchId: 88,
    sha256: 'cafe',
    total: 10,
    newCount: 6,
    updateCount: 3,
    unchangedCount: 1,
    deprecateCount: 0,
    errorCount: 1,
    replayed: false,
    rows: [],
    errors: [],
    skippedSheets: [],
    toCreate: 6,
    toUpdate: 3,
    conflicts: 1,
    headers: ['用例编号', '标题'],
    sampleRows: [
      ['C-1', '标题一'],
      ['C-2', '标题二'],
    ],
  })
  api.caseCommit.mockReset().mockResolvedValue({
    batchId: 88,
    inserted: 6,
    updated: 3,
    unchanged: 1,
    deprecated: 0,
    replayed: false,
  })
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('五步向导：步态', () => {
  it('初始在第 1 步；选文件后预检，可前进到第 2 步，也可回退', async () => {
    const root = await mountWizard()
    expect(activeStep(root)).toBe('iw-step-1')
    expect(api.assetPreview).not.toHaveBeenCalled()

    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!)
    await nextTick()
    await nextTick()
    expect(api.assetPreview).toHaveBeenCalledTimes(1)
    // 首次预检不带 mapping：行为与 5.17 一致
    expect(api.assetPreview.mock.calls[0][1]).toBeUndefined()

    click(root, 'iw-next')
    await nextTick()
    expect(activeStep(root)).toBe('iw-step-2')

    click(root, 'iw-prev')
    await nextTick()
    expect(activeStep(root)).toBe('iw-step-1')
  })

  it('未选文件时「下一步」不可点', async () => {
    const root = await mountWizard()
    const next = root.querySelector<HTMLButtonElement>('[data-testid="iw-next"]')
    expect(next?.disabled).toBe(true)
  })
})

describe('五步向导：字段映射', () => {
  it('映射页按目标字段展示前 3 行样例，改列后标签与取值随之变化', async () => {
    const root = await mountWizard()
    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!)
    await nextTick()
    await nextTick()
    click(root, 'iw-next')
    await nextTick()

    // 还没指定字段 → 没有样例行，给的是提示而不是空白
    expect(root.querySelectorAll('[data-testid^="iw-sample-row-"]').length).toBe(0)
    expect(root.querySelector('[data-testid="iw-sample-empty"]')).toBeTruthy()

    // 把「设备名称」列指派为「端口名」：样例标签必须跟着变
    selectField(root, 0, 'portName')
    await nextTick()
    const rows = root.querySelectorAll('[data-testid^="iw-sample-row-"]')
    expect(rows.length).toBe(1)
    expect(rows[0].textContent).toContain('端口名')
    expect(rows[0].textContent).toContain('sw1 / sw2 / sw3')

    // 改成「设备名称」：标签变回设备名称，取值仍是该列 → 证明样例跟的是映射，不是写死的
    selectField(root, 0, 'deviceName')
    await nextTick()
    const after = root.querySelectorAll('[data-testid^="iw-sample-row-"]')
    expect(after[0].textContent).toContain('设备名称')
    expect(after[0].textContent).toContain('sw1 / sw2 / sw3')
  })

  it('改过列后进入预览会带上 mapping 重新预检', async () => {
    const root = await mountWizard()
    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!)
    await nextTick()
    await nextTick()
    click(root, 'iw-next')
    await nextTick()

    selectField(root, 1, 'portName')
    await nextTick()
    click(root, 'iw-next')
    await nextTick()
    await nextTick()

    expect(api.assetPreview).toHaveBeenCalledTimes(2)
    expect(api.assetPreview.mock.calls[1][1]).toEqual({ 端口: 'portName' })
    // 重新预检是异步的：步态要等 promise 链走完，不能只 tick 一次就断言
    await vi.waitFor(() => expect(activeStep(root)).toBe('iw-step-3'))
  })
})

describe('五步向导：预览 / 提交 / 结果', () => {
  it('预览三数取自响应，提交后一致性核对为 true', async () => {
    const root = await mountWizard()
    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!)
    await nextTick()
    await nextTick()
    click(root, 'iw-next')
    await nextTick()
    click(root, 'iw-next')
    await nextTick()

    expect(root.querySelector('[data-testid="iw-count-create"]')?.textContent).toContain('2')
    expect(root.querySelector('[data-testid="iw-count-update"]')?.textContent).toContain('1')
    expect(root.querySelector('[data-testid="iw-count-conflict"]')?.textContent).toContain('0')

    click(root, 'iw-next')
    await nextTick()
    expect(activeStep(root)).toBe('iw-step-4')

    click(root, 'iw-commit')
    await nextTick()
    await nextTick()
    expect(api.assetCommit).toHaveBeenCalledTimes(1)
    expect(activeStep(root)).toBe('iw-step-5')

    const consistent = root.querySelector('[data-testid="iw-consistent"]')
    expect(consistent?.getAttribute('data-consistent')).toBe('true')

    // 判据 7：结果页能跳到导入记录（父页负责真正的路由，这里只验事件发出）
    click(root, 'iw-open-batches')
    await nextTick()
    expect(openedBatches).toBe(1)
  })

  it('用例分支：走 previewImport / commitImport，冲突数取自 errorCount', async () => {
    const root = await mountWizard('case', true)
    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!, 'cases.xlsx')
    await nextTick()
    await nextTick()
    expect(api.casePreview).toHaveBeenCalledTimes(1)
    // force 由父页透传：true 时按当前库重算，false 时保持回放语义（旧行为）
    expect(api.casePreview.mock.calls[0][1]).toBe(true)

    click(root, 'iw-next')
    await nextTick()
    click(root, 'iw-next')
    await nextTick()
    expect(root.querySelector('[data-testid="iw-count-create"]')?.textContent).toContain('6')
    expect(root.querySelector('[data-testid="iw-count-conflict"]')?.textContent).toContain('1')

    click(root, 'iw-next')
    await nextTick()
    click(root, 'iw-commit')
    await nextTick()
    await nextTick()
    expect(api.caseCommit).toHaveBeenCalledWith(88)
    expect(activeStep(root)).toBe('iw-step-5')
  })
})

describe('五步向导：取消', () => {
  it('取消只关窗，不调用任何提交接口', async () => {
    const root = await mountWizard()
    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!)
    await nextTick()
    await nextTick()
    click(root, 'iw-next')
    await nextTick()
    click(root, 'iw-next')
    await nextTick()
    click(root, 'iw-next')
    await nextTick()

    click(root, 'iw-cancel')
    await nextTick()
    expect(closed).toBe(1)
    expect(api.assetCommit).not.toHaveBeenCalled()
    expect(api.caseCommit).not.toHaveBeenCalled()
  })
})

describe('五步向导：force 透传', () => {
  it('不传 force 时按回放语义预检（与 5.17 一致）', async () => {
    const root = await mountWizard('case')
    attachFile(root.querySelector<HTMLInputElement>('[data-testid="iw-file"]')!, 'cases.xlsx')
    await nextTick()
    await nextTick()
    expect(api.casePreview.mock.calls[0][1]).toBe(false)
  })
})
