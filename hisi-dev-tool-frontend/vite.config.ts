import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
import { ServerResponse } from 'node:http'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, 'src')
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        selfHandleResponse: true,
        configure: (proxy) => {
          proxy.on('error', (err, _req, res) => {
            const msg = `[Proxy] ${err.message}`
            if (res instanceof ServerResponse) {
              res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
              res.end(JSON.stringify({ code: 502, message: msg }))
            }
          })
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            // SSE streams: pipe directly without buffering
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              res.writeHead(proxyRes.statusCode!, proxyRes.headers)
              proxyRes.pipe(res)
              return
            }
            // All other responses: collect and forward normally
            const chunks: Buffer[] = []
            proxyRes.on('data', (chunk: Buffer) => chunks.push(chunk))
            proxyRes.on('end', () => {
              const body = Buffer.concat(chunks)
              const headers = { ...proxyRes.headers }
              if (headers['transfer-encoding']) {
                delete headers['transfer-encoding']
                headers['content-length'] = String(body.length)
              }
              res.writeHead(proxyRes.statusCode!, headers)
              res.end(body)
            })
          })
        }
      },
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  }
})
