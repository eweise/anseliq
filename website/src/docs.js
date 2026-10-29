import './styles.css'

// The docs pages share the site's styles but none of the landing page's behaviour (the consulting
// form, the tick-and-tie mark animation), so they load this instead of main.js.
document.getElementById('year').textContent = String(new Date().getFullYear())

// Highlights the section of the current page that's in view in the sidebar's table of contents.
const tocLinks = [...document.querySelectorAll('.docs-toc a')]
const sections = tocLinks.map((a) => document.getElementById(a.hash.slice(1))).filter(Boolean)
if (sections.length && 'IntersectionObserver' in window) {
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        for (const link of tocLinks) {
          link.classList.toggle('!text-ink', link.hash === `#${entry.target.id}`)
          link.classList.toggle('font-semibold', link.hash === `#${entry.target.id}`)
        }
      }
    },
    { rootMargin: '-20% 0px -70% 0px' }
  )
  sections.forEach((s) => observer.observe(s))
}
