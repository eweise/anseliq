import { resolve } from 'node:path'
import { defineConfig } from 'vite'

// A plain static site: `npm run build` writes everything to dist/, which can be served from any
// static host (Netlify, Vercel, Cloudflare Pages, S3 + CloudFront). Each HTML page is its own entry,
// so a new docs page has to be added to `input` as well.
export default defineConfig({
  build: {
    outDir: 'dist',
    rollupOptions: {
      input: {
        main: resolve(import.meta.dirname, 'index.html'),
        docs: resolve(import.meta.dirname, 'docs/index.html'),
        userGuide: resolve(import.meta.dirname, 'docs/user-guide.html'),
        install: resolve(import.meta.dirname, 'docs/install.html'),
        developers: resolve(import.meta.dirname, 'docs/developers.html')
      }
    }
  }
})
