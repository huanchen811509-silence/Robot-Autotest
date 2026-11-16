/**
 * 资产导入块（v5.18.0 / FR-v5.18-01 / W68-05 抽取后首次建立）。
 *
 * 这组只钉两条边界，两条都属于「权限或入口判断写错就静默丢功能」：
 * ① 只读用户不该看到导入按钮（点了必然 403，比没有更糟）；
 * ② 批次面板要能打开并取数——这是判据 7「结果页可跳 ?view=imports」的落点，
 *    面板打不开的话，导入完就再也找不到那批写了什么。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { i18n } from '@/locales'
import AssetImportBlock from '@/components/AssetImportBlock.vue'

const api = vi.hoisted(() => ({ batches: vi.fn() }))

vi.mock('@/api/globalAssets', () => ({
  globalAssetApi: { batches: (...args: unknown[]) => api.batches(...args) },
}))
vi.mock('@/components/ImportWizard.vue', () => ({
  default: { name: 'ImportWizard', props: ['kind'], template: '<div class="wizard-stub" />' },
}))

let app: App | null = null
let host: HTMLElement | null = null

async function mount(canWrite: boolean, openBatches = false): Promise<HTMLElement> {
  host = document.createElement('div')
  document.body.appendChild(host)
  const open = ref(false)
  const Wrapper = defineComponent({
    setup() {
      return () =>
        h(AssetImportBlock, {
          open: open.value,
          'onUpdate:open': (value: boolean) => {
            open.value = value
          },
          canWrite,
          openBatches,
        })
    },
  })
  app = createApp(Wrapper)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return host
}

beforeEach(() => {
  api.batches.mockReset().mockResolvedValue({
    items: [
      { id: 77, source_name: 'assets.xlsx', status: 'COMMITTED', total_rows: 12 },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('AssetImportBlock', () => {
  it('可写用户看到导入入口', async () => {
    const root = await mount(true)
    expect(root.querySelector('[data-testid="asset-import-open"]')).toBeTruthy()
  })

  it('只读用户没有导入入口，但批次入口仍在（查历史不是写操作）', async () => {
    const root = await mount(false)
    expect(root.querySelector('[data-testid="asset-import-open"]')).toBeNull()
    expect(root.querySelector('[data-testid="asset-batches-toggle"]')).toBeTruthy()
  })

  it('打开批次面板会取数并渲染行', async () => {
    const root = await mount(true)
    root.querySelector<HTMLElement>('[data-testid="asset-batches-toggle"]')?.click()
    await nextTick()
    await nextTick()
    expect(api.batches).toHaveBeenCalledTimes(1)
    expect(root.querySelector('[data-testid="asset-batches-panel"]')).toBeTruthy()
  })

  it('openBatches=true（?view=imports）时首屏直接展开并取数', async () => {
    const root = await mount(true, true)
    await nextTick()
    expect(root.querySelector('[data-testid="asset-batches-panel"]')).toBeTruthy()
    expect(api.batches).toHaveBeenCalled()
  })

  it('批次接口失败时给空表，不把整块功能打挂', async () => {
    api.batches.mockRejectedValue(new Error('boom') as never)
    const root = await mount(true, true)
    await nextTick()
    await nextTick()
    // 面板仍在（能看见「没有批次」），不是白屏
    expect(root.querySelector('[data-testid="asset-batches-panel"]')).toBeTruthy()
  })
})
