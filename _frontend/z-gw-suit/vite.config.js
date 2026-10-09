import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// z-gw 独立运行壳。dev 3011；proxy /gw → z-gw-admin（本机 9090 或 8888 反代）。
export default defineConfig({
    plugins: [react()],
    server: {
        port: 3011,
        fs: {allow: ['..']},
        proxy: {
            '/gw': {target: 'http://localhost:8888', changeOrigin: true},
            '/actuator': {target: 'http://localhost:8888', changeOrigin: true},
        },
    },
})
