import './styles.css'

// The Formspree form that receives early-access requests, set at build time (see .env.example).
const FORMSPREE_FORM_ID = import.meta.env.VITE_FORMSPREE_FORM_ID

document.getElementById('year').textContent = String(new Date().getFullYear())

const form = document.getElementById('early-access-form')
const status = document.getElementById('form-status')
const submitButton = form.querySelector('button[type="submit"]')

function showStatus(message, tone) {
  status.textContent = message
  status.className = `text-sm ${tone === 'error' ? 'text-status-mismatched' : 'text-status-final'}`
}

form.addEventListener('submit', async (event) => {
  event.preventDefault()

  if (!form.checkValidity()) {
    form.reportValidity()
    return
  }
  if (!FORMSPREE_FORM_ID) {
    showStatus("The form isn't connected yet. Email us instead and we'll get back to you.", 'error')
    console.error('VITE_FORMSPREE_FORM_ID is not set; see website/.env.example')
    return
  }

  // Checkbox values are sent as one comma-separated field so they read cleanly in the Formspree
  // inbox and email notification, rather than as repeated keys.
  const data = new FormData(form)
  const sources = data.getAll('sources')
  data.delete('sources')
  data.append('sources', sources.join(', ') || 'Not specified')

  submitButton.disabled = true
  showStatus('Sending…', 'info')
  try {
    const response = await fetch(`https://formspree.io/f/${FORMSPREE_FORM_ID}`, {
      method: 'POST',
      body: data,
      headers: { Accept: 'application/json' }
    })
    if (response.ok) {
      form.reset()
      showStatus("Thanks — we'll be in touch within two business days.", 'success')
    } else {
      const body = await response.json().catch(() => ({}))
      const detail = body.errors?.map((e) => e.message).join(' ')
      showStatus(detail || "Something went wrong sending that. Please try again.", 'error')
    }
  } catch {
    showStatus("Couldn't reach the server. Check your connection and try again.", 'error')
  } finally {
    submitButton.disabled = false
  }
})

// Tick-and-tie marks redraw themselves the first time they scroll into view. They're already
// visible without this (and with reduced motion), so nothing depends on it running.
if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches && 'IntersectionObserver' in window) {
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-drawing')
          observer.unobserve(entry.target)
        }
      }
    },
    { threshold: 0.6 }
  )
  document.querySelectorAll('.mark, .mark-inline').forEach((el) => observer.observe(el))
}
