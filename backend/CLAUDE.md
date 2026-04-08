# Backend Rules

Rules here apply only to the `backend/` module.

## Stack

- Scala 3, Magnum (SQL), PostgreSQL
- ZIO is only for existing code, no new code uses it
- Tests use munit with `rollBackOnly` for DB isolation

## Guidelines

<!-- Add backend-specific coding standards and conventions here -->

- Backend should follow a hexagon architecture.
