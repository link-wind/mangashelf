import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 同源代理（implement-manga-reading D9）：/api → Java API；/media → S3 兼容存储。
// 预签名 URL 按 Host 校验，changeOrigin 保留目标 Host，签名不受影响。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/media': { target: 'http://localhost:9000', changeOrigin: true, rewrite: p => p.replace(/^\/media/, '') },
    },
  },
})
