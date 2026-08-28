module.exports = {
  content: [
    './src/main/scala/**/*.scala',
    "./node_modules/flowbite/**/*.js"
  ],
  theme: {
    extend: {
      colors: {
        'md-primary':                   '#6d28d9',
        'md-primary-dark':              '#a78bfa',
        'md-primary-hover':             '#5b21b6',
        'md-primary-hover-dark':        '#c4b0fb',
        'md-on-primary':                '#ffffff',
        'md-on-primary-dark':           '#211a3d',
        'md-primary-container':         '#f1ecfd',
        'md-primary-container-dark':    '#362a5c',
        'md-on-primary-container':      '#4c1d95',
        'md-on-primary-container-dark': '#ede9fe',
        'md-surface-variant':           '#f3f4f6',
        'md-surface-alt':               '#e5e7eb',
        'md-surface-alt-dark':          '#1a2230',
        'md-on-surface':                '#1c1b1f',
        'md-on-surface-variant':        '#49454f',
        'md-outline':                   '#79747e',
        'md-error':                     '#b3261e',
        'md-good':                      '#0ca30c',
        'md-good-dark':                 '#3fcf5f',
        'md-good-bg':                   '#e4f6e4',
        'md-good-bg-dark':              '#163821',
        'md-warning':                   '#a86400',
        'md-warning-dark':              '#f0b13a',
        'md-warning-bg':                '#fdf0d2',
        'md-warning-bg-dark':           '#3c2f10',
        'md-serious':                   '#c1502a',
        'md-serious-dark':              '#f0916a',
        'md-serious-bg':                '#fbe6dc',
        'md-serious-bg-dark':           '#3d2415',
        'md-critical':                  '#d03b3b',
        'md-critical-dark':             '#ef6f6f',
        'md-critical-bg':               '#fbe2e2',
        'md-critical-bg-dark':          '#3a1717',
      }
    },
  },
  darkMode: 'selector',
  plugins: [
    require('flowbite/plugin')
  ],
}
