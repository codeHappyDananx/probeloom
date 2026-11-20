import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

function readLocalServiceUrl() {
  const configPath = fileURLToPath(new URL('../config/platform-ui.json', import.meta.url))
  if (!existsSync(configPath)) return ''
  const config = JSON.parse(readFileSync(configPath, 'utf8')) as { serviceUrl?: unknown }
  if (config.serviceUrl === undefined || config.serviceUrl === '') return ''
  if (typeof config.serviceUrl !== 'string') throw new Error('config/platform-ui.json 中的 serviceUrl 必须是字符串')
  const url = new URL(config.serviceUrl)
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('config/platform-ui.json 中的 serviceUrl 只支持 HTTP 或 HTTPS')
  return url.toString().replace(/\/$/, '')
}

// https://vite.dev/config/
export default defineConfig(({ command }) => {
  const localServiceUrl = command === 'serve' ? readLocalServiceUrl() : ''
  const buildId = new Date().toISOString()
  return {
    plugins: [react(), tailwindcss(), {
      name: 'platform-build-version',
      generateBundle() {
        this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify({ buildId }) })
      },
    }],
    define: {
      'import.meta.env.VITE_PLATFORM_SERVICE_URL': JSON.stringify(localServiceUrl),
      'import.meta.env.VITE_PLATFORM_BUILD_ID': JSON.stringify(buildId),
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      proxy: {
        '/api': { target: localServiceUrl || 'http://127.0.0.1:18090', changeOrigin: true },
      },
    },
  }
})
