# AnselIQ

Reconciliation platform: ingests processor, bank and internal ledger files, matches them with
configurable rules, and explains and resolves whatever doesn't tie out.

- `backend/` — Scala 3 backend (cask, Magnum, PostgreSQL)
- `common/` — models shared by backend and frontend
- `frontend/` — Scala.js (Laminar) web app, built with Vite and Tailwind
- `website/` — the AnselIQ marketing site (static Vite + Tailwind)

### Prerequisites

- [Java JDK](https://jdk.java.net/26/) 26 (the backend uses `UUID.ofEpochMillis`, new in 26)
- [sbt](https://www.scala-sbt.org/download.html) (Scala Build Tool)
- [Nodejs V22.13.0](https://nodejs.org/en/blog/release/v22.13.0)

## Getting Started

To see all available commands:

```bash
sbt
```

The backend won't start its HTTP server without a JWT secret of at least 32 bytes, and reads S3 from
real AWS unless pointed at LocalStack. This sets up both in `.env` (gitignored) the first time, then
loads `.env` into your shell:

```bash
eval "$(scripts/dev-env.sh)"
```

For IntelliJ, point the backend run configuration's environment variables at the same `.env`.

# reconproto

# Installation

## Docker

## Kubernetes

Manifests are in `k8s/` (Kustomize, built into `kubectl`). `k8s/base` runs the backend as two
deployments, `backend-api` (HTTP) and `backend-worker` (jobs and scheduler), plus the frontend, behind
one Ingress that sends `/api` to the backend.

`k8s/overlays/local` runs it all on Docker Desktop's Kubernetes (Settings → Kubernetes → Enable
Kubernetes), with a Postgres of its own in the cluster and LocalStack on the Mac for S3:

```bash
cp k8s/overlays/local/secrets.env.example k8s/overlays/local/secrets.env   # then fill it in
sbt "backend/Docker/publishLocal; frontend/Docker/publishLocal"
kubectl apply -k k8s/overlays/local
```

The app is then at http://localhost:8088; sign in as `ADMIN_EMAIL` from `secrets.env`. After
rebuilding the images, `kubectl -n anseliq rollout restart deployment` picks them up, and
`kubectl delete namespace anseliq` removes everything, the database included.

`k8s/overlays/eks` is the template for a customer install on AWS EKS (RDS, S3 through an IAM role,
an ALB with an ACM certificate); its `README.md` covers the AWS setup and what to fill in.

An init container in each backend pod applies the database migrations before the app starts (see
Database migrations below), so the database itself only has to exist.

## Tests

The build uses sbt 2, where `sbt test` only reruns the tests that failed or whose code changed since
the last run; `sbt testFull` runs them all. sbt 2 also keeps a server running in the background
between commands, and that server is what runs the app and the tests. It keeps the environment
variables it started with, so after changing one (e.g. reloading `.env`), run `sbt shutdown` for the
next command to start a server that sees it.

## CI

The CI workflow (`.github/workflows/ci.yml`) runs the tests on every push to `main` and every pull
request, against a fresh Postgres and LocalStack. LocalStack needs an auth token: set it as the
repository secret `LOCALSTACK_AUTH_TOKEN` (Settings → Secrets and variables → Actions).

## Releases

Pushing a version tag publishes that release's images. Tag a commit whose CI run passed:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The Release workflow (`.github/workflows/release.yml`) builds and pushes
`ghcr.io/eweise/anseliq-backend:1.2.3` and `ghcr.io/eweise/anseliq-frontend:1.2.3`. The version comes
from git (sbt-dynver): a build anywhere other than a release tag gets one like `1.2.3-4-abcd1234`,
plus a `dev` tag, which the local overlay deploys. The backend logs its version when it starts.
`DOCKER_REGISTRY` publishes somewhere other than `ghcr.io/eweise`.

## Database migrations

The schema is managed by [Flyway](https://documentation.red-gate.com/flyway). Migrations are in
`backend/src/main/resources/db/migration`, and the backend applies any pending ones when it starts
(`db.migrate-on-start`). To change the schema, add a new `V<n>__<description>.sql` there; never edit
one that has already been applied anywhere.

- `make create-db` creates an empty `recon_db` and migrates it. No users are seeded: the backend
  creates the first Admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD` (set in `.env` by `scripts/dev-env.sh`)
  when it starts and there isn't one.
- `sbt "backend/runMain anseliq.app.Main migrate"` migrates without starting the app.

A database created from the old `schema.sql` is recorded as already at V1 the first time it's migrated.

## Minikube

## llm
- brew install ollama
- ollama serve              # in its own terminal
- ollama pull qwen2.5:7b    # about 4.7 GB
- make seed-demo-llm

## Localstack

### Start localstack

- Get your auth token from the Auth Tokens page at https://app.localstack.cloud (never commit it)
- Add the token locally
    - localstack auth set-token <your auth token>
- Start localstack
    - localstack start

Configuring a custom profile

You can configure a custom profile to use with LocalStack. Add the following profile to your AWS configuration file (by
default, this file is at ~/.aws/config):
Terminal window

[profile localstack]
region=us-east-1
output=json
endpoint_url = http://localhost.localstack.cloud:4566

Add the following profile to your AWS credentials file (by default, this file is at ~/.aws/credentials):
Terminal window

[localstack]
aws_access_key_id=test
aws_secret_access_key=test

You can now use the localstack profile with the aws CLI:
Terminal window

aws s3 mb s3://test --profile localstack
aws s3 ls --profile localstack

## AWS CLI

## Helm

* brew install helm
  $ brew install openapi-generator

## Postgres on Kubernetes

** Please be patient while the chart is being deployed **

PostgreSQL can be accessed via port 5432 on the following DNS names from within your cluster:

    my-release-postgresql.default.svc.cluster.local - Read/Write connection

To get the password for "postgres" run:

    export POSTGRES_PASSWORD=$(kubectl get secret --namespace default my-release-postgresql -o jsonpath="{.data.postgres-password}" | base64 -d)

To connect to your database run the following command:

    kubectl run my-release-postgresql-client --rm --tty -i --restart='Never' --namespace default --image docker.io/bitnami/postgresql:17.2.0-debian-12-r2 --env="PGPASSWORD=$POSTGRES_PASSWORD" \
      --command -- psql --host my-release-postgresql -U postgres -d postgres -p 5432

    > NOTE: If you access the container using bash, make sure that you execute "/opt/bitnami/scripts/postgresql/entrypoint.sh /bin/bash" in order to avoid the error "psql: local user with ID 1001} does not exist"

To connect to your database from outside the cluster execute the following commands:

    kubectl port-forward --namespace default svc/my-release-postgresql 5432:5432 &
    PGPASSWORD="$POSTGRES_PASSWORD" psql --host 127.0.0.1 -U postgres -d postgres -p 5432

# Building

sbt assembly

# Website
You run two commands in website/:

cd website
npm install      # first time only
npm run build

That produces website/dist/, and that folder is what you upload. I ran both: the build succeeded and your Formspree ID is in the built JavaScript. dist/ now holds index.html, favicon.svg and two files in assets/.

