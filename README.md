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

## Minikube

## llm
- brew install ollama
- ollama serve              # in its own terminal
- ollama pull qwen2.5:7b    # about 4.7 GB
- make seed-demo-llm

## Localstack

### Start localstack

- Get the token
- Add the token locally
    - localstack auth set-token ls-YaDEZADA-9583-SoKu-RAKA-tuQo9565e887
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

