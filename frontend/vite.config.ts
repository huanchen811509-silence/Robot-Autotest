import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 单测环境（UX-02 引入 vitest）：happy-dom 提供 localStorage / document（locales/index.ts
// 在模块顶层就读 localStorage，node 纯环境会炸）。用例文件放在 src/**/*.spec.ts。
const test = {
  environment: 'happy-dom',
  include: ['src/**/*.spec.ts'],
  setupFiles: ['src/test-setup.ts'],
  // 2026-11-09 B1：全量 73 文件并发时组件挂载偶发越过默认 5s 边界
  // （单跑该用例 598ms，两次单文件复跑 8/8 全绿）。只放大超时余量，
  // 不改任何断言、不跳过用例；根因与复跑证据见 v3.2.0/flaky-ledger.md §3.1。
  testTimeout: 10000,
  hookTimeout: 10000,
}

// 开发期把 /api 代理到本地后端（默认 :8081），生产由同域静态服务承载，前端不直连任何外部服务（N13）
//
// 两个值都允许用环境变量覆盖，因为一台机器上跑多个后端实例是常态：
// 8081 被别的系统占用时用 `VITE_API_TARGET=http://127.0.0.1:8082 npm run dev` 即可，
// 不必改这份文件（改了容易误提交，别人 clone 下来就连错后端）。
const API_TARGET = process.env.VITE_API_TARGET ?? 'http://127.0.0.1:8081'
const DEV_PORT = Number(process.env.VITE_DEV_PORT ?? 5173)
// 编辑器包以构建产物接入：宿主只认 `dist/editor.js` 的公共出口，源码里的 `@/` 别名不会串味。
const editorDist = fileURLToPath(new URL('../packages/editor/dist/editor.js', import.meta.url))
const editorCss = fileURLToPath(new URL('../packages/editor/dist/editor.css', import.meta.url))
// 语言包单独走源码入口：只带入两个纯对象，避免把整个编辑器组件树拉进首屏分包（EDT-07）
const editorLocales = fileURLToPath(
  new URL('../packages/editor/src/locales/index.ts', import.meta.url),
)

export default defineConfig({
  test,
  plugins: [vue()],
  resolve: {
    // 单实例强制：packages/editor 把 pinia / vue / vue-i18n 声明为 peer，但它的
    // node_modules 下实际存在副本；不 dedupe 时生产构建会打进**两份 pinia**，
    // 编辑器页在真实浏览器里整页崩（pinia._s undefined）。dev 下顺序不同、看不出问题。
    dedupe: ['pinia', 'vue', 'vue-i18n'],
    alias: [
      { find: /^@robot-autotest\/editor$/, replacement: editorDist },
      { find: '@robot-autotest/editor-css', replacement: editorCss },
      { find: '@robot-autotest/editor-locales', replacement: editorLocales },
      { find: '@', replacement: fileURLToPath(new URL('./src', import.meta.url)) },
    ],
  },
  build: {
    rollupOptions: {
      output: {
        // UX-04 步骤 1 / TC-S1-01：图标必须独立成 chunk 才能判「图标 chunk ≤ 15 KiB gzip」。
        // 坑：manualChunks 会把**依赖一起拉进**同一个 chunk —— 只分 lucide 时，
        // vue runtime（@vue/shared）也被塞进 lucide chunk，实测 37.8 KiB gzip 直接超预算。
        // 所以把 vue 显式分到自己的 chunk，lucide chunk 才真的只剩图标 + createLucideIcon。
        manualChunks(id) {
          if (id.includes('/node_modules/vue/') || id.includes('/node_modules/@vue/')) return 'vue'
          if (id.includes('/node_modules/lucide-vue-next/')) return 'lucide'
          return undefined
        },
      },
    },
  },
  server: {
    port: DEV_PORT,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
      },
    },
  },
})
