import { defineConfig, loadEnv } from 'vite'
import path from 'node:path'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://127.0.0.1:8080'
  // Default HLS origin to backend when nginx is not configured
  const hlsOrigin = env.VITE_HLS_ORIGIN || apiTarget

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src')
      }
    },
    server: {
      port: 5173,
      host: true,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true
        },
        // Proxy HLS assets in dev so the player can request /streams/... directly
        '/streams': {
          target: hlsOrigin,
          changeOrigin: true
        }
      }
    }
  }
})
