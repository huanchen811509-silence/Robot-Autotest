import { createApp } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { i18n } from './locales'
// fonts.css 由 tools/subset_font.py 生成：三片 woff2 + unicode-range 分包 + font-display: swap。
// 放在 theme.css 之前，保证首帧解析 @font-face 时语义变量也已就位。
import './styles/fonts.css'
import './styles/theme.css'
import './styles/target-size.css'
import './styles/main.css'

const app = createApp(App)
// 2026-11-06 实测：只 app.use(pinia) 时，生产 chunk 里「装配前」执行的 useStore()
// 会拿到 undefined 的 activePinia，编辑器页整页崩（pinia 内部 `pinia._s.set(...)`）。
// 先显式激活，装配顺序再不同也不会踩空。
const pinia = createPinia()
setActivePinia(pinia)
app.use(pinia)
app.use(router)
app.use(i18n)
app.mount('#app')
