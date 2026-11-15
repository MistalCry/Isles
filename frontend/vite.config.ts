import { existsSync, readdirSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 开发服务器把 /api 与 /uploads 代理到本地后端，避免跨域并保持与生产一致的相对路径。
// 后端不在默认 8080 时，用 VITE_API_TARGET 覆盖（如 VITE_API_TARGET=http://127.0.0.1:8082）。
const apiTarget = process.env.VITE_API_TARGET || 'http://127.0.0.1:8080'

/**
 * 预构建 Element Plus 的按需样式入口。
 *
 * <p>ElementPlusResolver 在转换阶段才注入 `element-plus/es/components/*\/style/css`，
 * Vite 的启动扫描看不到这些依赖，于是每次首次访问新页面都会「发现新依赖 → 重新预构建 →
 * 整页 reload」。reload 会中断正在进行的请求（表现为提交表单时提示无法连接服务器）。
 * 这里在启动时把它们全部列入 optimizeDeps.include，消除运行期重新预构建。
 */
function elementPlusStyleEntries(): string[] {
  const dir = fileURLToPath(new URL('./node_modules/element-plus/es/components', import.meta.url))
  if (!existsSync(dir)) return []
  return readdirSync(dir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && existsSync(`${dir}/${entry.name}/style/css.mjs`))
    .map((entry) => `element-plus/es/components/${entry.name}/style/css`)
}

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: 'src/components.d.ts',
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  optimizeDeps: {
    include: ['element-plus/es', ...elementPlusStyleEntries()],
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/uploads': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/ws': {
        target: apiTarget,
        changeOrigin: true,
        ws: true,
      },
    },
  },
  preview: {
    port: 4173,
    host: '127.0.0.1',
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/uploads': { target: apiTarget, changeOrigin: true },
      '/ws': { target: apiTarget, changeOrigin: true, ws: true },
    },
  },
  build: {
    chunkSizeWarningLimit: 900,
    sourcemap: false,
  },
})
