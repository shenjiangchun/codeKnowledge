import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import pinia from './stores'
import '@/styles/global.css'

// 开发环境 Mock WebSocket 已禁用
// MockWebSocket 仅用于 Dialog 组件的独立测试
// 全局启用会拦截所有 WebSocket（包括 /ws/terminal），导致终端无法连接后端
// 如需测试 Dialog，请在 Dialog 组件内手动启用 Mock

const app = createApp(App)

// 注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(ElementPlus)
app.use(router)
app.use(pinia)

app.mount('#app')