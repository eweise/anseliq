// AnselIQ design tokens. The look borrows from the darkroom the name nods to: graphite "ink" for
// the base, a cool silver-gelatin "paper" for light sections, and one warm "safelight" amber as the
// accent. Status colors match the product's own reconciliation states.
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.js'],
  theme: {
    extend: {
      colors: {
        ink: {
          DEFAULT: '#12161b',
          900: '#0c0f13',
          800: '#171c22',
          700: '#212830',
          600: '#2e3741',
          400: '#6b7682',
          300: '#9aa4ae',
          200: '#c6ccd2'
        },
        paper: {
          DEFAULT: '#f3f5f3',
          100: '#ffffff',
          200: '#e7ebe8',
          300: '#d6dcd8'
        },
        safelight: {
          DEFAULT: '#e8992f',
          soft: '#f6dcb4',
          deep: '#a3600f'
        },
        status: {
          final: '#2f8a57',
          progress: '#4f7bbd',
          mismatched: '#cf4a3c'
        }
      },
      fontFamily: {
        display: ['"Schibsted Grotesk"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        sans: ['"Hanken Grotesk"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace']
      },
      maxWidth: {
        prose: '65ch'
      }
    }
  },
  plugins: []
}
