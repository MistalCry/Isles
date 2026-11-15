import { createApp } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

import '@/styles/tokens.css'
import '@/styles/element.scss'
import '@/styles/global.scss'

import App from './App.vue'
import i18n from './i18n'
import router from './router'
import { pinia } from './stores'

const app = createApp(App)

// 页面级兜底：避免未捕获异常导致白屏（规范 §3.3 错误边界）
app.config.errorHandler = (error) => {
  console.error('[isles] unhandled error', error)
  ElMessage.error(i18n.global.t('common.errorBoundary'))
}

app.use(i18n)
app.use(pinia)
app.use(router)
app.mount('#app')
