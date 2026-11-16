#!/usr/bin/env node
/**
 * 登录页与帮助入口探针（v5.20.0 / FR-v5.20-07）。
 *
 * 守三件事：
 * ① 登录页跟随主题：dark 下背景必须是暗的（计算样式层面的实测，不是"看起来"）；
 * ② 登录页版本号与 `/_ping` 一致（同源，不另抄一份）；
 * ③ 帮助入口 ≥3 处：登录页页脚、登录后账户菜单、`?` 快捷键面板。
 *
 * 用法：node scripts/ux-probes/login-brand.mjs [--json]
 */
import { writeFileSync } from 'node:fs'
import { openSession, goto, login, closeSession, sleep, waitFor, APP } from './cdp.mjs'

const args = { json: process.argv.includes('--json') }
const out = { fails: [] }
const fails = out.fails

function push(cond, msg) {
  if (!cond) fails.push(msg)
}

const s = await openSession({ width: 1440, height: 900 })
try {
  // ---- ① / ②：暗色下的登录页 ----
  await goto(s, `${APP}/login`, 1800)
  await s.evaluate(`(() => { try { localStorage.clear(); localStorage.setItem('theme','dark') } catch(e){} return 1 })()`)
  await goto(s, `${APP}/login`, 2400)
  await sleep(900)

  out.dark = await s.evaluate(`(() => {
    const theme = document.documentElement.getAttribute('data-theme');
    const body = getComputedStyle(document.body).backgroundColor;
    const wrap = document.querySelector('.login-wrap');
    const wrapBg = wrap ? getComputedStyle(wrap).backgroundColor : '';
    // 把 rgb 拆出来算亮度，避免拿"看起来黑不黑"当判据
    const lum = (c) => {
      const m = c.match(/rgba?\\((\\d+), ?(\\d+), ?(\\d+)/);
      if (!m) return null;
      return (Number(m[1]) * 299 + Number(m[2]) * 587 + Number(m[3]) * 114) / 1000;
    };
    return { theme, body, wrapBg, bodyLum: lum(body), wrapLum: wrapBg ? lum(wrapBg) : null };
  })()`)
  push(out.dark.theme === 'dark', `暗色主题没生效（data-theme=${out.dark.theme}）`)
  if (out.dark.wrapLum !== null) {
    push(out.dark.wrapLum < 90, `暗色下登录容器背景偏亮（亮度 ${Math.round(out.dark.wrapLum)}）`)
  }
  if (out.dark.bodyLum !== null) {
    push(out.dark.bodyLum < 110, `暗色下页面背景偏亮（亮度 ${Math.round(out.dark.bodyLum)}）`)
  }

  out.version = await s.evaluate(`(() => {
    const el = document.querySelector('[data-testid="login-version"]');
    return el ? (el.textContent || '').trim() : '';
  })()`)
  const ping = await fetch(`${APP}/api/_ping`).then((r) => r.json()).catch(() => null)
  const pingVersion = ping ? (ping.version || ping.data?.version || '') : ''
  out.pingVersion = pingVersion
  push(Boolean(out.version), '登录页没有显示版本号')
  if (out.version && pingVersion) {
    push(out.version.replace(/^v/, '') === String(pingVersion).replace(/^v/, ''),
      `登录页版本号 ${out.version} 与 /_ping (${pingVersion}) 不一致`)
  }

  // 帮助入口（第 1 处：登录页页脚）
  const helpBtn = await s.evaluate(`(() => {
    const b = document.querySelector('[data-testid="login-help"]');
    if (!b) return null;
    b.scrollIntoView({ block: 'center' });
    const r = b.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height };
  })()`)
  push(Boolean(helpBtn), '登录页没有「登录遇到问题」入口')
  if (helpBtn) {
    push(helpBtn.w > 0 && helpBtn.h > 0, '登录页帮助入口尺寸为 0（不可见）')
    const click = async (b) => {
      await s.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: b.x, y: b.y, button: 'left', clickCount: 1 })
      await s.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: b.x, y: b.y, button: 'left', clickCount: 1 })
    }
    await click(helpBtn)
    await sleep(900)
    out.helpOpened = await s.evaluate(`/登录帮助|Sign-in help/.test(document.body.innerText)`)
    push(out.helpOpened, '点登录页帮助入口没有打开帮助内容')
    // 关掉，避免挡住后面的登录
    await s.evaluate(`(() => { const b=[...document.querySelectorAll('button')].find(e=>(e.innerText||'').trim()==='关闭'||(e.innerText||'').trim()==='Close'); if (b) b.click(); return 1 })()`)
    await sleep(500)
  }

  // ---- ③：登录后账户菜单与快捷键面板 ----
  await s.evaluate(`(() => { try { localStorage.setItem('theme','dark') } catch(e){} return 1 })()`)
  await login(s)
  await sleep(1500)

  out.accountMenuHelp = await s.evaluate(`(() => {
    // 账户菜单触发按钮：AccountMenu.vue 的 .account-trigger（用类名，不用位置选择器，
    // 位置选择器会先命中顶栏其它按钮，把人骗过去）
    const btn = document.querySelector('.account-trigger');
    if (!btn) return null;
    btn.click();
    return true;
  })()`)
  await sleep(800)
  out.accountMenuText = await s.evaluate(`(document.body.innerText || '').slice(0, 1200)`)
  out.hasShortcutEntry = /快捷键|Shortcuts/i.test(out.accountMenuText)
  push(Boolean(out.accountMenuHelp), '找不到账户菜单入口')
  push(out.hasShortcutEntry, '账户菜单里没有「快捷键/帮助」项')
  await s.evaluate(`(() => { document.body.click(); return 1 })()`)

  // `?` 打开快捷键面板
  await s.send('Input.dispatchKeyEvent', { type: 'keyDown', windowsVirtualKeyCode: 191, key: '?', text: '?' })
  await s.send('Input.dispatchKeyEvent', { type: 'keyUp', windowsVirtualKeyCode: 191, key: '?' })
  await sleep(900)
  out.shortcutPanel = await s.evaluate(`/快捷键|Shortcuts/.test(document.body.innerText)`)
  push(out.shortcutPanel, '按 ? 没有打开快捷键面板')

  if (process.env.SHOT_DIR) {
    const r = await s.send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${process.env.SHOT_DIR}/login-dark.png`, Buffer.from(r.data, 'base64'))
  }
} finally {
  await closeSession(s)
}

const helpSpots = [out.helpOpened, out.hasShortcutEntry, out.shortcutPanel].filter(Boolean).length
out.helpSpots = helpSpots
push(helpSpots >= 3, `帮助入口只有 ${helpSpots} 处可达（要求 ≥3）`)

if (args.json) console.log(JSON.stringify(out, null, 2))
else {
  console.log(`[主题] data-theme=${out.dark?.theme} 容器亮度=${out.dark?.wrapLum === null ? 'n/a' : Math.round(out.dark.wrapLum)} 页面亮度=${out.dark?.bodyLum === null ? 'n/a' : Math.round(out.dark.bodyLum)}`)
  console.log(`[版本] 登录页=${out.version || '(空)'} /_ping=${out.pingVersion || '(空)'}`)
  console.log(`[帮助] 登录页=${out.helpOpened} 账户菜单=${out.hasShortcutEntry} 快捷键面板=${out.shortcutPanel} → ${helpSpots}/3`)
}

if (fails.length) {
  console.error(`\n[FAIL] ${fails.length} 条：`)
  for (const f of fails) console.error('  - ' + f)
  process.exit(1)
}
console.log('\n[PASS] 登录页与帮助入口检查通过')
