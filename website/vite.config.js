import { defineConfig } from 'vite'

// A plain static site: `npm run build` writes everything to dist/, which can be served from any
// static host (Netlify, Vercel, Cloudflare Pages, S3 + CloudFront).
export default defineConfig({
  build: { outDir: 'dist' }
})
