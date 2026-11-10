/**
 * `LoginView` 视图级回归（UX-04b 第七批）。
 *
 * 登录页只有 60 行，但它是**唯一一个"提交即改路由"**的页面，三类缺陷只有挂载才发现：
 * ① 必填校验被绕过（空用户名/空密码仍发请求）→ 白跑一次 401；
 * ② 提交按钮换了控件后**回车提交失效**（`NButton` 的 `type` 是语义色档，HTML 提交行为
 *    要用 `attr-type="submit"`；写错了页面看起来完全正常，只是回车没反应）；
 * ③ 失败后错误信息没落到界面上（只 console 里看得到）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/locales'
import { LOGOUT_INTENT_KEY } from '@/composables/useLogout'
import LoginView from '@/views/LoginView.vue'

const authState = vi.hoisted(() => ({
  // 用泛型签名声明参数而不是写实现：既不触发 ESLint 的 no-unused-vars（连 `_` 前缀也拦），
  // 又能让 `toHaveBeenCalledWith(username, password)` 保持类型检查
  login: vi.fn<(username: string, password: string) => Promise<void>>(),
  failWith: null as null | Error,
  authenticated: false,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    authenticated: authState.authenticated,
    login: async (username: string, password: string) => {
      if (authState.failWith) throw authState.failWith
      return authState.login(username, password)
    },
  }),
}))

const pushState = vi.hoisted(() => ({ replaced: [] as string[] }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { redirect: '/reports' } }),
  useRouter: () => ({
    replace: async (target: string) => {
      pushState.replaced.push(target)
    },
  }),
}))

let app: App | null = null
let host: HTMLElement | null = null
let pinia: ReturnType<typeof createPinia> | null = null

async function flush(): Promise<void> {
  for (let i = 0; i < 3; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }
}

async function mountView(): Promise<HTMLElement> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  host = root
  app = createApp(LoginView)
  app.use(pinia!)
  app.use(i18n)
  app.mount(root)
  await nextTick()
  return root
}

/** 往 `NInput` 里打字：它是受控组件，必须派发原生 input 事件才能走到 setter。 */
async function typeInto(root: HTMLElement, index: number, value: string): Promise<void> {
  const input = [...root.querySelectorAll<HTMLInputElement>('input')][index]
  expect(input).not.toBeUndefined()
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flush()
}

function submitButton(root: HTMLElement): HTMLButtonElement {
  const button = root.querySelector<HTMLButtonElement>('form button')
  expect(button).not.toBeNull()
  return button!
}

beforeEach(() => {
  authState.failWith = null
  authState.authenticated = false
  vi.clearAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  pushState.replaced = []
  pinia = createPinia()
  setActivePinia(pinia)
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = null
  host = null
  pinia = null
})

describe('LoginView 表单', () => {
  it('两个字段都渲染成输入框，密码框类型正确', async () => {
    const root = await mountView()
    const inputs = [...root.querySelectorAll<HTMLInputElement>('input')]
    expect(inputs.length).toBe(2)
    expect(inputs[1].type).toBe('password')
    expect(inputs[0].getAttribute('autocomplete')).toBe('username')
  })

  it('提交按钮必须是原生 submit（否则回车提交静默失效）', async () => {
    const root = await mountView()
    const button = submitButton(root)
    // NButton 的 type 走语义色档，HTML 提交行为只能在 attr-type 上
    expect(button.getAttribute('type')).toBe('submit')
  })

  it('用户名或密码为空时不发请求，并给必填提示', async () => {
    const root = await mountView()
    await typeInto(root, 0, 'evergreen')
    submitButton(root).click()
    await flush()

    expect(authState.login).not.toHaveBeenCalled()
    expect(root.textContent).toContain(i18n.global.t('login.required'))
  })

  it('填齐后提交走 auth.login，并按 redirect 参数跳转', async () => {
    const root = await mountView()
    await typeInto(root, 0, '  evergreen  ')
    await typeInto(root, 1, 'secret')
    submitButton(root).click()
    await flush()

    // 用户名两侧空白要去掉：后端按精确匹配，带空格会直接 401
    expect(authState.login).toHaveBeenCalledWith('evergreen', 'secret')
    expect(pushState.replaced).toEqual(['/reports'])
  })

  it('登录失败把错误渲染到界面上，而不是只留在控制台', async () => {
    authState.failWith = new Error('bad credentials')
    const root = await mountView()
    await typeInto(root, 0, 'evergreen')
    await typeInto(root, 1, 'wrong')
    submitButton(root).click()
    await flush()

    // 跳转不该发生
    expect(pushState.replaced).toEqual([])
    // ErrorLineView 会渲染错误摘要；用「非空且有内容」判定，不依赖具体文案
    const text = root.textContent ?? ''
    expect(text.length).toBeGreaterThan(0)
    expect(text).not.toContain(i18n.global.t('login.required'))
    expect(root.querySelector('.error-line, [role="alert"]')).not.toBeNull()
  })

  it('品牌区：登录页有标记 + 产品名 + 定位语，且不含版本号（版本在关于里）', async () => {
    const root = await mountView()
    const brand = root.querySelector('.login-brand')
    expect(brand).not.toBeNull()
    // 标记来自品牌资产（无 <text>），与 favicon 同形
    expect(brand!.querySelector('.app-logo__mark svg')).not.toBeNull()
    expect(brand!.querySelectorAll('text')).toHaveLength(0)
    expect(brand!.textContent).toContain(i18n.global.t('app.title'))
    expect(brand!.textContent).toContain(i18n.global.t('app.subtitle'))
    // 版本号不该在登录页出现：它只在「关于」里，且从 /_ping 读
    expect(root.textContent).not.toMatch(/v\d+\.\d+/)
  })
})

describe('LoginView 记住账号名与自动登录', () => {
  it('勾选记住账号名：登录成功后落 localStorage，重挂载自动预填', async () => {
    const root = await mountView()
    const checks = [...root.querySelectorAll<HTMLElement>('.login-remember .n-checkbox')]
    expect(checks.length).toBe(2)
    // NCheckbox 的原生 input 被 ui 库隐藏，点击组件根才可靠触发勾选
    ;(checks[0] as HTMLElement).click()
    await flush()

    await typeInto(root, 0, 'evergreen')
    await typeInto(root, 1, 'secret')
    submitButton(root).click()
    await flush()

    expect(localStorage.getItem('login_remember_username')).toBe('evergreen')
    // 只勾了账号名：凭据不得落盘
    expect(localStorage.getItem('login_saved_credentials')).toBeNull()

    const root2 = await mountView()
    const nameInput = [...root2.querySelectorAll<HTMLInputElement>('input')][0]!
    expect(nameInput.value).toBe('evergreen')
  })

  it('预置记住的凭据：未认证挂载即自动提交一次登录', async () => {
    localStorage.setItem(
      'login_saved_credentials',
      JSON.stringify({ username: 'evergreen', password: 'secret' }),
    )
    await mountView()
    await flush()

    expect(authState.login).toHaveBeenCalledWith('evergreen', 'secret')
    expect(pushState.replaced).toEqual(['/reports'])
  })

  it('自动登录失败：清掉记住的密码（防反复 401 撞锁定），保留账号名', async () => {
    authState.failWith = new Error('bad credentials')
    localStorage.setItem('login_remember_username', 'evergreen')
    localStorage.setItem(
      'login_saved_credentials',
      JSON.stringify({ username: 'evergreen', password: 'expired' }),
    )
    const root = await mountView()
    await flush()

    // mock 在 failWith 非空时直接 throw（spy 不计数），所以断言效果而非调用：
    // 「凭据被清」只会发生在 submit 的 catch 里，即自动登录确实尝试过并失败了
    // 密码已失效：必须清，否则下次自动重试会去撞「连续 5 次锁 15 分钟」
    expect(localStorage.getItem('login_saved_credentials')).toBeNull()
    expect(localStorage.getItem('login_remember_username')).toBe('evergreen')
    expect((root.textContent ?? '').length).toBeGreaterThan(0)
  })

  it('已认证状态不自动提交（路由守卫本该拦住，这里兜底防御）', async () => {
    authState.authenticated = true
    localStorage.setItem(
      'login_saved_credentials',
      JSON.stringify({ username: 'evergreen', password: 'secret' }),
    )
    await mountView()
    await flush()

    expect(authState.login).not.toHaveBeenCalled()
  })

  it('刚点过退出（退出意图标记）：不自动登录、不预填密码；账号名预填保留', async () => {
    localStorage.setItem('login_remember_username', 'evergreen')
    localStorage.setItem(
      'login_saved_credentials',
      JSON.stringify({ username: 'evergreen', password: 'secret' }),
    )
    sessionStorage.setItem(LOGOUT_INTENT_KEY, '1')
    const root = await mountView()
    await flush()

    // 不被自动登回：这是 v2.6.1 修复的现场（点退出 → 秒回登录态）
    expect(authState.login).not.toHaveBeenCalled()
    expect(pushState.replaced).toEqual([])
    // 密码不预填（退出后可能换账号，旧密码 + 新用户名会撞 401 甚至触发锁定）
    const inputs = [...root.querySelectorAll<HTMLInputElement>('input')]
    expect(inputs[1]!.value).toBe('')
    // 账号名记忆无凭据语义，保留预填
    expect(inputs[0]!.value).toBe('evergreen')
  })

  it('退出意图标记一次性消费：标记消掉后自动登录恢复原行为', async () => {
    localStorage.setItem(
      'login_saved_credentials',
      JSON.stringify({ username: 'evergreen', password: 'secret' }),
    )
    sessionStorage.setItem(LOGOUT_INTENT_KEY, '1')
    await mountView()
    await flush()
    expect(authState.login).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(LOGOUT_INTENT_KEY)).toBeNull()

    // 消费掉之后再进登录页（同一标签页）：自动登录照常——401 过期场景不受影响
    await mountView()
    await flush()
    expect(authState.login).toHaveBeenCalledWith('evergreen', 'secret')
    expect(pushState.replaced).toEqual(['/reports'])
  })
})
