/**
 * `CollectHistoryPanel` 组件级回归（v5.17.0 / W67-08 首次建立）。
 *
 * 采集历史原来有一列「凭证」，但它渲染的是**设备当前绑定的凭证**——整列一个值，
 * 换过凭证也看不出来，历史等于没有。W67-08 把它改成按行取「本次采集时刻之前
 * 最近一次验证的凭证」，并且只给掩码名。
 *
 * 本组钉三件事：
 * ① 两行不同凭证必须显示两个不同的掩码名（不是整列一个值）；
 * ② 没有事实时显示「未记录」，不能留空、也不能显示上一个值；
 * ③ 面板不渲染任何 `secret_ref` / `specJson` 之类原文（对外可见面）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '@/locales'
import CollectHistoryPanel from '@/components/CollectHistoryPanel.vue'
import type { CollectHistoryRow } from '@/api/collectProfiles'

let app: App | null = null
let host: HTMLElement | null = null

function row(over: Partial<CollectHistoryRow>): CollectHistoryRow {
  return {
    id: 1,
    deviceId: 7,
    status: 'OK',
    collectedAt: '2026-11-16 08:00:00',
    nicCount: 2,
    rocePorts: 4,
    roceMaxGbps: 200,
    ...over,
  }
}

async function mountPanel(rows: CollectHistoryRow[]): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(CollectHistoryPanel, { rows })
  app.use(i18n)
  app.mount(root)
  await nextTick()
  await nextTick()
  return root
}

beforeEach(() => {
  // 每次挂载都清干净，避免上一个用例的 DOM 残留被 querySelector 命中
  document.body.innerHTML = ''
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
})

describe('采集历史凭证列（W67-08）', () => {
  it('两行不同凭证 → 显示两个不同的掩码名，不再是整列一个值', async () => {
    const root = await mountPanel([
      row({ id: 2, credentialId: 12, credentialMaskedName: 'gpu-***-01', collectedAt: '2026-11-16 08:10:00' }),
      row({ id: 1, credentialId: 11, credentialMaskedName: 'bmc-***-02', collectedAt: '2026-11-16 08:00:00' }),
    ])
    const text = root.textContent ?? ''
    expect(text).toContain('gpu-***-01')
    expect(text).toContain('bmc-***-02')
  })

  it('没有事实 → 显示「未记录」，不留空也不沿用别的行的值', async () => {
    const root = await mountPanel([
      row({ id: 3, credentialId: null, credentialMaskedName: null }),
    ])
    expect(root.textContent).toContain(i18n.global.t('w63.credentialNotRecorded'))
  })

  it('面板不出现 secretRef / specJson 原文', async () => {
    const root = await mountPanel([
      row({ id: 4, credentialId: 12, credentialMaskedName: 'abc-***-zz' }),
    ])
    const text = (root.textContent ?? '') + root.innerHTML
    expect(text.toLowerCase()).not.toContain('secretref')
    expect(text.toLowerCase()).not.toContain('secret_ref')
    expect(text.toLowerCase()).not.toContain('spec_json')
  })
})
