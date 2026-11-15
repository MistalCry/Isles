import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// 前端测试分两类：
// 1) 纯逻辑（适配器 / 校验 / 裁剪 / 提及）—— 不依赖 DOM，跑得快；
// 2) 组件（PostCard / CommentItem / MentionPicker 等）—— happy-dom 环境。
// 组件里用到的 Element Plus 组件在 src/test/setup.ts 里统一 stub，
// 避免为了测试引入整套 UI 库、也避免 Element Plus 的样式依赖。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.spec.ts'],
    setupFiles: ['src/test/setup.ts'],
  },
})
