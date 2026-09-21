import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/admin/wren/',
  build: { outDir: '../resources/static/admin/wren', emptyOutDir: true },
  server: { proxy: { '/internal': 'http://127.0.0.1:6001' } }
});
