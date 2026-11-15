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
 * 是否监听所有网卡。默认只监听 127.0.0.1（更安全）；
 * 手机/平板通过局域网或电脑热点访问时用 ISLES_LAN=1 启动（isles.bat lan 已封装）。
 */
const lanMode = process.env.ISLES_LAN === '1'
const devHost = lanMode ? true : '127.0.0.1'

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

/**
 * 浏览器用局域网 IP（如手机连热点后访问 http://192.168.137.2:5173）时，
 * 请求经代理转发仍会带着该 IP 的 Origin，后端 CORS 白名单只放行 localhost/127.0.0.1 → 403。
 * 代理本身已是同源转发，这里把 Origin 改写成后端地址，避免误判为跨域。
 */
function rewriteOrigin(proxy: { on: (event: string, handler: (proxyReq: { setHeader: (k: string, v: string) => void }) => void) => void }): void {
  proxy.on('proxyReq', (proxyReq) => {
    proxyReq.setHeader('origin', apiTarget)
  })
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
    // 默认只监听本机；ISLES_LAN=1 时才开放到局域网（手机连热点调试用）
    host: devHost,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        configure: rewriteOrigin,
      },
      '/uploads': {
        target: apiTarget,
        changeOrigin: true,
        configure: rewriteOrigin,
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
    host: devHost,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true, configure: rewriteOrigin },
      '/uploads': { target: apiTarget, changeOrigin: true, configure: rewriteOrigin },
      '/ws': { target: apiTarget, changeOrigin: true, ws: true },
    },
  },
  build: {
    chunkSizeWarningLimit: 900,
    sourcemap: false,
  },
})
