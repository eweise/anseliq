module.exports = {
  content: [
    './src/main/scala/**/*.scala',
    "./node_modules/flowbite/**/*.js"
  ],
  theme: {
    extend: {
      colors: {
        'md-primary':             '#6750a4',
        'md-on-primary':          '#ffffff',
        'md-primary-container':   '#eaddff',
        'md-surface-variant':     '#ece6f0',
        'md-on-surface':          '#1c1b1f',
        'md-on-surface-variant':  '#49454f',
        'md-outline':             '#79747e',
        'md-error':               '#b3261e',
        'md-primary-hover':       '#5b4397',
      }
    },
  },
  darkMode: 'selector',
  plugins: [
    require('flowbite/plugin')
  ],
}
