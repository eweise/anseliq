# AnselIQ — Project-wide Rules

Rules here apply to all modules: backend, common, and frontend.

## Project Structure

- `backend/` — Scala backend (ZIO, Magnum, PostgreSQL)
- `common/` — Shared Scala model/domain definitions used by backend
- `frontend/` — TypeScript/Vite/Tailwind frontend
- `website/` — AnselIQ marketing site (static Vite + Tailwind; `npm run dev` / `npm run build` in that folder). Early-access form posts to Formspree via `VITE_FORMSPREE_FORM_ID` (see `website/.env.example`)

## General Guidelines

<!-- Add project-wide coding standards, conventions, and guidelines here -->
