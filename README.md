# AnselIQ

Reconciliation platform: ingests processor, bank and internal ledger files, matches them with
configurable rules, and explains and resolves whatever doesn't tie out.

The documentation is at **[anseliq.com/docs](https://anseliq.com/docs/)**:

- [Overview & concepts](https://anseliq.com/docs/): ledgers, accounts, feeds, rules and reconciliations
- [User guide](https://anseliq.com/docs/user-guide.html): setting up ledgers and working exceptions
- [Installation & operations](https://anseliq.com/docs/install.html): building the images and installing on Kubernetes
- [Developers](https://anseliq.com/docs/developers.html): architecture, running it locally, tests and the HTTP API

AnselIQ is licensed under the [Apache License, Version 2.0](LICENSE).

## Repository layout

- `backend/` — Scala 3 backend (cask, Magnum, PostgreSQL)
- `common/` — models shared by backend and frontend
- `frontend/` — Scala.js (Laminar) web app, built with Vite and Tailwind
- `k8s/` — Kustomize base, plus overlays for Docker Desktop (`local`) and AWS EKS (`eks`)
- `website/` — anseliq.com, including the documentation (static Vite + Tailwind)

## Quick start

You need JDK 26, [sbt](https://www.scala-sbt.org/download.html), Node.js 22, PostgreSQL 18 and
[LocalStack](https://www.localstack.cloud) (for S3).

```bash
eval "$(scripts/dev-env.sh)"   # creates .env: a JWT secret, the first Admin, LocalStack and (on a Mac) JAVA_HOME
localstack start -d
make create-db                 # an empty recon_db, migrated
sbt                            # then `up`: the backend in the background, then the web app
```

Sign in at http://localhost:9876 as the `ADMIN_EMAIL` in `.env`. In sbt, `x` stops the backend.
The [developers guide](https://anseliq.com/docs/developers.html#local) has the rest: demo data, tests,
migrations and the local Kubernetes install.

For IntelliJ, point the backend run configuration's environment variables at the same `.env`.

## Notes for working on the repo

### sbt 2

- `sbt test` only reruns the tests that failed or whose code changed since the last run;
  `sbt testFull` runs them all.
- The backend run from sbt (`bgRun`, `run`, `runMain`) reads `.env` itself each time it starts, so
  an edited `.env` only needs a backend restart. A variable set in the shell that started sbt wins.
- Every `sbt` in this folder connects to one background server, which runs the tests with the
  environment it started with. After changing an environment variable, run `sbt shutdown` so the
  next command starts a server that sees it.
- The server runs one command at a time, so a running `frontend/devServer` holds up everything else
  until Ctrl+C. Start the backend first with `backend/bgRun` (or `up`, which does both).
- The welcome menu prints only when the server starts; `welcome` prints it again.

### LocalStack

LocalStack needs an auth token, from the Auth Tokens page at https://app.localstack.cloud. Never
commit it:

```bash
localstack auth set-token <your auth token>
```

To use the `aws` CLI against it, add a profile to `~/.aws/config`:

```ini
[profile localstack]
region = us-east-1
output = json
endpoint_url = http://localhost.localstack.cloud:4566
```

and to `~/.aws/credentials`:

```ini
[localstack]
aws_access_key_id = test
aws_secret_access_key = test
```

Then e.g. `aws s3 ls --profile localstack`.

### AI matching

Match suggestions come from a model served locally by [Ollama](https://ollama.com):

```bash
brew install ollama
ollama serve              # in its own terminal
ollama pull qwen2.5:7b    # about 4.7 GB
make seed-demo-llm        # demo data including AI suggestions
```

### Database migrations

Migrations are in `backend/src/main/resources/db/migration` (Flyway). To change the schema, add a new
`V<n>__<description>.sql`; never edit one that has already been applied anywhere.

### CI and releases

CI (`.github/workflows/ci.yml`) runs the tests on every push to `main` and every pull request, against
a fresh Postgres and LocalStack. LocalStack's token is the repository secret `LOCALSTACK_AUTH_TOKEN`
(Settings → Secrets and variables → Actions).

Pushing a version tag on a commit whose CI run passed publishes that release's images:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The Release workflow (`.github/workflows/release.yml`) pushes `ghcr.io/eweise/anseliq-backend:1.2.3`
and `ghcr.io/eweise/anseliq-frontend:1.2.3`. The version comes from git (sbt-dynver): a build anywhere
other than a release tag gets one like `1.2.3-4-abcd1234`, plus a `dev` tag, which the local overlay
deploys. `DOCKER_REGISTRY` publishes somewhere other than `ghcr.io/eweise`.

### Website

```bash
cd website
npm install
npm run build    # then upload website/dist/
```

The consulting form needs `VITE_FORMSPREE_FORM_ID` in `website/.env` at build time (see
`website/.env.example`).
